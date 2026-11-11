package sh.belfry.core.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import sh.belfry.core.crypto.Bech32
import sh.belfry.core.crypto.sha256Hex
import sh.belfry.core.nostr.Event
import sh.belfry.core.nostr.arr
import sh.belfry.core.nostr.obj
import sh.belfry.core.nostr.parseJsonOrNull
import sh.belfry.core.nostr.str
import sh.belfry.core.outbox.OutboxItem
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/**
 * Everything the screens show, derived from the local event log plus the queued outbox items
 * (SPEC 6): an immutable snapshot. Build a new one when the log or the outbox changes; every view
 * is computed on first use and cached. Port of the web chat's `src/core/model.ts` (+ people.ts,
 * access.ts canAdmin and the feed logic of ui/Chat.tsx).
 *
 * Nothing here reads the clock: functions that depend on the time take `now` (Unix seconds).
 */
class World(events: List<Event>, outbox: List<OutboxItem> = emptyList(), val ctx: WorldContext) {

    val me: String get() = ctx.me

    /** The whole outbox, oldest first (gift wraps on their way included). */
    val outboxItems: List<OutboxItem> = outbox.sortedBy { it.createdAt }

    private val pendingById: Map<String, OutboxItem> = outbox.filter { it.signed == null }.associateBy { it.localId }

    /** All events, queued ones shown as if published (not the pre-signed gift wraps). In arrival order. */
    val all: List<Event> = events + outbox.filter { it.signed == null }.map { it.asEvent(ctx.me) }

    /** [all] by `created_at`, ties by id. */
    val sorted: List<Event> by lazy { all.sortedWith(BY_TIME) }

    /** The outbox item behind an event that is not sent yet (queued or failed), else null. */
    fun pending(eventId: String): OutboxItem? = pendingById[eventId]
    fun isPending(eventId: String): Boolean = pendingById.containsKey(eventId)

    private val byH: Map<String, List<Event>> by lazy {
        val out = HashMap<String, MutableList<Event>>()
        for (e in all) { val h = e.t("h") ?: continue; out.getOrPut(h) { ArrayList() }.add(e) }
        out
    }
    private val byKind: Map<Int, List<Event>> by lazy { all.groupBy { it.kind } }
    private val sortedByH = ConcurrentHashMap<String, List<Event>>()

    /** Events carrying this `h`, in arrival order. */
    private fun inGroup(id: String): List<Event> = byH[id] ?: emptyList()
    private fun inGroupSorted(id: String): List<Event> = sortedByH.getOrPut(id) { inGroup(id).sortedWith(BY_TIME) }
    private fun ofKind(kind: Int): List<Event> = byKind[kind] ?: emptyList()
    /** A group's events by time (Events.kt reads an event's people from them). */
    internal fun groupEventsSorted(id: String): List<Event> = inGroupSorted(id)

    // ---- deletion ------------------------------------------------------------------------------

    /** Deleted event ids and addresses (`kind:pubkey:d`): kind 5 by the same author, kind 9005 by a moderator. */
    val deleted: Set<String> by lazy {
        val out = HashSet<String>()
        val authors = HashMap<String, String>()
        for (e in all) authors[e.id] = e.pubkey
        for (e in ofKind(9005)) for (t in e.tags) if (t.size >= 2 && t[0] == "e" && t[1].isNotEmpty()) out.add(t[1])
        for (e in ofKind(5)) for (t in e.tags) {
            if (t.size < 2) continue
            if (t[0] == "e" && authors[t[1]] == e.pubkey) out.add(t[1])
            if (t[0] == "a" && t[1].split(":").getOrNull(1) == e.pubkey) out.add(t[1])
        }
        out
    }

    // ---- profiles ------------------------------------------------------------------------------

    val profiles: Map<String, Profile> by lazy {
        val out = HashMap<String, Profile>()
        val guests = ArrayList<Pair<String, String>>()
        for (e in all) {
            if (e.kind == 9000) {
                val name = e.t("name")
                val p = e.t("p")
                if (!name.isNullOrEmpty() && !p.isNullOrEmpty()) guests.add(p to name.sliceUnits(100))
            }
            if (e.kind != 0) continue
            val prev = out[e.pubkey]
            if (prev != null && prev.at >= e.createdAt) continue
            val o = parseJsonOrNull(e.content).obj() ?: continue
            out[e.pubkey] = Profile(
                name = o["display_name"].str()?.takeIf { it.isNotEmpty() } ?: o["name"].str(),
                picture = o["picture"].str(),
                nip05 = o["nip05"].str(),
                about = o["about"].str() ?: "",
                at = e.createdAt,
            )
        }
        for ((pk, name) in guests) {
            val had = out[pk]
            if (had?.name.isNullOrEmpty()) out[pk] = (had ?: Profile()).copy(name = name)
        }
        out
    }

    /** The name other people know someone by (never "You"): e-mail From lines, mentions. "" when unknown. */
    fun realName(pubkey: String): String {
        val p = profiles[pubkey]
        return p?.name?.takeIf { it.isNotEmpty() } ?: p?.nip05?.substringBefore('@')?.takeIf { it.isNotEmpty() } ?: ""
    }

    fun displayName(pubkey: String): String {
        if (pubkey == me) return "You"
        return realName(pubkey).ifEmpty { shortKey(pubkey) }
    }

    /** The name over a message: "Drop box" for an upload through a drop link by a key without a profile. */
    fun nameOver(m: Message): String =
        if (m.viaDropBox && profiles[m.pubkey]?.name.isNullOrEmpty()) "Drop box" else displayName(m.pubkey)

    fun aboutOf(pubkey: String): String = profiles[pubkey]?.about ?: ""

    /** My address here (`<username>@<domain>`), from my profile's nip05; null when I have none. */
    val myAddress: String? by lazy { profiles[me]?.nip05?.lowercase()?.takeIf { it.contains('@') } }

    // ---- groups (NIP-29) -----------------------------------------------------------------------

    private class G(val id: String, at: Long) {
        var name = id
        var about = ""
        var picture: String? = null
        var createdAt = at
        var lastActivity = at
        var members = LinkedHashSet<String>()
        val admins = LinkedHashSet<String>()
        var parent: String? = null
        var closed = false
        var prompt: String? = null
        val applications = ArrayList<Application>()
        var pending = false
        val metaAt = HashMap<String, Long>()
    }

