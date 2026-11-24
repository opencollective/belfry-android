package sh.belfry.core.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import sh.belfry.core.crypto.B64
import sh.belfry.core.crypto.Random
import sh.belfry.core.crypto.sha256Hex
import sh.belfry.core.nostr.Event
import sh.belfry.core.nostr.EventTemplate
import sh.belfry.core.nostr.Tag
import sh.belfry.core.outbox.Target
import java.net.URI
import java.text.Normalizer
import java.util.Locale

/** An event an action queues: where it goes and what it says, before the client tag and signing. */
data class Draft(val target: Target, val kind: Int, val content: String = "", val tags: List<Tag> = emptyList()) {
    fun template(createdAt: Long = 0): EventTemplate = EventTemplate(kind, content, tags, createdAt)
}

/** Who can join a pod: [closed] (+ the question) and, SPEC 19.59, [isPrivate] (closed + private; no question). */
data class PodAccess(val closed: Boolean = false, val prompt: String = "", val isPrivate: Boolean = false)

data class NewEvent(
    val title: String,
    val description: String = "",
    val location: String = "",
    val allDay: Boolean = false,
    /** Timed events: Unix seconds. */
    val start: Long = 0,
    val end: Long? = null,
    /** All-day events: ISO dates (yyyy-MM-dd). */
    val startDate: String = "",
    val endDate: String? = null,
    /** An online meeting link (https only), a NIP-52 `r` tag. */
    val online: String? = null,
    /** IANA zone of the times (`start_tzid`). */
    val tzid: String = "UTC",
    /** How the card's text says when ("12 Nov 2026, 18:00"): formatted by the app in the reader's locale. */
    val whenLabel: String = "",
)

data class NewProposal(val title: String, val summary: String = "", val text: String = "", val status: ProposalStatus = ProposalStatus.DRAFT)

/**
 * Every user action as the events it queues (SPEC 7-13, 19; the web chat's `actions.ts`).
 * Pure: random ids and the time come in as parameters. The engine appends [CLIENT_TAG], sets
 * `created_at`, saves the drafts in the outbox in the order given, and flushes.
 */
object Actions {
    val CLIENT_TAG: Tag = listOf("client", "belfry-android")
    const val DEFAULT_PROMPT = "Why do you want to join, and what would you bring?"
    // 🔥 after the heart (canvas channels/message-menu, to-do c76b680c)
    val DEFAULT_EMOJIS = listOf("👍", "❤️", "🔥", "😂", "🎉", "🙏", "👀")
    val MORE_EMOJIS = listOf(
        "👍", "❤️", "😂", "🎉", "🙏", "👀", "🔥", "👏", "😍", "🤔", "😮", "😢",
        "😅", "🙌", "💪", "✅", "❌", "💯", "🚀", "✨", "🌱", "☕", "🍕", "🍻",
        "😊", "😎", "🤗", "🥳", "😴", "🤯", "😬", "🙈", "👋", "🤝", "👌", "✌️",
        "💡", "📌", "📅", "⏰", "🎶", "📷", "🌞", "🌧️", "❄️", "🌈", "🐝", "🌻",
    )
    val SUGGESTED_LABELS = listOf("organiser", "speaker", "volunteer", "maintainer", "host", "newcomer")
    const val SEEN_EVERY = 6 * 3600L

    /** Random hex of [bytes] bytes (the web chat's newId). */
    fun randomId(bytes: Int = 8): String = Random.hex(bytes)

    fun slug(name: String, newId: (Int) -> String = { randomId(it) }): String {
        val s = Normalizer.normalize(name.lowercase(Locale.ROOT), Normalizer.Form.NFKD)
            .replace(Regex("[^a-z0-9]+"), "-").replace(Regex("^-|-$"), "").sliceUnits(32)
        return s.ifEmpty { "channel-" + newId(3) }
    }

    // ---- channels ----

    fun newChannelId(name: String, newId: (Int) -> String = { randomId(it) }): String = slug(name, newId) + "-" + newId(2)

    fun createChannel(id: String, name: String, about: String): List<Draft> = listOf(
        Draft(Target.GROUPS, 9007, tags = listOf(listOf("h", id))),
        Draft(Target.GROUPS, 9002, tags = listOf(listOf("h", id), listOf("name", name), listOf("about", about))),
    )

