package sh.belfry.core.dm

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import sh.belfry.core.crypto.B64
import sh.belfry.core.crypto.Hex
import sh.belfry.core.mail.KnownMail
import sh.belfry.core.mail.MailPart
import sh.belfry.core.mail.MailThreads
import sh.belfry.core.mail.Mime
import sh.belfry.core.model.World
import sh.belfry.core.nostr.Event
import sh.belfry.core.nostr.Signer
import sh.belfry.core.nostr.SignerTimeout
import sh.belfry.core.nostr.arr
import sh.belfry.core.nostr.bool
import sh.belfry.core.nostr.computedId
import sh.belfry.core.nostr.int
import sh.belfry.core.nostr.long
import sh.belfry.core.nostr.obj
import sh.belfry.core.nostr.parseJsonOrNull
import sh.belfry.core.nostr.s
import sh.belfry.core.nostr.str
import sh.belfry.core.outbox.OutboxItem
import sh.belfry.core.outbox.OutboxStatus
import sh.belfry.core.outbox.Target
import sh.belfry.core.store.Store
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// Direct messages (SPEC 10): NIP-17 (kind 14 in NIP-59 gift wraps), old NIP-04 (kind 4, read
// only) and e-mail as Nostr Mail (kind 1301 in gift wraps) through the belfry's bridge. Port of
// the web chat's src/core/dm.ts. Decrypted messages are kept on the device only (the Store's
// key/value table, wiped at sign-out).

data class DmAttachment(val name: String, val type: String, val size: Long)

/** A large e-mail kept on the file server, AES-256-GCM encrypted (key and nonce base64). */
data class LargeMail(val url: String, val key: String, val nonce: String)

/** Where an e-mail stands: `delivered`, `failed`, `delayed` or `queued`, with a line to show. */
data class Report(val status: String, val details: String) {
    val isOpen: Boolean get() = status == QUEUED

    companion object {
        const val QUEUED = "queued"
        const val DELIVERED = "delivered"
        const val FAILED = "failed"
        const val DELAYED = "delayed"
    }
}

/** One direct message or e-mail, decrypted. */
data class Dm(
    /** The rumor's id (the same in every copy). */
    val rid: String,
    /** "n:<pubkey>[,<pubkey>…]" (Nostr) or "m:<address>|<subject key>" (an e-mail thread). */
    val conv: String,
    /** The author's pubkey, or the sender's address for an e-mail that came in. */
    val from: String,
    val mine: Boolean,
    val at: Long,
    /** 14 (NIP-17), 4 (old NIP-04) or 1301 (e-mail). */
    val kind: Int,
    val text: String,
    val subject: String? = null,
    val fromName: String? = null,
    val emailId: String? = null,
    val messageId: String? = null,
    val references: String? = null,
    val inReplyTo: String? = null,
    /** E-mail: the bridge saw DKIM or SPF aligned with From. */
    val verified: Boolean? = null,
    val attachments: List<DmAttachment> = emptyList(),
    /** The e-mail's MIME when it fitted in the wrap (attachments are read from it). */
    val raw: String? = null,
    val large: LargeMail? = null,
    /** Saved here, its gift wraps still in the outbox. */
    val pending: Boolean = false,
    /** The relay refused it for good: why (Retry puts it back in the outbox). */
    val failed: String? = null,
) {
    val isMail: Boolean get() = kind == 1301

    fun toJson(): JsonObject = buildJsonObject {
        put("rid", JsonPrimitive(rid))
        put("conv", JsonPrimitive(conv))
        put("from", JsonPrimitive(from))
        put("mine", JsonPrimitive(mine))
        put("at", JsonPrimitive(at))
        put("kind", JsonPrimitive(kind))
        put("text", JsonPrimitive(text))
        if (subject != null) put("subject", JsonPrimitive(subject))
        if (fromName != null) put("fromName", JsonPrimitive(fromName))
        if (emailId != null) put("emailId", JsonPrimitive(emailId))
        if (messageId != null) put("messageId", JsonPrimitive(messageId))
        if (references != null) put("references", JsonPrimitive(references))
        if (inReplyTo != null) put("inReplyTo", JsonPrimitive(inReplyTo))
        if (verified != null) put("verified", JsonPrimitive(verified))
        if (attachments.isNotEmpty()) put("attachments", buildJsonArray {
            for (a in attachments) add(buildJsonObject {
                put("name", JsonPrimitive(a.name)); put("type", JsonPrimitive(a.type)); put("size", JsonPrimitive(a.size))
            })
        })
        if (raw != null) put("raw", JsonPrimitive(raw))
        if (large != null) put("large", buildJsonObject {
            put("url", JsonPrimitive(large.url)); put("key", JsonPrimitive(large.key)); put("nonce", JsonPrimitive(large.nonce))
        })
        if (pending) put("pending", JsonPrimitive(true))
        if (failed != null) put("failed", JsonPrimitive(failed))
    }

    companion object {
        fun fromJson(o: JsonObject): Dm? {
            return Dm(
                rid = o.s("rid") ?: return null,
                conv = o.s("conv") ?: return null,
                from = o.s("from") ?: "",
                mine = o["mine"].bool() ?: false,
                at = o["at"].long() ?: 0,
                kind = o["kind"].int() ?: 14,
                text = o.s("text") ?: "",
                subject = o.s("subject"),
                fromName = o.s("fromName"),
                emailId = o.s("emailId"),
                messageId = o.s("messageId"),
                references = o.s("references"),
                inReplyTo = o.s("inReplyTo"),
                verified = o["verified"].bool(),
                attachments = o["attachments"].arr()?.mapNotNull { it.obj() }?.map {
                    DmAttachment(it.s("name") ?: "", it.s("type") ?: "", it["size"].long() ?: 0)
                } ?: emptyList(),
                raw = o.s("raw"),
                large = o["large"].obj()?.let { LargeMail(it.s("url") ?: "", it.s("key") ?: "", it.s("nonce") ?: "") },
                pending = o["pending"].bool() ?: false,
                failed = o.s("failed"),
            )
        }
    }
}