    /** `general` first, then by latest activity. */
    val groups: List<Group> by lazy {
        val map = LinkedHashMap<String, G>()
        val applied = LinkedHashMap<Pair<String, String>, Event>()
        val decided = HashMap<Pair<String, String>, Long>()
        val gone = HashSet<String>()
        for (e in sorted) {
            val id = (if (e.kind in 39000..39003) e.t("d") else e.t("h")) ?: continue
            if (id.isEmpty()) continue
            val g = map.getOrPut(id) { G(id, e.createdAt) }
            if (e.kind == 9008) gone.add(id)
            if (e.kind == 9007) {
                g.createdAt = minOf(g.createdAt, e.createdAt)
                if (isPending(e.id)) g.pending = true
            }
            if (e.kind == 9007 || e.kind == 9002 || e.kind == 39000) {
                for (k in META) {
                    val v = e.t(k) ?: continue
                    if ((g.metaAt[k] ?: 0) > e.createdAt) continue
                    when (k) { "name" -> g.name = v; "about" -> g.about = v; else -> g.picture = v }
                    g.metaAt[k] = e.createdAt
                }
                val access = if (e.tags.any { it.firstOrNull() == "closed" }) true else if (e.tags.any { it.firstOrNull() == "open" }) false else null
                if (access != null && (g.metaAt["closed"] ?: 0) <= e.createdAt) { g.closed = access; g.metaAt["closed"] = e.createdAt }
                val prompt = e.t("prompt")
                if (prompt != null && (g.metaAt["prompt"] ?: 0) <= e.createdAt) { g.prompt = prompt; g.metaAt["prompt"] = e.createdAt }
            }
            if (e.kind == 9007) g.admins.add(e.pubkey)
            if (e.kind == 39001) for (t in e.tags) if (t.size >= 2 && t[0] == "p") g.admins.add(t[1])
            if (e.kind == 39002) g.members = e.tags.filter { it.size >= 2 && it[0] == "p" }.mapTo(LinkedHashSet()) { it[1] }
            if (e.kind == 9000 || e.kind == 9001) {
                val p = e.t("p")
                if (!p.isNullOrEmpty()) {
                    if (e.kind == 9000) g.members.add(p) else g.members.remove(p)
                    decided[id to p] = e.createdAt
                    // a co-host (events): put in with the admin role; the relay's 39001 says the same
                    if (e.kind == 9000 && e.tags.any { it.size >= 3 && it[0] == "p" && it[1] == p && it[2] == "admin" }) g.admins.add(p)
                }
            }
            if (e.kind == 9) {
                g.lastActivity = maxOf(g.lastActivity, e.createdAt)
                g.members.add(e.pubkey)
            }
            if (e.kind == 9021) {
                // an event's application or waitlist place is not a join
                if (!g.closed && e.tags.none { it.firstOrNull() == "waitlist" || it.firstOrNull() == "ticket" }) g.members.add(e.pubkey) else applied[id to e.pubkey] = e
            }
            if (e.kind == 9022) { g.members.remove(e.pubkey); applied.remove(id to e.pubkey) }
        }
        // a pod or proposal lives where it was announced: the first post carrying its tag
        for (e in sorted) {
            if (e.kind != 9) continue
            val sub = podOf(e) ?: proposalOf(e) ?: continue
            val h = e.t("h")
            val g = map[sub] ?: continue
            if (!h.isNullOrEmpty() && h != sub && g.parent == null) g.parent = h
        }
        for (g in map.values) if ((isPodId(g.id) || isProposalId(g.id)) && g.parent == null) g.parent = "general"
        for ((k, e) in applied) {
            val g = map[k.first] ?: continue
            if (e.pubkey in g.members || (decided[k] ?: 0) >= e.createdAt) continue
            g.applications.add(Application(e.pubkey, e.content, e.createdAt, e))
        }
        map.values.filter { it.id !in gone }
            .sortedWith(compareBy<G> { if (it.id == "general") 0 else 1 }.thenByDescending { it.lastActivity })
            .map {
                Group(
                    id = it.id, name = it.name, about = it.about, picture = it.picture, createdAt = it.createdAt,
                    lastActivity = it.lastActivity, members = it.members, admins = it.admins, type = typeOfId(it.id),
                    parent = it.parent, closed = it.closed, prompt = it.prompt, applications = it.applications, pending = it.pending,
                )
            }
    }

    private val groupById: Map<String, Group> by lazy { groups.associateBy { it.id } }

    /** Who signed each channel's metadata (kind 39000, the relay's key), by group id: the author of a channel reference. */
    val metaAuthors: Map<String, String> by lazy {
        val out = HashMap<String, String>()
        for (e in ofKind(39000)) { val d = e.t("d"); if (!d.isNullOrEmpty()) out[d] = e.pubkey }
        out
    }
    fun group(id: String): Group? = groupById[id]

    /** Sub-groups announced in this channel or pod. */
    fun pods(parent: String): List<Group> = groups.filter { it.isPod && it.parent == parent }
    fun proposalsIn(parent: String): List<Proposal> = proposals.filter { group(it.groupId)?.parent == parent }

    // ---- admin rights --------------------------------------------------------------------------

    val isCommunityAdmin: Boolean get() = me in ctx.communityAdmins
    /** "Admin of the belfry" on someone's profile. */
    fun isBelfryAdmin(pubkey: String): Boolean = pubkey in ctx.communityAdmins
    /** May I administer this channel: its own admins, and the community's. Unknown channel → false. */
    fun canAdmin(g: Group?): Boolean = g != null && (me in g.admins || isCommunityAdmin)
    fun canAdmin(groupId: String): Boolean = canAdmin(group(groupId))

    // ---- messages ------------------------------------------------------------------------------

    /** The author's latest edit (kind 1010) per message id. Edits by anyone else are ignored. */
    private val edits: Map<String, Event> by lazy {
        val authors = HashMap<String, String>()
        for (e in all) authors[e.id] = e.pubkey
        val out = HashMap<String, Event>()
        for (e in ofKind(EDIT_KIND)) {
            if (e.id in deleted) continue
            val id = e.t("e") ?: continue
            if (authors[id] != e.pubkey) continue
            val had = out[id]
            if (had == null || had.createdAt < e.createdAt || (had.createdAt == e.createdAt && had.id < e.id)) out[id] = e
        }
        out
    }

