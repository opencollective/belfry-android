package sh.belfry.core.sealed

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import sh.belfry.core.crypto.Hex
import sh.belfry.core.crypto.Keys
import sh.belfry.core.crypto.Nip44
import sh.belfry.core.dm.GiftWrap
import sh.belfry.core.nostr.Event
import sh.belfry.core.nostr.EventTemplate
import sh.belfry.core.nostr.LocalSigner
import sh.belfry.core.nostr.isValid
import sh.belfry.core.nostr.parseJsonOrNull
import sh.belfry.core.nostr.unixNow
import sh.belfry.core.nostr.withId

/**
 * Every letterbox secret this device was ever given, each by its public key (sealed.md §2).
 * Never logged; the app keeps it in `Secrets` per account slot, never in the store.
 */
class KeyChain(keys: Map<String, String> = emptyMap()) {
    private val map = LinkedHashMap<String, String>()

    init { keys.forEach { (pub, secret) -> add(pub, secret) } }

    val size: Int get() = map.size
    val publicKeys: Set<String> get() = map.keys

    fun secretFor(lb: String): String? = map[lb]

    /** Adds a pair only when the secret's public key is LB (§5.5: "checks each secret against its lb"). */
    fun add(lb: String, secret: String): Boolean {
        if (!Hex.isKey(lb) || !Hex.isKey(secret)) return false
        val pub = try { Keys.publicKeyHex(secret) } catch (e: Exception) { return false }
        if (pub != lb) return false
        map[lb] = secret
        return true
    }

    /** Merges the valid pairs of OTHER; how many were new. */
    fun merge(other: Map<String, String>): Int = other.count { (pub, secret) -> pub !in map && add(pub, secret) }

    fun toMap(): Map<String, String> = LinkedHashMap(map)

    /** The `keys` array of a handover (8561). */
    fun toJson(): JsonArray = buildJsonArray { map.forEach { (lb, s) -> add(buildJsonObject { put("lb", JsonPrimitive(lb)); put("secret", JsonPrimitive(s)) }) } }

    companion object {
        /** A new letterbox key pair: (public, secret). */
        fun newLetterbox(): Pair<String, String> {
            val secret = Keys.generateSecretHex()
            return Keys.publicKeyHex(secret) to secret
        }
    }
}

/** What opening a wrap gave (§4). */
sealed interface Opening {
    /** No `lb` tag: the old kind, for the signer as today. */
    data object NotSealed : Opening
    /** No secret for its `lb` on this device: locked here; keep the wrap. */
    data class Locked(val lb: String) : Opening
    /** Broken or forged: ignore it. */
    data object Invalid : Opening
    /** Opened. The caller still checks `sealPubkey` against the author's chain ([Chain.accepts]). */
    data class Opened(val rumor: Event, val sealPubkey: String, val lb: String) : Opening
}

/** Sealing and opening sealed messages (§4), handing keys over (§5.5), requests (§5.2), shared history (§8). */
object Sealing {
    const val SEAL = 13
    const val WRAP = 1059
    const val KEYS = 8561
    const val REQUEST = 8562
    const val SHARE = 8564

    fun isSealed(wrap: Event): Boolean = wrap.kind == WRAP && wrap.tag("lb") != null

    /**
     * One wrap of RUMOR for a person: the seal signed by DEVICE and encrypted to their letterbox
     * LB, the wrap signed by a one-time key, tagged `p` (their account) and `lb`.
     */
    fun seal(rumor: Event, device: LocalSigner, recipient: String, lb: String, now: Long = unixNow()): Event {
        require(Hex.isKey(recipient) && Hex.isKey(lb))
        val sealed = Nip44.encryptFor(device.secretHex, lb, rumor.copy(sig = "").toJsonString())
        val seal = device.signNow(EventTemplate(SEAL, sealed, emptyList(), GiftWrap.backdate(now)))
        val oneTime = LocalSigner.random()
        val content = oneTime.nip44EncryptNow(lb, seal.toJsonString())
        return oneTime.signNow(EventTemplate(WRAP, content, listOf(listOf("p", recipient), listOf("lb", lb)), GiftWrap.backdate(now)))
    }

    /**
     * The wraps of one message: one per participant's head letterbox and one to my own head
     * (the self-copy). HEADS maps each account (me included) to its head `lb`.
     */
    fun sealAll(rumor: Event, device: LocalSigner, heads: Map<String, String>, now: Long = unixNow()): List<Event> =
        heads.map { (account, lb) -> seal(rumor, device, account, lb, now) }

