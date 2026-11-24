package sh.belfry.core.enc

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import sh.belfry.core.crypto.Hex
import sh.belfry.core.crypto.Keys
import sh.belfry.core.crypto.Nip44
import sh.belfry.core.crypto.sha256Hex
import sh.belfry.core.nostr.Event
import sh.belfry.core.nostr.EventTemplate
import sh.belfry.core.nostr.Tag

/** OK, WAIT (keep it and judge again when lists come), NO (drop). */
enum class Verdict { OK, WAIT, NO }

/** A conversation key pair: SECRET = k (hex), PUB = K (x-only hex, the key's name). */
data class KeyPair(val secret: String, val pub: String)

/** A person of a people key: the account and, when it has one, its current list id. */
data class KeyPerson(val account: String, val list: String? = null)

/** A person of a people envelope: the account and the keys that get an entry (its list's receivers). */
data class EnvelopeFor(val account: String, val devices: List<String>)

/** A seal and the rumor it held (the rumor's id computed here). */
data class Opened(val seal: Event, val rumor: Event)

/** A group key from my entry (§16.1, §18.4): SECRET = k, PUB = K; ACCOUNT = the envelope's signer; SET = the hash of the devices with an entry. */
data class GroupKey(val secret: String, val pub: String, val group: String, val maker: String, val account: String, val at: Long, val set: String)

/**
 * A key this device holds, for [EncKeys.pickKey]. MINE: made by a device of my own account (§17.8);
 * SERVICE: made by the belfry key or the groups key (never sent with, §18.7); LISTS: people → the
 * lists it was made for ([EncKeys.peopleLists]), group → the hash of its entry set ([EncKeys.deviceSet]).
 */
data class HeldKey(
    val pub: String,
    val secret: String,
    val conv: String,
    val mine: Boolean,
    val lists: String,
    val at: Long,
    val group: Boolean = false,
    val service: Boolean = false,
)

/** §5, §16, §18.4, §18.7: names, conversation keys, their envelopes, and the key to send with. */
object EncKeys {
    /** §5: the key alias of K for ACCOUNT: sha256("belfry:key:" + K + ":" + account). */
    fun alias(K: String, account: String): String = sha256Hex("belfry:key:$K:$account")

    /** §16.1: the lists hash of a group key: sha256 of the sorted lines "account:list id", joined by "\n". */
    fun listsHash(lists: List<Pair<String, String>>): String = sha256Hex(lists.map { "${it.first}:${it.second}" }.sorted().joinToString("\n"))

    /** A people conversation's id: its accounts, sorted, once each, joined by ",". */
    fun peopleId(accounts: Iterable<String>): String = accounts.toSet().sorted().joinToString(",")

    /** The accounts of a rumor's people conversation: its p tags (keys only) and its author. */
    fun peopleOf(r: Event): String = peopleId(listOf(r.pubkey) + Enc.values(r, "p").filter { Enc.isKey(it) })

    /** The lists a people key is made for, as one string ("account:listid" lines, sorted). */
    fun peopleLists(lists: List<Pair<String, String>>): String = lists.map { "${it.first}:${it.second}" }.sorted().joinToString("\n")

    /** §18.4: the hash of a set of device keys (once each, sorted, joined by "\n"). */
    fun deviceSet(devices: Iterable<String>): String = sha256Hex(devices.toSet().sorted().joinToString("\n"))

    /** A new conversation key (SECRET fixed only by tests). */
    fun newKey(secret: ByteArray = randomSecret()): KeyPair = KeyPair(Hex.encode(secret), Enc.pub(secret))

    private fun randomSecret(): ByteArray = Keys.generateSecret()

    /** §5 the key rumor of a people conversation, made by the device MAKER for PEOPLE (an account without a list: no list tag). */
    fun keyRumor(maker: String, k: String, people: List<KeyPerson>, at: Long = Enc.now()): Event {
        val K = Enc.pub(Hex.decode(k))
        val tags = listOf(listOf("K", K)) + people.map { listOf("p", it.account) } + people.filter { it.list != null }.map { listOf("list", it.account, it.list!!) }
        return Enc.withId(Event("", maker, at, Enc.KEY, tags, k))
    }