    /** The first channel of a new community (SPEC 4). */
    fun createGeneral(communityName: String): List<Draft> = createChannel("general", "general", "Everything about $communityName. Say hi 👋")

    fun editChannel(id: String, name: String, about: String): Draft =
        Draft(Target.GROUPS, 9002, tags = listOf(listOf("h", id), listOf("name", name), listOf("about", about)))

    // SPEC 19.59: open + public / closed + public (+ its question when it has one) / closed + private
    private fun accessTags(a: PodAccess): List<Tag> {
        val closed = a.closed || a.isPrivate
        val q = a.prompt.jsTrim()
        return GroupJoin.tags(closed, a.isPrivate) + if (closed && !a.isPrivate && q.isNotEmpty()) listOf(listOf("prompt", q)) else emptyList()
    }

    fun newPodId(name: String, newId: (Int) -> String = { randomId(it) }): String = "pod-" + slug(name, newId).sliceUnits(24) + "-" + newId(2)

    /**
     * A pod: its own group `pod-…`, announced in its parent (channel or pod) by a post with a `pod`
     * tag — never a Private one (SPEC 19.59: it posts no announcement anywhere). ADD: the people the
     * creator adds at once, one 9000 each right after the 9007 / 9002.
     */
    fun createPod(id: String, name: String, about: String, parent: String = "general", access: PodAccess = PodAccess(), add: List<String> = emptyList()): List<Draft> =
        listOf(
            Draft(Target.GROUPS, 9007, tags = listOf(listOf("h", id)) + GroupJoin.tags(access.closed || access.isPrivate, access.isPrivate)),
            Draft(Target.GROUPS, 9002, tags = listOf(listOf("h", id), listOf("name", name), listOf("about", about)) + accessTags(access)),
        ) + addMembers(id, add) + if (access.isPrivate) emptyList() else listOf(
            Draft(Target.GROUPS, 9, "⬡ New pod: $name" + (if (about.isNotEmpty()) " · $about" else ""), listOf(listOf("h", parent), listOf("pod", id))),
        )

    fun editPod(id: String, name: String, about: String, access: PodAccess): Draft =
        Draft(Target.GROUPS, 9002, tags = listOf(listOf("h", id), listOf("name", name), listOf("about", about)) + accessTags(access))

    /** Join (open group) or apply (closed group: the content answers its prompt). */
    fun join(id: String, answer: String = ""): Draft = Draft(Target.GROUPS, 9021, answer.sliceUnits(2000), listOf(listOf("h", id)))
    fun leave(id: String): Draft = Draft(Target.GROUPS, 9022, tags = listOf(listOf("h", id)))
    fun admit(id: String, pubkey: String): Draft = Draft(Target.GROUPS, 9000, tags = listOf(listOf("h", id), listOf("p", pubkey, "member")))
    fun decline(id: String, pubkey: String): Draft = Draft(Target.GROUPS, 9001, tags = listOf(listOf("h", id), listOf("p", pubkey)))

    // ---- messages ----

    fun postMessage(groupId: String, text: String, topic: String? = null, announce: Boolean = false): Draft {
        val topics = LinkedHashSet<String>()
        if (!topic.isNullOrEmpty()) topics.add(Topics.norm(topic))
        if (announce) topics.add(Topics.ANNOUNCE)
        topics.addAll(Topics.hashtags(text))
        return Draft(Target.GROUPS, 9, text, listOf(listOf("h", groupId)) + topics.map { listOf("t", it) } + Mentions.tags(text) + refTags(text))
    }

    /** SPEC 19.44: an `a` tag for each event or proposal naddr in the text (readers parse the text, never need it). */
    fun refTags(text: String): List<Tag> =
        References.parts(text, "").mapNotNull { p ->
            val t = when (p) { is RefPart.Ref -> p.target; is RefPart.Box -> p.target; else -> null }
            when (t) { is RefTarget.Event -> t.coord; is RefTarget.Proposal -> t.coord; else -> null }
        }.distinct().map { listOf("a", it) }

