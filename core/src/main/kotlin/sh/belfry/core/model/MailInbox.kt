package sh.belfry.core.model

import sh.belfry.core.mail.MailThreads

// E-mail threads in a channel (SPEC 19.33; web src/core/mailthreads.ts, collective-belfry
// docs/mail.md; vectors/mailthreads.json). Nothing new on the wire: the bridge posts each e-mail
// from outside as a kind 9 by the groups key (`client belfry-mail`, `mail-from`, `message-id`,
// `in-reply-to`, `references`) and what went out as a kind 9 `client belfry-mail-out` with `e` = the
// e-mail it answers. Each device joins them:
// - an e-mail naming (References + In-Reply-To, newest first) one already in a thread joins it;
// - else the same sender with the same base subject joins theirs;
// - else it starts one and is its root (the id of its route, labels and NIP-22 replies);
// - what went out joins the thread of the e-mail it answers.
// The channel's feed shows one row per thread, where its latest activity is.

/** One e-mail of a thread. */
data class MailItem(
    val m: Message,
    val incoming: Boolean,
    /** in: the sender's address (lower case); out: the address it went to. */
    val address: String,
    /** in: the sender's name as their mail gave it. */
    val name: String,
    val subject: String,
    val messageId: String?,
    /** in: DKIM or SPF aligned with the From domain. */
    val verified: Boolean,
    /** out: the member who sent it. */
    val by: String?,
    /** The whole e-mail on Blossom (in only). */
    val original: MailOriginal?,
    /** Who it was for (docs/one-shape.md 4): `["to", address, name]`, never Bcc; empty on cards before 2026-11-22. */
    val to: List<MailPerson> = emptyList(),
    val cc: List<MailPerson> = emptyList(),
    /** out: the internal draft it sent (`["draft", id]`). */
    val draftId: String? = null,
)

/** An address on a card's `to` / `cc` tag: an outsider is an address only, with the name their mail gave. */
data class MailPerson(val address: String, val name: String) {
    /** The name, else the address. */
    val shown: String get() = name.ifEmpty { address }
}

/** A shared inbox's e-mail thread. [id] = its first e-mail's event id (route, labels, replies). */
data class InboxThread(
    val id: String,
    val mails: List<MailItem>,
    val ids: List<String>,
    val address: String,
    val name: String,
    val subject: String,
    /** The latest e-mail received (what a reply answers). */
    val lastIn: MailItem?,
    /** Latest activity: an e-mail either way or a team reply. */
    val last: Long,
    val replies: Int,
    val who: List<String>,
    val state: CardState,
    /** Every Message-ID in it, oldest first (References of an answer). */
    val refs: List<String>,
    /** The internal drafts still shown (docs/one-shape.md 4): by its admins, not sent, not deleted; oldest first. */
    val drafts: List<Message> = emptyList(),
    /** Its `belfry/inbox` labels (any of its e-mails), oldest first: the activity lines. */
    val labels: List<sh.belfry.core.nostr.Event> = emptyList(),
) {
    val root: Message? get() = mails.firstOrNull()?.m
    val latest: MailItem? get() = mails.lastOrNull()
}

/** A group's threads, oldest first, and which thread (index in [list]) each e-mail is in. */
class MailThreadIndex(val list: List<InboxThread>, val of: Map<String, Int>) {
    fun threadOf(id: String): InboxThread? = of[id]?.let { list[it] }
    companion object { val EMPTY = MailThreadIndex(emptyList(), emptyMap()) }
}

object MailInbox {
    /** A card's `to` / `cc` tags (docs/one-shape.md 4), at most 20 in all, first of each address. */
    fun people(tags: List<List<String>>, kind: String): List<MailPerson> {
        val seen = HashSet<String>()
        return tags.filter { it.size >= 2 && it[0] == kind && it[1].contains('@') }.take(OneShape.MAX_RECIPIENTS)
            .mapNotNull { t -> val a = t[1].trim().lowercase(); if (seen.add(a)) MailPerson(a, t.getOrNull(2)?.trim().orEmpty()) else null }
    }