    /** §5 + §16.2 + §17 + §20: may RECEIVER (an account) take this people key rumor? DEVICES: each account's devices. */
    fun acceptKeyRumor(r: Event, receiver: String, devices: (String) -> Devices, now: Long = Enc.now()): Verdict {
        if (r.kind != Enc.KEY || !Enc.isKey(r.content) || r.createdAt > now + Enc.FUTURE) return Verdict.NO
        val K = Enc.one(r, "K")
        val pub = try { Enc.pub(Hex.decode(r.content)) } catch (e: Exception) { return Verdict.NO }
        if (K.isNullOrEmpty() || K != pub) return Verdict.NO
        val people = Enc.values(r, "p")
        if (receiver !in people || people.any { !Enc.isKey(it) }) return Verdict.NO
        var wait = false
        for (a in people) {
            val c = devices(a)
            if (c == Devices.Service) { if (r.pubkey == a) return Verdict.OK; continue } // the belfry or the groups key: its own device
            val named = r.tags.firstOrNull { it.size >= 2 && it[0] == "list" && it[1] == a }?.getOrNull(2)
            if (named.isNullOrEmpty()) continue
            val chain = (c as? Devices.Listed)?.chain
            if (chain == null) { wait = true; continue }
            val l = chain.listById(named)
            if (l == null) { wait = true; continue } // the named list isn't loaded yet
            if (r.pubkey in l.devices) return Verdict.OK // a self account's key is on its own list (§20)
        }
        return if (wait) Verdict.WAIT else Verdict.NO
    }

    /** DEVICES' entries in chunks whose JSON stays under MAX_ENVELOPE bytes; ENTRY(chunk, device) encrypts one entry (again when it opens a new chunk). */
    private fun split(devices: List<String>, entry: (Int, String) -> String): List<LinkedHashMap<String, String>> {
        val out = mutableListOf(LinkedHashMap<String, String>())
        var size = 2
        for (d in devices) {
            var v = entry(out.size - 1, d)
            val add = d.length + v.length + 6
            if (size + add > Enc.MAX_ENVELOPE && out.last().isNotEmpty()) {
                out += LinkedHashMap<String, String>(); size = 2; v = entry(out.size - 1, d)
            }
            out.last()[d] = v
            size += d.length + v.length + 6
        }
        return out
    }

    private fun mapJson(m: Map<String, String>): String = EncJson.stringify(JsonObject(m.mapValues { JsonPrimitive(it.value) }))

    /**
     * §5 people envelopes: for each person, kind 8571 by a one-time key, `["p", account]`,
     * `["d", alias]`, content {device: nip44(one-time → device, seal by MAKER of the key rumor)}; split at MAX_ENVELOPE.
     */
    fun peopleEnvelopes(maker: ByteArray, r: Event, people: List<EnvelopeFor>, f: Fixed? = null): List<Event> {
        val K = Enc.one(r, "K") ?: throw EncException("the key rumor has no K")
        val now = f?.at ?: Enc.now()
        val out = mutableListOf<Event>()
        var n = 0
        val rumor = EncJson.rumorJson(r)
        for (p in people) {
            val seals = LinkedHashMap<String, String>()
            for (d in p.devices) {
                val seal = Enc.signWith(maker, Enc.SEAL, f?.sealAt ?: now, emptyList(), Nip44.encrypt(rumor, Enc.conv(maker, d), f.nonceOf(n++)), f?.aux)
                seals[d] = EncJson.signedJson(seal)
            }
            val onces = HashMap<Int, ByteArray>()
            val onceOf = { i: Int -> onces.getOrPut(i) { f?.oneTime ?: Keys.generateSecret() } }
            for ((i, content) in split(p.devices) { i, d -> Nip44.encrypt(seals.getValue(d), Enc.conv(onceOf(i), d), f.nonceOf(n++)) }.withIndex()) {
                out += Enc.signWith(onceOf(i), Enc.KEY, now, listOf(listOf("p", p.account), listOf("d", alias(K, p.account))), mapJson(content), f?.aux)
            }
        }
        return out
    }

    private fun entryMap(env: Event): JsonObject? = EncJson.parse(env.content) as? JsonObject