    /** A reply in the thread of a message (NIP-22 comment, kept in the group by its `h` tag). */
    fun postThreadReply(groupId: String, rootId: String, rootPubkey: String, rootKind: Int, text: String): Draft {
        val k = rootKind.toString()
        return Draft(
            Target.GROUPS, 1111, text,
            listOf(
                listOf("h", groupId), listOf("E", rootId, "", rootPubkey), listOf("K", k), listOf("P", rootPubkey),
                listOf("e", rootId, "", rootPubkey), listOf("k", k), listOf("p", rootPubkey),
            ) + Topics.hashtags(text).map { listOf("t", it) } + Mentions.tags(text, listOf(rootPubkey)),
        )
    }

    fun postThreadReply(groupId: String, root: Event, text: String): Draft = postThreadReply(groupId, root.id, root.pubkey, root.kind, text)

    /**
     * Edit my own message or thread reply: kind 1010 naming it (`e`), with the new text. Its
     * photos, the topic it was sent in and an announcement mark stay.
     */
    fun editMessage(groupId: String, m: Message, text: String): Draft {
        val typed = Topics.hashtags(m.text).toSet()
        // its photos; its link preview's picture stays an imeta entry but is not a photo in the text (SPEC 19.87)
        val allImeta = m.ev.tags.filter { it.isNotEmpty() && it[0] == "imeta" }
        val imeta = allImeta.filter { !sh.belfry.core.preview.LinkPreviews.isPreviewImeta(it) }
        val keptTopics = m.ev.tags.filter { it.size >= 2 && it[0] == "t" && it[1].isNotEmpty() && Topics.norm(it[1]) !in typed }.map { Topics.norm(it[1]) }
        val urls = imeta.mapNotNull { t -> t.firstOrNull { it.startsWith("url ") }?.substring(4) }.filter { it.isNotEmpty() }
        val topics = LinkedHashSet(keptTopics).apply { addAll(Topics.hashtags(text)) }
        return Draft(
            Target.GROUPS, World.EDIT_KIND, (listOf(text) + urls).filter { it.isNotEmpty() }.joinToString("\n"),
            listOf(listOf("h", groupId), listOf("e", m.ev.id), listOf("k", m.ev.kind.toString())) + imeta + topics.map { listOf("t", it) } + Mentions.tags(text) +
                // an edit keeps the preview as it was (the message's current one): never fetched again
                m.preview.ifEmpty { sh.belfry.core.preview.LinkPreviews.wireTags(allImeta + m.ev.tags.filter { it.size >= 2 && it[0] == "preview" }) },
        )
    }

    fun deleteMessage(groupId: String, id: String): Draft = Draft(Target.GROUPS, 5, tags = listOf(listOf("h", groupId), listOf("e", id)))

    // ---- reactions ----

    /** A reaction: `k` = the target's kind ("9" a message, "1111" a thread reply); readers key on `e`, never on `k`. */
    fun react(groupId: String, messageId: String, author: String, emoji: String, kind: Int = 9): Draft =
        Draft(Target.GROUPS, 7, emoji, listOf(listOf("h", groupId), listOf("e", messageId), listOf("p", author), listOf("k", kind.toString())))

    fun unreact(groupId: String, reactionId: String): Draft =
        Draft(Target.GROUPS, 5, tags = listOf(listOf("h", groupId), listOf("e", reactionId), listOf("k", "7")))

    /**
     * One tap adds, a second removes. Null when there is nothing to send: my reaction is still in
     * the outbox ([minePending]), so there is nothing to take back on the relay.
     */
    fun toggleReaction(groupId: String, messageId: String, author: String, emoji: String, mine: Event?, minePending: Boolean, kind: Int = 9): Draft? =
        if (mine != null) (if (minePending) null else unreact(groupId, mine.id)) else react(groupId, messageId, author, emoji, kind)

    /** My most used emojis ([usage]: emoji → times), topped up with the defaults. */
    fun topEmojis(usage: Map<String, Int>, n: Int = 6): List<String> =
        (usage.entries.sortedByDescending { it.value }.map { it.key } + DEFAULT_EMOJIS).distinct().take(n)

    // ---- channel settings ----