/** A conversation in the Messages list. */
data class DmThread(val conv: String, val last: Dm, val unread: Int, val count: Int) {
    val isMail: Boolean get() = MailThreads.isMail(conv)
}

/** Everything the Messages screens show: an immutable snapshot (a new one after every change). */
class DmSnapshot(
    val items: List<Dm>,
    val reports: Map<String, Report>,
    val read: Map<String, Long>,
    val me: String,
) {
    private val byConv: Map<String, List<Dm>> by lazy { items.groupBy { it.conv }.mapValues { (_, l) -> l.sortedBy { it.at } } }

    /** Conversations, the most recent first. */
    val threads: List<DmThread> by lazy {
        byConv.map { (conv, list) ->
            DmThread(conv, list.last(), list.count { !it.mine && it.at > (read[conv] ?: 0) }, list.size)
        }.sortedByDescending { it.last.at }
    }

    /** How many conversations have something unread. */
    val unreadTotal: Int by lazy { threads.count { it.unread > 0 } }

    /** A conversation's messages, oldest first. */
    fun thread(conv: String): List<Dm> = byConv[conv] ?: emptyList()

    fun report(emailId: String?): Report? = emailId?.let { reports[it] }

    fun isMail(conv: String): Boolean = MailThreads.isMail(conv)
    /** "m:<address>" alone: a new e-mail that has no thread yet (needs a subject). */
    fun isNewMail(conv: String): Boolean = MailThreads.isNewMail(conv)
    fun mailAddress(conv: String): String = MailThreads.mailAddress(conv)
    fun peers(conv: String): List<String> = MailThreads.peers(conv)

    /** A mail thread's name: its subject without Re:/Fwd:. */
    fun threadName(conv: String): String =
        MailThreads.baseSubject(thread(conv).firstOrNull { !it.subject.isNullOrEmpty() }?.subject).ifEmpty { "(no subject)" }

    /** Who a conversation is with: the sender's name or address for e-mail, the people's names otherwise. */
    fun convName(conv: String, world: World): String {
        if (isMail(conv)) {
            val a = mailAddress(conv)
            val named = items.firstOrNull { !it.mine && !it.fromName.isNullOrEmpty() && isMail(it.conv) && mailAddress(it.conv) == a }?.fromName
            return named ?: a
        }
        return peers(conv).joinToString(", ") { world.displayName(it) }
    }

    /** The title of a conversation's row and screen: the subject for e-mail threads, the names otherwise. */
    fun title(conv: String, world: World): String = when {
        isNewMail(conv) -> mailAddress(conv)
        isMail(conv) -> threadName(conv)
        else -> convName(conv, world)
    }

    /** The line under a message of mine that is not out, or an e-mail's delivery state; null = nothing to say. */
    fun stateLine(d: Dm): String? {
        if (!d.mine) return null
        if (d.failed != null) return "Not sent: ${d.failed}"
        val r = report(d.emailId)
        if (d.isMail && r != null) return when (r.status) {
            Report.DELIVERED -> "Delivered"
            Report.FAILED -> "Not delivered" + (if (r.details.isNotEmpty()) ": ${r.details}" else "")
            Report.DELAYED -> "Delayed" + (if (r.details.isNotEmpty()) ": ${r.details}" else "")
            else -> r.details.ifEmpty { "Sending…" }
        }
        return if (d.pending) "Sending…" else null
    }

    companion object {
        fun empty(me: String = ""): DmSnapshot = DmSnapshot(emptyList(), emptyMap(), emptyMap(), me)
    }
}