    private fun parse(e: Event): Message {
        val x = edits[e.id]
        val src = x ?: e
        val media = ArrayList<Media>()
        for (t in src.tags) {
            if (t.isEmpty() || t[0] != "imeta") continue
            val f = HashMap<String, String>()
            for (p in t.drop(1)) {
                val i = p.indexOf(' ')
                if (i < 0) f[p.dropLast(1)] = p else f[p.substring(0, i)] = p.substring(i + 1)
            }
            val url = f["url"] ?: continue
            if (!url.startsWith("http:") && !url.startsWith("https:")) continue
            val dim = (f["dim"] ?: "").split("x")
            media.add(Media(url, f["m"] ?: "", f["alt"], dimOf(dim.getOrNull(0)), dimOf(dim.getOrNull(1))))
        }
        var text = src.content
        for (m in media) text = text.replace(m.url, "")
        val topics = LinkedHashSet<String>()
        for (t in src.tags) if (t.size >= 2 && t[0] == "t" && t[1].isNotEmpty()) topics.add(Topics.norm(t[1]))
        val shown = text.jsTrim()
        // events: a message carrying an event's URL (or its `a` tag) shows its cards under the text, at
        // most three; the older automatic post ("📅 …", the `a` tag only) is the card alone
        val links = EventLinks.linksIn(shown, ctx.domain)
        val legacy = links.isEmpty() && shown.startsWith("📅") && eventCoordOf(e) != null
        val refs = ArrayList<EventRef>()
        if (!legacy) {
            val seen = HashSet<String>()
            for (c in EventLinks.coordsOf(e)) {
                val d = c.split(":").getOrElse(2) { "" }
                if (!seen.add(d)) continue
                refs.add(if (calendar.any { it.coord == c }) EventRef(coord = c) else EventRef(link = d))
            }
            for (l in links) {
                val d = calendar.firstOrNull { it.d == l || it.slug == l }?.d ?: l
                if (!seen.add(d)) continue
                refs.add(EventRef(link = l))
            }
        }
        return Message(
            ev = e, text = shown, media = media, topics = topics.toList(), edited = x?.createdAt,
            special = specialOf(e, legacy), viaDropBox = e.hasTag("client", "belfry-drop"), eventRefs = refs.take(3),
        )
    }

    private fun specialOf(e: Event, legacyEvent: Boolean): Special {
        val key = ctx.groupsKey
        val mail = if (e.hasTag("client", "belfry-mail")) {
            val o = e.tags.firstOrNull { it.size >= 4 && it[0] == "mail-original" && it[1].startsWith("https://") && it[2].isNotEmpty() && it[3].isNotEmpty() }
            Special.MemberMail(e.t("subject") ?: "", o?.let { MailOriginal(it[1], it[2], it[3]) })
        } else null
        if (key != null && e.pubkey == key) {
            val from = e.t("mail-from")
            if (!from.isNullOrEmpty() && mail != null)
                return Special.InboxCard(from, e.t("mail-name") ?: "", mail.subject, e.t("message-id"), e.t("belfry-auth") == "pass", mail.original)
            if (e.hasTag("client", "belfry-mail-out"))
                return Special.SentAsChannel(e.t("mail-to") ?: "", e.t("subject") ?: "", e.t("sent-by") ?: "")
        }
        if (legacyEvent) eventCoordOf(e)?.let { return Special.EventCard(it) }
        podOf(e)?.let { return Special.PodCard(it) }
        proposalOf(e)?.let { return Special.ProposalCard(it) }
        return mail ?: Special.None
    }

    private val messagesCache = ConcurrentHashMap<String, List<Message>>()

    /** A channel's messages: kind 9, not deleted, oldest first, each as its author last edited it. */
    fun messages(groupId: String): List<Message> = messagesCache.getOrPut(groupId) {
        inGroupSorted(groupId).filter { it.kind == 9 && it.id !in deleted }.map(::parse)
    }

    fun message(groupId: String, id: String): Message? =
        messages(groupId).firstOrNull { it.id == id } ?: allReplies(groupId).firstOrNull { it.id == id }

    /** Your own plain message or reply can be edited (not a card, an e-mail, or one still in the outbox). */
    fun canEdit(m: Message): Boolean = m.pubkey == me && !isPending(m.id) && m.text.isNotEmpty() && m.special == Special.None
    fun canDelete(m: Message): Boolean = m.pubkey == me && !isPending(m.id)

    /** What the delete confirmation shows of the message (240 chars, mentions hidden). */
    fun deletePreview(m: Message): String {
        val t = m.text.ifEmpty { if (m.media.isEmpty()) "" else if (m.media.size == 1) "A photo or file" else "${m.media.size} photos or files" }
        return plainRefs(t).sliceUnits(240)
    }

    /** An announcement: tagged `t announcement` by one of the channel's admins. */
    fun isAnnouncement(m: Message, g: Group?): Boolean = Topics.ANNOUNCE in m.topics && g != null && m.pubkey in g.admins

    /** Every photo and file posted in the channel, newest first. */
    fun files(groupId: String): List<Media> = messages(groupId).flatMap { it.media }.reversed()

    // ---- threads (NIP-22 kind 1111, `E` = the message) -----------------------------------------

    private val repliesCache = ConcurrentHashMap<String, List<Message>>()
    private fun allReplies(groupId: String): List<Message> = repliesCache.getOrPut(groupId) {
        inGroupSorted(groupId).filter { it.kind == 1111 && it.id !in deleted }.map(::parse)
    }

    fun threadReplies(groupId: String, rootId: String): List<Message> = allReplies(groupId).filter { rootOf(it.ev) == rootId }

    /** The inline thread's window: the latest 3 replies and how many earlier ones are hidden. */
    fun inlineThread(groupId: String, rootId: String, showAll: Boolean = false): Pair<List<Message>, Int> {
        val replies = threadReplies(groupId, rootId)
        val shown = if (showAll) replies else replies.takeLast(3)
        return shown to (replies.size - shown.size)
    }

    private val threadsCache = ConcurrentHashMap<String, Map<String, ThreadInfo>>()

    /** Per message id: how many replies its thread has, the latest time, who wrote (in arrival order). */
    fun threadsOf(groupId: String): Map<String, ThreadInfo> = threadsCache.getOrPut(groupId) {
        val out = LinkedHashMap<String, ThreadInfo>()
        for (e in inGroup(groupId)) {
            if (e.kind != 1111 || e.id in deleted) continue
            val root = rootOf(e) ?: continue
            val t = out[root] ?: ThreadInfo(0, 0, emptyList())
            out[root] = ThreadInfo(t.count + 1, maxOf(t.last, e.createdAt), if (e.pubkey in t.who) t.who else t.who + e.pubkey)
        }
        out
    }

    // ---- the feed ------------------------------------------------------------------------------

    /**
     * A channel's feed: messages, guest joins and resurfaced threads, by time (at equal times
     * messages first). With a topic: only its messages (`announcement` = admins' announcements).
     */
    fun feed(groupId: String, topic: String? = null): List<FeedItem> {
        val g = group(groupId)
        val list = messages(groupId).filter { m -> topic == null || (if (topic == Topics.ANNOUNCE) isAnnouncement(m, g) else topic in m.topics) }
        val items = ArrayList<FeedItem>(list.size + 8)
        for (m in list) items.add(FeedItem.Msg(m))
        if (topic == null) {
            for (j in guestJoins(groupId)) items.add(FeedItem.Join(j))
            val byId = list.associateBy { it.id }
            for ((rootId, info) in threadsOf(groupId)) {
                val root = byId[rootId] ?: continue
                if (list.none { it.at > root.at && it.at < info.last }) continue
                val last = threadReplies(groupId, rootId).lastOrNull() ?: continue
                items.add(FeedItem.Activity(root, last, info.last))
            }
        }
        return items.withIndex().sortedWith(
            compareBy<IndexedValue<FeedItem>> { it.value.at }.thenBy { if (it.value is FeedItem.Msg) 0 else 1 }.thenBy { it.index },
        ).map { it.value }
    }