    /** My entry of a people envelope, or null when it has none for this device. Throws [EncException] on a bad one. */
    fun openPeopleEnvelope(device: ByteArray, env: Event): Opened? {
        if (!Enc.valid(env) || env.kind != Enc.KEY) throw EncException("not an envelope")
        val me = Enc.pub(device)
        val map = EncJson.parse(env.content) ?: throw EncException("not an envelope")
        val entry = ((map as? JsonObject)?.get(me) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val seal = (try { EncJson.event(Nip44.decrypt(entry, Enc.conv(device, env.pubkey))) } catch (e: Exception) { throw EncException("the entry does not open") })
            ?: throw EncException("the seal is not signed")
        if (!Enc.valid(seal) || seal.kind != Enc.SEAL) throw EncException("the seal is not signed")
        val rumor = (try { EncJson.rumor(EncJson.parse(Nip44.decrypt(seal.content, Enc.conv(device, seal.pubkey)))) } catch (e: Exception) { null })
            ?: throw EncException("the seal does not open")
        if (rumor.pubkey != seal.pubkey) throw EncException("the key rumor is not the seal signer's")
        return Opened(seal, rumor)
    }

    /**
     * §16.1 + §18.4 group envelopes, unsigned (the maker's account signs them through the signer):
     * tags h, d = K, maker, lists; content {device: nip44(maker device → device, {"k","K","h","set"})}, split.
     */
    fun groupEnvelopes(maker: ByteArray, k: String, group: String, devices: List<String>, lists: String, f: Fixed? = null): List<EventTemplate> {
        val K = Enc.pub(Hex.decode(k))
        val body = EncJson.stringify(buildJsonObject {
            put("k", JsonPrimitive(k)); put("K", JsonPrimitive(K)); put("h", JsonPrimitive(group)); put("set", JsonPrimitive(deviceSet(devices)))
        })
        var n = 0
        val tags: List<Tag> = listOf(listOf("h", group), listOf("d", K), listOf("maker", Enc.pub(maker)), listOf("lists", lists))
        val at = f?.at ?: Enc.now()
        return split(devices) { _, d -> Nip44.encrypt(body, Enc.conv(maker, d), f.nonceOf(n++)) }.map { EventTemplate(Enc.KEY, mapJson(it), tags, at) }
    }

    /** The device keys that have an entry, over every envelope event of one key (a group's d), sorted. */
    fun entryKeys(envs: List<Event>): List<String> {
        val out = sortedSetOf<String>()
        for (e in envs) {
            if (!Enc.valid(e) || e.kind != Enc.KEY) continue
            entryMap(e)?.keys?.filter { Enc.isKey(it) }?.let { out += it }
        }
        return out.toList()
    }

    /**
     * My entry of a group envelope found in GROUP, or null when there is none for this device. Throws
     * on a bad one. §17.15: the entry, opened with the maker device's key, is what authenticates.
     */
    fun openGroupEnvelope(device: ByteArray, env: Event, group: String): GroupKey? {
        if (!Enc.valid(env) || env.kind != Enc.KEY) throw EncException("not an envelope")
        val h = Enc.one(env, "h")
        val d = Enc.one(env, "d")
        val maker = Enc.one(env, "maker")
        if (h != group || !Enc.isKey(d) || !Enc.isKey(maker)) throw EncException("not this group's envelope")
        val map = EncJson.parse(env.content) ?: throw EncException("not an envelope")
        val entry = ((map as? JsonObject)?.get(Enc.pub(device)) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val x = (try { EncJson.parse(Nip44.decrypt(entry, Enc.conv(device, maker!!))) as? JsonObject } catch (e: Exception) { null })
            ?: throw EncException("the entry does not open")
        fun s(key: String) = (x[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val k = s("k")
        val set = s("set")
        val ok = Enc.isKey(k) && s("K") == d && s("h") == group && Enc.isKey(set) &&
            try { Enc.pub(Hex.decode(k!!)) == d } catch (e: Exception) { false }
        if (!ok) throw EncException("the entry is not this key")
        return GroupKey(k!!, d!!, group, maker!!, env.pubkey, env.createdAt, set!!)
    }

    /** §16.2 + §21: the maker was a device of the envelope's account at its date (the start-of-listing grace included). */
    fun acceptGroupKey(key: GroupKey, devices: Devices, now: Long = Enc.now()): Verdict {
        if (key.at > now + Enc.FUTURE) return Verdict.NO
        return when (devices) {
            Devices.Service -> if (key.maker == key.account) Verdict.OK else Verdict.NO
            Devices.Unknown -> Verdict.WAIT
            is Devices.Listed -> when (EncLists.listedAt(devices.chain, key.maker, key.at)) { null -> Verdict.WAIT; true -> Verdict.OK; false -> Verdict.NO }
        }
    }

    /**
     * §5 + §17.8 + §18.7: the key to send with in CONV: the newest younger than 30 days and not more
     * than 15 minutes ahead, made for exactly LISTS (people: by my own account's devices; group: any
     * member's whose authenticated entry set is the device set). Never a service key. Null: make one.
     */
    fun pickKey(held: List<HeldKey>, conv: String, lists: String, now: Long = Enc.now()): HeldKey? =
        held.filter { it.conv == conv && it.lists == lists && !it.service && (it.mine || it.group) && now - it.at < Enc.KEY_AGE && it.at <= now + Enc.FUTURE }
            .sortedWith(compareByDescending<HeldKey> { it.at }.thenBy { it.pub })
            .firstOrNull()
}
