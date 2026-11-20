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

    /** A sealed rumor's JSON is at most 40 000 bytes (NIP-44's 65 535 applies to the wrap around the seal); never seal a larger one. */
    const val MAX_RUMOR_BYTES = 40_000

    class TooLarge : IllegalArgumentException("a sealed message is at most 40 000 bytes")

    fun rumorBytes(rumor: Event): Int = rumor.copy(sig = "").toJsonString().toByteArray(Charsets.UTF_8).size
    fun fits(rumor: Event): Boolean = rumorBytes(rumor) <= MAX_RUMOR_BYTES

    /**
     * Never filter by `#lb` on the relay: belfry.sh's relay ignores a multi-letter tag filter and
     * returns every 1059 (the lead's live check, 2026-11-20). Ask by `#p` only; match lb here.
     */
    fun isSealed(wrap: Event): Boolean = wrap.kind == WRAP && wrap.tag("lb") != null

    /**
     * One wrap of RUMOR for a person: the seal signed by DEVICE and encrypted to their letterbox
     * LB, the wrap signed by a one-time key, tagged `p` (their account) and `lb`.
     */
    fun seal(rumor: Event, device: LocalSigner, recipient: String, lb: String, now: Long = unixNow()): Event {
        require(Hex.isKey(recipient) && Hex.isKey(lb))
        val json = rumor.copy(sig = "").toJsonString()
        if (!fits(rumor)) throw TooLarge()
        val sealed = Nip44.encryptFor(device.secretHex, lb, json)
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
        val lbs = wrap.tags.filter { it.size >= 2 && it[0] == "lb" }
        if (lbs.isEmpty()) return Opening.NotSealed
        if (lbs.size > 1) return Opening.Invalid // a tag given twice makes the event invalid (294c94f)
        val lb = lbs[0][1]
        val secret = keys.secretFor(lb) ?: return Opening.Locked(lb)
        return try {
            val seal = Event.fromJsonString(Nip44.decryptFrom(secret, wrap.pubkey, wrap.content)) ?: return Opening.Invalid
            if (seal.kind != SEAL || !seal.isValid()) return Opening.Invalid
            // the id and sig inside are ignored: the id is always computed here
            var rumor = Event.fromJsonString(Nip44.decryptFrom(secret, seal.pubkey, seal.content)) ?: return Opening.Invalid
            rumor = rumor.withId().copy(sig = "")
            Opening.Opened(rumor, seal.pubkey, lb)
        } catch (e: Exception) {
            Opening.Invalid
        }
    }

    /** What to do with a wrap (§4 / §9). */
    enum class Verdict { ACCEPT, KEEP, DROP, LOCKED, NOT_SEALED }

    const val KEEP_FOR = 30 * 86400L

    /**
     * ACCEPT when the seal's device is allowed for the rumor's author by their chain; KEEP (retry on
     * each new roster of that author and at each start) when it decrypted but the device isn't in a
     * chain loaded yet, for 30 days after FIRST_SEEN, then DROP; DROP at once when it doesn't
     * decrypt or its seal signature is bad; LOCKED when this device has no secret for its lb (keep
     * the wrap). A mail from the belfry (§7) is the caller's: its seal is the belfry's key.
     */
    fun verdict(o: Opening, authorChain: Chain?, firstSeen: Long, now: Long, belfry: String? = null, myLetterboxes: Set<String>? = null): Verdict = when (o) {
        is Opening.NotSealed -> Verdict.NOT_SEALED
        // locked only for a letterbox of MY chain; a wrap naming any other lb is dropped (anyone can write one)
        is Opening.Locked -> if (myLetterboxes == null || o.lb in myLetterboxes) Verdict.LOCKED else Verdict.DROP
        is Opening.Invalid -> Verdict.DROP
        is Opening.Opened -> when {
            // §7, the only exception: a kind 1301 whose pubkey and seal signer are both the belfry's key (from belfry.json)
            belfry != null && o.sealPubkey == belfry -> if (o.rumor.pubkey == belfry && o.rumor.kind == 1301) Verdict.ACCEPT else Verdict.DROP
            authorChain != null && authorChain.account == o.rumor.pubkey && authorChain.accepts(o.sealPubkey, o.rumor.createdAt) -> Verdict.ACCEPT
            now - firstSeen < KEEP_FOR -> Verdict.KEEP
            else -> Verdict.DROP
        }
    }

    /**
     * A queued wrap is sealed again when the head it was sealed to changed before it was sent (§9):
     * my head for the self-copy, the recipient's for theirs.
     */
    fun needsReseal(sealedTo: String, currentHead: String?): Boolean = currentHead != null && currentHead != sealedTo

    // ---- §5.5 handing keys over ---------------------------------------------------------------

    /**
     * Gift wraps to the receiving DEVICE carrying the giver's whole chain (rumors 8561), split into
     * several when one would pass the 40 000-byte ceiling (§5.5, 61d7846).
     */
    suspend fun keysFor(giver: LocalSigner, account: String, receivingDevice: String, chain: KeyChain, now: Long = unixNow()): List<Event> =
        keysRumors(giver.pubkey, account, chain, now).map { GiftWrap.wrap(it, receivingDevice, giver) }

    /** The 8561 rumors of a hand-over, each filled until one more key would take its JSON past 40 000 bytes. */
    fun keysRumors(giver: String, account: String, chain: KeyChain, now: Long = unixNow()): List<Event> {
        fun rumor(pairs: List<Pair<String, String>>): Event {
            val content = buildJsonObject {
                put("v", JsonPrimitive(1))
                put("account", JsonPrimitive(account))
                put("keys", buildJsonArray { pairs.forEach { (lb, s) -> add(buildJsonObject { put("lb", JsonPrimitive(lb)); put("secret", JsonPrimitive(s)) }) } })
            }.toString()
            return GiftWrap.rumor(KEYS, giver, listOf(listOf("p", account)), content, now)
        }
        val rumors = ArrayList<Event>()
        var cur = ArrayList<Pair<String, String>>()
        for (p in chain.toMap().toList()) {
            if (cur.isEmpty() || fits(rumor(cur + p))) cur.add(p) else { rumors.add(rumor(cur)); cur = arrayListOf(p) }
        }
        if (cur.isNotEmpty() || rumors.isEmpty()) rumors.add(rumor(cur))
        return rumors
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
        if (r.tags.count { it.size >= 2 && it[0] == "p" } != 1) return null
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
        val proof = e.tag("link")?.lowercase() ?: return false
        // constant time (294c94f)
        return secrets.any { java.security.MessageDigest.isEqual(linkProof(it, e.pubkey).toByteArray(), proof.toByteArray()) }
    }

    // ---- §8 sharing history -------------------------------------------------------------------

    const val SHARE_MAX_RUMORS = 200
    /** Only these are shared and taken (never an e-mail), §8. */
    val SHARED_KINDS = setOf(14, 7, 5)

    /**
     * The 8564 rumors sharing RUMORS (oldest first) with PERSON: at most 200 rumors each, and each
     * 8564's own JSON at most [MAX_RUMOR_BYTES] (sealed.md §4 / §8, ruled 2026-11-20); a rumor too
     * large alone is left out.
     */
    fun shareRumors(sharer: String, person: String, rumors: List<Event>, now: Long = unixNow()): List<Event> {
        // packed until the 8564's own JSON (the exact string that is encrypted) would pass 40 000 bytes
        fun build(list: List<JsonObject>): Event {
            val content = buildJsonObject { put("v", JsonPrimitive(1)); put("rumors", JsonArray(list)) }.toString()
            return GiftWrap.rumor(SHARE, sharer, listOf(listOf("p", person)), content, now)
        }
        val out = ArrayList<Event>()
        var cur = ArrayList<JsonObject>()
        for (r in rumors) {
            val j = r.copy(sig = "").toJson()
            if (!fits(build(listOf(j)))) continue // too large alone: left out
            if (cur.size < SHARE_MAX_RUMORS && fits(build(cur + j))) {
                cur.add(j)
            } else {
                out.add(build(cur))
                cur = arrayListOf(j)
            }
        }
        if (cur.isNotEmpty()) out.add(build(cur))
        return out
    }

    /**
     * The rumors of a received 8564 to add: only when its seal's signer is a device of the sharer's
     * chain (SHARE.pubkey's), and for each rumor only when both the sharer and RECEIVER are its
     * participants (author or p tags), §8. Marked as shared by the sharer: not proven authentic.
     */
    fun acceptShared(share: Event, sealPubkey: String, sharerChain: Chain?, receiver: String): List<Event> {
        if (share.kind != SHARE || sharerChain == null || sharerChain.account != share.pubkey) return emptyList()
        // the 8564's p must be the receiver (06a1386), given once (294c94f)
        val ps = share.tags.filter { it.size >= 2 && it[0] == "p" }
        if (ps.size != 1 || ps[0][1] != receiver) return emptyList()
        if (!sharerChain.accepts(sealPubkey, share.createdAt)) return emptyList()
        fun inIt(r: Event, who: String) = r.pubkey == who || r.tags.any { it.size >= 2 && it[0] == "p" && it[1] == who }
        return sharedRumors(share).filter { it.kind in SHARED_KINDS && inIt(it, share.pubkey) && inIt(it, receiver) }
    }

    /** Is a handover's giver a device of a roster of one of my account's chains (current or the pinned earlier one)? */
    fun acceptHandover(h: Handover, myAccount: String, chains: List<Chain>): Boolean =
        h.account == myAccount && chains.any { it.account == myAccount && it.everHad(h.giver) }

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

    /**
     * The client keys of the sessions that count for `ready` (§6): the belfry's own chat clients
     * (`chat` true) last active less than 30 × 86 400 s ago, by last_activity, or created_at when
     * never active. Times in Unix seconds.
     */
    fun countedSessions(sessions: List<Triple<String?, Boolean, Long?>>, now: Long): List<String?> =
        sessions.filter { (_, chat, lastSeen) -> chat && lastSeen != null && now - lastSeen < 30 * 86400L }.map { it.first }

    /**
     * May the roster I publish say `ready`: every counted session's client key is a device of it.
     * A counted session with no client key is no device (not ready); no counted session at all: ready
     * (vectors/sealed.json "ready").
     */
    fun readyFor(devices: Collection<String>, counted: Collection<String?>): Boolean =
        counted.all { it != null && it in devices }
}

