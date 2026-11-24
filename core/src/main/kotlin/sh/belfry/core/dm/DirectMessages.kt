package sh.belfry.core.dm

import sh.belfry.core.model.OneShape
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
import sh.belfry.core.enc.Devices
import sh.belfry.core.enc.Enc
import sh.belfry.core.enc.EncPlan
import sh.belfry.core.enc.EncRules
import sh.belfry.core.enc.EncText
import sh.belfry.core.enc.EncWrite
import sh.belfry.core.engine.Enc3
import sh.belfry.core.read.ReadState
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
    /** Held undecided since (sealed.md §6, 64a07fa): the device couldn't tell yet whether to seal it. */
    val heldSince: Long? = null,
    /** Why it is held (canvas v98): null = can't check yet, "device:<pubkey>" = their new device isn't ready, "too_long". */
    val heldWhy: String? = null,
    /** Came the old, unsealed way after this device saw the conversation sealed (canvas v89). */
    val notSealed: Boolean = false,
    /** From a history someone shared with this device (§8): who; not proven authentic. */
    val sharedBy: String? = null,
    /** Kind 15: the file (vectors/dm-files.json). */
    val file: DmFileRef? = null,
    /** Pictures sent together, and their caption (a kind 14), share it. */
    val group: String? = null,
    /** A reply (docs/one-shape.md 3): the rumor id of the message whose thread it is in. */
    val root: String? = null,
    /** Format 3 (SPEC 19.83): opened from a kind 8572, or sent as one. */
    val enc: Boolean = false,
    /** Format 3 (§17.2): came unencrypted after its conversation's first key: shown "Not encrypted", never acting. */
    val clear: Boolean = false,
    /** Came unencrypted the old way (NIP-17 or NIP-04): judged again when a key of its conversation arrives (§17.2). */
    val plain: Boolean = false,
    /** Format 3 (§9): a placeholder for an 8572 whose key is here but that can't be opened yet ("Opening this message…"). */
    val opening: Boolean = false,
    /** Format 3 (§17.5): people of the conversation with no device, who won't get this message of mine. */
    val notTo: List<String> = emptyList(),
    /** Its link preview's tags as the sender attached them (SPEC 19.87; `preview` and the preview `imeta`), for [sh.belfry.core.preview.LinkPreviews.read]. */
    val preview: List<List<String>> = emptyList(),
) {
    val isMail: Boolean get() = kind == 1301
    val isFile: Boolean get() = kind == DmFiles.KIND

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
        if (heldSince != null) put("heldSince", JsonPrimitive(heldSince))
        if (heldWhy != null) put("heldWhy", JsonPrimitive(heldWhy))
        if (notSealed) put("notSealed", JsonPrimitive(true))
        if (sharedBy != null) put("sharedBy", JsonPrimitive(sharedBy))
        if (file != null) put("file", file.toJson())
        if (group != null) put("group", JsonPrimitive(group))
        if (root != null) put("root", JsonPrimitive(root))
        if (enc) put("enc", JsonPrimitive(true))
        if (clear) put("clear", JsonPrimitive(true))
        if (plain) put("plain", JsonPrimitive(true))
        if (opening) put("opening", JsonPrimitive(true))
        if (notTo.isNotEmpty()) put("notTo", JsonArray(notTo.map { JsonPrimitive(it) }))
        if (preview.isNotEmpty()) put("preview", JsonArray(preview.map { t -> JsonArray(t.map { JsonPrimitive(it) }) }))
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
                heldSince = o["heldSince"].long(),
                heldWhy = o.s("heldWhy"),
                notSealed = o["notSealed"].bool() ?: false,
                sharedBy = o.s("sharedBy"),
                file = o["file"].obj()?.let(DmFileRef::fromJson),
                group = o.s("group"),
                root = o.s("root"),
                enc = o["enc"].bool() ?: false,
                clear = o["clear"].bool() ?: false,
                plain = o["plain"].bool() ?: false,
                opening = o["opening"].bool() ?: false,
                notTo = o["notTo"].arr()?.mapNotNull { it.str() } ?: emptyList(),
                preview = o["preview"].arr()?.mapNotNull { t -> t.arr()?.mapNotNull { it.str() } } ?: emptyList(),
            )
        }
    }
}

/**
 * A file in a direct message (kind 15): where its ciphertext is and the rumor's tags (the key,
 * nonce, x, ox… that [DmFiles.open] checks). LOCAL_BLOB: a file of mine not sent yet, its plain
 * bytes in the store under that key.
 */
data class DmFileRef(val url: String, val tags: List<List<String>>, val localBlob: String? = null) {
    private fun tag(n: String) = tags.firstOrNull { it.size >= 2 && it[0] == n }?.get(1)
    val mime: String get() = (tag("file-type") ?: "application/octet-stream").lowercase()
    val size: Long get() = tag("size")?.toLongOrNull() ?: 0
    /** The name shown (never the raw tag). */
    val name: String get() = DmFiles.shownName(tag("name"), mime)
    val ox: String? get() = tag("ox")
    val dim: Pair<Int, Int>? get() = tag("dim")?.split('x')?.takeIf { it.size == 2 }?.let { (w, h) -> w.toIntOrNull()?.let { a -> h.toIntOrNull()?.let { b -> a to b } } }
    /** Seconds (recordings). */
    val duration: Int? get() = tag("duration")?.toDoubleOrNull()?.toInt()
    val isPicture: Boolean get() = mime.startsWith("image/")
    val isAudio: Boolean get() = mime.startsWith("audio/")

    fun toJson(): JsonObject = buildJsonObject {
        put("url", JsonPrimitive(url))
        put("tags", buildJsonArray { tags.forEach { t -> add(buildJsonArray { t.forEach { add(JsonPrimitive(it)) } }) } })
        if (localBlob != null) put("blob", JsonPrimitive(localBlob))
    }

    companion object {
        fun fromJson(o: JsonObject): DmFileRef = DmFileRef(
            o.s("url") ?: "",
            o["tags"].arr()?.map { t -> t.arr()?.mapNotNull { it.str() } ?: emptyList() } ?: emptyList(),
            o.s("blob"),
        )

        /** From a kind 15 rumor (content = the blob URL); null when it is no https address. */
        fun of(rumor: Event): DmFileRef? {
            val url = rumor.content.trim()
            if (!url.startsWith("https://") && !url.startsWith("http://")) return null
            return DmFileRef(url, rumor.tags.filter { it.isNotEmpty() && it[0] != "p" })
        }
    }
}

/**
 * A reaction to a direct message (NIP-17, the web's dm.ts of 2026-11-15; canvas home/conversation):
 * an unsigned kind 7 rumor in its own gift wraps to every participant and to yourself, content =
 * the emoji, `["e", <the kind 14 rumor's id>, "", <its author>]`, `["p", <its author>]`,
 * `["k", "14"]`; taking it back = a wrapped kind 5 rumor naming it (`e`, `["k","7"]`). Readers key
 * on `e`, one per person per emoji. Never on e-mail threads.
 */
data class DmReaction(val rid: String, val target: String, val from: String, val emoji: String, val at: Long, val plain: Boolean = false) {
    fun toJson(): JsonObject = buildJsonObject {
        put("rid", JsonPrimitive(rid)); put("target", JsonPrimitive(target)); put("from", JsonPrimitive(from))
        put("emoji", JsonPrimitive(emoji)); put("at", JsonPrimitive(at))
        if (plain) put("plain", JsonPrimitive(true))
    }

    companion object {
        fun fromJson(o: JsonObject): DmReaction? {
            return DmReaction(o.s("rid") ?: return null, o.s("target") ?: return null, o.s("from") ?: return null, o.s("emoji") ?: return null, o["at"].long() ?: 0, o["plain"].bool() ?: false)
        }

        /**
         * Reads a kind 7 (a reaction) or kind 5 (one taken back: only by its author) rumor into
         * REACTS (by reaction id) and GONE (taken back: id → author; a kind 5 may come first).
         */
        fun apply(reacts: MutableMap<String, DmReaction>, gone: MutableMap<String, String>, r: Event, plain: Boolean = false) {
            if (r.kind == 7) {
                val x = (of(r) ?: return).copy(plain = plain)
                if (gone[x.rid] == x.from) return
                reacts[x.rid] = x
                return
            }
            if (r.kind != 5) return
            for (t in r.tags) {
                if (t.size < 2 || t[0] != "e") continue
                val had = reacts[t[1]]
                if (had == null) gone[t[1]] = r.pubkey
                else if (had.from == r.pubkey) { reacts.remove(t[1]); gone[t[1]] = r.pubkey }
            }
        }

        /** A kind 7 rumor as a reaction (null when it isn't one we show). */
        fun of(rumor: Event): DmReaction? {
            if (rumor.kind != 7) return null
            val target = rumor.tags.lastOrNull { it.size >= 2 && it[0] == "e" }?.get(1) ?: return null
            val raw = rumor.content.trim()
            val emoji = if (raw.isEmpty() || raw == "+") "👍" else raw
            if (emoji.length > 16) return null
            return DmReaction(rumor.id.ifEmpty { rumor.computedId() }, target, rumor.pubkey, emoji, rumor.createdAt)
        }
    }
}

/** One emoji's chip under a message: who gave it (→ when), and mine to take back. */
data class DmChip(val emoji: String, val who: Map<String, Long>, val mine: DmReaction?)

/** A conversation in the Messages list. */
data class DmThread(val conv: String, val last: Dm, val unread: Int, val count: Int) {
    val isMail: Boolean get() = MailThreads.isMail(conv)
}

/** A row of Messages: a conversation, or Notes to myself (THREAD null while it has no note). */
data class MessagesRow(val conv: String, val thread: DmThread?, val notes: Boolean) {
    val unread: Int get() = if (notes) 0 else thread?.unread ?: 0
}