/** A person a new conversation can start with. */
data class DmCandidate(val pubkey: String, val name: String, val nip05: String? = null)

/** A failure with a fixed message that is safe to show. */
class DmException(message: String) : Exception(message)

/** What [DirectMessages] needs from the engine (Session implements it). */
interface DmHost {
    val me: String
    val domain: String
    val blossom: String
    val localOnly: Boolean
    val signer: Signer
    fun now(): Long
    /** The mail bridge's key (`_smtp`), when known. */
    fun bridgeKey(): String?
    fun realName(pubkey: String): String
    fun outbox(): List<OutboxItem>
    suspend fun enqueueSigned(target: Target, event: Event, dm: String?): String
    /** Put these outbox items back as queued and flush. */
    suspend fun requeue(items: List<OutboxItem>)
    /** Demo only: events that "arrived". */
    fun addLocalEvents(events: List<Event>)
    suspend fun queryMain(filter: JsonObject): List<Event>
    suspend fun fetchBytes(url: String): ByteArray
    /** NIP-05 on the community's domain. */
    suspend fun nip05(name: String): String?
    fun later(delayMs: Long, block: suspend () -> Unit)
}

/** Pure parts of reading a rumor, testable without an engine. */
object DmReader {
    /** The conversation key of a rumor before e-mail threads are looked at. */
    fun convOf(rumor: Event, me: String, bridge: String?): String {
        if (rumor.kind == 1301) {
            val from = rumor.tag("mail-from")
            val to = rumor.tag("rcpt-to")
            if (rumor.pubkey == me && !to.isNullOrEmpty()) return "m:" + to.lowercase()
            if (!from.isNullOrEmpty() && bridge != null && rumor.pubkey == bridge) return "m:" + from.lowercase()
        }
        return MailThreads.nostrConv(rumor.pubkey, rumor.tagValues("p"), me)
    }

    /**
     * A rumor as a [Dm]: kind 14, or 1301 (e-mail; RAW is its MIME, already fetched when it was a
     * large one, null when that failed). KNOWN are the mails seen so far, for thread placement.
     */
    fun fromRumor(rumor: Event, me: String, bridge: String?, blossom: String, known: List<KnownMail>, raw: String? = rumor.content): Dm? {
        val rid = rumor.id.ifEmpty { rumor.computedId() }
        val conv = convOf(rumor, me, bridge)
        val mine = rumor.pubkey == me
        if (rumor.kind == 14) return Dm(rid, conv, rumor.pubkey, mine, rumor.createdAt, 14, rumor.content)
        if (rumor.kind != 1301) return null
        val bridged = bridge != null && rumor.pubkey == bridge && !rumor.tag("mail-from").isNullOrEmpty()
        var d = Dm(
            rid = rid, conv = conv, mine = mine, at = rumor.createdAt, kind = 1301, text = "",
            from = if (bridged) rumor.tag("mail-from")!!.lowercase() else rumor.pubkey,
            emailId = rumor.tag("email-id"),
            subject = rumor.tag("subject"),
            verified = if (bridged) rumor.tag("belfry-auth") == "pass" else true,
        )
        val x = rumor.tag("x")
        val isLarge = rumor.content.isEmpty() && !x.isNullOrEmpty()
        if (isLarge) {
            d = d.copy(large = LargeMail(rumor.tag("url") ?: "$blossom/$x", rumor.tag("decryption-key") ?: "", rumor.tag("decryption-nonce") ?: ""))
            if (raw.isNullOrEmpty()) d = d.copy(text = "(This e-mail is stored on the file server and could not be fetched.)")
        } else {
            d = d.copy(raw = rumor.content)
        }
        val body = if (isLarge) raw else rumor.content
        if (!body.isNullOrEmpty()) {
            val m = Mime.parseMail(body)
            d = d.copy(
                subject = m.subject.ifEmpty { d.subject ?: "" }.ifEmpty { null },
                text = m.text,
                fromName = Mime.addressOf(m.from).name.ifEmpty { null },
                messageId = m.headers["message-id"],
                references = m.headers["references"],
                inReplyTo = m.headers["in-reply-to"],
                attachments = m.attachments.map { DmAttachment(it.name, it.type, it.data.size.toLong()) },
            )
            if (MailThreads.isMail(d.conv))
                d = d.copy(conv = MailThreads.place(MailThreads.mailAddress(d.conv), d.subject, m.headers["in-reply-to"], d.references, known))
        } else if (MailThreads.isMail(d.conv)) {
            d = d.copy(conv = MailThreads.mailThreadOf(MailThreads.mailAddress(d.conv), d.subject))
        }
        return d
    }