/**
 * My device names (sealed.md §3.5, as replaced in 1390386): ONE event per device, kind 30078
 * `d = belfry:device:<device pubkey>`, signed by the account, written only by that device;
 * content `{"v":1,"name","kind","added"}` NIP-44 with conv(lb secret, lb pub) of the head
 * letterbox, tag `["lb", …]`. Other people never receive it.
 */
object DeviceNames {
    const val PREFIX = "belfry:device:"

    data class Entry(val name: String, val kind: String, val added: Long?)

    fun d(device: String): String = PREFIX + device

    /** The device and its entry, when this device holds the secret of its lb; else null. */
    fun open(e: Event, account: String, keys: KeyChain): Pair<String, Entry>? {
        if (e.kind != Anchor.KIND || e.pubkey != account || !e.isValid()) return null
        val device = e.tag("d")?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.takeIf { Hex.isKey(it) } ?: return null
        val lb = e.tag("lb") ?: return null
        val secret = keys.secretFor(lb) ?: return null
        val o = try { parseJsonOrNull(Nip44.decryptFrom(secret, lb, e.content)) as? JsonObject } catch (x: Exception) { null } ?: return null
        if ((o["v"] as? JsonPrimitive)?.longOrNull != 1L) return null
        val name = (o["name"] as? JsonPrimitive)?.content?.trim()?.take(80)?.ifEmpty { null } ?: return null
        val kind = (o["kind"] as? JsonPrimitive)?.content?.takeIf { it in KINDS } ?: "phone"
        return device to Entry(name, kind, (o["added"] as? JsonPrimitive)?.longOrNull)
    }

