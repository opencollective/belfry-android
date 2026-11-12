package sh.belfry.core.model

import sh.belfry.core.outbox.Target
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

// Pinned messages, to-dos made from messages, pod shapes, and the ONE line under a channel's
// name with what it summarises (the web's model.pinned / podSides and ui/ChannelLine.tsx).
// Extensions on World: nothing here is cached, the inputs are.

/** NIP-32 namespace of pinned messages (SPEC 19). */
const val PIN_NS = "belfry/pin"

/** Who may pin in a channel: its admins and the community's. */
fun World.mayPin(g: Group?, pubkey: String): Boolean = g != null && (pubkey in g.admins || isBelfryAdmin(pubkey))

/** May I pin here? */
fun World.canPin(groupId: String): Boolean = mayPin(group(groupId), me)

/**
 * A channel's pinned messages, most recently pinned first: kind 1985 in the group with
 * `L belfry/pin`, `l pinned|unpinned`, `e` = the message; only labels by a channel or community
 * admin count and the latest label per message wins.
 */
fun World.pinned(groupId: String): List<Message> {
    val g = group(groupId)
    val state = HashMap<String, Pair<Boolean, Long>>()
    for (e in sorted) {
        if (e.kind != 1985 || e.tag("h") != groupId || e.tag("L") != PIN_NS || !mayPin(g, e.pubkey)) continue
        val id = e.tag("e") ?: continue
        val l = e.tag("l")
        if (l != "pinned" && l != "unpinned") continue
        val had = state[id]
        if (had == null || had.second <= e.createdAt) state[id] = (l == "pinned") to e.createdAt
    }
    if (state.isEmpty()) return emptyList()
    return messages(groupId).filter { state[it.id]?.first == true }.sortedByDescending { state[it.id]!!.second }
}

fun World.isPinned(groupId: String, messageId: String): Boolean = pinned(groupId).any { it.id == messageId }

/** Pin a message as the channel's notice, or unpin it. */
fun Actions.pinMessage(groupId: String, messageId: String, pin: Boolean): Draft =
    Draft(Target.GROUPS, 1985, "", listOf(listOf("h", groupId), listOf("L", PIN_NS), listOf("l", if (pin) "pinned" else "unpinned", PIN_NS), listOf("e", messageId)))

/** A pod's mark: a regular polygon with one side per member, 3 at least; 13 and more draw a circle. */
object PodShapes {
    fun sides(members: Int): Int = members.coerceIn(3, 12)
    fun isCircle(members: Int): Boolean = members > 12
}

/** The to-dos made from messages ("Turn into a to-do"), by the message's id. */
fun World.todosBySource(groupId: String): Map<String, Task> =
    tasks(groupId).filter { it.source != null }.associateBy { it.source!! }

/** The title a message gives its to-do: its text without mentions, channels as "#name", on one line, 300 chars. */
fun World.todoTitle(m: Message): String =
    plainRefs(m.text, "").replace(Regex("\\s+"), " ").trim().sliceUnits(300)

/** What a to-do's mark on its message reads. */
fun World.todoChip(t: Task): String = when {
    t.done -> "Done"
    t.assignee != null -> "To do · " + displayName(t.assignee!!)
    else -> "To do"
}

/** Who is on a to-do, as the pull-down says it; with NOW, also whether they are working on it right now and for how long. */
fun World.todoWho(t: Task, now: Long = 0): String {
    val a = t.assignee ?: return "Nobody on it yet"
    val busy = now > 0 && t.isWorking(now)
    val time = if (busy) " · " + Task.stretch(now - (t.since ?: now)) else ""
    return if (a == me) (if (busy) "You are working on it$time" else "You are on it")
    else displayName(a) + (if (busy) " is working on it$time" else " is on it")
}

/** Everything the line and its pull-down show about a channel. */
class ChannelFacts(
    val group: Group?,
    val pods: List<Group>,
    /** Open proposals of this channel (draft, feedback, voting). */
    val proposals: List<Proposal>,
    val events: List<CalEvent>,
    val pins: List<Message>,
    /** To-dos not done yet. */
    val todos: List<Task>,
    /** All of the channel's to-dos and how many are done (the line's "done/all"). */
    val todosAll: Int = todos.size,
    val todosDone: Int = 0,
    val active: List<Active>,
    /** The proposal this channel is the chat of. */
    val own: Proposal?,
    /** Its members (web 2026-11-12: the line counts them, the pull-down shows their faces). */
    val members: List<String> = emptyList(),
    /** Every picture posted here, newest first, each address once; and how many other files (videos left out). */
    val photos: List<Media> = emptyList(),
    val files: Int = 0,
)