    /** AES-256-GCM as WebCrypto writes it: the tag follows the ciphertext. */
    fun decryptLarge(blob: ByteArray, l: LargeMail): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(B64.decode(l.key), "AES"), GCMParameterSpec(128, B64.decode(l.nonce)))
        return String(cipher.doFinal(blob), Charsets.UTF_8)
    }
}

/**
 * The direct messages and e-mail of the person signed in. The engine feeds it events
 * ([offer] + [process]) and outbox results ([onSent], [onFailed]); screens read [snapshot].
 */
class DirectMessages(private val store: Store, private val host: DmHost) {
    private val lock = Any()
    private val items = LinkedHashMap<String, Dm>() // by rid
    private val seen = HashSet<String>() // event ids already read (gift wraps, kind 4)
    private val reports = HashMap<String, Report>() // by email-id
    private val read = HashMap<String, Long>()
    private val channelSends = HashSet<String>() // email-ids sent as a shared inbox, still without a report
    private val backlog = ArrayList<Event>()
    private val busy = Mutex()

    private val _snapshot = MutableStateFlow(DmSnapshot.empty(host.me))
    val snapshot: StateFlow<DmSnapshot> = _snapshot

    init {
        load()
        publish(save = false)
    }

    private fun load() {
        parseJsonOrNull(store.get(K_ITEMS) ?: "").arr()?.forEach { el -> el.obj()?.let(Dm::fromJson)?.let { items[it.rid] = it } }
        parseJsonOrNull(store.get(K_SEEN) ?: "").arr()?.forEach { el -> el.str()?.let(seen::add) }
        parseJsonOrNull(store.get(K_REPORTS) ?: "").obj()?.forEach { (k, v) ->
            v.obj()?.let { reports[k] = Report(it.s("status") ?: Report.QUEUED, it.s("details") ?: "") }
        }
        parseJsonOrNull(store.get(K_READ) ?: "").obj()?.forEach { (k, v) -> v.long()?.let { read[k] = it } }
        // a message marked pending whose wraps are gone from the outbox is out
        val waiting = host.outbox().mapNotNull { it.dm }.toSet()
        for ((rid, d) in items.toList()) if (d.pending && rid !in waiting) items[rid] = d.copy(pending = false)
    }

    private fun publish(save: Boolean = true) {
        val snap = synchronized(lock) {
            if (save) {
                store.set(K_ITEMS, JsonArray(items.values.map { it.toJson() }).toString())
                store.set(K_SEEN, JsonArray(seen.map { JsonPrimitive(it) }).toString())
                store.set(K_REPORTS, buildJsonObject {
                    for ((k, r) in reports) put(k, buildJsonObject { put("status", JsonPrimitive(r.status)); put("details", JsonPrimitive(r.details)) })
                }.toString())
                store.set(K_READ, buildJsonObject { for ((k, v) in read) put(k, JsonPrimitive(v)) }.toString())
            }
            DmSnapshot(items.values.toList(), HashMap(reports), HashMap(read), host.me)
        }
        _snapshot.value = snap
    }

    // ---- reading ---------------------------------------------------------------------------------

    /** Events that arrived (any kinds): the gift wraps for me and old kind-4 messages are kept for [process]. */
    fun offer(events: Collection<Event>) {
        val me = host.me
        synchronized(lock) {
            for (e in events) {
                if (e.id in seen) continue
                val mine = when (e.kind) {
                    1059 -> e.hasTag("p", me)
                    4 -> e.pubkey == me || e.hasTag("p", me)
                    else -> false
                }
                if (mine && backlog.none { it.id == e.id }) backlog.add(e)
            }
        }
    }