    /**
     * Wrap → seal (signature checked) → rumor, with the letterbox secret found by the `lb` tag.
     * BELFRY is the belfry's mail key: a mail rumor (1301) whose seal it signed is accepted as from
     * the belfry (§7); every other rumor must claim the account whose device signed (checked by the caller).
     */
    fun open(wrap: Event, keys: KeyChain): Opening {
        if (wrap.kind != WRAP) return Opening.Invalid
        val lb = wrap.tag("lb") ?: return Opening.NotSealed
        val secret = keys.secretFor(lb) ?: return Opening.Locked(lb)
        return try {
            val seal = Event.fromJsonString(Nip44.decryptFrom(secret, wrap.pubkey, wrap.content)) ?: return Opening.Invalid
            if (seal.kind != SEAL || !seal.isValid()) return Opening.Invalid
            var rumor = Event.fromJsonString(Nip44.decryptFrom(secret, seal.pubkey, seal.content)) ?: return Opening.Invalid
            rumor = rumor.withId().copy(sig = "")
            Opening.Opened(rumor, seal.pubkey, lb)
        } catch (e: Exception) {
            Opening.Invalid
        }
    }

    // ---- §5.5 handing keys over ---------------------------------------------------------------

    /** A gift wrap to the receiving DEVICE carrying the giver's whole chain (rumor 8561). */
    suspend fun keysFor(giver: LocalSigner, account: String, receivingDevice: String, chain: KeyChain, now: Long = unixNow()): Event {
        val content = buildJsonObject {
            put("v", JsonPrimitive(1))
            put("account", JsonPrimitive(account))
            put("keys", chain.toJson())
        }.toString()
        val rumor = GiftWrap.rumor(KEYS, giver.pubkey, listOf(listOf("p", account)), content, now)
        return GiftWrap.wrap(rumor, receivingDevice, giver)
    }

    /** A received handover: who gave it (a device) and the pairs that check out. */
    class Handover(val giver: String, val account: String, val keys: Map<String, String>)

    /**
     * Opens a handover with this device's key. The caller accepts it only when [Handover.giver] is a
     * device of a roster of its own account's chains ([Chain.everHad]).
     */
    suspend fun openKeys(wrap: Event, device: LocalSigner): Handover? {
        if (wrap.kind != WRAP || wrap.tag("p") != device.pubkey) return null
        val opened = try { GiftWrap.unwrap(wrap, device) } catch (e: Exception) { null } ?: return null
        val r = opened.rumor
        if (r.kind != KEYS) return null
        val o = parseJsonOrNull(r.content) as? JsonObject ?: return null
        if ((o["v"] as? JsonPrimitive)?.longOrNull != 1L) return null
        val account = (o["account"] as? JsonPrimitive)?.content?.takeIf { Hex.isKey(it) } ?: return null
        if (r.tag("p") != account) return null
        val check = KeyChain()
        (o["keys"] as? JsonArray)?.forEach { k ->
            val ko = k as? JsonObject ?: return@forEach
            val lb = (ko["lb"] as? JsonPrimitive)?.content ?: return@forEach
            val s = (ko["secret"] as? JsonPrimitive)?.content ?: return@forEach
            check.add(lb, s)
        }
        return Handover(opened.sealPubkey, account, check.toMap())
    }

    // ---- §5.2 a new device asks ---------------------------------------------------------------

    /** HMAC-SHA256(link secret, device pubkey bytes), hex: the `link` tag of a request from a Link-a-phone code. */
    fun linkProof(linkSecret: String, device: String): String = Hex.encode(Nip44.hmacSha256(Hex.decode(linkSecret), Hex.decode(device)))

    /** The request (8562) a new device publishes, signed by its own key. */
    fun request(account: String, device: String, linkSecret: String?, now: Long = unixNow()): EventTemplate {
        val tags = arrayListOf(listOf("p", account))
        if (linkSecret != null) tags.add(listOf("link", linkProof(linkSecret, device)))
        return EventTemplate(REQUEST, "", tags, now)
    }

    /** A request's device and account when it is well formed and signed, else null. */
    fun readRequest(e: Event): Pair<String, String>? {
        if (e.kind != REQUEST || !e.isValid()) return null
        val account = e.tags.singleValue("p")?.takeIf { Hex.isKey(it) } ?: return null
        return e.pubkey to account
    }

    /** Does a request carry a valid proof for one of the link SECRETS this device showed (last 15 minutes)? */
    fun linkMatches(e: Event, secrets: Collection<String>): Boolean {
        val proof = e.tag("link") ?: return false
        return secrets.any { linkProof(it, e.pubkey).equals(proof, ignoreCase = true) }
    }

    // ---- §8 sharing history -------------------------------------------------------------------

    const val SHARE_MAX_RUMORS = 200
    const val SHARE_MAX_BYTES = 200_000