    /**
     * The feed with layout hints: a day separator before the first item and at every change of
     * local day; a message starts a block after a day change, a different author, more than
     * 300 s, a non-message item, an unfolded thread ([openThreads]: root ids), or when it is an
     * announcement, an e-mail or a card.
     */
    fun feedRows(groupId: String, topic: String? = null, openThreads: Set<String> = emptySet()): List<FeedRow> {
        val g = group(groupId)
        val items = feed(groupId, topic)
        val out = ArrayList<FeedRow>(items.size)
        var prev: FeedItem? = null
        for (item in items) {
            val before = prev
            val newDay = before == null || dayOf(before.at) != dayOf(item.at)
            if (item is FeedItem.Msg) {
                val p = (before as? FeedItem.Msg)?.m?.takeIf { x -> x.id !in openThreads }
                val announce = isAnnouncement(item.m, g)
                val first = newDay || p == null || p.pubkey != item.m.pubkey || item.m.at - p.at > 300 ||
                    announce || item.m.special != Special.None
                out.add(FeedRow(item, newDay, first, announce))
            } else {
                out.add(FeedRow(item, newDay, false))
            }
            prev = item
        }
        return out
    }

    /** The local calendar day of a time (ISO date), in the context's zone. */
    fun dayOf(at: Long): LocalDate = Instant.ofEpochSecond(at).atZone(ctx.zone).toLocalDate()

    /** A resurfaced thread's first line: the root's text (80 chars; "a photo" when it has none), mentions hidden, channel references named. */
    fun activitySnippet(root: Message): String {
        val base = root.text.ifEmpty { if (root.media.isNotEmpty()) "a photo" else "" }
        return plainRefs(base).sliceUnits(80) + if (root.text.length > 80) "…" else ""
    }
    fun activityReply(last: Message): String = plainRefs(last.text.ifEmpty { "a photo" }).sliceUnits(140)

    /** Topics a message carries that its text doesn't show (sent inside an open topic), except the open one. */
    fun extraTopics(m: Message, openTopic: String? = null): List<String> {
        val inText = Topics.hashtags(m.text).toSet()
        return m.topics.filter { it !in inText && it != openTopic && it != Topics.ANNOUNCE }
    }

    // ---- reactions (NIP-25) --------------------------------------------------------------------

    private val reactionsCache = ConcurrentHashMap<String, Map<String, List<Reaction>>>()

    /** Per message id: its reactions, most given first; `mine` is my reaction event (to undo it). */
    fun reactions(groupId: String): Map<String, List<Reaction>> = reactionsCache.getOrPut(groupId) {
        class R(val emoji: String) { val who = LinkedHashSet<String>(); var mine: Event? = null }
        val byMsg = LinkedHashMap<String, LinkedHashMap<String, R>>()
        for (e in inGroupSorted(groupId)) {
            if (e.kind != 7 || e.id in deleted) continue
            val target = e.tags.lastOrNull { it.isNotEmpty() && it[0] == "e" }?.getOrNull(1)
            val c = e.content.ifEmpty { "+" }.jsTrim()
            val emoji = if (c == "+") "👍" else c
            if (target.isNullOrEmpty() || emoji.isEmpty() || emoji.length > 16) continue
            val r = byMsg.getOrPut(target) { LinkedHashMap() }.getOrPut(emoji) { R(emoji) }
            r.who.add(e.pubkey)
            if (e.pubkey == me) r.mine = e
        }
        byMsg.mapValues { (_, m) -> m.values.sortedByDescending { it.who.size }.map { Reaction(it.emoji, it.who, it.mine) } }
    }

    fun reactionsOf(groupId: String, messageId: String): List<Reaction> = reactions(groupId)[messageId] ?: emptyList()

    // ---- guests --------------------------------------------------------------------------------

    private val joinsCache = ConcurrentHashMap<String, List<GuestJoin>>()

    /** Who joined a channel as its guest, oldest first (SPEC 13 and 19). */
    fun guestJoins(groupId: String): List<GuestJoin> = joinsCache.getOrPut(groupId) {
        val list = inGroup(groupId)
        val saved = HashSet<String?>()
        val member = HashSet<String?>()
        for (e in list) {
            if (e.kind != 1985 || e.t("L") != GUEST_NS) continue
            if (e.tags.any { it.size >= 2 && it[0] == "l" && it[1] == "saved" }) saved.add(e.t("p"))
            if (e.tags.any { it.size >= 2 && it[0] == "l" && it[1] == "member" }) member.add(e.t("p"))
        }
        val out = ArrayList<GuestJoin>()
        val gone = HashMap<String, Long>()
        val wrote = HashSet<String>()
        for (e in list) {
            if (e.kind == 9001) { val p = e.t("p"); if (!p.isNullOrEmpty()) gone[p] = maxOf(gone[p] ?: 0, e.createdAt) }
            else if (e.kind == 9 || e.kind == 1111) wrote.add(e.pubkey)
            if (e.kind != 9000 || e.t("invited-by").isNullOrEmpty()) continue
            val p = e.t("p")
            if (p.isNullOrEmpty()) continue
            // the register (whois) knows who became a member; the labels are the fallback
            val isMember = when (ctx.kinds[p]) { "member", "agent" -> true; "guest" -> false; else -> p in member }
            out.add(GuestJoin(
                id = e.id, pubkey = p, by = e.t("invited-by"), at = e.createdAt, agent = e.t("agent") == "1",
                unsaved = e.t("unsaved") == "1" && p !in saved && !isMember, member = isMember,
            ))
        }
        // taken out again without ever writing here (a cleaned-up double sign-up): no line
        out.filter { it.pubkey in wrote || (gone[it.pubkey] ?: 0) < it.at }.sortedBy { it.at }
    }

    /** Everyone brought in by a guest link or as a member's agent (9000 with `invited-by`, belfry.json
     *  `agents`): the keys to ask the register about (`POST /whois`). */
    val guestJoinKeys: Set<String> by lazy {
        val out = LinkedHashSet<String>()
        for (e in ofKind(9000)) if (!e.t("invited-by").isNullOrEmpty()) e.t("p")?.takeIf { it.isNotEmpty() }?.let(out::add)
        out.addAll(ctx.agents.keys)
        out
    }