    /** Decrypts what is waiting. Stops when the signer does not answer (the rest waits for the next call). */
    suspend fun process() = busy.withLock {
        val todo = synchronized(lock) { backlog.sortedByDescending { it.createdAt } }
        if (todo.isEmpty()) return@withLock
        var changed = false
        for (e in todo) {
            try {
                val d = if (e.kind == 1059) readWrap(e) else readOld(e)
                synchronized(lock) {
                    if (d != null) {
                        val old = items[d.rid]
                        items[d.rid] = d.copy(mine = d.mine || old?.mine == true, pending = old?.pending ?: false, failed = old?.failed)
                    }
                    seen.add(e.id)
                    backlog.removeAll { it.id == e.id }
                }
                changed = true
            } catch (x: SignerTimeout) {
                break // the signer is away: next time
            } catch (x: Exception) {
                synchronized(lock) { seen.add(e.id); backlog.removeAll { it.id == e.id } } // not for me, or broken
                changed = true
            }
        }
        if (changed) {
            rethread()
            publish()
        }
    }

    /**
     * Mails are decrypted newest wrap first, and wraps are back-dated at random: an answer may be
     * read before the mail it answers. Once a batch is in, every mail that came in is placed
     * again, oldest first, so a thread never depends on the order of arrival.
     */
    private fun rethread() = synchronized(lock) {
        val known = ArrayList<KnownMail>()
        for (d in items.values.filter { it.isMail && MailThreads.isMail(it.conv) }.sortedBy { it.at }) {
            val conv = if (d.mine) d.conv else MailThreads.place(MailThreads.mailAddress(d.conv), d.subject, d.inReplyTo, d.references, known)
            if (conv != d.conv) {
                val readOld = read[d.conv]
                if (readOld != null && (read[conv] ?: 0) < readOld) read[conv] = readOld
                items[d.rid] = d.copy(conv = conv)
            }
            known.add(KnownMail(d.messageId, conv))
        }
    }

    private suspend fun readWrap(wrap: Event): Dm? {
        val opened = GiftWrap.unwrap(wrap, host.signer) ?: return null
        val rumor = opened.rumor
        var raw: String? = rumor.content
        val x = rumor.tag("x")
        if (rumor.kind == 1301 && rumor.content.isEmpty() && !x.isNullOrEmpty()) {
            val l = LargeMail(rumor.tag("url") ?: "${host.blossom}/$x", rumor.tag("decryption-key") ?: "", rumor.tag("decryption-nonce") ?: "")
            raw = try { DmReader.decryptLarge(host.fetchBytes(l.url), l) } catch (e: Exception) { null }
        }
        return DmReader.fromRumor(rumor, host.me, host.bridgeKey(), host.blossom, known(), raw)
    }

    private suspend fun readOld(e: Event): Dm {
        val me = host.me
        val peer = if (e.pubkey == me) e.tag("p") ?: me else e.pubkey
        val text = host.signer.nip04Decrypt(peer, e.content)
        return Dm(e.id, "n:$peer", e.pubkey, e.pubkey == me, e.createdAt, 4, text)
    }

    private fun known(): List<KnownMail> = synchronized(lock) { items.values.filter { it.isMail }.map { KnownMail(it.messageId, it.conv) } }

    /** The attachments of an e-mail, with their bytes (read from the message again). */
    suspend fun attachmentsOf(d: Dm): List<MailPart> {
        val raw = d.raw ?: d.large?.let { l -> try { DmReader.decryptLarge(host.fetchBytes(l.url), l) } catch (e: Exception) { null } } ?: return emptyList()
        return Mime.parseMail(raw).attachments
    }

    fun markRead(conv: String) {
        synchronized(lock) {
            val last = items.values.filter { it.conv == conv }.maxOfOrNull { it.at } ?: 0
            if ((read[conv] ?: 0) >= last) return
            read[conv] = last
        }
        publish()
    }

    // ---- sending ---------------------------------------------------------------------------------

