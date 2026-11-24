package sh.belfry.core.engine

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import sh.belfry.core.crypto.Hex
import sh.belfry.core.enc.EncFormatState
import sh.belfry.core.enc.HeldKey
import sh.belfry.core.net.Community
import sh.belfry.core.nostr.arr
import sh.belfry.core.nostr.long
import sh.belfry.core.nostr.obj
import sh.belfry.core.nostr.parseJsonOrNull
import sh.belfry.core.nostr.s
import sh.belfry.core.nostr.str

/**
 * One belfry's `encrypted.format` as this DEVICE knows it (SPEC 19.82; docs/encrypted.md §12, §17.13):
 * kept per belfry (its domain), device-wide in the vault under `enc-format:<domain>` — never per account
 * slot, so it outlives sign-outs and account switches (Onboarding.signOut never removes it). Not a secret.
 * Without a vault (tests) it lives in memory.
 */
class EncFormatCache(private val vault: Vault?, domain: String) {
    private val name = V_FORMAT + Community.cleanDomain(domain)
    /** Without a vault, or when the vault refused the last write: this object only. */
    private val memory = java.util.concurrent.ConcurrentHashMap<String, EncFormatState>()

    fun state(): EncFormatState {
        memory[name]?.let { return it } // no vault, or the vault refused the last write
        val o = parseJsonOrNull(vault?.get(name) ?: return EncFormatState()) as? JsonObject ?: return EncFormatState()
        val f = o["format"].long()?.toInt()?.takeIf { it == 1 || it == 3 }
        return EncFormatState(f, (o["seen"] as? JsonPrimitive)?.content == "true", o["read_at"].long() ?: 0, o["since3"].long() ?: 0)
    }

    /** A successful read of belfry.json said DOC (`Community.encrypted`) at NOW. Returns the state after it. */
    @Synchronized
    fun record(doc: String, now: Long): EncFormatState {
        val before = state()
        val next = before.after(doc, now)
        if (next != before) save(next)
        return next
    }

    private fun save(s: EncFormatState) {
        val v = vault
        if (v == null) { memory[name] = s; return }
        try {
            memory.remove(name)
            v.set(name, buildJsonObject {
                s.format?.let { put("format", JsonPrimitive(it)) }
                put("seen", JsonPrimitive(s.seen))
                put("read_at", JsonPrimitive(s.readAt))
                if (s.since3 > 0) put("since3", JsonPrimitive(s.since3))
            }.toString())
        } catch (e: Exception) { /* the Keystore said no: this run only (a 3 stays a 3 in memory below) */ memory[name] = s }
    }

    companion object {
        const val V_FORMAT = "enc-format:"
    }
}

/**
 * A conversation key this device holds (docs/encrypted.md §5, §16, §17.12, §18.4): K → k, the
 * conversation it was first declared for (`n:<others>` / `n:<me>` for people, `g:<id>` for a group), the
 * maker device, its date, MINE (made by a device of my own account: §17.8), SERVICE (the belfry key or
 * the groups key made it: never sent with), GROUP, LISTS (people: [sh.belfry.core.enc.EncKeys.peopleLists];
 * group: the authenticated entry set, '' until it adds up) and PEOPLE (the accounts of a people key).
 */
