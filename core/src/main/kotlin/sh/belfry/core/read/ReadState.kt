package sh.belfry.core.read

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * Read state (SPEC 19.27): what a person has read up to when, per channel (`c`), thread (`t`) and
 * direct-message conversation (`d`), plus the start `s` and the @Mentions mark `m`, merged across
 * their devices. The pure rules, a port of the web's `core/readcore.ts`; vectors/readstate.json.
 * Every number = read up to and including that unix second.
 */
data class ReadState(
    val s: Long = 0,
    val m: Long = 0,
    val c: Map<String, Long> = emptyMap(),
    val t: Map<String, Long> = emptyMap(),
    val d: Map<String, Long> = emptyMap(),
    /** Fields a newer client wrote, kept as they are. */
    val extra: Map<String, JsonElement> = emptyMap(),
) {
    fun section(sec: Char): Map<String, Long> = when (sec) { 'c' -> c; 't' -> t; 'd' -> d; else -> emptyMap() }

    private fun withSection(sec: Char, map: Map<String, Long>): ReadState = when (sec) {
        'c' -> copy(c = map); 't' -> copy(t = map); 'd' -> copy(d = map); else -> this
    }

    /** What goes in the event before encryption: unknown fields (sorted), then v s m c t d, keys sorted. */
    fun serialize(): String {
        val sb = StringBuilder("{")
        for (k in extra.keys.sorted()) {
            if (k in RESERVED) continue
            sb.append(JsonPrimitive(k).toString()).append(':').append(extra[k].toString()).append(',')
        }
        sb.append("\"v\":1,\"s\":").append(s).append(",\"m\":").append(m)
        for (sec in SECTIONS) {
            sb.append(",\"").append(sec).append("\":{")
            section(sec).keys.sorted().forEachIndexed { i, k ->
                if (i > 0) sb.append(',')
                sb.append(JsonPrimitive(k).toString()).append(':').append(section(sec)[k])
            }
            sb.append('}')
        }
        return sb.append('}').toString()
    }

    /** Read KEY of SEC ('c', 't', 'd' or 'm') up to AT, never backwards. The same object when nothing changes. */
    fun mark(sec: Char, key: String, at: Long): ReadState {
        val a = if (at > 0) at else 0
        if (sec == 'm') return if (a > m) copy(m = a) else this
        if (sec !in SECTIONS || a == 0L || key.isEmpty() || key.length > MAX_KEY || (section(sec)[key] ?: 0) >= a) return this
        return withSection(sec, section(sec) + (key to a))
    }

    /** Below this everything counts as read: KEEP_DAYS ago, and for c / t / m also `s`. */
    fun floor(sec: Char, now: Long): Long {
        val old = now - KEEP_DAYS * DAY
        return if (sec == 'd') old else maxOf(old, s)
    }

    fun readUpTo(sec: Char, key: String, now: Long): Long {
        val own = if (sec == 'm') m else (section(sec)[key] ?: 0)
        return maxOf(own, floor(sec, now))
    }

    /** Drop marks at or under their floor, then the oldest c / t marks while too heavy (raising s), then d. */
    fun prune(now: Long): ReadState {
        data class E(val sec: Char, val key: String, val at: Long)
        val list = ArrayList<E>()
        for (sec in SECTIONS) for ((k, at) in section(sec)) if (at > floor(sec, now)) list.add(E(sec, k, at))
        val order = mapOf('c' to 0, 't' to 1, 'd' to 2)
        list.sortWith(
            compareBy<E> { if (it.sec == 'd') 1 else 0 }.thenBy { it.at }.thenBy { order[it.sec] }.thenBy { it.key }
        )
        var weight = list.sumOf { weightOf(it.key) }
        var newS = s
        var i = 0
        while (weight > MAX_WEIGHT && i < list.size) {
            val e = list[i++]
            weight -= weightOf(e.key)
            if (e.sec != 'd') newS = maxOf(newS, e.at)
        }
        val out = copy(s = newS)
        val maps = mutableMapOf('c' to LinkedHashMap<String, Long>(), 't' to LinkedHashMap(), 'd' to LinkedHashMap())
        for (e in list.subList(i, list.size)) if (e.at > out.floor(e.sec, now)) maps[e.sec]!![e.key] = e.at
        return out.copy(c = maps['c']!!, t = maps['t']!!, d = maps['d']!!)
    }

    companion object {
        const val READ_D = "belfry:read"
        const val KIND = 30078
        const val DAY = 86400L
        const val KEEP_DAYS = 90L
        const val MAX_WEIGHT = 40000L
        const val MAX_KEY = 200
        val SECTIONS = listOf('c', 't', 'd')
        private val RESERVED = setOf("v", "s", "m", "c", "t", "d")

        val EMPTY = ReadState()

        private fun weightOf(key: String): Long = key.toByteArray(Charsets.UTF_8).size + 16L

        private fun num(x: JsonElement?): Long {
            val p = x as? JsonPrimitive ?: return 0
            if (p.isString) return 0
            val v = p.doubleOrNull ?: return 0
            if (!v.isFinite() || v <= 0) return 0
            return kotlin.math.floor(v).toLong()
        }

        /** Reads the decrypted content; anything unreadable is dropped, never thrown. */
        fun parse(json: String): ReadState {
            val el = try { Json.parseToJsonElement(json) } catch (e: Exception) { return EMPTY }
            return parse(el)
        }

        fun parse(el: JsonElement): ReadState {
            val o = el as? JsonObject ?: return EMPTY
            var r = ReadState()
            val extra = LinkedHashMap<String, JsonElement>()
            val maps = mutableMapOf('c' to LinkedHashMap<String, Long>(), 't' to LinkedHashMap(), 'd' to LinkedHashMap())
            for ((k, v) in o) {
                when (k) {
                    "v" -> {}
                    "s" -> r = r.copy(s = num(v))
                    "m" -> r = r.copy(m = num(v))
                    "c", "t", "d" -> (v as? JsonObject)?.forEach { (key, at) ->
                        val n = num(at)
                        if (n > 0 && key.isNotEmpty() && key.length <= MAX_KEY) maps[k[0]]!![key] = n
                    }
                    else -> extra[k] = v
                }
            }
            return r.copy(c = maps['c']!!, t = maps['t']!!, d = maps['d']!!, extra = extra)
        }

        /** Every number the highest of the two; unknown fields REMOTE's first. */
        fun merge(local: ReadState, remote: ReadState): ReadState {
            fun mx(a: Map<String, Long>, b: Map<String, Long>): Map<String, Long> {
                val out = LinkedHashMap(a)
                for ((k, at) in b) out[k] = maxOf(out[k] ?: 0, at)
                return out
            }
            return ReadState(
                s = maxOf(local.s, remote.s), m = maxOf(local.m, remote.m),
                c = mx(local.c, remote.c), t = mx(local.t, remote.t), d = mx(local.d, remote.d),
                extra = LinkedHashMap(local.extra).apply { putAll(remote.extra) },
            )
        }

        /** Does REMOTE already say everything LOCAL says? False → publish merge(local, remote).prune(now). */
        fun covers(remote: ReadState, local: ReadState, now: Long): Boolean {
            if (local.s > remote.s || local.m > maxOf(remote.m, remote.floor('m', now))) return false
            for (sec in SECTIONS) for ((k, at) in local.section(sec))
                if (at > local.floor(sec, now) && at > remote.readUpTo(sec, k, now)) return false
            return true
        }
    }
}
