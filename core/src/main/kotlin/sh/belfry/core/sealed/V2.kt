package sh.belfry.core.sealed

import sh.belfry.core.crypto.Hex
import sh.belfry.core.crypto.Nip44
import sh.belfry.core.dm.GiftWrap
import sh.belfry.core.nostr.Event
import sh.belfry.core.nostr.EventTemplate
import sh.belfry.core.nostr.LocalSigner
import sh.belfry.core.nostr.isValid
import sh.belfry.core.nostr.unixNow
import sh.belfry.core.nostr.withId

/**
 * Sealed conversations, version 2 (collective-belfry docs/sealed-v2.md at belfry-sh df53525): one list of
 * devices per account, written and signed by the belfry; a copy of every sealed message for each device. The
 * pure rules, as the vectors' `v2` section has them. Where the doc left a choice the stricter reading was taken
 * and confirmed by the lead; each is marked R1 … R13 (the section's `readings`) at the ONE place it is decided.
 */
object V2 {
    /** The `d` of an account's device list: `belfry:devices:<account hex>`. */
    const val D = "belfry:devices:"
    const val LIST_KIND = 30078
    const val WRAP = 1059
    /** A list dated more than this ahead of the reader's clock is ignored (seconds). */
    const val AHEAD = 600L
    const val MAX_DEVICES = 20
    /** The devices of all participants together; over it a conversation is not sealed. */
    const val MAX_TOTAL = 100
    const val KEEP_FOR = 30 * 86_400L

    fun d(account: String): String = D + account

    /** The account a device-list event is about, from its `d` (not checked further), else null. */
    fun accountOf(e: Event): String? =
        if (e.kind == LIST_KIND) e.tag("d")?.takeIf { it.startsWith(D) }?.removePrefix(D)?.takeIf { Hex.isKey(it) } else null

    /**
     * §3.1: the devices of ACCOUNT's list event E as a reader at NOW takes it, in the belfry's order; null when
     * E is not that list. Only from the belfry's key.
     */
    fun readList(e: Event, belfry: String, account: String, now: Long): List<String>? {
        if (e.kind != LIST_KIND || e.pubkey != belfry || !e.isValid()) return null
        // R1: exactly one d and exactly one p, and p is the account that d names
        if (e.tags.singleValue("d") != d(account) || e.tags.singleValue("p") != account) return null
        // R2: content ""
        if (e.content.isNotEmpty()) return null
        // R13: MORE than 10 minutes ahead is ignored; exactly 10 minutes is taken
        if (e.createdAt > now + AHEAD) return null
        val devices = ArrayList<String>()
        for (t in e.tags) {
            if (t.isEmpty() || t[0] != "device") continue
            // R3: a device tag that is not 64 lower-case hex characters is skipped, the others count
            val dev = t.getOrNull(1)?.takeIf { Hex.isKey(it) && it == it.lowercase() } ?: continue
            // R4: a device given twice counts once, at its first place
            if (dev !in devices) devices.add(dev)
        }
        // R5: more than 20 is not a list (the reader keeps the one it had)
        if (devices.size > MAX_DEVICES) return null
        return devices
    }

    /** A list this app has seen, and when it first saw it (§3.2: kept for good). */
    class Seen(val event: Event, val firstSeen: Long)

    /** The list a reader holds among EVENTS: the newest valid one (`created_at`, then the lower id). */
    fun newest(events: Collection<Event>, belfry: String, account: String, now: Long): Event? =
        events.filter { readList(it, belfry, account, now) != null }
            .sortedWith(compareByDescending<Event> { it.createdAt }.thenBy { it.id.lowercase() }).firstOrNull()

    // ---- wraps (§4) ------------------------------------------------------------------------------------

    enum class Kind { V2, V1, OLD, NOT_MINE, INVALID }

    /** A kind 1059 that carries a `d` tag is (meant as) a version 2 wrap. */
    fun isV2(wrap: Event): Boolean = wrap.kind == WRAP && wrap.tags.any { it.size >= 2 && it[0] == "d" }

    /** §4.2: what a kind 1059 is to this device, from its tags alone. */
    fun classify(wrap: Event, myAccount: String, myDevice: String): Kind {
        val ds = wrap.tags.filter { it.size >= 2 && it[0] == "d" }.map { it[1] }
        val lb = wrap.tags.any { it.size >= 2 && it[0] == "lb" }
        val ps = wrap.tags.filter { it.size >= 2 && it[0] == "p" }.map { it[1] }
        if (ds.isEmpty()) return if (lb) Kind.V1 else Kind.OLD
        // R6: with a d tag it is version 2 only with exactly one d, exactly one p and no lb; else invalid
        if (lb || ds.size != 1 || ps.size != 1) return Kind.INVALID
        if (ds[0] != myDevice) return Kind.NOT_MINE
        // R7: a wrap names the account of the device it is for
        return if (ps[0] == myAccount) Kind.V2 else Kind.INVALID
    }