    /** Turn a channel's shared inbox on or off (counts only from the channel's admins). */
    /**
     * A channel's page on the web and its visibility (admins; web actions.ts setWebPage): NIP-78,
     * `d` = belfry:web:<channel>. `posts` (a one-type channel) is kept when the channel has one.
     */
    fun setWebPage(groupId: String, on: Boolean, slug: String, visibility: String, posts: String? = null): Draft {
        val content = kotlinx.serialization.json.buildJsonObject {
            put("on", kotlinx.serialization.json.JsonPrimitive(on))
            put("slug", kotlinx.serialization.json.JsonPrimitive(slug))
            put("visibility", kotlinx.serialization.json.JsonPrimitive(if (visibility == "members") "members" else "public"))
            if (posts != null) put("posts", kotlinx.serialization.json.JsonPrimitive(posts))
        }.toString()
        return Draft(Target.GROUPS, 30078, content, listOf(listOf("d", World.WEB_D + groupId), listOf("h", groupId), listOf("t", "belfry-web")))
    }

    /**
     * The shared inbox switch: d = `belfry:inbox:<channel>` + h (docs/mail.md @73a414f). Never the
     * old d `belfry:inbox` alone: one admin's setting for a channel replaced theirs for every other.
     */
    fun setInbox(groupId: String, on: Boolean): Draft =
        Draft(Target.GROUPS, 30078, "{\"on\":$on}", listOf(listOf("d", World.inboxD(groupId)), listOf("h", groupId)))

    /** Close or reopen an e-mail card. */
    fun setCardStatus(groupId: String, card: String, closed: Boolean): Draft =
        Draft(Target.GROUPS, 1985, tags = listOf(listOf("h", groupId), listOf("L", World.INBOX_NS), listOf("l", if (closed) "closed" else "open", World.INBOX_NS), listOf("e", card)))

    /** docs/one-shape.md 4: the thread's first card is answered (sent by the app whose reply was delivered, or by hand). */
    fun markAnswered(groupId: String, card: String): Draft =
        Draft(Target.GROUPS, 1985, tags = listOf(listOf("h", groupId), listOf("L", World.INBOX_NS), listOf("l", "answered", World.INBOX_NS), listOf("e", card)))

    /** An internal draft of a reply all: a NIP-22 comment on the thread's first card with `["mail-draft", "1"]` (docs/one-shape.md 4). */
    fun mailDraft(groupId: String, root: sh.belfry.core.nostr.Event, text: String): Draft {
        val d = postThreadReply(groupId, root.id, root.pubkey, root.kind, text)
        return d.copy(tags = d.tags + listOf(listOf(OneShape.MAIL_DRAFT, "1")))
    }

    /** Who has an e-mail card; `assignee` "" = nobody. */
    fun assignCard(groupId: String, card: String, assignee: String): Draft =
        Draft(Target.GROUPS, 1985, tags = listOf(listOf("h", groupId), listOf("L", World.INBOX_NS), listOf("l", "assigned", World.INBOX_NS), listOf("e", card)) +
            (if (assignee.isNotEmpty()) listOf(listOf("p", assignee)) else emptyList()))

    /** Pin / unpin a channel's topics: one label event with the whole list (latest wins). */
    fun setTopics(groupId: String, list: List<String>): Draft =
        Draft(Target.GROUPS, 1985, tags = listOf(listOf("h", groupId), listOf("L", Topics.NS)) + list.map(Topics::norm).distinct().map { listOf("l", it, Topics.NS) })

    fun pinTopic(groupId: String, pinned: List<String>, topic: String, pin: Boolean): Draft {
        val t = Topics.norm(topic)
        return setTopics(groupId, if (pin) pinned + t else pinned.filter { it != t })
    }

    fun setMemberLabels(groupId: String, pubkey: String, labels: List<String>): Draft =
        Draft(Target.GROUPS, 1985, tags = listOf(listOf("h", groupId), listOf("L", World.LABEL_NS)) + labels.map { listOf("l", it, World.LABEL_NS) } + listOf(listOf("p", pubkey)))

    // ---- tasks (tasklist.sh) ----