    /** Someone's guest joins across all channels, members-since left out. Empty for a member. */
    fun guestJoinsOf(pubkey: String): List<GuestJoin> = allGuestJoins.filter { it.pubkey == pubkey }
    private val allGuestJoins: List<GuestJoin> by lazy { groups.flatMap { guestJoins(it.id) }.filter { !it.member } }

    /** Am I (still) a guest here, and not a member. */
    val amGuest: Boolean by lazy { allGuestJoins.any { it.pubkey == me } }

    /** Why this person can't be made an admin of the belfry (fixed text), or null when they can. */
    fun noAdminReason(pubkey: String): String? {
        val joins = guestJoinsOf(pubkey)
        return when {
            joins.any { it.agent } -> "An agent cannot be an admin."
            joins.isNotEmpty() && joins.all { it.unsaved } -> "This guest has no e-mail yet. Once they save their account, you can make them an admin."
            else -> null
        }
    }

    /** The channels someone is in (not proposals), for their profile. */
    fun sharedGroups(pubkey: String): List<Group> = groups.filter { pubkey in it.members && !it.isProposal }

    // ---- who sees a channel --------------------------------------------------------------------

    /** The people who opened or wrote in this channel in the last 30 days, most recent first. */
    fun recentlyActive(groupId: String, now: Long): List<Active> {
        val since = now - ACTIVE_DAYS * 86400L
        val g = group(groupId)
        val last = LinkedHashMap<String, Long>()
        val seen = seenD(groupId)
        for (e in inGroup(groupId)) {
            if (e.createdAt < since) continue
            val isSeen = e.kind == 30078 && e.t("d") == seen
            if (!isSeen && e.kind != 9 && e.kind != 1111 && e.kind != 7) continue
            if ((last[e.pubkey] ?: 0) < e.createdAt) last[e.pubkey] = e.createdAt
        }
        val known = if (g != null && g.members.isNotEmpty()) g.members + g.admins else null
        return last.entries.filter { known == null || it.key in known }.map { Active(it.key, it.value) }.sortedByDescending { it.at }
    }

    /** "N active members this month" under a channel's name. */
    fun activeLine(groupId: String, now: Long): String {
        val n = recentlyActive(groupId, now).size
        return if (n == 0) "Nobody active this month yet" else "$n active ${if (n == 1) "member" else "members"} this month"
    }

    /** When I last told this channel I opened it (0: never). */
    fun mySeen(groupId: String): Long {
        val d = seenD(groupId)
        var at = 0L
        for (e in inGroup(groupId)) if (e.kind == 30078 && e.pubkey == me && e.t("d") == d) at = maxOf(at, e.createdAt)
        return at
    }

    /** A closed pod I'm not in is not marked as seen. */
    fun maySee(groupId: String): Boolean {
        val g = group(groupId) ?: return false
        return !(g.isPod && g.closed && me !in g.members)
    }

    // ---- topics --------------------------------------------------------------------------------

    /** A channel's pinned topics: the `l` tags of the latest kind 1985 with namespace `#t`. */
    fun pinnedTopics(groupId: String): List<String> {
        val latest = inGroupSorted(groupId).lastOrNull { it.kind == 1985 && it.t("L") == Topics.NS } ?: return emptyList()
        return latest.tags.filter { it.size >= 3 && it[0] == "l" && it[2] == Topics.NS }.map { Topics.norm(it[1]) }
    }

    /** The topics line under a channel's name: pinned ones (the open one first when not pinned). */
    fun topicsBar(groupId: String, active: String? = null): List<String> {
        val list = pinnedTopics(groupId).filter { it != Topics.ANNOUNCE }
        return if (active != null && active != Topics.ANNOUNCE && active !in list) listOf(active) + list else list
    }

    fun hasAnnouncements(groupId: String): Boolean {
        val g = group(groupId)
        return messages(groupId).any { isAnnouncement(it, g) }
    }

    // ---- drop box links ------------------------------------------------------------------------

    /** Live drop box links of a channel (kind 30078, d = drop:<token>, NIP-40 expiration), newest first. */
    fun dropLinks(groupId: String, now: Long): List<DropLink> {
        val out = ArrayList<DropLink>()
        for (e in inGroup(groupId)) {
            val d = e.t("d") ?: ""
            if (e.kind != 30078 || !d.startsWith("drop:")) continue
            if (e.id in deleted || "30078:${e.pubkey}:$d" in deleted) continue
            val expires = e.t("expiration")?.toDoubleOrNull()?.toLong() ?: 0
            if (expires <= now) continue
            out.add(DropLink(d.substring(5), groupId, expires, e.tags.any { it.firstOrNull() == "login" }, e.pubkey, e))
        }
        return out.sortedByDescending { it.ev.createdAt }
    }

    fun dropUrl(token: String): String = "https://${ctx.domain}/drop/$token"

    // ---- shared inbox --------------------------------------------------------------------------

    /** Is the channel's shared inbox on: the latest `belfry:inbox` setting by one of its admins. */
    fun isInbox(g: Group?): Boolean {
        if (g == null) return false
        val own = inGroupSorted(g.id).lastOrNull { it.kind == 30078 && it.t("d") == INBOX_D && it.pubkey in g.admins } ?: return false
        val on = parseJsonOrNull(own.content.ifEmpty { "{}" }).obj()?.get("on") ?: return false
        return truthy(on)
    }

    // ---- a channel's page on the web, its visibility, its type of post -----------------------
    // (web model.ts webPage; Belfry webthreads.py, 2026-11-11.) The latest NIP-78 setting
    // `belfry:web:<channel>` by one of its admins or by the relay's own key (the default
    // channels). "visibility": public | members; "posts": proposal | event | request.

    data class WebPage(val on: Boolean, val slug: String, val visibility: String, val posts: String?)

    fun webPage(g: Group?): WebPage? {
        if (g == null) return null
        val relayKey = all.firstOrNull { it.kind == 39000 && it.t("d") == g.id }?.pubkey
        val own = all.filter { it.kind == 30078 && it.t("d") == WEB_D + g.id && it.t("h") == g.id && (it.pubkey in g.admins || it.pubkey == relayKey) }
            .maxByOrNull { it.createdAt } ?: return null
        val c = parseJsonOrNull(own.content.ifEmpty { "{}" }).obj() ?: return null
        val vis = c["visibility"]?.str()
        val members = vis == "members" || (vis == null && c["list"]?.str() == "own")
        val posts = c["posts"]?.str()?.takeIf { it in setOf("proposal", "event", "request") }
        return WebPage(c["on"]?.let { truthy(it) } == true, c["slug"]?.str().orEmpty(), if (members) "members" else "public", posts)
    }

    /** A public channel: its page on the web is on and anyone may read it (a globe, not a hash). */
    fun isPublicChannel(g: Group?): Boolean = webPage(g)?.let { it.on && it.visibility == "public" } == true