private val OPEN_STATUS = setOf(ProposalStatus.DRAFT, ProposalStatus.FEEDBACK, ProposalStatus.VOTING)

fun World.needsMyVote(p: Proposal): Boolean = p.votingNow && p.poll?.votes?.get(me) == null

fun World.channelFacts(groupId: String, now: Long): ChannelFacts {
    val g = group(groupId)
    val subs = groups.filter { it.parent == groupId }
    val media = files(groupId).distinctBy { it.url }
    val photos = media.filter { it.isImage }
    return ChannelFacts(
        members = g?.members?.toList() ?: emptyList(),
        photos = photos,
        files = media.size - photos.size - media.count { it.mime.startsWith("video/") },
        group = g,
        pods = subs.filter { it.isPod },
        proposals = subs.filter { it.isProposal }.mapNotNull { proposalFor(it.id) }.filter { it.status in OPEN_STATUS },
        events = upcoming(groupId, now),
        pins = pinned(groupId),
        todos = tasks(groupId).filter { !it.done },
        todosAll = tasks(groupId).size,
        todosDone = tasks(groupId).count { it.done },
        active = recentlyActive(groupId, now),
        own = if (g?.isProposal == true) proposalFor(groupId) else null,
    )
}

enum class LineIcon { PEOPLE, TOPIC, PROPOSAL, POD, EVENT, PIN }

/** What tapping the line's pill does. */
sealed interface LineAction {
    data object ShowAll : LineAction
    /** Open the pull-down (the vote is in it). */
    data object Open : LineAction
    /** The people asking to join: the channel's members. */
    data object Review : LineAction
    data class OpenProposal(val groupId: String) : LineAction
    data class OpenEvent(val coord: String) : LineAction
}

/** [of]: a fraction (the to-dos: done of all, like the web's "13/17"). */
data class LineCount(val kind: String, val n: Int, val plus: Boolean, val label: String, val of: Int? = null)

/**
 * The one line under a channel's name: [bold] then [text], an optional [pill] (a question when
 * [ask], with what it does in [action]; else my answer), small [counts] at the right.
 */
data class ChannelLine(
    val icon: LineIcon,
    val bold: String,
    val text: String,
    val pill: String? = null,
    val ask: Boolean = false,
    val action: LineAction? = null,
    /** Nothing asks for attention: shown muted. */
    val quiet: Boolean = false,
    val counts: List<LineCount> = emptyList(),
    /** Members of the pod, when the icon is its shape. */
    val podMembers: Int = 0,
)

private val DAY_FMT = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

/** "Today" or "Thu 12 Nov". */
fun World.lineDay(at: Long, now: Long): String =
    if (dayOf(at) == dayOf(now)) "Today" else DAY_FMT.format(dayOf(at))

fun World.lineTime(at: Long): String = TIME_FMT.format(Instant.ofEpochSecond(at).atZone(ctx.zone))

/** "18:00 – 21:00", "18:00" or "All day". */
fun World.lineWhen(e: CalEvent): String = when {
    e.allDay -> "All day"
    e.end != null -> lineTime(e.start) + " – " + lineTime(e.end!!)
    else -> lineTime(e.start)
}

private fun World.dayAt(e: CalEvent, now: Long): String = lineDay(e.start, now) + if (e.allDay) "" else " " + lineTime(e.start)

fun peopleLine(n: Int): String = if (n > 0) "$n ${if (n == 1) "person" else "people"} active this month" else "Nobody active this month yet"
/** "12 members": what the line says when nothing else needs saying (web 2026-11-12). */
fun membersLine(n: Int): String = "$n ${if (n == 1) "member" else "members"}"

private fun membersWord(n: Int) = "$n ${if (n == 1) "member" else "members"}"
private fun cap(s: String) = s.replaceFirstChar { it.uppercase() }