/** Everything the Messages screens show: an immutable snapshot (a new one after every change). */
class DmSnapshot(
    val items: List<Dm>,
    val reports: Map<String, Report>,
    /** What I read, on every device (SPEC 19.27): a conversation is read up to `readUpTo('d', conv)`. */
    val read: ReadState,
    val me: String,
    /** When this snapshot was made (Unix seconds): the 90-day floor of the read state. */
    val now: Long = 0,
    val reactions: List<DmReaction> = emptyList(),
    /**
     * Sealed wraps to my letterbox this device can't open yet (Verdict LOCKED: not allowed, or its key
     * not here): no conversation shows them, so Messages says how many are waiting and their push stays.
     */
    val lockedCount: Int = 0,
    /** Format 3 (§9): messages for me whose key isn't here yet (fetching, or offline): "Opening". */
    val openingCount: Int = 0,
    /** Format 3 (§9): messages for me whose envelopes have no copy for this device: "Can't be read on this device". */
    val unreadableCount: Int = 0,
) {
    private val reactsByTarget: Map<String, List<DmReaction>> by lazy { reactions.groupBy { it.target } }

    /** The reactions to message RID, most given first; one per person per emoji (as the web). */
    fun reactionsOf(rid: String): List<DmChip> {
        val by = LinkedHashMap<String, Pair<LinkedHashMap<String, Long>, Array<DmReaction?>>>()
        for (r in (reactsByTarget[rid] ?: return emptyList()).sortedBy { it.at }) {
            val c = by.getOrPut(r.emoji) { LinkedHashMap<String, Long>() to arrayOf(null) }
            c.first[r.from] = r.at
            if (r.from == me) c.second[0] = r
        }
        return by.map { (e, c) -> DmChip(e, c.first, c.second[0]) }.sortedByDescending { it.who.size }
    }

    private val byConv: Map<String, List<Dm>> by lazy { items.groupBy { it.conv }.mapValues { (_, l) -> l.sortedBy { it.at } } }

    /** Conversations, the most recent first. Notes to myself never counts as unread. */
    val threads: List<DmThread> by lazy {
        byConv.map { (conv, list) ->
            val upTo = read.readUpTo('d', conv, now)
            DmThread(conv, list.last(), if (conv == notesConv) 0 else list.count { !it.mine && it.at > upTo }, list.size)
        }.sortedByDescending { it.last.at }
    }

    /** Notes to myself (docs/one-shape.md, SPEC 19.66): the conversation whose only participant is me. */
    val notesConv: String get() = sh.belfry.core.model.Notes.conv(me)

    /** The last note, null while there is none (the row is there all the same). */
    val lastNote: Dm? get() = thread(notesConv).lastOrNull()

    /**
     * The Messages list: Notes to myself first, always (also when empty; it can't be unpinned, archived
     * or hidden, and is never unread), then every other conversation, the most recent first.
     */
    val messagesRows: List<MessagesRow> by lazy {
        val others = threads.filter { it.conv != notesConv }.map { MessagesRow(it.conv, it, notes = false) }
        if (me.isEmpty()) others else listOf(MessagesRow(notesConv, threads.firstOrNull { it.conv == notesConv }, notes = true)) + others
    }

    /** How many conversations have something unread. */
    val unreadTotal: Int by lazy { threads.count { it.unread > 0 } }

    /** A conversation's messages, oldest first. */
    fun thread(conv: String): List<Dm> = byConv[conv] ?: emptyList()

    private val splitByConv = java.util.concurrent.ConcurrentHashMap<String, DmReplies.Split>()
    private fun split(conv: String): DmReplies.Split = splitByConv.getOrPut(conv) { DmReplies.split(thread(conv)) }

    /** What the conversation shows in line (docs/one-shape.md 3): every message but the replies whose root is here. */
    fun inline(conv: String): List<Dm> = split(conv).inline

    /** The replies in the thread of message ROOT (a rumor id) of CONV, oldest first. */
    fun replies(conv: String, root: String): List<Dm> = split(conv).replies[root] ?: emptyList()

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

    /**
     * The conversation list's last line for a file (canvas v109): "Picture", "3 pictures",
     * "File: Garden plan 2027.pdf", "Audio recording · 0:42"; null when D is no file.
     */
    fun fileLine(d: Dm): String? {
        val f = d.file ?: return null
        if (f.isPicture) {
            val n = if (d.group == null) 1 else thread(d.conv).count { it.group == d.group && it.from == d.from && it.file?.isPicture == true }
            return if (n <= 1) "Picture" else "$n pictures"
        }
        if (f.isAudio) return "Audio recording" + (f.duration?.let { " · %d:%02d".format(it / 60, it % 60) } ?: "")
        return "File: " + f.name
    }

    /** The line under a message of mine that is not out, or an e-mail's delivery state; null = nothing to say. */
    fun stateLine(d: Dm, name: (String) -> String = { "They" }): String? {
        if (!d.mine) return null
        if (d.failed != null) return "Not sent: ${d.failed}"
        val held = d.heldSince
        if (held != null) return when {
            enc3Hold(d) != null -> sh.belfry.core.enc.EncText.held(enc3Hold(d)!!, name) // format 3's held words (SPEC 19.83)
            d.heldWhy == "too_long" -> HELD_TOO_LONG
            d.heldWhy == DirectMessages.LOCKED -> sh.belfry.core.model.SealedText.WRITE_LATER
            d.heldWhy == DirectMessages.NOTES_CHAIN -> "Sending…" // a note waiting for my chain (SPEC 19.66)
            d.heldWhy?.startsWith("device:") == true -> null // named by the screen ("‹Name›'s new device isn't ready.")
            now - held >= 60 -> HELD_LONG
            else -> HELD_FIRST
        }
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
        /** A held message after a minute (64a07fa: say why). */
        const val HELD_FIRST = "Saved · waiting for the connection"
        const val HELD_LONG = "Not sent yet: this device can't reach the belfry to check how to lock it."
        const val HELD_TOO_LONG = "Not sent: the message is too long to lock. Shorten it and send again."
        fun heldDevice(name: String) = "Not sent yet: $name's new device isn't ready."

        /** [Dm.heldWhy] of a message format 3 holds: `enc3:<reason>[:<who>]` (SPEC 19.83). */
        fun enc3Why(h: sh.belfry.core.enc.EncWrite.Hold): String = "enc3:" + h.reason.name + (h.who?.let { ":$it" } ?: "")

        /** The hold of a message format 3 holds, or null. */
        fun enc3Hold(d: Dm): sh.belfry.core.enc.EncWrite.Hold? {
            val w = d.heldWhy?.takeIf { it.startsWith("enc3:") }?.split(":") ?: return null
            val r = sh.belfry.core.enc.HoldReason.entries.firstOrNull { it.name == w.getOrNull(1) } ?: return null
            return sh.belfry.core.enc.EncWrite.Hold(r, w.getOrNull(2)?.takeIf { it.isNotEmpty() })
        }
        fun empty(me: String = ""): DmSnapshot = DmSnapshot(emptyList(), emptyMap(), ReadState.EMPTY, me)
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
    /** Sealed conversations on this device (null: the demo, a guest, not started). */
    val sealed: sh.belfry.core.sealed.SealedEngine? get() = null
    /** The belfry's key from belfry.json: the only key whose seal a sealed mail (1301) may carry (§7; the review's 14). */
    fun belfryKey(): String? = null
    /** The send trace (SPEC 19.50): message ID is being prepared (wrapped, its seals signed). */
    fun preparing(id: String) {}
    /** Message ID never reached the outbox. */
    fun notPrepared(id: String) {}
    /** A guest here (files: 5 MB at most instead of 25). */
    val isGuest: Boolean get() = false
    fun putBlob(key: String, bytes: ByteArray) {}
    fun blob(key: String): ByteArray? = null
    /** Puts ITEM in the outbox (a file to encrypt, upload and wrap when it goes). */
    suspend fun enqueue(item: OutboxItem) {}
    /** Uploads a file's ciphertext to the belfry's Blossom; its URL. PROGRESS: bytes sent, of. */
    suspend fun uploadSealed(bytes: ByteArray, progress: (Long, Long) -> Unit): String = throw java.io.IOException("upload failed (no storage)")
    /** The send trace: the message's id changed (a file's local id → its rumor's). */
    fun renamed(from: String, to: String) {}

    // ---- format 3 (SPEC 19.83) ----
    /** Format 3 on this device (null: the demo, a guest's session, no device key). */
    val enc3: sh.belfry.core.engine.Enc3? get() = null
    /** What a write in CONV (`n:…`, or `m:` for e-mail out) does now: the session's decision (SPEC 19.82). V1: as before. */
    suspend fun encWrite(conv: String): sh.belfry.core.enc.EncWrite = sh.belfry.core.enc.EncWrite.V1
    /** Queues a people 8572 of message RID under key K (it waits for K's envelopes: [sh.belfry.core.engine.Enc3.unconfirmed]). */
    suspend fun enqueueEnc(event: Event, dm: String, key: String): String = enqueueSigned(Target.MAIN, event, dm)
    /** Takes an item out of the outbox (a format-3 copy rebuilt with fresh dates). */
    suspend fun dropOutbox(localId: String) {}
    /** Since when (seconds) this device knows the belfry as format 3; null: it doesn't. */
    fun format3Since(): Long? = null
}

/** A file picked to send in a direct message. */
class DmNewFile(val name: String, val mime: String, val bytes: ByteArray, val width: Int? = null, val height: Int? = null, val durationSec: Int? = null)

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
        if (rumor.kind == 14) return Dm(
            rid, conv, rumor.pubkey, mine, rumor.createdAt, 14, rumor.content, group = rumor.tag("group"), root = OneShape.dmRoot(rumor.tags)?.takeIf { it != rid },
            preview = sh.belfry.core.preview.LinkPreviews.wireTags(rumor.tags),
        )
        if (rumor.kind == DmFiles.KIND) {
            val f = DmFileRef.of(rumor) ?: return null
            return Dm(rid, conv, rumor.pubkey, mine, rumor.createdAt, DmFiles.KIND, "", file = f, group = rumor.tag("group"))
        }
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
    private val reacts = LinkedHashMap<String, DmReaction>() // by the reaction rumor's id
    /** Reactions taken back (rid → its author): wraps come in any order, a kind 5 may come first. */
    private val gone = HashMap<String, String>()
    /** The per-conversation marks kept here before SPEC 19.27: only read to seed the shared read state. */
    private val legacyRead = HashMap<String, Long>()
    /** The shared read state (Session's [sh.belfry.core.engine.ReadSync]); set with [setReadState]. */
    @Volatile private var readState: ReadState = ReadState.EMPTY
    /** Told when a conversation is read up to a time (Session moves the shared read state). */
    @Volatile var onRead: ((conv: String, at: Long) -> Unit)? = null
    private val channelSends = HashSet<String>() // email-ids sent as a shared inbox, still without a report
    private val backlog = ArrayList<Event>()
    private val busy = Mutex()
    /** Sealed wraps kept for later (locked here, or the author's chain not loaded yet), by id. */
    private val kept = LinkedHashMap<String, Event>()
    /**
     * Wraps that came LOCKED (this device has no secret for their letterbox), id → wrap created_at:
     * counted until they open or are dropped, kept across restarts so the waiting line and its push
     * survive one; at most [MAX_LOCKED], none older than the 30-day keep (+ 2 days of wrap jitter).
     */
    private val locked = LinkedHashMap<String, Long>()
    private var lockedWritten: List<String>? = null

    /** Under [lock]. */
    private fun capLocked() {
        val floor = host.now() - sh.belfry.core.sealed.Sealing.KEEP_FOR - 2 * 86400L
        locked.entries.removeAll { it.value < floor }
        val over = locked.size - MAX_LOCKED
        if (over > 0) locked.keys.take(over).forEach { locked.remove(it) }
    }
    /** The kept list's ids as last written (it is written only when they change: fourth review 2c). */
    private var keptWritten: List<String>? = null

    /** At most [sh.belfry.core.sealed.SealedEngine.MAX_KEPT] kept wraps: the oldest go, never marked seen. Under [lock]. */
    private fun capKept() {
        val over = kept.size - sh.belfry.core.sealed.SealedEngine.MAX_KEPT
        if (over > 0) kept.keys.take(over).forEach { kept.remove(it) }
    }
    /** Messages held undecided (64a07fa): rid → the rumor and who it goes to. */
    private val held = LinkedHashMap<String, Held>()
    /** Sealed wraps in the outbox: wrap id → what was sealed to whom (re-sealed when a head moved, §9). */
    private val sealQ = LinkedHashMap<String, QueuedSeal>()

    /** The rumors of sealed conversations (sealed or sent sealed), by id: what Share history sends (§8). */
    private val sealedRumors = LinkedHashMap<String, Event>()

    private class Held(val rumor: Event, val to: List<String>, val self: Boolean, val conv: String)

    /**
     * Format 3 (SPEC 19.83): 8572s for me judged again later (their key or the author's lists not here yet; never marked
     * seen on that), by id, kept across restarts (catch-up is ordinary since, §17.11), at most [MAX_KEPT3]; and what
     * each one shows meanwhile: [K3_OPENING] (no key here yet), [K3_UNREADABLE] (no copy for this device), [K3_PLACED]
     * (its key is here: a placeholder in its conversation).
     */
    private val kept3 = LinkedHashMap<String, Event>()
    private val kept3State = HashMap<String, Int>()
    private var kept3Written: List<String>? = null
    /** Format 3: my messages on their way (rid → what makes their copies again with fresh dates), until every copy is out. */
    private val encOut = LinkedHashMap<String, Held>()
    /** Format 3: the key K a message's copies are under (files: their copies are queued by the session). */
    private val encKeys = java.util.concurrent.ConcurrentHashMap<String, String>()
    /** How often a message's copies were made again after a refusal in this run (once by itself; Retry always). */
    private val rebuilt = java.util.concurrent.ConcurrentHashMap<String, Int>()

    private fun capKept3() {
        val over = kept3.size - MAX_KEPT3
        if (over > 0) kept3.keys.take(over).forEach { kept3.remove(it); kept3State.remove(it); items.remove("p:$it") }
    }

    private fun heldJson(m: Map<String, Held>): String = buildJsonObject {
        for ((rid, h) in m) put(rid, buildJsonObject {
            put("rumor", h.rumor.toJson()); put("to", JsonArray(h.to.map { JsonPrimitive(it) })); put("self", JsonPrimitive(h.self)); put("conv", JsonPrimitive(h.conv))
        })
    }.toString()

    private fun heldFrom(json: String?, into: MutableMap<String, Held>) {
        parseJsonOrNull(json ?: "").obj()?.forEach { (rid, v) ->
            val o = v.obj() ?: return@forEach
            val r = o["rumor"].obj()?.let { Event.fromJson(it) } ?: return@forEach
            into[rid] = Held(r, o["to"].arr()?.mapNotNull { it.str() } ?: emptyList(), o["self"].bool() ?: true, o.s("conv") ?: return@forEach)
        }
    }
    private class QueuedSeal(val rumor: Event, val recipient: String, val lb: String)

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
        parseJsonOrNull(store.get(K_READ) ?: "").obj()?.forEach { (k, v) -> v.long()?.let { legacyRead[k] = it } }
        parseJsonOrNull(store.get(K_REACTS) ?: "").arr()?.forEach { el -> el.obj()?.let(DmReaction::fromJson)?.let { reacts[it.rid] = it } }
        parseJsonOrNull(store.get(K_GONE) ?: "").obj()?.forEach { (k, v) -> v.str()?.let { gone[k] = it } }
        parseJsonOrNull(store.get(K_KEPT) ?: "").arr()?.forEach { el -> el.obj()?.let { Event.fromJson(it) }?.let { kept[it.id] = it } }
        parseJsonOrNull(store.get(K_HELD) ?: "").obj()?.forEach { (rid, v) ->
            val o = v.obj() ?: return@forEach
            val r = o["rumor"].obj()?.let { Event.fromJson(it) } ?: return@forEach
            held[rid] = Held(r, o["to"].arr()?.mapNotNull { it.str() } ?: emptyList(), o["self"].bool() ?: true, o.s("conv") ?: return@forEach)
        }
        parseJsonOrNull(store.get(K_SEALQ) ?: "").obj()?.forEach { (wid, v) ->
            val o = v.obj() ?: return@forEach
            val r = o["rumor"].obj()?.let { Event.fromJson(it) } ?: return@forEach
            sealQ[wid] = QueuedSeal(r, o.s("to") ?: return@forEach, o.s("lb") ?: return@forEach)
        }
        parseJsonOrNull(store.get(K_REFUSED) ?: "").obj()?.forEach { (rid, v) ->
            val o = v.obj() ?: return@forEach
            val r = o["rumor"].obj()?.let { Event.fromJson(it) } ?: return@forEach
            refused[rid] = Held(r, o["to"].arr()?.mapNotNull { it.str() } ?: emptyList(), o["self"].bool() ?: true, o.s("conv") ?: return@forEach)
        }
        parseJsonOrNull(store.get(K_SEALED_RUMORS) ?: "").arr()?.forEach { el -> el.obj()?.let { Event.fromJson(it) }?.let { sealedRumors[it.id] = it } }
        parseJsonOrNull(store.get(K_LOCKED) ?: "").obj()?.forEach { (id, v) -> v.long()?.let { locked[id] = it } }
        capLocked()
        parseJsonOrNull(store.get(K_KEPT3) ?: "").arr()?.forEach { el -> el.obj()?.let { Event.fromJson(it) }?.let { kept3[it.id] = it } }
        heldFrom(store.get(K_ENCOUT), encOut)
        backlog.addAll(kept.values) // tried again at each start
        backlog.addAll(kept3.values)
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
                store.set(K_REACTS, JsonArray(reacts.values.map { it.toJson() }).toString())
                store.set(K_GONE, buildJsonObject { for ((k, v) in gone) put(k, JsonPrimitive(v)) }.toString())
                val keptIds = kept.keys.toList()
                if (keptIds != keptWritten) { store.set(K_KEPT, JsonArray(kept.values.map { it.toJson() }).toString()); keptWritten = keptIds }
                val lockedIds = locked.keys.toList()
                if (lockedIds != lockedWritten) { store.set(K_LOCKED, buildJsonObject { for ((id, t) in locked) put(id, JsonPrimitive(t)) }.toString()); lockedWritten = lockedIds }
                store.set(K_SEALED_RUMORS, JsonArray(sealedRumors.values.map { it.toJson() }).toString())
                val kept3Ids = kept3.keys.toList()
                if (kept3Ids != kept3Written) { store.set(K_KEPT3, JsonArray(kept3.values.map { it.toJson() }).toString()); kept3Written = kept3Ids }
                store.set(K_ENCOUT, heldJson(encOut))
                store.set(K_REFUSED, buildJsonObject {
                    for ((rid, h) in refused) put(rid, buildJsonObject {
                        put("rumor", h.rumor.toJson()); put("to", JsonArray(h.to.map { JsonPrimitive(it) })); put("self", JsonPrimitive(h.self)); put("conv", JsonPrimitive(h.conv))
                    })
                }.toString())
                store.set(K_HELD, buildJsonObject {
                    for ((rid, h) in held) put(rid, buildJsonObject {
                        put("rumor", h.rumor.toJson()); put("to", JsonArray(h.to.map { JsonPrimitive(it) })); put("self", JsonPrimitive(h.self)); put("conv", JsonPrimitive(h.conv))
                    })
                }.toString())
                store.set(K_SEALQ, buildJsonObject {
                    for ((wid, q) in sealQ) put(wid, buildJsonObject { put("rumor", q.rumor.toJson()); put("to", JsonPrimitive(q.recipient)); put("lb", JsonPrimitive(q.lb)) })
                }.toString())
            }
            DmSnapshot(items.values.toList(), HashMap(reports), readState, host.me, host.now(), reacts.values.toList(), locked.size,
                openingCount = kept3State.values.count { it == K3_OPENING }, unreadableCount = kept3State.values.count { it == K3_UNREADABLE })
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
                    Enc.MSG -> e.hasTag("p", me) && e.tags.none { it.isNotEmpty() && it[0] == "h" } // format 3, people (SPEC 19.83)
                    4 -> e.pubkey == me || e.hasTag("p", me)
                    else -> false
                }
                if (mine && backlog.none { it.id == e.id }) backlog.add(e)
            }
        }
    }

    /** Decrypts what is waiting. Stops when the signer does not answer (the rest waits for the next call). */
    suspend fun process() = busy.withLock {
        // format 3: what waits for its key or the author's lists is judged again at each pass (cheap: a lookup)
        synchronized(lock) { for (w in kept3.values) if (backlog.none { it.id == w.id }) backlog.add(w) }
        val todo = synchronized(lock) { backlog.sortedByDescending { it.createdAt } }
        // held messages and wraps the engine accepted later are looked at even when nothing new came
        if (todo.isEmpty() && synchronized(lock) { held.isEmpty() } && host.sealed?.later.isNullOrEmpty()) return@withLock
        var changed = false
        val eng = host.sealed
        for (e in todo) {
            if (e.kind == Enc.MSG) {
                takeEnc3(e)
                changed = true
                continue
            }
            if (e.kind == 1059 && sh.belfry.core.sealed.Sealing.isSealed(e)) {
                // sealed (§4): never the signer; kept until the engine can open and accept it
                if (eng == null) continue
                val got = try { eng.receive(e, host.belfryKey()) { DmReader.convOf(it, host.me, host.bridgeKey()) } } catch (x: Exception) { continue }
                when (got.verdict) {
                    sh.belfry.core.sealed.Sealing.Verdict.ACCEPT -> {
                        try { takeSealed(got.rumor!!, got.sealPubkey) } catch (x: Exception) { }
                        synchronized(lock) { seen.add(e.id); backlog.removeAll { it.id == e.id }; kept.remove(e.id); locked.remove(e.id) }
                    }
                    sh.belfry.core.sealed.Sealing.Verdict.KEEP, sh.belfry.core.sealed.Sealing.Verdict.LOCKED ->
                        synchronized(lock) {
                            backlog.removeAll { it.id == e.id }
                            if (got.verdict == sh.belfry.core.sealed.Sealing.Verdict.LOCKED) { locked[e.id] = e.createdAt; capLocked() } else locked.remove(e.id)
                            // to my letterbox, this device without its secret: not kept, not seen; read again
                            // from the relay when keys arrive (fourth review 2b)
                            if (got.stored) { kept[e.id] = e; capKept() } else kept.remove(e.id)
                        }
                    else -> synchronized(lock) { seen.add(e.id); backlog.removeAll { it.id == e.id }; kept.remove(e.id); locked.remove(e.id) }
                }
                changed = true
                continue
            }
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
        // kept wraps the engine settled meanwhile: accepted / dropped are seen, the rest let go unseen
        if (eng != null) {
            val (seenNow, letGo) = eng.drainSettled()
            if (seenNow.isNotEmpty() || letGo.isNotEmpty()) synchronized(lock) {
                for (id in seenNow) { seen.add(id); kept.remove(id); locked.remove(id) }
                for (id in letGo) kept.remove(id)
                changed = true
            }
        }
        // accepted later by the engine (the author's new roster, keys that arrived)
        if (eng != null) {
            val later = eng.later.toList().also { eng.later.removeAll(it.toSet()) }
            for ((r, seal) in later) {
                try { takeSealed(r, seal) } catch (x: Exception) { }
                DmReader.convOf(r, host.me, host.bridgeKey()).let { eng.markSealed(it) } // its conversation is sealed too
                changed = true
            }
            eng.flushState() // once per pass, not per wrap
        }
        if (synchronized(lock) { held.isNotEmpty() }) { retryHeld(); changed = true }
        if (rejudgeClear()) changed = true
        if (changed) {
            rethread()
            publish()
        }
    }

    /**
     * A people 8572 for me (SPEC 19.83; §6, §9): opened and accepted by format 3, its rumor handed on exactly as an opened
     * sealed rumor is (14, 15, 1301 as messages; 7 and 5 as reactions; nothing else); kept and judged again on a wait
     * (never marked seen), dropped on a refusal. Without a format-3 runtime here (a guest's session) it is left alone.
     */
    private suspend fun takeEnc3(e: Event) {
        val e3 = host.enc3
        if (e3 == null) { synchronized(lock) { backlog.removeAll { it.id == e.id } }; return }
        val got = try { e3.open(e) } catch (x: kotlinx.coroutines.CancellationException) { throw x } catch (x: Exception) { Enc3.Opened3.Wait(false, null) }
        when (got) {
            is Enc3.Opened3.Ok -> {
                try { takeEnc3Rumor(got.rumor) } catch (x: kotlinx.coroutines.CancellationException) { throw x } catch (x: Exception) { }
                synchronized(lock) { seen.add(e.id); backlog.removeAll { it.id == e.id }; kept3.remove(e.id); kept3State.remove(e.id); items.remove("p:" + e.id) }
            }
            is Enc3.Opened3.Wait -> synchronized(lock) {
                backlog.removeAll { it.id == e.id }
                kept3[e.id] = e
                kept3State[e.id] = when { got.unreadable -> K3_UNREADABLE; got.conv != null -> K3_PLACED; else -> K3_OPENING }
                // its conversation is known (its key is here): "Opening this message…" there until it opens
                if (got.conv != null && "p:" + e.id !in items) items["p:" + e.id] = Dm("p:" + e.id, got.conv, "", false, e.createdAt, 14, "", opening = true)
                capKept3()
            }
            Enc3.Opened3.Drop -> synchronized(lock) { seen.add(e.id); backlog.removeAll { it.id == e.id }; kept3.remove(e.id); kept3State.remove(e.id); items.remove("p:" + e.id) }
        }
    }

    private suspend fun takeEnc3Rumor(rumor: Event) {
        if (rumor.kind == 7 || rumor.kind == 5) { synchronized(lock) { reactionRumor(rumor) }; return }
        if (rumor.kind != 14 && rumor.kind != DmFiles.KIND && rumor.kind != 1301) return
        // e-mail in (§10): the belfry key is the sender (service); its mail is placed like the bridge's
        val b = host.belfryKey()
        val bridge = if (b != null && rumor.kind == 1301 && rumor.pubkey == b) b else host.bridgeKey()
        val d = readRumor(rumor, bridge)?.copy(enc = true) ?: return
        synchronized(lock) {
            val old = items[d.rid]
            items[d.rid] = d.copy(mine = d.mine || old?.mine == true, pending = old?.pending ?: false, failed = old?.failed, notTo = old?.notTo ?: emptyList(),
                heldSince = old?.heldSince, heldWhy = old?.heldWhy)
        }
    }

    /**
     * §17.2: messages that came unencrypted, dated after the first key of their conversation known here, are marked
     * "Not encrypted"; unencrypted reactions there stop counting. Run at each pass (keys may have arrived). True: changed.
     */
    private fun rejudgeClear(): Boolean {
        val e3 = host.enc3 ?: return false
        var changed = false
        synchronized(lock) {
            for ((rid, d) in items.toList()) if (d.plain && !d.clear && !d.isMail && EncRules.clearAfterKey(e3.firstKeyAt(d.conv), d.at)) { items[rid] = d.copy(clear = true); changed = true }
            for ((id, r) in reacts.toList()) {
                if (!r.plain) continue
                val t = items[r.target] ?: continue
                if (EncRules.clearAfterKey(e3.firstKeyAt(t.conv), r.at)) { reacts.remove(id); changed = true }
            }
        }
        return changed
    }

    /** Format 3: keys arrived (an envelope, a pack): clear ones judged again, and what waited for them read again. */
    suspend fun keysArrived() {
        if (rejudgeClear()) publish()
        process()
    }

    /** Messages held on this device (format 3 tries them again when lists change). */
    fun hasHeld(): Boolean = synchronized(lock) { held.isNotEmpty() }

    /** A sealed rumor the engine accepted: a message, a reaction, or a shared history (§8). */
    private suspend fun takeSealed(rumor: Event, sealPubkey: String?) {
        val eng = host.sealed
        // "It was me" (8563, sealed.md §5.2 step 2b) is the engine's and stops there: never a message
        if (rumor.kind == sh.belfry.core.sealed.Sealing.CONFIRM) return
        if (rumor.kind == sh.belfry.core.sealed.Sealing.SHARE) {
            if (eng == null || sealPubkey == null) return
            val chain = eng.chainOf(rumor.pubkey) ?: eng.refresh(rumor.pubkey)
            for (r in sh.belfry.core.sealed.Sealing.acceptShared(rumor, sealPubkey, chain, host.me)) {
                // reactions from a shared history are not applied as authentic; a held message is never replaced
                if (r.kind != 14) continue
                val d = DmReader.fromRumor(r, host.me, host.bridgeKey(), host.blossom, known()) ?: continue
                synchronized(lock) { if (r.id !in items) items[d.rid] = d.copy(sharedBy = rumor.pubkey) }
            }
            return
        }
        if (rumor.kind in sh.belfry.core.sealed.Sealing.SHARED_KINDS) synchronized(lock) { sealedRumors[rumor.id] = rumor }
        if (rumor.kind == 7 || rumor.kind == 5) { synchronized(lock) { reactionRumor(rumor) }; return }
        val d = readRumor(rumor) ?: return
        synchronized(lock) {
            val old = items[d.rid]
            // arriving sealed takes the "shared" mark off
            items[d.rid] = d.copy(mine = d.mine || old?.mine == true, pending = old?.pending ?: false, failed = old?.failed, sharedBy = null)
        }
    }

    /**
     * Mails are decrypted newest wrap first, and wraps are back-dated at random: an answer may be
     * read before the mail it answers. Once a batch is in, every mail that came in is placed
     * again, oldest first, so a thread never depends on the order of arrival.
     */
    private fun rethread() {
        val moved = synchronized(lock) { rethreadLocked() }
        for ((conv, at) in moved) onRead?.invoke(conv, at)
    }

    private fun rethreadLocked(): List<Pair<String, Long>> {
        val moved = ArrayList<Pair<String, Long>>()
        val known = ArrayList<KnownMail>()
        for (d in items.values.filter { it.isMail && MailThreads.isMail(it.conv) }.sortedBy { it.at }) {
            val conv = if (d.mine) d.conv else MailThreads.place(MailThreads.mailAddress(d.conv), d.subject, d.inReplyTo, d.references, known)
            if (conv != d.conv) {
                // the old conversation's mark follows the mail to its thread
                val readOld = readState.d[d.conv] ?: legacyRead[d.conv]
                if (readOld != null) moved.add(conv to readOld)
                items[d.rid] = d.copy(conv = conv)
            }
            known.add(KnownMail(d.messageId, conv))
        }
        return moved
    }

    private suspend fun readWrap(wrap: Event): Dm? {
        val opened = GiftWrap.unwrap(wrap, host.signer) ?: return null
        val rumor = opened.rumor
        val conv = DmReader.convOf(rumor, host.me, host.bridgeKey())
        // format 3 (SPEC 19.83): on a belfry this device knows as format 3, an account with a device list writes format
        // 3; its plain NIP-17 dated from then on is dropped (older history stays readable, §12)
        val since3 = host.format3Since()
        if (since3 != null && rumor.createdAt >= since3 && host.enc3?.devices(rumor.pubkey) is Devices.Listed) return null
        // an old-style rumor in a conversation this device saw sealed (§6, 294c94f (8))
        val since = host.sealed?.sealedSince?.get(conv)
        val late = since != null && rumor.createdAt > since
        // §17.2: after its conversation's first key, an unencrypted event never acts and shows "Not encrypted"
        val clear = rumor.kind != 1301 && EncRules.clearAfterKey(host.enc3?.firstKeyAt(conv), rumor.createdAt)
        if (rumor.kind == 7 || rumor.kind == 5) {
            if (!late && !clear) synchronized(lock) { reactionRumor(rumor, plain = true) } // after sealing or a key: ignored
            return null
        }
        if (synchronized(lock) { rumor.id in items }) return null // never replaces a message held here
        val d = readRumor(rumor) ?: return null
        return d.copy(plain = true, clear = clear, notSealed = late && d.kind == 14)
    }

    private suspend fun readRumor(rumor: Event, bridge: String? = host.bridgeKey()): Dm? {
        var raw: String? = rumor.content
        val x = rumor.tag("x")
        if (rumor.kind == 1301 && rumor.content.isEmpty() && !x.isNullOrEmpty()) {
            val l = LargeMail(rumor.tag("url") ?: "${host.blossom}/$x", rumor.tag("decryption-key") ?: "", rumor.tag("decryption-nonce") ?: "")
            raw = try { DmReader.decryptLarge(host.fetchBytes(l.url), l) } catch (e: Exception) { null }
        }
        return DmReader.fromRumor(rumor, host.me, bridge, host.blossom, known(), raw)
    }

    /** Holds the lock. */
    private fun reactionRumor(r: Event, plain: Boolean = false) = DmReaction.apply(reacts, gone, r, plain)

    private suspend fun readOld(e: Event): Dm {
        val me = host.me
        val peer = if (e.pubkey == me) e.tag("p") ?: me else e.pubkey
        val text = host.signer.nip04Decrypt(peer, e.content)
        return Dm(e.id, "n:$peer", e.pubkey, e.pubkey == me, e.createdAt, 4, text, plain = true,
            clear = EncRules.clearAfterKey(host.enc3?.firstKeyAt("n:$peer"), e.createdAt))
    }

    private fun known(): List<KnownMail> = synchronized(lock) { items.values.filter { it.isMail }.map { KnownMail(it.messageId, it.conv) } }

    /** The attachments of an e-mail, with their bytes (read from the message again). */
    suspend fun attachmentsOf(d: Dm): List<MailPart> {
        val raw = d.raw ?: d.large?.let { l -> try { DmReader.decryptLarge(host.fetchBytes(l.url), l) } catch (e: Exception) { null } } ?: return emptyList()
        return Mime.parseMail(raw).attachments
    }

    /** The conversation is on screen: read up to its newest message or e-mail (reactions are not items: they never move it). */
    fun markRead(conv: String) {
        val last = synchronized(lock) { items.values.filter { it.conv == conv }.maxOfOrNull { it.at } ?: 0 }
        if (last <= 0 || readState.readUpTo('d', conv, host.now()) >= last) return
        onRead?.invoke(conv, last)
    }

    /** The marks of the old per-device store (SPEC 19.27's migration seeds `d` with them). */
    fun legacyMarks(): Map<String, Long> = synchronized(lock) { HashMap(legacyRead) }

    /** The shared read state changed (here or on another device): unread counts follow. */
    fun setReadState(r: ReadState) {
        if (r === readState) return
        readState = r
        publish(save = false)
    }

    // ---- sending ---------------------------------------------------------------------------------

    /**
     * Wrap RUMOR for TO (and me) and put the wraps in the outbox. Never waits for the network.
     * Sealed when the conversation is (§4, §6): never the signer for it. False = held undecided
     * (the device couldn't tell yet; tried again at the first complete answer).
     */
    private suspend fun deliver(rumor: Event, to: List<String>, self: Boolean = true, conv: String? = null): Boolean {
        // format 3 first (SPEC 19.82 / 19.83): the session's decision; V1 = the ways below, exactly as before
        if (!host.localOnly && conv != null) {
            when (val w = host.encWrite(decisionConv(rumor, conv))) {
                EncWrite.V1 -> if (namesBelfry(rumor)) throw DmException("This e-mail was prepared for encrypted sending. Write it again.")
                EncWrite.Clear -> { oldWay(rumor, to, self); return true } // no lock: never encrypted, a guest or over 100 devices
                is EncWrite.Hold -> {
                    // a reaction or a take-back has no bubble to wait in: not sent, said at once
                    if (rumor.kind == 7 || rumor.kind == 5) throw DmException(EncText.held(w, ::nameOf))
                    hold(rumor, to, self, conv, DmSnapshot.enc3Why(w)); return false
                }
                is EncWrite.V3 -> {
                    val plan = w.plan as? EncPlan.People
                    if (plan == null) { hold(rumor, to, self, conv, DmSnapshot.enc3Why(EncWrite.Hold(sh.belfry.core.enc.HoldReason.NOT_LISTED))); return false }
                    deliverEnc(rumor, to, self, conv, plan); return true
                }
            }
        }
        val eng = host.sealed
        if (eng != null && !host.localOnly) {
            if (rumor.kind == 1301) {
                // §7, the same plan as a message (the review's 3): sealed by the device, the old way only on
                // knowledge, else held or refused; never the signer for a sealed thread
                when (val plan = eng.plan(conv ?: "mail", emptyList())) {
                    is sh.belfry.core.sealed.SealedEngine.Plan.Seal -> { mailSealed(rumor, to, self, plan); return true }
                    is sh.belfry.core.sealed.SealedEngine.Plan.Refuse -> throw DmException(plan.why)
                    sh.belfry.core.sealed.SealedEngine.Plan.Hold -> { hold(rumor, to, self, conv ?: "mail", null); return false }
                    sh.belfry.core.sealed.SealedEngine.Plan.Locked -> { hold(rumor, to, self, conv ?: "mail", LOCKED); return false }
                    sh.belfry.core.sealed.SealedEngine.Plan.OldWay -> {}
                }
            } else if (conv != null) {
                when (val plan = eng.plan(conv, to.filter { it != host.me })) {
                    is sh.belfry.core.sealed.SealedEngine.Plan.Seal -> {
                        // from the box: no bubble, the box's error line, the text stays (canvas v99)
                        if (!sh.belfry.core.sealed.Sealing.fits(rumor)) throw DmException(DmSnapshot.HELD_TOO_LONG)
                        enqueueSealed(eng.seal(rumor, plan), rumor.id); return true
                    }
                    // a sealed conversation with nobody to seal to for someone: it waits (canvas v98), never the old way
                    is sh.belfry.core.sealed.SealedEngine.Plan.Refuse -> if (plan.who != null) { hold(rumor, to, self, conv, "device:${plan.who}"); return false } else throw DmException(plan.why)
                    sh.belfry.core.sealed.SealedEngine.Plan.Hold -> {
                        // a reaction or a take-back has no bubble to wait in: not sent, said at once (the review's 16)
                        if (rumor.kind == 7 || rumor.kind == 5) throw DmException("Not sent yet: this device can't reach the belfry to check how to lock it. Try again in a moment.")
                        // a note written before my chain is anchored: shown as sending, sealed once it is
                        hold(rumor, to, self, conv, if (sh.belfry.core.model.Notes.isNotes(conv, host.me)) NOTES_CHAIN else null); return false
                    }
                    sh.belfry.core.sealed.SealedEngine.Plan.Locked -> {
                        if (rumor.kind == 7 || rumor.kind == 5) throw DmException(sh.belfry.core.model.SealedText.WRITE_LATER)
                        hold(rumor, to, self, conv, LOCKED); return false
                    }
                    sh.belfry.core.sealed.SealedEngine.Plan.OldWay -> {}
                }
            }
        }
        oldWay(rumor, to, self)
        return true
    }

    private val heldWhyNow = HashMap<String, String?>()

    private fun nameOf(pk: String): String = host.realName(pk).ifEmpty { "They" }

    /** The conversation the format-3 decision is asked for: an e-mail (to an address, or as a shared inbox) is `m:`. */
    private fun decisionConv(rumor: Event, conv: String): String = if (rumor.kind == 1301 || conv.startsWith("channel:")) "m:" else conv

    /**
     * Format 3 (SPEC 19.83): RUMOR encrypted for PLAN and queued: the key's envelopes first (when it is new, or its
     * unconfirmed ones aren't live any more), then one 8572 per person (they wait for those envelopes in the outbox).
     * Kept (what makes its copies again) until every copy is out.
     */
    private suspend fun deliverEnc(rumor: Event, to: List<String>, self: Boolean, conv: String, plan: EncPlan.People) {
        val e3 = host.enc3 ?: throw DmException(EncText.NOT_SET_UP)
        host.preparing(rumor.id)
        val live = host.outbox().filter { it.status == OutboxStatus.QUEUED }.mapNotNull { it.signed?.id }.toSet()
        val out = try { e3.encryptPeople(rumor, plan, self) { it in live } } catch (e: sh.belfry.core.enc.EncException) {
            host.notPrepared(rumor.id)
            throw DmException(if (e.message?.contains("too long") == true) DmSnapshot.HELD_TOO_LONG else "The message could not be prepared. Try again.")
        }
        synchronized(lock) { encOut[rumor.id] = Held(rumor, to, self, conv); encKeys[rumor.id] = out.key }
        for (env in out.envelopes) host.enqueueSigned(Target.MAIN, env, null)
        for (m in out.messages) host.enqueueEnc(m, rumor.id, out.key)
        update(rumor.id) { it.copy(enc = true, notTo = out.missing) }
    }

    /** The key a message's format-3 copies are under (the session queues a file's). */
    fun encKeyOf(rid: String): String? = encKeys[rid]

    /** "Try again" on a held message: held messages decided again now. */
    suspend fun retryHeldNow() {
        busy.withLock { retryHeld() }
        publish()
    }

    /**
     * Format 3: message RID's copies made again from its kept rumor (fresh dates: the relay refuses a date more than 5
     * minutes off, §18.1). Its queued copies go; its key's envelopes stay (other messages may need them).
     */
    private suspend fun rebuildEnc(rid: String): Boolean {
        val h = synchronized(lock) { encOut.remove(rid) } ?: return false
        for (o in host.outbox().filter { it.dm == rid && it.kind == Enc.MSG }) host.dropOutbox(o.localId)
        update(rid) { it.copy(failed = null, pending = true) }
        publish()
        try {
            if (!deliver(h.rumor, h.to, h.self, h.conv)) {
                val why = synchronized(lock) { heldWhyNow.remove(rid) }
                update(rid) { it.copy(heldSince = host.now(), heldWhy = why) }
            }
        } catch (e: Exception) {
            synchronized(lock) { refused[rid] = h }
            update(rid) { it.copy(pending = false, failed = (e as? DmException)?.message ?: "It could not be prepared.") }
        }
        publish()
        return true
    }

    /** A mail sealed by the device to the belfry's mail key (§7), the self-copy to my head. */
    private suspend fun mailSealed(rumor: Event, to: List<String>, self: Boolean, plan: sh.belfry.core.sealed.SealedEngine.Plan.Seal) {
        val eng = host.sealed ?: return
        for (t in to) host.enqueueSigned(Target.MAIN, GiftWrap.wrap(rumor, t, eng.device), rumor.id)
        val mine = plan.heads[host.me]
        if (self && mine != null) enqueueSealed(eng.seal(rumor, sh.belfry.core.sealed.SealedEngine.Plan.Seal(mapOf(host.me to mine))), rumor.id)
    }

    private fun hold(rumor: Event, to: List<String>, self: Boolean, conv: String, why: String?) {
        synchronized(lock) { held[rumor.id] = Held(rumor, to, self, conv); heldWhyNow[rumor.id] = why }
    }

    private suspend fun enqueueSealed(qs: List<sh.belfry.core.sealed.SealedEngine.Queued>, rid: String) {
        qs.firstOrNull()?.rumor?.takeIf { it.kind in sh.belfry.core.sealed.Sealing.SHARED_KINDS }?.let { r -> synchronized(lock) { sealedRumors[r.id] = r } }
        for (q in qs) {
            synchronized(lock) { sealQ[q.wrap.id] = QueuedSeal(q.rumor, q.recipient, q.lb) }
            host.enqueueSigned(Target.MAIN, q.wrap, rid)
        }
    }

    /** Held messages: decided at the first complete answer; queued before the held copy goes (64a07fa). */
    private suspend fun retryHeld() {
        val eng = host.sealed
        for ((rid, h) in synchronized(lock) { held.toList() }) {
            if (synchronized(lock) { items[rid]?.heldWhy } == "too_long") continue // never: Cancel only
            // format 3 first (SPEC 19.83): decided again now; never the version-1 or the clear way once it says V3 or Hold
            if (!host.localOnly) {
                val w = try { host.encWrite(decisionConv(h.rumor, h.conv)) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { continue }
                when (w) {
                    EncWrite.V1 -> if (namesBelfry(h.rumor)) { update(rid) { it.copy(heldWhy = null, failed = "This e-mail was prepared for encrypted sending. Write it again.") }; synchronized(lock) { held.remove(rid) }; continue }
                    EncWrite.Clear -> {
                        try { oldWay(h.rumor, h.to, h.self) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { continue }
                        synchronized(lock) { held.remove(rid) }
                        update(rid) { it.copy(heldSince = null, heldWhy = null) }
                        continue
                    }
                    is EncWrite.Hold -> { update(rid) { it.copy(heldWhy = DmSnapshot.enc3Why(w)) }; continue }
                    is EncWrite.V3 -> {
                        val plan = w.plan as? EncPlan.People ?: continue
                        try { deliverEnc(h.rumor, h.to, h.self, h.conv, plan) } catch (e: DmException) {
                            if (e.message == DmSnapshot.HELD_TOO_LONG) update(rid) { it.copy(heldWhy = "too_long") }
                            continue
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { continue }
                        synchronized(lock) { held.remove(rid) }
                        update(rid) { it.copy(heldSince = null, heldWhy = null) }
                        continue
                    }
                }
            }
            if (eng == null) {
                // nothing to seal with here (no device key): the old way, as a message sent now would go
                try { oldWay(h.rumor, h.to, h.self) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { continue }
                synchronized(lock) { held.remove(rid) }
                update(rid) { it.copy(heldSince = null, heldWhy = null) }
                continue
            }
            // a held mail is decided exactly like a direct one: only my own chain matters (§7; N1)
            val who = if (h.rumor.kind == 1301) emptyList() else h.to.filter { it != host.me }
            val plan = try { eng.plan(h.conv, who) } catch (e: Exception) { continue }
            when (plan) {
                sh.belfry.core.sealed.SealedEngine.Plan.Hold -> update(rid) { it.copy(heldWhy = if (sh.belfry.core.model.Notes.isNotes(h.conv, host.me)) NOTES_CHAIN else null) }
                sh.belfry.core.sealed.SealedEngine.Plan.Locked -> update(rid) { it.copy(heldWhy = LOCKED) }
                is sh.belfry.core.sealed.SealedEngine.Plan.Refuse -> if (plan.who != null) update(rid) { it.copy(heldWhy = "device:${plan.who}") } else {
                    synchronized(lock) { held.remove(rid) }
                    update(rid) { it.copy(heldSince = null, pending = false, failed = plan.why) }
                }
                is sh.belfry.core.sealed.SealedEngine.Plan.Seal -> {
                    // decided after waiting, but too long to lock: it stays with its line and Cancel
                    if (!sh.belfry.core.sealed.Sealing.fits(h.rumor)) { update(rid) { it.copy(heldWhy = "too_long") }; continue }
                    if (h.rumor.kind == 1301) mailSealed(h.rumor, h.to, h.self, plan) else enqueueSealed(eng.seal(h.rumor, plan), rid)
                    synchronized(lock) { held.remove(rid) }
                    update(rid) { it.copy(heldSince = null) }
                }
                sh.belfry.core.sealed.SealedEngine.Plan.OldWay -> {
                    oldWay(h.rumor, h.to, h.self)
                    synchronized(lock) { held.remove(rid) }
                    update(rid) { it.copy(heldSince = null) }
                }
            }
        }
    }

    /** A conversation's sealed messages (14 / 7 / 5), oldest first: what Share history sends. */
    fun sealedRumorsOf(conv: String): List<Event> = synchronized(lock) {
        sealedRumors.values.filter { DmReader.convOf(it, host.me, host.bridgeKey()) == conv }.sortedBy { it.createdAt }
    }

    /** A sealed wrap in the outbox (flushed only after AUTH and a head refresh; the review's 8). */
    fun isSealedQueued(item: OutboxItem): Boolean = item.signed?.let { w -> synchronized(lock) { w.id in sealQ } } == true

    /**
     * Wraps to read again although they were settled (sealed.md §5.7: wraps to the letterbox of a device that added
     * itself, which a build that did not follow joins took as "not for me" and marked seen). Only ever called for
     * a letterbox this device has just learnt of, so none of them was accepted before.
     */
    fun forgetSeen(ids: Collection<String>) {
        synchronized(lock) { seen.removeAll(ids.toSet()) }
    }

    /** Keys arrived or my chain moved: the wraps kept as locked are tried again at the next [process]. */
    fun retryKept() {
        synchronized(lock) { for (w in kept.values) if (backlog.none { it.id == w.id }) backlog.add(w) }
    }

    /** The person cancels a held message: it goes, and its text comes back (64a07fa). */
    suspend fun cancelHeld(rid: String): String? = busy.withLock { // the lock its sending (retryHeld) takes
        val text = synchronized(lock) {
            if (held.remove(rid) == null) return@withLock null
            items.remove(rid)?.text
        }
        publish()
        text
    }

    /**
     * Before a flush: queued sealed wraps whose recipient's head moved are sealed again (§9),
     * reactions and deletes too; the recipients' chains are asked again first (≤ 3 s).
     */
    suspend fun resealQueued(items: List<OutboxItem>): List<OutboxItem> {
        val eng = host.sealed ?: return items
        val ours = items.filter { it.signed?.let { w -> w.id in synchronized(lock) { sealQ.keys } } == true }
        if (ours.isEmpty()) return items
        val people = ours.mapNotNull { synchronized(lock) { sealQ[it.signed!!.id]?.recipient } }.toSet()
        kotlinx.coroutines.withTimeoutOrNull(3_000) { (people + host.me).forEach { eng.refresh(it) } }
        // out of my head: nothing sealed goes out (0.7.7); its messages say so, and they go once allowed (resealed)
        if (!eng.inHeadNow()) {
            val rids = ours.mapNotNull { it.dm }.toSet()
            for (rid in rids) update(rid) { if (it.heldWhy == null || it.heldWhy == LOCKED) it.copy(heldSince = it.heldSince ?: host.now(), heldWhy = LOCKED) else it }
            publish()
            return items - ours.toSet()
        }
        for (rid in ours.mapNotNull { it.dm }.toSet()) update(rid) { if (it.heldWhy == LOCKED && synchronized(lock) { rid !in held }) it.copy(heldSince = null, heldWhy = null) else it }
        return items.map { item ->
            val w = item.signed ?: return@map item
            val q = synchronized(lock) { sealQ[w.id] } ?: return@map item
            val again = eng.resealIfNeeded(sh.belfry.core.sealed.SealedEngine.Queued(q.rumor, q.recipient, q.lb, w))
            if (again.wrap.id == w.id) item else {
                synchronized(lock) { sealQ.remove(w.id); sealQ[again.wrap.id] = QueuedSeal(q.rumor, q.recipient, again.lb) }
                item.copy(signed = again.wrap)
            }
        }
    }

    private suspend fun oldWay(rumor: Event, to: List<String>, self: Boolean) {
        val me = host.me
        val people = LinkedHashSet(to).apply { if (self) add(me) }
        host.preparing(rumor.id)
        val wraps = try {
            people.map { GiftWrap.wrap(rumor, it, host.signer) }
        } catch (e: Exception) {
            host.notPrepared(rumor.id)
            throw e
        }
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
    suspend fun send(
        conv: String, text: String, subject: String? = null, group: String? = null, at: Long? = null, root: String? = null,
        /** The link preview's tags (SPEC 19.87), inside the rumor: sealed, encrypted or wrapped with it. Not for e-mail. */
        preview: List<List<String>> = emptyList(),
    ): String {
        val me = host.me
        if (!MailThreads.isMail(conv)) {
            val to = MailThreads.peers(conv)
            if (to.any { !Hex.isKey(it) }) throw DmException("This is not a valid Nostr key.")
            // a reply (docs/one-shape.md 3): ["e", root, "", "root"]; wrapped and sealed like any other 14
            val rumor = GiftWrap.rumor(14, me, sh.belfry.core.model.Notes.pTags(conv, me, to) + listOfNotNull(group?.let { listOf("group", it) }, root?.let { OneShape.dmReplyTag(it) }) + preview, text, at ?: nextAt())
            set(Dm(rumor.id, conv, me, true, rumor.createdAt, 14, text, pending = true, group = group, root = root, preview = sh.belfry.core.preview.LinkPreviews.wireTags(preview)))
            publish()
            try {
                if (!deliver(rumor, to, conv = conv)) {
                    val why = synchronized(lock) { heldWhyNow.remove(rumor.id) }
                    update(rumor.id) { it.copy(heldSince = host.now(), heldWhy = why) }
                }
            } catch (e: Exception) {
                synchronized(lock) { items.remove(rumor.id) } // the composer gets the text back
                publish()
                throw if (e is DmException) e else DmException(if (e is SignerTimeout) "Your signer did not answer. Check your connection and try again." else "The message could not be prepared. Try again.")
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
        val rumor = GiftWrap.rumor(1301, me, listOf(listOf("email-id", emailId), listOf("mail-from", myAddress), listOf("rcpt-to", to), listOf("subject", subj)) + belfryPerson(), built.raw, host.now())
        val refs = if (before != null) listOfNotNull(before.references, before.messageId).joinToString(" ").ifEmpty { null } else null
        set(Dm(rumor.id, target, me, true, rumor.createdAt, 1301, text, subject = subj, emailId = emailId, raw = built.raw, messageId = built.messageId, references = refs, inReplyTo = before?.messageId, verified = true, pending = true))
        synchronized(lock) { reports[emailId] = Report(Report.QUEUED, "Waiting for the connection…") }
        publish()
        try {
            if (!deliver(rumor, listOf(bridge), conv = target)) {
                val why = synchronized(lock) { heldWhyNow.remove(rumor.id) }
                update(rumor.id) { it.copy(heldSince = host.now(), heldWhy = why) }
            }
        } catch (e: Exception) {
            synchronized(lock) { refused[rumor.id] = Held(rumor, listOf(bridge), true, target) }
            val why = if (e is DmException) e.message ?: "It could not be prepared." else if (e is SignerTimeout) "Your signer did not answer." else "It could not be prepared."
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

    /**
     * E-mail out on format 3 (SPEC 19.83; §10): the belfry key is one more person of that one message (`["p", belfry]`, so
     * the rumor's people are the key's). Added ONLY when the mail will be written as format 3 (the lead's review of P3):
     * the write decision for mail says V3, or holds on a device that knows the belfry as format 3 (a held mail can then
     * only go as format 3: §17.13, one way). A mail that goes the version-1 way keeps today's tags exactly; [deliver]
     * refuses a version-1 mail that carries the belfry key (never sent to the bridge with an extra `p`).
     */
    internal suspend fun belfryPerson(): List<List<String>> {
        if (host.localOnly) return emptyList()
        val b = host.belfryKey() ?: return emptyList()
        if (host.enc3 == null || host.format3Since() == null) return emptyList()
        val w = try { host.encWrite("m:") } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { return emptyList() }
        return if (w is EncWrite.V3 || w is EncWrite.Hold) listOf(listOf("p", b)) else emptyList()
    }

    /** A mail rumor that names the belfry key as a person (format 3 only). */
    private fun namesBelfry(rumor: Event): Boolean {
        val b = host.belfryKey() ?: return false
        return rumor.kind == 1301 && rumor.tags.any { it.size >= 2 && it[0] == "p" && it[1] == b }
    }

    @Volatile private var lastAt = 0L
    /** A rumor's created_at: now, and strictly after the last one sent here (files of a group keep their order). */
    private fun nextAt(): Long = synchronized(lock) { maxOf(lastAt + 1, host.now()).also { lastAt = it } }

    /**
     * Sends FILES in CONV (a Nostr conversation), then CAPTION (a kind 14) after them, all in one
     * group when there are several pictures or a caption (vectors/dm-files.json). Each file goes
     * through the outbox: encrypted, uploaded and wrapped when it goes ([prepareFile]). Throws
     * [DmException] (too large, not a Nostr conversation) before anything is queued.
     */
    suspend fun sendFiles(conv: String, files: List<DmNewFile>, caption: String? = null) {
        if (files.isEmpty()) { if (!caption.isNullOrBlank()) send(conv, caption); return }
        if (MailThreads.isMail(conv)) throw DmException("Files go in a conversation on this belfry, not by e-mail.")
        val to = MailThreads.peers(conv)
        if (to.any { !Hex.isKey(it) }) throw DmException("This is not a valid Nostr key.")
        val max = if (host.isGuest) DmFiles.MAX_GUEST else DmFiles.MAX
        if (files.any { it.bytes.size > max }) throw DmException(DmFiles.tooLarge(host.isGuest))
        val me = host.me
        val others = to.filter { it != me }
        val group = if (files.size > 1 || !caption.isNullOrBlank()) DmFiles.newGroup() else null
        for (f in files) {
            val id = "lf" + sh.belfry.core.crypto.Random.hex(10)
            host.putBlob(id, f.bytes)
            val at = nextAt()
            val mime = f.mime.ifEmpty { "application/octet-stream" }
            // the key and nonce are chosen now and kept with the item: a retry uploads the same ciphertext
            val meta = buildList {
                addAll(sh.belfry.core.model.Notes.pTags(conv, me, others)) // a note: my own account, once
                add(listOf("file-type", mime))
                add(listOf("_key", sh.belfry.core.crypto.Random.hex(32)))
                add(listOf("_nonce", sh.belfry.core.crypto.Random.hex(12)))
                if (!mime.startsWith("image/") && !mime.startsWith("audio/")) add(listOf("name", f.name))
                if (f.width != null && f.height != null) add(listOf("dim", "${f.width}x${f.height}"))
                f.durationSec?.let { add(listOf("duration", it.toString())) }
                group?.let { add(listOf("group", it)) }
            }
            val shown = meta.filter { it[0] != "_key" && it[0] != "_nonce" && it[0] != "p" } + listOf(listOf("size", f.bytes.size.toString()))
            set(Dm(id, conv, me, true, at, DmFiles.KIND, "", pending = true, group = group, file = DmFileRef("", shown, localBlob = id)))
            host.enqueue(OutboxItem(
                localId = id, target = Target.MAIN, kind = DmFiles.KIND, content = "", tags = meta, createdAt = at,
                attachments = listOf(sh.belfry.core.outbox.Attachment(id, f.name, mime, f.bytes.size.toLong(), f.width, f.height)), dm = id,
            ))
        }
        publish()
        if (!caption.isNullOrBlank()) send(conv, caption, group = group)
    }

    /**
     * A queued file goes (the outbox calls it): encrypt (STEP "encrypting"), upload the ciphertext
     * ("uploading 2.2 of 4.8 MB"), make the kind 15 rumor and wrap it for everyone and me ("waiting
     * for the server": the seals are signed). The message is now known by the rumor's id; its wraps
     * are returned for the outbox. Throws with an outbox reason ("upload failed (413)"…).
     */
    suspend fun prepareFile(item: OutboxItem, step: (String) -> Unit): List<Event> {
        val me = host.me
        fun t(n: String) = item.tags.firstOrNull { it.size >= 2 && it[0] == n }?.get(1)
        val a = item.attachments.firstOrNull() ?: throw IllegalStateException("invalid: no file")
        val plain = host.blob(a.blobKey) ?: throw IllegalStateException("invalid: the file is gone")
        val others = item.tags.filter { it.size >= 2 && it[0] == "p" }.map { it[1] }
        // a sealed conversation: the file's message is sealed by the device like a kind 14 (§4, §6), decided
        // before the upload; never the old way into it. Waiting (can't tell yet / their device not ready) retries.
        val conv = synchronized(lock) { items[item.localId]?.conv } ?: MailThreads.nostrConv(me, others, me)
        // format 3 (SPEC 19.83): decided before any upload; held = the outbox tries again (never uploaded or sent in clear)
        var plan3: EncPlan.People? = null
        var clear = false
        if (!host.localOnly) when (val w = host.encWrite(conv)) {
            EncWrite.V1 -> {}
            EncWrite.Clear -> clear = true
            is EncWrite.Hold -> throw FileWaits("waiting: " + EncText.held(w, ::nameOf))
            is EncWrite.V3 -> plan3 = w.plan as? EncPlan.People ?: throw FileWaits("waiting: " + EncText.NOT_SET_UP)
        }
        val eng = if (host.localOnly || plan3 != null || clear) null else host.sealed
        val plan = eng?.plan(conv, others)
        when (plan) {
            is sh.belfry.core.sealed.SealedEngine.Plan.Refuse ->
                if (plan.who != null) throw FileWaits("waiting: their new device isn't ready for the lock") else throw IllegalStateException("invalid: " + plan.why)
            sh.belfry.core.sealed.SealedEngine.Plan.Hold -> throw FileWaits("waiting: can't check yet how to lock it")
            sh.belfry.core.sealed.SealedEngine.Plan.Locked -> throw FileWaits("waiting: this device isn't allowed yet")
            else -> {}
        }
        step(sh.belfry.core.outbox.SendState.STEP_PREPARE)
        val sealed = DmFiles.seal(plain, t("_key") ?: throw IllegalStateException("invalid: no key"), t("_nonce") ?: throw IllegalStateException("invalid: no nonce"))
        val url = host.uploadSealed(sealed.ciphertext) { sent, total -> step(sh.belfry.core.outbox.SendState.uploading(sent, total)) }
        val tags = DmFiles.tags(
            others, t("file-type") ?: a.mime, sealed, name = t("name"), dim = t("dim"),
            duration = t("duration")?.toIntOrNull(), group = t("group"),
        )
        val rumor = GiftWrap.rumor(DmFiles.KIND, me, tags, url, item.createdAt)
        step("waiting for the server")
        val wraps = if (plan3 != null) {
            // format 3: the key's envelopes (when needed) and one 8572 per person, sealed by this device (the session
            // queues the copies behind the envelopes: [encKeyOf])
            val e3 = host.enc3 ?: throw FileWaits("waiting: " + EncText.NOT_SET_UP)
            val live = host.outbox().filter { it.status == OutboxStatus.QUEUED }.mapNotNull { it.signed?.id }.toSet()
            val out = try { e3.encryptPeople(rumor, plan3) { it in live } } catch (e: sh.belfry.core.enc.EncException) { throw IllegalStateException("invalid: the file's message can't be encrypted") }
            synchronized(lock) { encOut[rumor.id] = Held(rumor, others, true, conv); encKeys[rumor.id] = out.key }
            update(item.localId) { it.copy(enc = true, notTo = out.missing) }
            out.envelopes + out.messages
        } else if (plan is sh.belfry.core.sealed.SealedEngine.Plan.Seal && eng != null) {
            val qs = eng.seal(rumor, plan)
            synchronized(lock) {
                for (q in qs) sealQ[q.wrap.id] = QueuedSeal(q.rumor, q.recipient, q.lb)
            }
            qs.map { it.wrap }
        } else {
            val people = LinkedHashSet(others).apply { add(me) }
            people.map { GiftWrap.wrap(rumor, it, host.signer) }
        }
        // the message is the rumor from now on (its reactions, its copy coming back from the relay)
        synchronized(lock) {
            val old = items.remove(item.localId)
            items[rumor.id] = (old ?: Dm(rumor.id, "", me, true, rumor.createdAt, DmFiles.KIND, "")).copy(
                rid = rumor.id, file = DmFileRef(url, tags.filter { it[0] != "p" }, localBlob = a.blobKey), pending = true,
            )
        }
        renames[item.localId] = rumor.id
        host.renamed(item.localId, rumor.id)
        if (host.localOnly) {
            val own = wraps.filter { it.tag("p") == me }
            synchronized(lock) { own.forEach { seen.add(it.id) } }
        }
        publish()
        return wraps
    }

    /** A file that waits for the lock (sealed conversation, not decided yet): the outbox tries again later. */
    class FileWaits(why: String) : Exception(why)

    private val renames = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** A file's message id after [prepareFile] (its rumor's), from the outbox item's local id. */
    fun idOf(localId: String): String? = renames[localId]

    /** My address here (`name@domain`), from my profile's nip05. */
    @Volatile var myAddressProvider: () -> String? = { null }
    private fun myAddress(): String? = myAddressProvider()?.lowercase()?.takeIf { it.contains('@') }

    /**
     * Answers an e-mail card as the shared inbox CHANNEL (only its admins can: the bridge checks).
     * Returns the email-id; `snapshot.report(id)` then follows its delivery.
     */
    suspend fun sendAsChannel(
        channel: String, channelName: String, to: String, subject: String, text: String, inReplyTo: String?, cardId: String,
        fromLocal: String = channel, references: String? = null,
        /** Reply all: the others (Cc), one `rcpt-to` each (docs/one-shape.md 4). */
        cc: List<String> = emptyList(),
        /** The internal draft this sends (`["draft", id]`), so every app hides it. */
        draftId: String? = null,
    ): String {
        val bridge = host.bridgeKey() ?: throw DmException("This belfry does not send e-mail yet.")
        val me = host.me
        val from = "$fromLocal@${host.domain}"
        val name = host.realName(me)
        val built = Mime.buildMail(
            fromName = if (name.isNotEmpty()) "$name for $channelName" else channelName, from = from, to = to, subject = subject, text = text,
            domain = host.domain, inReplyTo = inReplyTo, references = references, now = host.now(), cc = cc,
        )
        val emailId = UUID.randomUUID().toString()
        val rcpts = (listOf(to) + cc).map { it.trim().lowercase() }.filter { it.contains('@') }.distinct().take(sh.belfry.core.model.OneShape.MAX_RECIPIENTS)
        val tags = listOf(listOf("email-id", emailId), listOf("mail-from", from)) + rcpts.map { listOf("rcpt-to", it) } +
            listOf(listOf("subject", subject), listOf("e", cardId)) + listOfNotNull(draftId?.let { listOf("draft", it) }) + belfryPerson()
        val rumor = GiftWrap.rumor(1301, me, tags, built.raw, host.now())
        synchronized(lock) { reports[emailId] = Report(Report.QUEUED, "Sending…"); channelSends.add(emailId) }
        publish()
        if (host.localOnly) {
            synchronized(lock) { reports[emailId] = Report(Report.DELIVERED, ""); channelSends.remove(emailId) }
            publish()
            return emailId
        }
        try {
            if (!deliver(rumor, listOf(bridge), self = false, conv = "channel:$channel")) {
                // format 3 held it (it goes when it can): the card says why meanwhile
                val why = synchronized(lock) { heldWhyNow.remove(rumor.id) }
                val h = why?.let { DmSnapshot.enc3Hold(Dm(rumor.id, "", me, true, 0, 1301, "", heldWhy = it)) }
                if (h != null) synchronized(lock) { reports[emailId] = Report(Report.QUEUED, EncText.held(h, ::nameOf)) }
            }
        } catch (e: Exception) {
            synchronized(lock) { reports[emailId] = Report(Report.FAILED, "It could not be prepared."); channelSends.remove(emailId) }
            publish()
            throw DmException(if (e is SignerTimeout) "Your signer did not answer. Check your connection and try again." else "The e-mail could not be prepared. Try again.")
        }
        for (wait in longArrayOf(2_000, 5_000, 10_000, 30_000)) host.later(wait) { checkReports() }
        return emailId
    }

    /**
     * React to D with EMOJI, or take my reaction back (MINE, from [DmSnapshot.reactionsOf]): sealed
     * to the conversation's people and to me, as the web does. Not on e-mail.
     */
    suspend fun react(d: Dm, emoji: String, mine: DmReaction? = null) {
        if (d.isMail || MailThreads.isMail(d.conv)) return
        val me = host.me
        val to = MailThreads.peers(d.conv)
        if (mine != null) {
            val rumor = GiftWrap.rumor(5, me, listOf(listOf("e", mine.rid), listOf("k", "7")) + sh.belfry.core.model.Notes.pTags(d.conv, me, to), "", host.now())
            synchronized(lock) { reacts.remove(mine.rid); gone[mine.rid] = me }
            publish()
            deliver(rumor, to, conv = d.conv)
            return
        }
        val rumor = GiftWrap.rumor(
            7, me,
            listOf(listOf("e", d.rid, "", d.from), listOf("p", d.from), listOf("k", d.kind.toString())) + to.filter { it != me && it != d.from }.map { listOf("p", it) },
            emoji, host.now(),
        )
        synchronized(lock) { reacts[rumor.id] = DmReaction(rumor.id, d.rid, me, emoji, rumor.createdAt) }
        publish()
        try {
            deliver(rumor, to, conv = d.conv)
        } catch (e: Exception) {
            synchronized(lock) { reacts.remove(rumor.id) }
            publish()
            throw DmException(if (e is SignerTimeout) "Your signer did not answer. Check your connection and try again." else "The reaction could not be prepared. Try again.")
        }
    }

    /** Adds EMOJI to D, or takes mine back when I gave it already (a chip's tap). */
    suspend fun toggleReaction(d: Dm, emoji: String) {
        val mine = _snapshot.value.reactionsOf(d.rid).firstOrNull { it.emoji == emoji }?.mine
        react(d, emoji, mine)
    }

    /** Mails refused before any wrap was queued (no head yet…): Retry plans them again from the kept rumor (N7). */
    private val refused = LinkedHashMap<String, Held>()

    /** Puts a refused message back in the outbox. */
    suspend fun retry(rid: String) {
        // format 3: a refused copy is made again with fresh dates (its key's envelopes kept)
        if (synchronized(lock) { rid in encOut } && host.outbox().filter { it.dm == rid }.let { q -> q.isEmpty() || q.any { it.status == OutboxStatus.FAILED } }) {
            if (rebuildEnc(rid)) return
        }
        val queued = host.outbox().filter { it.dm == rid }
        val again = synchronized(lock) { refused.remove(rid) }
        if (queued.isEmpty() && again != null) {
            update(rid) { it.copy(failed = null, pending = true) }
            publish()
            try {
                if (!deliver(again.rumor, again.to, again.self, again.conv)) update(rid) { it.copy(heldSince = host.now()) }
            } catch (e: Exception) {
                synchronized(lock) { refused[rid] = again }
                update(rid) { it.copy(pending = false, failed = (e as? DmException)?.message ?: "It could not be prepared.") }
            }
            publish()
            return
        }
        if (queued.isEmpty()) return
        val d = synchronized(lock) { items[rid] }
        update(rid) { it.copy(failed = null, pending = true) }
        if (d?.emailId != null) synchronized(lock) { reports[d.emailId] = Report(Report.QUEUED, "Waiting for the connection…") }
        publish()
        host.requeue(queued)
    }

    /** The engine tells: this outbox item is out (accepted by its relay). */
    fun onSent(item: OutboxItem) {
        item.signed?.let { w -> synchronized(lock) { sealQ.remove(w.id) } }
        val rid = item.dm ?: return
        // format 3: every copy out, nothing to make again
        if (host.outbox().none { it.dm == rid && it.localId != item.localId }) synchronized(lock) { if (encOut.remove(rid) != null) encKeys.remove(rid) }
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
        // format 3 (§18.1): a copy refused for good (its date too old after a long wait, most often): made again once by
        // itself with fresh dates; after that it says Not sent, and Retry makes it again
        if (item.kind == Enc.MSG && synchronized(lock) { rid in encOut } && (rebuilt[rid] ?: 0) < 1) {
            rebuilt[rid] = (rebuilt[rid] ?: 0) + 1
            host.later(0) { rebuildEnc(rid) }
            return
        }
        val me = host.me
        val d = synchronized(lock) { items[rid] } ?: return
        // my own copy failing says nothing, except in Notes to myself, where it is the only wrap
        if (!sh.belfry.core.model.Notes.isNotes(d.conv, me) && item.tags.none { it.size >= 2 && it[0] == "p" && it[1] != me }) return
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
            put("authors", buildJsonArray { listOfNotNull(bridge, host.belfryKey()).distinct().forEach { add(JsonPrimitive(it)) } })
            put("#r", buildJsonArray { open.takeLast(50).forEach { add(JsonPrimitive(it)) } })
        }
        var changed = false
        for (e in host.queryMain(filter)) {
            try {
                val r = e.tag("r") ?: continue
                val peer = e.tag("ephemeral-pubkey") ?: continue
                // a sealed sender's report is encrypted to the head letterbox, tagged lb (§7; vectors `reports`):
                // opened with that letterbox's secret, never the signer; without lb the old kind, for the signer
                val body: JsonObject = if (e.tag("lb") != null || e.pubkey == host.belfryKey() && host.belfryKey() != bridge) {
                    when (val got = sh.belfry.core.sealed.Sealing.openReport(e, host.belfryKey(), host.sealed?.keys ?: sh.belfry.core.sealed.KeyChain())) {
                        is sh.belfry.core.sealed.Sealing.Report.Opened -> got.body
                        sh.belfry.core.sealed.Sealing.Report.Signer -> parseJsonOrNull(host.signer.nip44Decrypt(peer, e.content)).obj() ?: continue
                        else -> continue // locked: tried again at the next check; invalid: dropped
                    }
                } else parseJsonOrNull(host.signer.nip44Decrypt(peer, e.content)).obj() ?: continue
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
            // an address on this belfry: a failed lookup never downgrades a member to e-mail in clear (P5)
            val pk = try { host.nip05(m.groupValues[1]) } catch (e: Exception) {
                throw DmException("No answer from the belfry about this address yet. Check your connection and try again.")
            }
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
        /** heldWhy: this device isn't in my head (the locked thread's words). */
        const val LOCKED = "locked"
        /** [Dm.heldWhy] of a note to myself that waits for my chain to be made and anchored (SPEC 19.66). */
        const val NOTES_CHAIN = "notes-chain"
        const val K_ITEMS = "dm-items"
        const val K_SEEN = "dm-seen"
        const val K_REPORTS = "dm-reports"
        const val K_READ = "dm-read"
        const val K_REACTS = "dm-reactions"
        const val K_GONE = "dm-reactions-gone"
        const val K_KEPT = "dm-sealed-kept"
        const val K_LOCKED = "dm-sealed-locked"
        const val MAX_LOCKED = 500
        const val K_HELD = "dm-held"
        const val K_SEALQ = "dm-sealed-queue"
        const val K_SEALED_RUMORS = "dm-sealed-rumors"
        const val K_REFUSED = "dm-refused"
        const val K_KEPT3 = "dm-enc3-kept"
        const val K_ENCOUT = "dm-enc3-out"
        const val MAX_KEPT3 = 500
        private const val K3_OPENING = 0
        private const val K3_UNREADABLE = 1
        private const val K3_PLACED = 2
        private val ADDRESS = Regex("^([^@\\s]+)@([^@\\s]+\\.[^@\\s]+)$")
    }
}