    /** The channel's e-mail address. */
    fun mailAddress(groupId: String): String = "$groupId@${ctx.domain}"

    /** Per e-mail card id: open / answered / closed, who has it, how many answers went out. */
    fun inboxStates(groupId: String): Map<String, CardState> {
        val out = HashMap<String, CardState>()
        val sender = ctx.groupsKey
        for (e in inGroupSorted(groupId)) {
            if (e.id in deleted) continue
            val card = e.t("e")
            if (card.isNullOrEmpty()) continue
            val c = out[card] ?: CardState()
            if (e.kind == 1985 && e.tags.any { it.size >= 2 && it[0] == "L" && it[1] == INBOX_NS }) {
                when (e.tags.firstOrNull { it.size >= 3 && it[0] == "l" && it[2] == INBOX_NS }?.get(1)) {
                    "open" -> out[card] = c.copy(status = CardStatus.OPEN)
                    "closed" -> out[card] = c.copy(status = CardStatus.CLOSED)
                    "assigned" -> out[card] = c.copy(assignee = e.t("p")?.takeIf { it.isNotEmpty() })
                    else -> out[card] = c
                }
            } else if (e.kind == 9 && sender != null && e.pubkey == sender && e.hasTag("client", "belfry-mail-out")) {
                out[card] = c.copy(answered = c.answered + 1, status = if (c.status == CardStatus.OPEN) CardStatus.WAITING else c.status)
            }
        }
        return out
    }

    // ---- member labels and the Members tab -----------------------------------------------------

    /** NIP-32 labels per member in a channel (one kind 1985 per member, latest wins). */
    fun memberLabels(groupId: String): Map<String, List<String>> {
        val latest = LinkedHashMap<String, Event>()
        for (e in inGroupSorted(groupId)) {
            if (e.kind != 1985 || e.t("L") != LABEL_NS) continue
            val p = e.t("p")
            if (!p.isNullOrEmpty()) latest[p] = e
        }
        return latest.mapValues { (_, e) -> e.tags.filter { it.size >= 3 && it[0] == "l" && it[2] == LABEL_NS }.map { it[1] } }
    }

    /** Everyone to list in a channel's Members tab. */
    fun memberRows(groupId: String): List<MemberRow> {
        val g = group(groupId)
        val labels = memberLabels(groupId)
        val keys = LinkedHashSet<String>()
        g?.members?.let(keys::addAll)
        keys.addAll(labels.keys)
        if (g?.isPod != true) keys.add(me)
        val invited = guestJoins(groupId).filter { !it.member }.associateBy { it.pubkey }
        return keys.filter { it.isNotEmpty() }.map {
            MemberRow(it, displayName(it), extras[it]?.place, invited[it], labels[it] ?: emptyList(), g != null && it in g.admins)
        }
    }

    // ---- task list (tasklist.sh protocol, kinds 2100-2103) -------------------------------------

    fun tasks(groupId: String): List<Task> {
        val listId = listIdFor(groupId)
        class T(val id: String, val title: String, val author: String, val pending: Boolean, val source: String?) {
            var assignee: String? = null; var done = false; val comments = ArrayList<TaskComment>()
        }
        val tasks = LinkedHashMap<String, T>()
        for (e in sorted) {
            if (e.kind !in 2100..2103 || e.t("t") != listId) continue
            if (e.kind == 2100) {
                val title = e.content.jsTrim().sliceUnits(300)
                if (title.isNotEmpty()) tasks[e.id] = T(e.id, title, e.pubkey, isPending(e.id), e.t("q"))
            } else if (e.kind != 2102) {
                val t = tasks[e.t("e") ?: ""] ?: continue
                if (e.kind == 2103) t.comments.add(TaskComment(e.pubkey, e.content.sliceUnits(2000), e.createdAt))
                when (e.t("action")) {
                    "claim" -> t.assignee = e.pubkey
                    "unclaim" -> if (t.assignee == e.pubkey) t.assignee = null
                    "done" -> t.done = true
                    "undone" -> t.done = false
                    "delete" -> if (e.pubkey == t.author) tasks.remove(t.id)
                }
            }
        }
        return tasks.values.map { Task(it.id, it.title, it.author, it.assignee, it.done, it.pending, it.comments, it.source) }
    }

    // ---- calendar (NIP-52) ---------------------------------------------------------------------