/** A pinned message as one plain line. */
fun World.pinLine(m: Message): String =
    plainRefs(m.text.ifEmpty { if (m.media.isNotEmpty()) "A photo or file" else "" })
        .replace(Regex("[*_`>]"), "").replace(Regex("(^|\\s)#(?=\\s|$)"), "$1").split('\n').first()

/**
 * The line, picked in this order: the topic the feed is filtered on; (a proposal's chat: its
 * state); what needs me (people asking to join a pod I administer, a vote I have not cast, an
 * event within 7 days I have not answered); a pinned message I did not hide; the next event; an
 * open proposal; (a pod: its members); else how many members it has.
 */
fun World.channelLine(groupId: String, now: Long, topic: String? = null, hiddenPins: Set<String> = emptySet()): ChannelLine {
    val f = channelFacts(groupId, now)
    val g = f.group
    var shown = ""
    var line = ChannelLine(LineIcon.PEOPLE, "", membersLine(f.members.size), quiet = true)

    val pin = f.pins.firstOrNull { it.id !in hiddenPins }
    val next = f.events.firstOrNull()
    val unanswered = f.events.firstOrNull { stateLabel(it) == null && it.status != EventStatus.CANCELLED }
    val toVote = f.proposals.firstOrNull { needsMyVote(it) }

    if (topic != null) {
        return ChannelLine(LineIcon.TOPIC, "Showing ", (if (topic == Topics.ANNOUNCE) "announcements" else "#$topic") + " only", "Show all", true, LineAction.ShowAll)
    } else if (g?.isProposal == true) {
        val p = f.own
        if (p != null) {
            val mine = p.poll?.votes?.get(me)
            line = if (p.votingNow) {
                ChannelLine(LineIcon.PROPOSAL, "Voting", " · ${p.tally["yes"] ?: 0} yes · ${p.tally["no"] ?: 0} no", if (mine != null) cap(mine) else "Vote", mine == null, if (mine == null) LineAction.Open else null)
            } else {
                ChannelLine(LineIcon.PROPOSAL, cap(p.status.key), " · version ${p.version} · by ${displayName(p.author)}")
            }
        }
    } else if (g != null && g.isPod && canAdmin(g) && g.applications.isNotEmpty()) {
        val k = g.applications.size
        line = ChannelLine(LineIcon.POD, "$k ${if (k == 1) "person asks" else "people ask"} to join", " · " + membersWord(g.members.size), "Review", true, LineAction.Review, podMembers = g.members.size)
    } else if (toVote != null) {
        line = ChannelLine(LineIcon.PROPOSAL, toVote.title, "", "Vote", true, LineAction.OpenProposal(toVote.groupId)); shown = "proposal"
    } else if (unanswered != null && unanswered.start - now < 7 * 86400) {
        line = ChannelLine(LineIcon.EVENT, dayAt(unanswered, now), " · " + unanswered.title, "RSVP", true, LineAction.OpenEvent(unanswered.coord)); shown = "event"
    } else if (pin != null) {
        line = ChannelLine(LineIcon.PIN, "", pinLine(pin))
    } else if (next != null) {
        val mine = stateLabel(next)
        line = ChannelLine(LineIcon.EVENT, dayAt(next, now), " · " + next.title, mine ?: "RSVP", mine == null, LineAction.OpenEvent(next.coord)); shown = "event"
    } else if (f.proposals.isNotEmpty()) {
        line = ChannelLine(LineIcon.PROPOSAL, f.proposals[0].title, " · " + f.proposals[0].status.key); shown = "proposal"
    } else if (g?.isPod == true) {
        line = ChannelLine(LineIcon.POD, "", membersWord(g.members.size) + " · " + if (g.closed) "by application" else "open to join", quiet = true, podMembers = g.members.size)
    }
    val counts = listOf(
        LineCount("events", f.events.size - if (shown == "event") 1 else 0, shown == "event", "more events"),
        LineCount("proposals", f.proposals.size - if (shown == "proposal") 1 else 0, shown == "proposal", "more open proposals"),
        LineCount("pods", f.pods.size, false, "pods"),
        LineCount("photos", f.photos.size, false, "photos"),
        LineCount("todos", f.todosDone, false, "to-dos done of ${f.todosAll}", of = f.todosAll),
    ).filter { it.n > 0 || (it.of ?: 0) > 0 }
    return line.copy(counts = counts)
}