    /** The 8564 rumors sharing RUMORS (oldest first) with PERSON, split at 200 rumors / 200 kB each. */
    fun shareRumors(sharer: String, person: String, rumors: List<Event>, now: Long = unixNow()): List<Event> {
        val chunks = ArrayList<List<JsonObject>>()
        var cur = ArrayList<JsonObject>()
        var bytes = 0
        for (r in rumors) {
            val j = r.copy(sig = "").toJson()
            val size = j.toString().toByteArray(Charsets.UTF_8).size
            if (cur.isNotEmpty() && (cur.size >= SHARE_MAX_RUMORS || bytes + size + 40 > SHARE_MAX_BYTES)) {
                chunks.add(cur); cur = ArrayList(); bytes = 0
            }
            cur.add(j); bytes += size + 1
        }
        if (cur.isNotEmpty()) chunks.add(cur)
        return chunks.map { list ->
            val content = buildJsonObject { put("v", JsonPrimitive(1)); put("rumors", JsonArray(list)) }.toString()
            GiftWrap.rumor(SHARE, sharer, listOf(listOf("p", person)), content, now)
        }
    }

    /** The rumors of a received 8564 (each with its own id recomputed), or empty. */
    fun sharedRumors(share: Event): List<Event> {
        if (share.kind != SHARE) return emptyList()
        val o = parseJsonOrNull(share.content) as? JsonObject ?: return emptyList()
        if ((o["v"] as? JsonPrimitive)?.longOrNull != 1L) return emptyList()
        return (o["rumors"] as? JsonArray)?.mapNotNull { el -> Event.fromJson(el)?.withId()?.copy(sig = "") } ?: emptyList()
    }

    // ---- §6 when a conversation is sealed -----------------------------------------------------

    /** Every participant, me included, has a ready chain. */
    fun canSeal(participants: Collection<String>, chains: Map<String, Chain?>): Boolean =
        participants.isNotEmpty() && participants.all { chains[it]?.ready == true }

    /** May the roster I publish say `ready`: every live session's client key is a device of it. */
    fun readyFor(devices: Collection<String>, liveSessionKeys: Collection<String>): Boolean =
        liveSessionKeys.isNotEmpty() && liveSessionKeys.all { it in devices }
}

/** My device names (30078 `d = belfry:devices`, §3.5): NIP-44 to the head letterbox itself. */
object DeviceNames {
    const val D = "belfry:devices"

    data class Entry(val name: String, val kind: String, val added: Long?, val extra: Map<String, JsonElement> = emptyMap())

    /** The entries by device pubkey, plus the whole object (to keep unknown fields). */
    class Doc(val devices: Map<String, Entry>, val raw: JsonObject)

    fun open(e: Event, keys: KeyChain): Doc? {
        if (e.kind != Anchor.KIND || e.tag("d") != D || !e.isValid()) return null
        val lb = e.tag("lb") ?: return null
        val secret = keys.secretFor(lb) ?: return null
        val o = try { parseJsonOrNull(Nip44.decryptFrom(secret, lb, e.content)) as? JsonObject } catch (x: Exception) { null } ?: return null
        val devs = (o["devices"] as? JsonObject) ?: JsonObject(emptyMap())
        val map = LinkedHashMap<String, Entry>()
        devs.forEach { (pk, v) ->
            val d = v as? JsonObject ?: return@forEach
            val name = (d["name"] as? JsonPrimitive)?.content?.trim()?.take(80) ?: return@forEach
            val kind = (d["kind"] as? JsonPrimitive)?.content?.takeIf { it in KINDS } ?: "phone"
            map[pk] = Entry(name, kind, (d["added"] as? JsonPrimitive)?.longOrNull, d.filterKeys { it !in setOf("name", "kind", "added") })
        }
        return Doc(map, o)
    }

    /**
     * The content to publish: DOC with MY entry written (my own name for myself), other entries and
     * unknown fields kept, entries of devices in no roster dropped after 90 days.
     */
    fun write(doc: Doc?, me: String, mine: Entry, everInRoster: (String) -> Boolean, droppedSince: (String) -> Long?, now: Long): JsonObject {
        val base = doc?.raw ?: JsonObject(emptyMap())
        val old = (base["devices"] as? JsonObject) ?: JsonObject(emptyMap())
        val devices = LinkedHashMap<String, JsonElement>()
        old.forEach { (pk, v) ->
            if (pk == me) return@forEach
            val gone = droppedSince(pk)
            if (!everInRoster(pk) && gone != null && now - gone > 90 * 86400L) return@forEach
            devices[pk] = v
        }
        devices[me] = JsonObject(mine.extra + mapOf("name" to JsonPrimitive(mine.name), "kind" to JsonPrimitive(mine.kind)) + (mine.added?.let { mapOf("added" to JsonPrimitive(it)) } ?: emptyMap()))
        return JsonObject(base + mapOf("v" to JsonPrimitive(1), "devices" to JsonObject(devices)))
    }

    /** The 30078 to sign with the account: content encrypted with conv(lb secret, lb pub). */
    fun template(content: JsonObject, lb: String, lbSecret: String, now: Long): EventTemplate =
        EventTemplate(Anchor.KIND, Nip44.encryptFor(lbSecret, lb, content.toString()), listOf(listOf("d", D), listOf("lb", lb)), now)

    val KINDS = setOf("phone", "tablet", "computer")
}