    /**
     * "Lou, Dev Patel and 1 more": the first [shown] names (each a name, else its address), then how
     * many more. Used by the sent card and the Undo bar.
     */
    fun names(list: List<String>, shown: Int = 2): String {
        if (list.isEmpty()) return ""
        if (list.size <= shown) return if (list.size == 1) list[0] else list.dropLast(1).joinToString(", ") + " and " + list.last()
        return list.take(shown).joinToString(", ") + " and ${list.size - shown} more"
    }

    /**
     * The drafts a thread shows: kind 1111 with `mail-draft` 1 under its first card, by one of
     * [admins], minus those a sent card names (`OneShape.visibleDrafts`; deleted ones never reach
     * the [replies] at all).
     */
    fun drafts(replies: List<Message>, admins: Set<String>, sentDraftIds: Set<String>): List<Message> {
        val mine = replies.filter { OneShape.isMailDraft(it.ev) && it.pubkey in admins }
        val keep = OneShape.visibleDrafts(mine.map { it.id to it.pubkey }, sentDraftIds, emptyList()).toSet()
        return mine.filter { it.id in keep }
    }

    /** "address|base subject" (lower case, spaces folded): how a sender's thread is found without references. */
    fun subjectKey(address: String, subject: String): String =
        address.lowercase() + "|" + MailThreads.foldSpaces(MailThreads.baseSubject(subject).lowercase())

    /** The thread state from its e-mails and its `belfry/inbox` labels, walked by time (then id). */
    internal fun state(rootId: String, mails: List<MailItem>, labels: List<sh.belfry.core.nostr.Event>): CardState {
        class Step(val at: Long, val id: String, val label: sh.belfry.core.nostr.Event?, val mail: MailItem?)
        val steps = mails.map { Step(it.m.at, it.m.id, null, it) } + labels.map { Step(it.createdAt, it.id, it, null) }
        var status = CardStatus.OPEN
        var assignee: String? = null
        var answered = 0
        for (x in steps.sortedWith(compareBy<Step> { it.at }.thenBy { it.id })) {
            val l = x.label
            if (l != null) {
                when (l.tags.firstOrNull { it.size >= 3 && it[0] == "l" && it[2] == World.INBOX_NS }?.get(1)) {
                    "open" -> status = CardStatus.OPEN
                    "closed" -> status = CardStatus.CLOSED
                    // automatic after a reply was delivered (docs/one-shape.md 4); a newer received mail reopens it below
                    "answered" -> status = CardStatus.WAITING
                    "assigned" -> assignee = l.t("p")?.takeIf { it.isNotEmpty() }
                }
            } else {
                val mail = x.mail ?: continue
                if (!mail.incoming) {
                    answered += 1
                    if (status == CardStatus.OPEN) status = CardStatus.WAITING
                } else if (mail.m.id != rootId) {
                    status = CardStatus.OPEN // they wrote back
                }
            }
        }
        // the status itself is the shared rule (vectors/one-shape.json "thread_state"): labels, each sent card
        // as `answered` at its time, a newer received e-mail reopens; the walk above keeps who has it
        val statusLabels = labels.mapNotNull { l ->
            val v = l.tags.firstOrNull { it.size >= 3 && it[0] == "l" && it[2] == World.INBOX_NS }?.get(1) ?: return@mapNotNull null
            OneShape.StatusLabel(l.id, l.createdAt, v)
        }
        val shared = OneShape.threadState(mails.filter { it.incoming }.map { it.m.at }, statusLabels, mails.filter { !it.incoming }.map { it.m.at })
        status = when (shared) { "closed" -> CardStatus.CLOSED; "answered" -> CardStatus.WAITING; else -> CardStatus.OPEN }
        return CardState(status, assignee, answered)
    }
}
