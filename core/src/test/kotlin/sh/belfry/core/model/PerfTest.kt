package sh.belfry.core.model

import sh.belfry.core.nostr.Event
import java.io.File
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Not a pass/fail benchmark: it times what a screen costs on a large log (a community of 40
 * channels and 30 000 events) and writes the numbers to `core/build/perf.txt`, which CI keeps as
 * the artifact `perf`. The JVM of a CI runner is not a phone: read them as relative numbers,
 * before and after a change. It fails only when something is absurdly slow.
 */
class PerfTest {
    private val now = 1_800_000_000L
    private val people = (0 until 120).map { i -> "%064x".format(i + 1) }
    private val relayKey = "f".repeat(64)
    private var n = 0
    private fun id(): String = "%064x".format(1_000_000 + n++)
    private fun ev(pk: String, at: Long, kind: Int, tags: List<List<String>>, content: String = "") = Event(id(), pk, at, kind, tags, content, "")

    /** A log shaped like a busy community's: mostly messages, with reactions, replies, edits, to-dos, labels, seen markers. */
    private fun log(groups: Int, perGroup: Int): List<Event> {
        val out = ArrayList<Event>(groups * perGroup + 1000)
        val rnd = java.util.Random(42)
        for ((i, p) in people.withIndex()) out.add(ev(p, now - 9_000_000, 0, emptyList(), """{"name":"Person $i","about":"","picture":"https://example.org/p/$i.jpg"}"""))
        for (g in 0 until groups) {
            val h = if (g == 0) "general" else "channel-$g"
            val t0 = now - 8_000_000
            out.add(ev(people[g % 5], t0, 9007, listOf(listOf("h", h))))
            out.add(ev(relayKey, t0 + 1, 39000, listOf(listOf("d", h), listOf("name", if (g == 0) "general" else "Channel $g"), listOf("about", "What channel $g is for."))))
            out.add(ev(relayKey, t0 + 1, 39001, listOf(listOf("d", h), listOf("p", people[g % 5]))))
            out.add(ev(relayKey, t0 + 1, 39002, listOf(listOf("d", h)) + people.take(30 + g).map { listOf("p", it) }))
            if (g % 4 == 0) out.add(ev(people[g % 5], t0 + 5, 30078, listOf(listOf("d", "belfry:web:$h"), listOf("h", h), listOf("t", "belfry-web")), """{"on":true,"slug":"c$g","visibility":"public"}"""))
            val list = World.listIdFor(h)
            val msgs = ArrayList<Event>()
            for (k in 0 until perGroup) {
                val at = t0 + 100 + k * (7_900_000L / perGroup)
                val who = people[rnd.nextInt(30 + g)]
                when (rnd.nextInt(100)) {
                    in 0..59 -> msgs.add(ev(who, at, 9, listOf(listOf("h", h)), "Message $k in $h with a few words and a #topic${k % 7}").also(out::add))
                    in 60..79 -> if (msgs.isNotEmpty()) out.add(ev(who, at, 7, listOf(listOf("h", h), listOf("e", msgs[rnd.nextInt(msgs.size)].id)), if (k % 3 == 0) "+" else "🎉"))
                    in 80..91 -> if (msgs.isNotEmpty()) { val root = msgs[maxOf(0, msgs.size - 1 - rnd.nextInt(20))]; out.add(ev(who, at, 1111, listOf(listOf("h", h), listOf("E", root.id), listOf("e", root.id)), "Reply $k")) }
                    in 92..93 -> if (msgs.isNotEmpty()) { val m = msgs.last(); out.add(ev(m.pubkey, at, 1010, listOf(listOf("h", h), listOf("e", m.id)), m.content + " (edited)")) }
                    in 94..95 -> out.add(ev(who, at, 2100, listOf(listOf("h", h), listOf("t", list)), "To-do $k"))
                    in 96..97 -> out.add(ev(who, at, 30078, listOf(listOf("d", "seen:$h"), listOf("h", h))))
                    else -> out.add(ev(who, at, 1985, listOf(listOf("h", h), listOf("L", "#t"), listOf("l", "topic${k % 7}", "#t"))))
                }
            }
        }
        return out
    }

    private inline fun <T> timed(label: String, lines: MutableList<String>, runs: Int = 5, block: () -> T): T {
        var best = Long.MAX_VALUE
        var last: T? = null
        repeat(runs) { val t = System.nanoTime(); last = block(); best = minOf(best, System.nanoTime() - t) }
        lines.add("%-58s %8.2f ms".format(label, best / 1e6))
        @Suppress("UNCHECKED_CAST") return last as T
    }

    @Test
    fun aLargeLog() {
        val events = log(groups = 40, perGroup = 750)
        val ctx = WorldContext(me = people[3], domain = "example.org", communityName = "Example", zone = ZoneOffset.UTC)
        val lines = ArrayList<String>()
        lines.add("events: ${events.size}")
        // warm the JIT a little, as an app that has been open for a minute
        repeat(3) { World(events, emptyList(), ctx).let { w -> w.groups.forEach { g -> w.feedRows(g.id); w.channelLine(g.id, now) } } }

        val w = timed("new World + groups (the drawer's list)", lines) { World(events, emptyList(), ctx).also { it.groups.size } }
        assertEquals(40, w.groups.size)
        timed("open a channel: feedRows + reactions + threads", lines) { World(events, emptyList(), ctx).let { it.feedRows("general").size + it.reactions("general").size + it.threadsOf("general").size } }
        timed("the drawer: public or not + unread marker, 40 channels", lines) { World(events, emptyList(), ctx).let { x -> x.groups.count { x.isPublicChannel(it) } + x.groups.sumOf { g -> x.mySeen(g.id) }.toInt() } }
        timed("the line of one channel (facts, to-dos, pins)", lines) { World(events, emptyList(), ctx).channelLine("general", now).counts.size }
        timed("the lines of all 40 channels", lines) { World(events, emptyList(), ctx).let { x -> x.groups.sumOf { x.channelLine(it.id, now).counts.size } } }
        timed("search \"message 12\" everywhere", lines, runs = 3) { World(events, emptyList(), ctx).search("message 12").size }
        // what one new message costs: the next snapshot and the screen that is open
        val more = events + ev(people[1], now, 9, listOf(listOf("h", "general")), "One more")
        timed("one more message: next World + the open channel again", lines) {
            World(more, emptyList(), ctx).let { it.groups.size + it.feedRows("general").size + it.channelLine("general", now).counts.size }
        }
        // the same views asked again on one snapshot (recompositions): must be cached
        val warm = World(events, emptyList(), ctx).also { it.feedRows("general"); it.channelLine("general", now); it.groups.forEach { g -> it.isPublicChannel(g) } }
        timed("asked again on the same snapshot (40 lines + drawer)", lines) { warm.groups.sumOf { warm.channelLine(it.id, now).counts.size } + warm.groups.count { warm.isPublicChannel(it) } }

        val text = lines.joinToString("\n") + "\n"
        File(System.getProperty("perf.out") ?: "build/perf.txt").apply { parentFile?.mkdirs() }.writeText(text)
        println(text)
        assertTrue(events.size > 25_000)
    }
}