    /**
     * Every calendar event, by start time. Belfry events (`d` = <year>-<6>, group ev-<d>): among the
     * versions with that d signed by a host (the group's creator and admins), the newest counts and
     * the coordinate stays the creator's (rule B). Older events (session-<x> ↔ ws-<x>): one author.
     */
    val calendar: List<CalEvent> by lazy {
        val gs = groups.associateBy { it.id }
        val creators = HashMap<String, String>() // group id → its creator (first 9007)
        for (e in ofKind(9007).sortedWith(BY_TIME)) { val h = e.t("h") ?: continue; if (h !in creators) creators[h] = e.pubkey }
        val byKey = LinkedHashMap<String, MutableList<Event>>()
        for (e in all) {
            if (e.kind != 31922 && e.kind != 31923) continue
            val d = e.t("d")
            if (d.isNullOrEmpty()) continue
            val key = if (EventLinks.D.matches(d)) "d:$d" else "${e.kind}:${e.pubkey}:$d"
            byKey.getOrPut(key) { ArrayList() }.add(e)
        }
        val rsvps = HashMap<String, LinkedHashMap<String, Pair<Rsvp, Event>>>()
        for (e in ofKind(31925)) {
            val a = e.t("a") ?: continue
            val s = Rsvp.of(e.t("status")) ?: continue
            val m = rsvps.getOrPut(a) { LinkedHashMap() }
            val prev = m[e.pubkey]
            if (prev == null || prev.second.createdAt <= e.createdAt) m[e.pubkey] = s to e
        }
        val statusLabels = HashMap<String, MutableList<Event>>()
        for (e in ofKind(1985)) if (e.t("L") == EventLinks.STATUS_NS) { val h = e.t("h") ?: continue; statusLabels.getOrPut(h) { ArrayList() }.add(e) }
        val out = ArrayList<CalEvent>()
        for ((key, versions) in byKey) {
            val sorted = versions.sortedWith(BY_TIME)
            var e = sorted.last()
            var creator = e.pubkey
            var hosts: Set<String> = setOf(e.pubkey)
            val d = e.t("d")!!
            val isNew = key.startsWith("d:")
            val groupId = if (isNew) "ev-$d" else e.t("h").orEmpty().ifEmpty { "ws-" + d.removePrefix("session-") }
            var hostVersions = sorted
            if (isNew) {
                creator = creators[groupId] ?: sorted.first().pubkey
                hosts = setOf(creator) + (gs[groupId]?.admins ?: emptySet())
                hostVersions = sorted.filter { it.pubkey in hosts }
                if (hostVersions.isEmpty()) continue
                e = hostVersions.last()
            }
            val kind = if (isNew) sorted.firstOrNull { it.pubkey == creator }?.kind ?: e.kind else e.kind
            val coord = "$kind:$creator:$d"
            if (coord in deleted) continue
            val allDay = e.kind == 31922
            val start = calTime(e.t("start"), allDay) ?: continue
            var status = if (e.t("status") == "cancelled") EventStatus.CANCELLED else EventStatus.SCHEDULED
            val labels = (statusLabels[groupId] ?: emptyList()).filter { it.pubkey in hosts }.sortedWith(BY_TIME)
            val last = labels.lastOrNull()
            if (last != null && (last.createdAt >= e.createdAt || status == EventStatus.SCHEDULED))
                status = if (last.tags.any { it.size >= 2 && it[0] == "l" && it[1] == "cancelled" }) EventStatus.CANCELLED else EventStatus.SCHEDULED
            val reason = if (status == EventStatus.CANCELLED) labels.lastOrNull { l -> l.tags.any { it.size >= 2 && it[0] == "l" && it[1] == "cancelled" } }?.content?.ifEmpty { null } else null
            val m = rsvps[coord] ?: LinkedHashMap()
            val answers = m.mapValues { it.value.first }
            val base = CalEvent(
                coord = coord, d = d, ev = e,
                title = e.t("title").orEmpty().ifEmpty { e.t("name").orEmpty().ifEmpty { "Untitled" } },
                description = e.content,
                location = e.t("location"),
                allDay = allDay, start = start, end = calTime(e.t("end"), allDay),
                groupId = groupId,
                links = e.tags.filter { it.size >= 2 && it[0] == "r" && it[1].startsWith("https://") }.map { it[1] },
                channels = emptyList(),
                rsvps = answers,
                rsvpEvents = m.mapValues { it.value.second },
                tz = e.t("start_tzid"), isNew = isNew, creator = creator, hosts = hosts,
                cover = e.t("image")?.ifEmpty { null },
                visibility = Visibility.of(e.t("visibility")),
                slug = e.t("slug")?.ifEmpty { null },
                status = status, statusReason = reason, versions = hostVersions,
            )
            if (isNew) {
                val going = goingList(base, EventLinks.people(inGroupSorted(groupId)))
                out.add(base.copy(goingKeys = going, goingCount = going.size))
            } else out.add(base)
        }
        // the channels showing each event's card: messages with its `a` tag or its URL, not deleted
        val byLink = HashMap<String, Int>()
        out.forEachIndexed { i, x -> byLink[x.d] = i; x.slug?.let { byLink[it] = i } }
        val byCoord = HashMap<String, Int>()
        out.forEachIndexed { i, x -> byCoord[x.coord] = i }
        val channels = HashMap<Int, LinkedHashSet<String>>()
        for (e in sorted) {
            if (e.kind != 9 || e.id in deleted) continue
            val h = e.t("h") ?: continue
            val found = LinkedHashSet<Int>()
            for (c in EventLinks.coordsOf(e)) byCoord[c]?.let(found::add)
            for (l in EventLinks.linksIn(e.content, ctx.domain)) byLink[l]?.let(found::add)
            for (i in found) if (out[i].groupId != h) channels.getOrPut(i) { LinkedHashSet() }.add(h)
        }
        out.mapIndexed { i, x -> channels[i]?.let { x.copy(channels = it.toList()) } ?: x }.sortedBy { it.start }
    }

    private fun calTime(v: String?, allDay: Boolean): Long? {
        if (v.isNullOrEmpty()) return null
        return if (allDay) {
            try { LocalDate.parse(v).atStartOfDay(ctx.zone).toEpochSecond() } catch (e: Exception) { null }
        } else v.trim().toDoubleOrNull()?.takeIf { it.isFinite() }?.toLong()
    }

    fun calEvent(coord: String): CalEvent? = calendar.firstOrNull { it.coord == coord }
    /** The event whose chat this group is. */
    fun calEventFor(groupId: String): CalEvent? = calendar.firstOrNull { it.groupId == groupId }

    /** Upcoming events (today onwards), of one channel or of all. */
    fun upcoming(channel: String? = null, now: Long): List<CalEvent> {
        val today = dayOf(now).atStartOfDay(ctx.zone).toEpochSecond()
        return calendar.filter { (it.end ?: it.start) >= today && (channel == null || channel in it.channels) }
    }

    /** The bar under a channel's name: its next event and how many more. */
    fun nextEvent(channel: String, now: Long): Pair<CalEvent, Int>? {
        val list = upcoming(channel, now)
        return list.firstOrNull()?.let { it to list.size - 1 }
    }

    fun myRsvp(e: CalEvent): Rsvp? = e.rsvps[me]

    /** Places already used for events: this channel's first (most recent first), then elsewhere. */
    fun eventPlaces(channel: String): List<String> {
        val recent = calendar.sortedByDescending { it.ev.createdAt }
        val out = LinkedHashSet<String>()
        for (e in recent) if (channel in e.channels && !e.location.isNullOrEmpty()) out.add(e.location)
        for (e in recent) if (!e.location.isNullOrEmpty()) out.add(e.location)
        return out.toList()
    }

    // ---- proposals -----------------------------------------------------------------------------

    val proposals: List<Proposal> by lazy {
        val latest = LinkedHashMap<String, Event>()
        for (e in ofKind(30023)) {
            if (!e.hasTag("t", "proposal")) continue
            val coord = "30023:${e.pubkey}:${e.t("d")}"
            val prev = latest[coord]
            if (prev == null || BY_TIME.compare(prev, e) < 0) latest[coord] = e
        }
        val out = ArrayList<Proposal>()
        for ((coord, e) in latest) {
            if (coord in deleted) continue
            val groupId = e.t("h") ?: ""
            val g = group(groupId)
            val parent = g?.parent?.let { group(it) }
            fun may(pk: String) = pk == e.pubkey || (g != null && pk in g.admins) || (parent != null && pk in parent.admins)
            var status = ProposalStatus.of(e.t("status")) ?: ProposalStatus.DRAFT
            var statusAt = 0L
            var pollEv: Event? = null
            for (x in inGroup(groupId)) {
                if (x.kind == 1985 && x.t("L") == PROPOSAL_NS && may(x.pubkey) && x.createdAt >= statusAt) {
                    val l = ProposalStatus.of(x.tags.firstOrNull { it.size >= 3 && it[0] == "l" && it[2] == PROPOSAL_NS }?.get(1))
                    if (l != null) { status = l; statusAt = x.createdAt }
                }
                if (x.kind == 1068 && x.t("a") == coord && (pollEv == null || BY_TIME.compare(pollEv, x) < 0)) pollEv = x
            }
            val poll = pollEv?.let { p ->
                val votes = LinkedHashMap<String, String>()
                val at = HashMap<String, Long>()
                for (x in ofKind(1018)) {
                    if (x.t("e") != p.id) continue
                    if ((at[x.pubkey] ?: -1) > x.createdAt) continue
                    val r = x.t("response")
                    if (!r.isNullOrEmpty()) { votes[x.pubkey] = r; at[x.pubkey] = x.createdAt }
                }
                Poll(p, p.t("version")?.toIntOrNull()?.takeIf { it != 0 } ?: 1, votes)
            }
            out.add(Proposal(
                coord = coord, d = e.t("d") ?: "", ev = e, author = e.pubkey, groupId = groupId,
                title = e.t("title").orEmpty().ifEmpty { "Untitled proposal" }, summary = e.t("summary") ?: "", text = e.content,
                version = e.t("version")?.toIntOrNull()?.takeIf { it != 0 } ?: 1, status = status, poll = poll,
            ))
        }
        out
    }