    /** Wrap RUMOR for TO (and me) and put the wraps in the outbox. Never waits for the network. */
    private suspend fun deliver(rumor: Event, to: List<String>, self: Boolean = true) {
        val me = host.me
        val people = LinkedHashSet(to).apply { if (self) add(me) }
        val wraps = people.map { GiftWrap.wrap(rumor, it, host.signer) }
        if (host.localOnly) {
            val own = wraps.filter { it.tag("p") == me }
            synchronized(lock) { own.forEach { seen.add(it.id) } }
            host.addLocalEvents(own)
        }
        for (w in wraps) host.enqueueSigned(Target.MAIN, w, rumor.id)
    }

    private fun set(d: Dm) = synchronized(lock) { items[d.rid] = d }
    private fun update(rid: String, f: (Dm) -> Dm) = synchronized(lock) { items[rid]?.let { items[rid] = f(it) } }

    /**
     * Sends TEXT in CONV: a NIP-17 message, or an e-mail through the belfry (SUBJECT is needed for a
     * new e-mail, conv "m:<address>"). Returns the conversation the message is in (a new e-mail
     * becomes its subject's thread). Throws [DmException] with a message to show.
     */
    suspend fun send(conv: String, text: String, subject: String? = null): String {
        val me = host.me
        if (!MailThreads.isMail(conv)) {
            val to = MailThreads.peers(conv)
            if (to.any { !Hex.isKey(it) }) throw DmException("This is not a valid Nostr key.")
            val rumor = GiftWrap.rumor(14, me, to.filter { it != me }.map { listOf("p", it) }, text, host.now())
            set(Dm(rumor.id, conv, me, true, rumor.createdAt, 14, text, pending = true))
            publish()
            try {
                deliver(rumor, to)
            } catch (e: Exception) {
                synchronized(lock) { items.remove(rumor.id) } // the composer gets the text back
                publish()
                throw DmException(if (e is SignerTimeout) "Your signer did not answer. Check your connection and try again." else "The message could not be prepared. Try again.")
            }
            if (host.localOnly) update(rumor.id) { it.copy(pending = false) }
            publish()
            return conv
        }
        val bridge = host.bridgeKey() ?: throw DmException("This belfry does not send e-mail yet.")
        val myAddress = myAddress() ?: throw DmException("You have no address here yet (name@domain).")
        val to = MailThreads.mailAddress(conv)
        val fresh = MailThreads.isNewMail(conv)
        // in a thread: answer its last mail (theirs or mine), Re: its subject; new: a thread of its own
        val before = if (fresh) null else _snapshot.value.thread(conv).lastOrNull { it.isMail }
        val base = if (fresh) subject?.trim().orEmpty().ifEmpty { "(no subject)" } else MailThreads.baseSubject(before?.subject).ifEmpty { "(no subject)" }
        val subj = if (fresh || before == null) base else "Re: $base"
        val built = Mime.buildMail(
            fromName = host.realName(me).ifEmpty { myAddress.substringBefore('@') }, from = myAddress, to = to, subject = subj, text = text,
            domain = host.domain, inReplyTo = before?.messageId, references = before?.references, now = host.now(),
        )
        val target = if (fresh) MailThreads.mailThreadOf(to, subj) else conv
        val emailId = UUID.randomUUID().toString()
        val rumor = GiftWrap.rumor(1301, me, listOf(listOf("email-id", emailId), listOf("mail-from", myAddress), listOf("rcpt-to", to), listOf("subject", subj)), built.raw, host.now())
        val refs = if (before != null) listOfNotNull(before.references, before.messageId).joinToString(" ").ifEmpty { null } else null
        set(Dm(rumor.id, target, me, true, rumor.createdAt, 1301, text, subject = subj, emailId = emailId, raw = built.raw, messageId = built.messageId, references = refs, inReplyTo = before?.messageId, verified = true, pending = true))
        synchronized(lock) { reports[emailId] = Report(Report.QUEUED, "Waiting for the connection…") }
        publish()
        try {
            deliver(rumor, listOf(bridge))
        } catch (e: Exception) {
            val why = if (e is SignerTimeout) "Your signer did not answer." else "It could not be prepared."
            synchronized(lock) { reports[emailId] = Report(Report.FAILED, why) }
            update(rumor.id) { it.copy(pending = false, failed = why) }
        }
        if (host.localOnly) {
            synchronized(lock) { reports[emailId] = Report(Report.FAILED, "Demo mode sends no e-mail.") }
            update(rumor.id) { it.copy(pending = false) }
        }
        publish()
        return target
    }