    class Opened(val rumor: Event, val sealPubkey: String)

    /**
     * Wrap → seal (kind 13, signature checked, `tags: []`) → rumor, both NIP-44 with this DEVICE's key. The
     * rumor's id is always computed here. Null: not for this key, or broken.
     */
    fun open(wrap: Event, device: LocalSigner): Opened? = try {
        val seal = Event.fromJsonString(Nip44.decryptFrom(device.secretHex, wrap.pubkey, wrap.content))
        if (seal == null || seal.kind != Sealing.SEAL || seal.tags.isNotEmpty() || !seal.isValid()) null
        else Event.fromJsonString(Nip44.decryptFrom(device.secretHex, seal.pubkey, seal.content))?.let { Opened(it.withId().copy(sig = ""), seal.pubkey) }
    } catch (e: Exception) { null }

    /** §3.3: the id of the list of ACCOUNT that RUMOR says it was sealed to, else null. */
    fun listTagOf(rumor: Event, account: String): String? =
        rumor.tags.firstOrNull { it.size >= 3 && it[0] == "list" && it[1] == account && Hex.isKey(it[2]) }?.get(2)

    /**
     * §3.3: one `["list", account, list id]` per participant. R12: the rumor's last tags, in the order of the
     * accounts' lower-case hex (readers depend on neither).
     */
    fun listTags(lists: Map<String, String>): List<List<String>> = lists.keys.sorted().map { listOf("list", it, lists.getValue(it)) }

    class Wrap(val account: String, val device: String, val wrap: Event)

    /**
     * §4.1: one seal and one wrap of RUMOR for every device of TARGETS (account → its list's devices), the sending
     * device itself included when it is there. Seal: kind 13 signed by DEVICE, `tags: []`, NIP-44 to that device;
     * wrap: a one-time key, tags `p` (the device's account), `d` (the device) and `silent` when the account is the
     * rumor's own.
     */
    fun wraps(rumor: Event, device: LocalSigner, targets: Map<String, List<String>>, now: Long = unixNow()): List<Wrap> {
        val json = rumor.copy(sig = "").toJsonString()
        if (!Sealing.fits(rumor)) throw Sealing.TooLarge()
        val out = ArrayList<Wrap>()
        for ((account, devices) in targets) for (d in devices.distinct()) {
            val seal = device.signNow(EventTemplate(Sealing.SEAL, Nip44.encryptFor(device.secretHex, d, json), emptyList(), GiftWrap.backdate(now)))
            val oneTime = LocalSigner.random()
            val tags = listOfNotNull(listOf("p", account), listOf("d", d), GiftWrap.SILENT.takeIf { account == rumor.pubkey })
            out.add(Wrap(account, d, oneTime.signNow(EventTemplate(WRAP, oneTime.nip44EncryptNow(d, seal.toJsonString()), tags, GiftWrap.backdate(now)))))
        }
        return out
    }

    // ---- accepting (§4.2) ------------------------------------------------------------------------------

    enum class Verdict { ACCEPT, KEEP, DROP }

    /**
     * §4.2 for a rumor that came in its OWN wrap (never for the rumors inside a history pack, 8564: those are
     * taken by the pack's rule, since version 1 rumors carry no list tag). SEEN: the lists of the rumor's author
     * this app has seen. ME: this reader's account. KEEP = judged again when the author's list is read.
     */
    fun accept(rumor: Event, signer: String, seen: Collection<Seen>, belfry: String?, me: String, now: Long, wrapFirstSeen: Long): Verdict {
        val author = rumor.pubkey
        // R8: the belfry's key is accepted only as BOTH seal signer and pubkey, for a mail (kind 1301); anything else
        // with that key is dropped at once. (Reports, 7679, get their per-device form from the server: not here yet.)
        if (belfry != null && (signer == belfry || author == belfry)) {
            return if (signer == belfry && author == belfry && rumor.kind == 1301) Verdict.ACCEPT else Verdict.DROP
        }
        // R9: a version 2 rumor says which list of THIS reader's account it was sealed to; one that doesn't is dropped
        if (listTagOf(rumor, me) == null) return Verdict.DROP
        if (belfry == null) return if (now - wrapFirstSeen < KEEP_FOR) Verdict.KEEP else Verdict.DROP
        // oldest first; of two lists in one second the lower id is the newer
        val lists = seen.mapNotNull { s -> readList(s.event, belfry, author, now)?.let { Triple(s.event, it, s.firstSeen) } }
            .sortedWith(compareBy<Triple<Event, List<String>, Long>> { it.first.createdAt }.thenByDescending { it.first.id.lowercase() })
        if (lists.isNotEmpty() && signer in lists.last().second) return Verdict.ACCEPT
        // R10: "this app first saw a list of A without S" = when it first saw the list that LAST dropped S
        val last = lists.indexOfLast { signer in it.second }
        if (last >= 0) {
            val goneSeen = lists[last + 1].third
            // R13: not dated AFTER: the same second is accepted
            if (rumor.createdAt <= goneSeen) return Verdict.ACCEPT
        }
        return if (now - wrapFirstSeen < KEEP_FOR) Verdict.KEEP else Verdict.DROP
    }