    /** The proposal whose chat this group is. */
    fun proposalFor(groupId: String): Proposal? = proposals.firstOrNull { it.groupId == groupId }

    /** Who may change a proposal's status: its author and the admins of its chat or its parent. */
    fun canDecide(p: Proposal): Boolean {
        val g = group(p.groupId)
        val parent = g?.parent?.let { group(it) }
        return me == p.author || (g != null && me in g.admins) || (parent != null && me in parent.admins)
    }

    fun myVote(p: Proposal): String? = p.poll?.votes?.get(me)

    // ---- people: tags and places (kind 30078, d = belfry:profile) ------------------------------

    /** Every member's tags and place, newest event per member. */
    val extras: Map<String, Extras> by lazy {
        val out = HashMap<String, Extras>()
        for (e in ofKind(30078)) {
            if (!e.hasTag("d", PROFILE_D)) continue
            val prev = out[e.pubkey]
            if (prev != null && prev.at >= e.createdAt) continue
            People.parseExtras(e.content, e.createdAt)?.let { out[e.pubkey] = it }
        }
        out
    }

    /** Tags any member uses, most used first: the suggestions when you add yours. */
    val allTags: List<TagCount> by lazy {
        val n = LinkedHashMap<String, Int>()
        for (x in extras.values) for (t in x.tags) n[t] = (n[t] ?: 0) + 1
        n.map { TagCount(it.key, it.value) }.sortedWith(compareByDescending<TagCount> { it.count }.thenBy { it.tag })
    }

    /** My latest kind 0 content as JSON (to keep its other fields when saving), or an empty object. */
    val myProfileJson: JsonObject by lazy {
        val mine = ofKind(0).filter { it.pubkey == me }.maxByOrNull { it.createdAt }
        mine?.let { parseJsonOrNull(it.content).obj() } ?: JsonObject(emptyMap())
    }

    /** People who can be mentioned or messaged: (pubkey, name) of everyone with a name, me left out. */
    val people: List<Pair<String, String>> by lazy {
        profiles.entries.filter { it.key != me && !it.value.name.isNullOrEmpty() }.map { it.key to it.value.name!! }
    }

    // ---- search (SPEC 14) ----------------------------------------------------------------------

    /** Every word must appear (accents and case folded) in the text, the author's name or a file name. */
    fun search(query: String, channel: String? = null, limit: Int = 200): List<SearchHit> {
        val words = fold(query).split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val scope = if (channel == null) groups.map { it.id } else listOf(channel)
        val out = ArrayList<SearchHit>()
        for (gid in scope) for (m in messages(gid)) {
            val hay = fold("${m.text} ${displayName(m.pubkey)} ${m.media.joinToString(" ") { it.name ?: "" }}")
            if (words.all { hay.contains(it) }) out.add(SearchHit(m, gid))
        }
        return out.sortedByDescending { it.m.at }.take(limit)
    }

    companion object {
        val BY_TIME: Comparator<Event> = compareBy<Event> { it.createdAt }.thenBy { it.id }

        const val EDIT_KIND = 1010
        const val ACTIVE_DAYS = 30
        const val INBOX_D = "belfry:inbox"
        const val WEB_D = "belfry:web:"
        const val INBOX_NS = "belfry/inbox"
        const val PROPOSAL_NS = "belfry/proposal-status"
        const val GUEST_NS = "belfry/guest"
        const val PROFILE_D = "belfry:profile"
        /** Member labels keep the web chat's namespace so both clients read each other's. */
        const val LABEL_NS = "osv/member-label"
        private val META = listOf("name", "about", "picture")

        fun seenD(groupId: String): String = "seen:$groupId"
        fun isPodId(id: String): Boolean = id.startsWith("pod-")
        fun isProposalId(id: String): Boolean = id.startsWith("prop-")
        fun typeOfId(id: String): ChannelType = when {
            id.startsWith("ws-") || id.startsWith("ev-") -> ChannelType.EVENT
            isPodId(id) -> ChannelType.POD
            isProposalId(id) -> ChannelType.PROPOSAL
            else -> ChannelType.CHANNEL
        }

        /** The pod a chat post announces (its `pod` tag), if any. */
        fun podOf(e: Event): String? = e.t("pod")?.takeIf { isPodId(it) }
        fun proposalOf(e: Event): String? = e.t("proposal")?.takeIf { isProposalId(it) }
        /** The calendar event a chat post links to (its `a` tag), if any. */
        fun eventCoordOf(e: Event): String? = e.t("a")?.takeIf { val k = it.substringBefore(':'); k == "31922" || k == "31923" }
        /** The message a thread reply hangs off (its `E` tag). */
        fun rootOf(e: Event): String? = e.t("E")
        /** The task list id of a channel (tasklist.sh; same derivation as the web chat). */
        fun listIdFor(groupId: String): String = sha256Hex("osv-village:$groupId").substring(0, 16)

        /** First 12 characters of the npub + "…". */
        fun shortKey(pubkey: String): String = try { Bech32.npub(pubkey).take(12) + "…" } catch (e: Exception) { pubkey.take(12) + "…" }

        private fun dimOf(s: String?): Int? = s?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() && it != 0.0 }?.toInt()

        /** JavaScript truthiness of a JSON value. */
        internal fun truthy(v: kotlinx.serialization.json.JsonElement): Boolean = when (v) {
            is JsonPrimitive -> if (v.isString) v.content.isNotEmpty() else (v.booleanOrNull ?: ((v.doubleOrNull ?: 0.0) != 0.0))
            else -> v.obj() != null || v.arr() != null
        }
    }
}

/** The first tag named [k]'s value, like the web chat's `tag(e, k)` (null when the tag has no value). */
internal fun Event.t(k: String): String? = tags.firstOrNull { it.isNotEmpty() && it[0] == k }?.getOrNull(1)