    private fun taskTags(groupId: String): List<Tag> = listOf(listOf("t", World.listIdFor(groupId)), listOf("h", groupId))
    /** A to-do; [from] = the message it was made from (a `q` tag). Never cut: the form keeps it within [TodoLimit.MAX]. */
    fun addTask(groupId: String, title: String, from: String? = null): Draft =
        Draft(Target.GROUPS, 2100, title, taskTags(groupId) + (if (from != null) listOf(listOf("q", from)) else emptyList()))
    /** action: claim | unclaim | done | undone | delete */
    fun taskAction(groupId: String, taskId: String, action: String): Draft =
        Draft(Target.GROUPS, 2101, tags = listOf(listOf("e", taskId), listOf("action", action)) + taskTags(groupId))
    /**
     * SPEC 19.40: the to-do goes to PUBKEY. To me = `claim` (what every client reads); to someone
     * else = `assign` with `["p", pubkey]`.
     */
    fun assignTask(groupId: String, taskId: String, pubkey: String, me: String): Draft =
        if (pubkey == me) taskAction(groupId, taskId, "claim")
        else Draft(Target.GROUPS, 2101, tags = listOf(listOf("e", taskId), listOf("action", "assign"), listOf("p", pubkey)) + taskTags(groupId))

    /** Nobody on it any more: my own = `unclaim`; someone else's = `unassign` with `["p", assignee]`. */
    fun unassignTask(groupId: String, taskId: String, assignee: String, me: String): Draft =
        if (assignee == me) taskAction(groupId, taskId, "unclaim")
        else Draft(Target.GROUPS, 2101, tags = listOf(listOf("e", taskId), listOf("action", "unassign"), listOf("p", assignee)) + taskTags(groupId))

    fun commentTask(groupId: String, taskId: String, text: String): Draft =
        Draft(Target.GROUPS, 2103, text.sliceUnits(2000), listOf(listOf("e", taskId)) + taskTags(groupId))

    // ---- events (NIP-52) ----

    /** A video call link anyone can open straight away: Jitsi Meet. */
    fun jitsiLink(title: String, newId: (Int) -> String = { randomId(it) }): String {
        val s = slug(title, newId)
        return "https://meet.jit.si/" + (if (s.startsWith("channel-")) "meeting" else s) + "-" + newId(5)
    }

    fun meetingLabel(url: String): String {
        val h = try { URI(url.trim()).host ?: "" } catch (e: Exception) { "" }
        return when {
            h.endsWith("meet.google.com") -> "Google Meet"
            h == "zoom.us" || h.endsWith(".zoom.us") -> "Zoom"
            h.endsWith("jit.si") || h.contains("jitsi") -> "Jitsi"
            h.endsWith("teams.microsoft.com") || h.endsWith("teams.live.com") -> "Teams"
            else -> h.ifEmpty { "Online" }
        }
    }

    /**
     * An event is a sub-channel of the channel it is posted in: its own chat `ws-<x>`, a NIP-52
     * event `session-<x>` on the community relay, and a post in the channel linking to it (`a`).
     * Returns the event's coordinate and the drafts. [x] = 4 random bytes as hex.
     */
    fun createEvent(n: NewEvent, channel: String, me: String, groupsRelay: String, mainRelay: String, x: String = randomId(4)): Pair<String, List<Draft>> {
        val d = "session-$x"
        val groupId = "ws-$x"
        val tags = ArrayList<Tag>()
        tags.add(listOf("d", d)); tags.add(listOf("title", n.title)); tags.add(listOf("h", groupId, groupsRelay))
        if (n.allDay) {
            tags.add(listOf("start", n.startDate.take(10)))
            if (!n.endDate.isNullOrEmpty()) tags.add(listOf("end", n.endDate.take(10)))
        } else {
            tags.add(listOf("start", n.start.toString())); tags.add(listOf("start_tzid", n.tzid))
            if (n.end != null) { tags.add(listOf("end", n.end.toString())); tags.add(listOf("end_tzid", n.tzid)) }
        }
        if (n.location.isNotEmpty()) tags.add(listOf("location", n.location))
        val online = n.online?.jsTrim().orEmpty()
        if (online.startsWith("https://")) tags.add(listOf("r", online))
        val kind = if (n.allDay) 31922 else 31923
        val coord = "$kind:$me:$d"
        val whenText = if (n.allDay) n.startDate.take(10) else n.whenLabel
        val drafts = createChannel(groupId, n.title, n.description.split("\n")[0].ifEmpty { "Chat for this event" }) + listOf(
            Draft(Target.MAIN, kind, n.description, tags),
            Draft(Target.GROUPS, 9, "📅 ${n.title} · $whenText" + (if (n.location.isNotEmpty()) " · ${n.location}" else ""),
                listOf(listOf("h", channel), listOf("a", coord, mainRelay))),
        )
        return coord to drafts
    }