    /** My address here (`name@domain`), from my profile's nip05. */
    @Volatile var myAddressProvider: () -> String? = { null }
    private fun myAddress(): String? = myAddressProvider()?.lowercase()?.takeIf { it.contains('@') }

    /**
     * Answers an e-mail card as the shared inbox CHANNEL (only its admins can: the bridge checks).
     * Returns the email-id; `snapshot.report(id)` then follows its delivery.
     */
    suspend fun sendAsChannel(channel: String, channelName: String, to: String, subject: String, text: String, inReplyTo: String?, cardId: String): String {
        val bridge = host.bridgeKey() ?: throw DmException("This belfry does not send e-mail yet.")
        val me = host.me
        val from = "$channel@${host.domain}"
        val name = host.realName(me)
        val built = Mime.buildMail(
            fromName = if (name.isNotEmpty()) "$name for $channelName" else channelName, from = from, to = to, subject = subject, text = text,
            domain = host.domain, inReplyTo = inReplyTo, now = host.now(),
        )
        val emailId = UUID.randomUUID().toString()
        val rumor = GiftWrap.rumor(1301, me, listOf(listOf("email-id", emailId), listOf("mail-from", from), listOf("rcpt-to", to), listOf("subject", subject), listOf("e", cardId)), built.raw, host.now())
        synchronized(lock) { reports[emailId] = Report(Report.QUEUED, "Sending…"); channelSends.add(emailId) }
        publish()
        if (host.localOnly) {
            synchronized(lock) { reports[emailId] = Report(Report.DELIVERED, ""); channelSends.remove(emailId) }
            publish()
            return emailId
        }
        try {
            deliver(rumor, listOf(bridge), self = false)
        } catch (e: Exception) {
            synchronized(lock) { reports[emailId] = Report(Report.FAILED, "It could not be prepared."); channelSends.remove(emailId) }
            publish()
            throw DmException(if (e is SignerTimeout) "Your signer did not answer. Check your connection and try again." else "The e-mail could not be prepared. Try again.")
        }
        for (wait in longArrayOf(2_000, 5_000, 10_000, 30_000)) host.later(wait) { checkReports() }
        return emailId
    }

    /** Puts a refused message back in the outbox. */
    suspend fun retry(rid: String) {
        val queued = host.outbox().filter { it.dm == rid }
        if (queued.isEmpty()) return
        val d = synchronized(lock) { items[rid] }
        update(rid) { it.copy(failed = null, pending = true) }
        if (d?.emailId != null) synchronized(lock) { reports[d.emailId] = Report(Report.QUEUED, "Waiting for the connection…") }
        publish()
        host.requeue(queued)
    }

    /** The engine tells: this outbox item is out (accepted by its relay). */
    fun onSent(item: OutboxItem) {
        val rid = item.dm ?: return
        val me = host.me
        val d = synchronized(lock) { items[rid] } ?: return
        // out once no wrap for someone else waits any more (my own copy may follow later)
        val left = host.outbox().filter { o -> o.dm == rid && o.localId != item.localId && o.tags.any { it.size >= 2 && it[0] == "p" && it[1] != me } }
        if (left.isNotEmpty()) return
        update(rid) { it.copy(pending = false, failed = null) }
        if (d.emailId != null && d.kind == 1301) {
            if (host.localOnly) {
                synchronized(lock) { reports[d.emailId] = Report(Report.FAILED, "Demo mode sends no e-mail.") }
            } else {
                synchronized(lock) { reports[d.emailId] = Report(Report.QUEUED, "Sent: waiting for the delivery report…") }
                for (wait in longArrayOf(3_000, 10_000, 30_000, 90_000)) host.later(wait) { checkReports() }
            }
        }
        publish()
    }

    /** The engine tells: the relay refused this outbox item for good. */
    fun onFailed(item: OutboxItem) {
        val rid = item.dm ?: return
        val me = host.me
        val d = synchronized(lock) { items[rid] } ?: return
        if (item.tags.none { it.size >= 2 && it[0] == "p" && it[1] != me }) return
        val why = item.error?.takeIf { it.isNotEmpty() } ?: "The relay refused it."
        update(rid) { it.copy(failed = why) }
        if (d.emailId != null) synchronized(lock) { reports[d.emailId] = Report(Report.FAILED, why) }
        publish()
    }

    /** Where a message stands in the outbox: waiting, and why after a failed try. */
    fun queueState(rid: String): Pair<Boolean, String?> {
        val mine = host.outbox().filter { it.dm == rid }
        return mine.isNotEmpty() to mine.firstOrNull { it.error != null && it.status == OutboxStatus.FAILED }?.error
    }