    /** The names of ACCOUNT's devices from its events, the newest per device. */
    fun all(events: List<Event>, account: String, keys: KeyChain): Map<String, Entry> =
        events.sortedWith(compareBy<Event> { it.createdAt }.thenByDescending { it.id })
            .mapNotNull { open(it, account, keys) }.toMap()

    /** My own entry, to sign with the account (rewritten under the new letterbox after a key change). */
    fun template(device: String, entry: Entry, lb: String, lbSecret: String, now: Long): EventTemplate {
        val content = buildJsonObject {
            put("v", JsonPrimitive(1))
            put("name", JsonPrimitive(entry.name))
            put("kind", JsonPrimitive(entry.kind))
            entry.added?.let { put("added", JsonPrimitive(it)) }
        }.toString()
        return EventTemplate(Anchor.KIND, Nip44.encryptFor(lbSecret, lb, content), listOf(listOf("d", d(device)), listOf("lb", lb)), now)
    }

    val KINDS = setOf("phone", "tablet", "computer")

    /**
     * This device's own name (§3.5): the `device` of its own row in /api/me/sessions (found by
     * client_pubkey), so it equals what the Allow prompt showed on the other device; the platform's
     * own name only when that is empty.
     */
    fun myName(sessionDevice: String?, platformName: String): String =
        sessionDevice?.trim()?.ifEmpty { null }?.take(80) ?: platformName.trim().take(80)
}