data class ConvKey(
    val pub: String,
    val secret: String,
    val conv: String,
    val maker: String,
    val at: Long,
    val mine: Boolean,
    val service: Boolean = false,
    val group: Boolean = false,
    val lists: String = "",
    val people: List<String> = emptyList(),
    /**
     * SPEC 19.83: a key this device made whose envelopes the outbox hasn't confirmed out yet (their event ids). A
     * message under it waits until they are; one that isn't queued any more is made again from [made] (same K).
     */
    val unconfirmed: List<String> = emptyList(),
    /** What makes this key's envelopes again with fresh dates (a key this device made): its rumor and receivers. */
    val made: MadeKey? = null,
    /** SPEC 19.85 (§7.5): a received people key's rumor as it came (it holds k: vault only), handed on as is in a key pack. */
    val rumor: sh.belfry.core.nostr.Event? = null,
) {
    fun held(): HeldKey = HeldKey(pub, secret, conv, mine, lists, at, group, service)

    /** Never in a log or a string: the secret stays out of toString. */
    override fun toString(): String = "ConvKey($pub, $conv)"

    internal fun toJson(): JsonObject = buildJsonObject {
        put("K", JsonPrimitive(pub)); put("k", JsonPrimitive(secret)); put("conv", JsonPrimitive(conv))
        put("maker", JsonPrimitive(maker)); put("at", JsonPrimitive(at)); put("mine", JsonPrimitive(mine))
        if (service) put("service", JsonPrimitive(true))
        if (group) put("group", JsonPrimitive(true))
        put("lists", JsonPrimitive(lists))
        if (people.isNotEmpty()) put("people", buildJsonArray { people.forEach { add(JsonPrimitive(it)) } })
        if (unconfirmed.isNotEmpty()) put("unconfirmed", buildJsonArray { unconfirmed.forEach { add(JsonPrimitive(it)) } })
        if (made != null) put("made", buildJsonObject {
            put("rumor", made.rumor.toJson())
            put("per", buildJsonArray { made.per.forEach { p -> add(buildJsonObject { put("account", JsonPrimitive(p.account)); put("devices", buildJsonArray { p.devices.forEach { add(JsonPrimitive(it)) } }) }) } })
        })
        if (rumor != null) put("rumor", rumor.toJson())
    }

    internal companion object {
        fun fromJson(o: JsonObject): ConvKey? {
            val K = o.s("K")?.takeIf(Hex::isKey) ?: return null
            val k = o.s("k")?.takeIf(Hex::isKey) ?: return null
            val conv = o.s("conv")?.takeIf { it.isNotEmpty() } ?: return null
            fun b(n: String) = (o[n] as? JsonPrimitive)?.content == "true"
            val made = o["made"].obj()?.let { m ->
                val r = m["rumor"].obj()?.let { sh.belfry.core.nostr.Event.fromJson(it) } ?: return@let null
                val per = m["per"].arr()?.mapNotNull { el -> el.obj()?.let { p -> p.s("account")?.takeIf(Hex::isKey)?.let { a -> sh.belfry.core.enc.EnvelopeFor(a, p["devices"].arr()?.mapNotNull { it.str()?.takeIf(Hex::isKey) } ?: emptyList()) } } } ?: return@let null
                MadeKey(r, per)
            }
            return ConvKey(K, k, conv, o.s("maker") ?: "", o["at"].long() ?: 0, b("mine"), b("service"), b("group"), o.s("lists") ?: "",
                o["people"].arr()?.mapNotNull { it.str()?.takeIf(Hex::isKey) } ?: emptyList(),
                o["unconfirmed"].arr()?.mapNotNull { it.str()?.takeIf(Hex::isKey) } ?: emptyList(), made,
                o["rumor"].obj()?.let { sh.belfry.core.nostr.Event.fromJson(it) })
        }
    }
}

/** A key's rumor (it holds k: kept only in the vault) and who gets an entry: its envelopes made again (SPEC 19.83). */
data class MadeKey(val rumor: sh.belfry.core.nostr.Event, val per: List<sh.belfry.core.enc.EnvelopeFor>) {
    override fun toString(): String = "MadeKey(${per.size} people)"
}

/**
 * Where an account's format-3 conversation keys are kept (SPEC 19.82): like version 1's
 * [SealedKeyStore], in the vault (the app's `Secrets`: AES-256-GCM under an Android Keystore key, the
 * record's name bound as associated data), one record per account slot (`enc-keys` / `enc-keys@<slot>`),
 * naming its account and checked on load, never in SQLite, never logged. Also there: the conversations
 * ever seen encrypted (§17.2, §26) and small notes. Wiped with the session at sign-out ([wipe], and
 * `Onboarding.signOut` removes the name); a write after that is refused. Version-1 letterbox secrets stay
 * in [SealedKeyStore].
 */
class EncKeyStore(private val vault: Vault, private val name: String, private val account: String) {
    @Volatile private var closed = false
    private val known: Boolean get() = Hex.isKey(account)

    data class Loaded(val keys: List<ConvKey>, val convs: Map<String, Long>, val notes: Map<String, String>)

    fun load(): Loaded {
        val none = Loaded(emptyList(), emptyMap(), emptyMap())
        if (!known) return none
        val o = parseJsonOrNull(vault.get(name) ?: return none) as? JsonObject ?: return none
        if (o.s("account") != account) return none // another account's keys: never used
        val keys = o["keys"].arr()?.mapNotNull { it.obj()?.let(ConvKey::fromJson) } ?: emptyList()
        val convs = o["convs"].obj()?.mapNotNull { (c, at) -> at.long()?.let { c to it } }?.toMap() ?: emptyMap()
        val notes = o["notes"].obj()?.mapNotNull { (n, v) -> v.str()?.let { n to it } }?.toMap() ?: emptyMap()
        return Loaded(keys, convs, notes)
    }

    /** True only when written and read back the same. */
    @Synchronized
    fun save(keys: Collection<ConvKey>, convs: Map<String, Long>, notes: Map<String, String>): Boolean {
        if (closed || !known) return false
        val v = buildJsonObject {
            put("account", JsonPrimitive(account))
            put("keys", JsonArray(keys.map { it.toJson() }))
            put("convs", buildJsonObject { convs.forEach { (c, at) -> put(c, JsonPrimitive(at)) } })
            put("notes", buildJsonObject { notes.forEach { (n, x) -> put(n, JsonPrimitive(x)) } })
        }.toString()
        return vault.setChecked(name, v)
    }

    /** Sign-out: forget them, and refuse any later write. */
    @Synchronized
    fun wipe() {
        closed = true
        vault.remove(name)
    }

    companion object {
        const val V_KEYS = "enc-keys"
    }
}