    /** A chat for an event that has none: same `ws-` convention. */
    fun openEventChat(e: CalEvent): List<Draft> = createChannel(e.groupId, e.title, "Chat about this session")

    /** [eventPending]: the event itself is still in my outbox (no `e` tag then). */
    fun rsvp(e: CalEvent, status: Rsvp, me: String, eventPending: Boolean = false): Draft {
        val parts = e.coord.split(":")
        val author = parts.getOrElse(1) { "" }
        val d = parts.getOrElse(2) { "" }
        return Draft(
            Target.MAIN, 31925,
            tags = listOf(listOf("d", sha256Hex("$me:$author:$d").substring(0, 30)), listOf("a", e.coord)) +
                (if (eventPending) emptyList() else listOf(listOf("e", e.ev.id))) + listOf(listOf("status", status.key), listOf("p", author)),
        )
    }

    fun deleteEvent(e: CalEvent): Draft = Draft(Target.MAIN, 5, tags = listOf(listOf("a", e.coord), listOf("e", e.ev.id)))

    // ---- proposals ----

    private fun proposalTags(d: String, groupId: String, title: String, summary: String, version: Int, status: ProposalStatus, groupsRelay: String, now: Long): List<Tag> = listOf(
        listOf("d", d), listOf("title", title), listOf("summary", summary), listOf("t", "proposal"), listOf("h", groupId, groupsRelay),
        listOf("version", version.toString()), listOf("status", status.key), listOf("published_at", now.toString()),
    )

    private fun openVote(groupId: String, coord: String, title: String, version: Int): Draft =
        Draft(Target.GROUPS, 1068, "$title (version $version): do you approve?",
            listOf(listOf("h", groupId), listOf("a", coord), listOf("version", version.toString()), listOf("polltype", "singlechoice")) +
                Proposal.VOTE_OPTIONS.map { listOf("option", it.first, it.second) })

    /**
     * A proposal: its own chat `prop-…`, its text (NIP-23 kind 30023, version 1, on the community
     * relay) and a card in the parent channel or pod. Returns the chat's id and the drafts.
     * [x] = 3 random bytes as hex.
     */
    fun createProposal(n: NewProposal, parent: String, me: String, groupsRelay: String, mainRelay: String, now: Long, x: String = randomId(3)): Pair<String, List<Draft>> {
        val groupId = "prop-" + slug(n.title).sliceUnits(24) + "-" + x
        val d = "proposal-$x"
        val coord = "30023:$me:$d"
        val drafts = ArrayList<Draft>()
        drafts.addAll(createChannel(groupId, n.title, n.summary.ifEmpty { "Proposal" }))
        drafts.add(Draft(Target.MAIN, 30023, n.text, proposalTags(d, groupId, n.title, n.summary, 1, n.status, groupsRelay, now)))
        drafts.add(Draft(Target.GROUPS, 9, "📝 Proposal: ${n.title}" + (if (n.summary.isNotEmpty()) " · ${n.summary}" else ""),
            listOf(listOf("h", parent), listOf("proposal", groupId), listOf("a", coord, mainRelay))))
        if (n.status == ProposalStatus.VOTING) drafts.add(openVote(groupId, coord, n.title, 1))
        return groupId to drafts
    }

    /** A new version: the text replaced (same d, version + 1), a note in its chat, and a new poll while under vote. */
    fun reviseProposal(p: Proposal, title: String, summary: String, text: String, note: String, groupsRelay: String, mainRelay: String, now: Long): List<Draft> {
        val version = p.version + 1
        val drafts = ArrayList<Draft>()
        drafts.add(Draft(Target.MAIN, 30023, text, proposalTags(p.d, p.groupId, title, summary, version, p.status, groupsRelay, now)))
        val nt = note.jsTrim()
        drafts.add(Draft(Target.GROUPS, 9, "📝 Version $version" + (if (nt.isNotEmpty()) ": $nt" else ""),
            listOf(listOf("h", p.groupId), listOf("a", p.coord, mainRelay), listOf("version", version.toString()))))
        if (p.status == ProposalStatus.VOTING) drafts.add(openVote(p.groupId, p.coord, title, version))
        return drafts
    }