    /** The bridge's delivery reports (kind 7679) for my e-mails that have none yet. */
    suspend fun checkReports() {
        val bridge = host.bridgeKey() ?: return
        if (host.localOnly) return
        val open = synchronized(lock) {
            items.values.filter { it.mine && it.emailId != null && (reports[it.emailId]?.status ?: Report.QUEUED) == Report.QUEUED }.map { it.emailId!! } + channelSends
        }
        if (open.isEmpty()) return
        val filter = buildJsonObject {
            put("kinds", buildJsonArray { add(JsonPrimitive(7679)) })
            put("authors", buildJsonArray { add(JsonPrimitive(bridge)) })
            put("#r", buildJsonArray { open.takeLast(50).forEach { add(JsonPrimitive(it)) } })
        }
        var changed = false
        for (e in host.queryMain(filter)) {
            try {
                val r = e.tag("r") ?: continue
                val peer = e.tag("ephemeral-pubkey") ?: continue
                val body = parseJsonOrNull(host.signer.nip44Decrypt(peer, e.content)).obj() ?: continue
                val status = body.s("status") ?: continue
                if (status !in setOf(Report.DELIVERED, Report.FAILED, Report.DELAYED, Report.QUEUED)) continue
                // the bridge's own words about a delivery (an SMTP answer); kept short
                val details = (body.s("details")?.takeIf { it.isNotEmpty() } ?: body.s("smtp_response") ?: "").take(300)
                synchronized(lock) {
                    reports[r] = Report(status, details)
                    if (status != Report.QUEUED) channelSends.remove(r)
                }
                changed = true
            } catch (x: Exception) {
                // not mine
            }
        }
        if (changed) publish()
    }

    /**
     * Where a new conversation goes: a member (name, name@this-domain, 64-hex key or npub) → "n:…",
     * any other address → "m:<address>" (a new e-mail). Null when the input is neither.
     */
    suspend fun convFor(input: String, candidates: List<DmCandidate>): String? {
        val v = input.trim().lowercase()
        val hit = candidates.firstOrNull { it.nip05?.lowercase() == v || it.name.lowercase() == v }
        if (hit != null) return "n:" + hit.pubkey
        if (Hex.isKey(v)) return "n:$v"
        if (v.startsWith("npub1")) sh.belfry.core.crypto.Bech32.npubToHex(v)?.let { return "n:$it" }
        val m = ADDRESS.matchEntire(v) ?: return null
        if (m.groupValues[2] == host.domain && !host.localOnly) {
            val pk = try { host.nip05(m.groupValues[1]) } catch (e: Exception) { null }
            if (pk != null) return "n:$pk"
        }
        return "m:$v"
    }

    /** Demo mode only: an e-mail arrives from SENDER (`"Name" <address>`) as the belfry would deliver it. */
    suspend fun demoMail(bridge: sh.belfry.core.nostr.LocalSigner, sender: String, subject: String, text: String, verified: Boolean = true, at: Long = host.now(), messageId: String = "<${UUID.randomUUID()}@example.org>", inReplyTo: String? = null): Event {
        val me = host.me
        val raw = "From: $sender\r\nTo: ${myAddress() ?: "me@demo"}\r\nSubject: $subject\r\nMessage-ID: $messageId\r\n" +
            (if (inReplyTo != null) "In-Reply-To: $inReplyTo\r\n" else "") + "Content-Type: text/plain; charset=utf-8\r\n\r\n$text\r\n"
        val rumor = GiftWrap.rumor(1301, bridge.pubkey, listOf(
            listOf("email-id", UUID.randomUUID().toString()), listOf("mail-from", Mime.addressOf(sender).address), listOf("p", me),
            listOf("subject", subject), listOf("belfry-auth", if (verified) "pass" else "none"),
        ), raw, at)
        val wrap = GiftWrap.wrap(rumor, me, bridge)
        host.addLocalEvents(listOf(wrap))
        return wrap
    }

    companion object {
        const val K_ITEMS = "dm-items"
        const val K_SEEN = "dm-seen"
        const val K_REPORTS = "dm-reports"
        const val K_READ = "dm-read"
        private val ADDRESS = Regex("^([^@\\s]+)@([^@\\s]+\\.[^@\\s]+)$")
    }
}