    // ---- sealed or not (§5) ----------------------------------------------------------------------------

    /**
     * One participant as the relay answered: COMPLETE = its list was read (after AUTH, to EOSE) or a non-empty one
     * is held; DEVICES null = no list; SEEN_NON_EMPTY = a non-empty list was seen for it before.
     */
    class Person(val account: String, val complete: Boolean, val devices: List<String>?, val seenNonEmpty: Boolean)

    enum class Decision { SEAL, OLD_WAY, HOLD, TOO_MANY, NOT_ON_MY_LIST }

    /** §5: how a message is sent. STICKY: this device saw the conversation sealed. */
    fun sealedOrNot(people: Collection<Person>, senderAccount: String, senderDevice: String, sticky: Boolean): Decision {
        if (people.any { !it.complete }) return Decision.HOLD
        for (p in people) if (p.devices.isNullOrEmpty()) {
            if (p.seenNonEmpty || sticky) return Decision.HOLD // hold, never unseal
            return Decision.OLD_WAY
        }
        // R11: over 100 in a conversation sealed here before: hold; in one never sealed here: not sealed, said in a line
        if (people.sumOf { it.devices!!.size } > MAX_TOTAL) return if (sticky) Decision.HOLD else Decision.TOO_MANY
        if (people.firstOrNull { it.account == senderAccount }?.devices?.contains(senderDevice) != true) return Decision.NOT_ON_MY_LIST
        return Decision.SEAL
    }

    // ---- "It was me", once for all (§6.5) --------------------------------------------------------------

    /**
     * The 8563 of version 2: `pubkey` = the confirming DEVICE, tags `p` (the account), `device` (the added
     * device) and `["list", <id of the list that has it>]` (two elements: not §3.3's tag). Sealed to my own
     * devices like a note.
     */
    fun confirmRumor(device: String, account: String, added: String, listId: String, now: Long = unixNow()): Event =
        GiftWrap.rumor(Sealing.CONFIRM, device, listOf(listOf("p", account), listOf("device", added), listOf("list", listId)), "", now)

    sealed interface Confirm {
        data class Accept(val device: String) : Confirm
        data class Reject(val why: String) : Confirm
        /** Well formed, but something it needs is not here yet (the list it names, the sessions' ages, an open card of its signer): judged again. */
        data object Later : Confirm
    }

    /**
     * §6.5: an opened 8563 clears the card for the device it names when its signer is on my list, is not that
     * device, has a session older than that device's, and has no open card of its own here. MY_LISTS: the lists of
     * my account this app has seen (by id → devices). NEWEST: my newest list's devices. SESSIONS: device → when its
     * session was opened (the sessions list; a device not in it = not known). OPEN_CARDS: devices whose card is
     * still open on this device.
     */
    fun acceptConfirm(
        rumor: Event, sealPubkey: String, account: String, newest: List<String>?, myLists: Map<String, List<String>>,
        sessions: Map<String, Long>?, openCards: Set<String>,
    ): Confirm {
        if (rumor.kind != Sealing.CONFIRM) return Confirm.Reject("not an 8563")
        if (rumor.pubkey != sealPubkey) return Confirm.Reject("the rumor's pubkey is not the seal's signer")
        if (rumor.content.isNotEmpty()) return Confirm.Reject("content is not empty")
        if (rumor.tags.singleValue("p") != account) return Confirm.Reject("p is not my account, or not given once")
        val added = rumor.tags.singleValue("device")?.takeIf { Hex.isKey(it) } ?: return Confirm.Reject("not exactly one device")
        val lists = rumor.tags.filter { it.isNotEmpty() && it[0] == "list" }
        val listId = lists.singleOrNull()?.takeIf { it.size == 2 && Hex.isKey(it[1]) }?.get(1) ?: return Confirm.Reject("not exactly one list tag of two elements")
        if (sealPubkey == added) return Confirm.Reject("the added device cannot confirm itself")
        if (newest == null) return Confirm.Later
        if (sealPubkey !in newest) return Confirm.Reject("the signer is not on my list")
        val named = myLists[listId] ?: return Confirm.Later
        if (added !in named) return Confirm.Reject("the list named does not have that device")
        val mine = sessions?.get(sealPubkey) ?: return Confirm.Later
        val theirs = sessions[added] ?: return Confirm.Later
        if (mine >= theirs) return Confirm.Reject("the signer's session is not older than the added device's")
        if (sealPubkey in openCards) return Confirm.Later
        return Confirm.Accept(added)
    }
}