    fun setProposalStatus(p: Proposal, status: ProposalStatus): List<Draft> {
        val drafts = ArrayList<Draft>()
        drafts.add(Draft(Target.GROUPS, 1985, tags = listOf(listOf("h", p.groupId), listOf("L", World.PROPOSAL_NS), listOf("l", status.key, World.PROPOSAL_NS), listOf("a", p.coord))))
        if (status == ProposalStatus.VOTING && (p.poll == null || p.poll.version != p.version)) drafts.add(openVote(p.groupId, p.coord, p.title, p.version))
        return drafts
    }

    /** Null when there is no poll to answer. */
    fun vote(p: Proposal, option: String): Draft? =
        p.poll?.let { Draft(Target.GROUPS, 1018, tags = listOf(listOf("h", p.groupId), listOf("e", it.ev.id), listOf("response", option))) }

    // ---- drop box links ----

    /** A fresh link token: 16 random bytes, base64url without padding. */
    fun newDropToken(): String = B64.url(Random.bytes(16))

    /** [days] 1, 7 or 30; [login]: uploaders must be signed in. */
    fun createDropLink(groupId: String, days: Double, login: Boolean, now: Long, token: String = newDropToken()): Pair<String, Draft> {
        val expires = now + Math.round(days * 86400)
        return token to Draft(
            Target.GROUPS, 30078, "",
            listOf(listOf("d", "drop:$token"), listOf("h", groupId), listOf("expiration", expires.toString())) +
                (if (login) listOf(listOf("login")) else emptyList()) +
                listOf(listOf("alt", "Drop box link: upload photos and files to this channel")),
        )
    }

    /** End a drop box link (kind 5 by its author: the event and its address). */
    fun endDropLink(l: DropLink): Draft =
        Draft(Target.GROUPS, 5, tags = listOf(listOf("h", l.groupId), listOf("e", l.ev.id), listOf("a", "30078:${l.ev.pubkey}:drop:${l.token}")))

    // ---- seen ----

    /** I opened this channel: say so at most every 6 hours per channel. */
    fun shouldMarkSeen(now: Long, mySeen: Long, lastSentHere: Long): Boolean = now - maxOf(mySeen, lastSentHere) >= SEEN_EVERY

    fun seen(groupId: String): Draft = Draft(Target.GROUPS, 30078, "", listOf(listOf("d", World.seenD(groupId)), listOf("h", groupId)))

    // ---- my profile ----

    /** My tags and place (kind 30078, d = belfry:profile), the other keys of [current] (World.myExtrasJson) kept. */
    fun saveExtras(tags: List<String>, share: Share, place: Place?, current: JsonObject = JsonObject(emptyMap())): Draft =
        Draft(Target.MAIN, 30078, People.extrasContent(tags, share, place, current), listOf(listOf("d", World.PROFILE_D)))

    /** Name and about in my kind 0, the rest of it ([current], see World.myProfileJson) kept. */
    fun saveProfile(current: JsonObject, name: String, about: String, nip05: String? = null, picture: String? = null): Draft {
        val n = name.jsTrim().sliceUnits(100)
        val content = buildJsonObject {
            for ((k, v) in current) put(k, v)
            put("name", JsonPrimitive(n))
            put("display_name", JsonPrimitive(n))
            put("about", JsonPrimitive(about.jsTrim().sliceUnits(2000)))
            if (!nip05.isNullOrEmpty()) put("nip05", JsonPrimitive(nip05))
            if (!picture.isNullOrEmpty()) put("picture", JsonPrimitive(picture))
        }
        return Draft(Target.MAIN, 0, content.toString(), emptyList())
    }

    /** A new picture in my kind 0 (the URL of the uploaded, resized image), the rest kept. */
    fun setAvatar(current: JsonObject, url: String): Draft {
        val content = buildJsonObject {
            for ((k, v) in current) put(k, v)
            put("picture", JsonPrimitive(url))
        }
        return Draft(Target.MAIN, 0, content.toString(), emptyList())
    }
}
