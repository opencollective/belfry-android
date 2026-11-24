package sh.belfry.core.model

import sh.belfry.core.nostr.Event
import java.time.ZoneId

/**
 * What a [World] needs to know besides the events: who I am and the community's settings.
 * `communityAdmins` = belfry.json `admins` ∪ `access.admins` (64-hex only).
 */
data class WorldContext(
    val me: String,
    val domain: String = "",
    val communityName: String = "",
    val communityAdmins: Set<String> = emptySet(),
    /** `_groups@domain`: posts e-mail cards, answers sent as a channel, guest joins. */
    val groupsKey: String? = null,
    /** `_smtp@domain`: the mail bridge. */
    val bridgeKey: String? = null,
    /** For day separators and all-day calendar events. */
    val zone: ZoneId = ZoneId.systemDefault(),
    /** The groups relay's address: the relay hint in a channel reference (`nostr:naddr1…`). */
    val groupsRelay: String = "",
    /** belfry.json `agents`: a member's own agent → that member (both 64-hex keys). */
    val agents: Map<String, String> = emptyMap(),
    /** What the belfry's register says each key is (`POST /whois`): "member", "agent" or "guest".
     *  Empty on an old belfry, offline or in the demo: join and label events decide then. */
    val kinds: Map<String, String> = emptyMap(),
    /**
     * Deleted by absence (SPEC 19.30): groups the groups relay said it has no metadata for → the
     * newest 9007 / 9002 / 39000 this device held for each then (0: none). Stored per account.
     */
    val groupsAbsent: Map<String, Long> = emptyMap(),
    /** Show general / events / proposals / website by their on-brand names (SPEC 19.31). True in the app, false in the vectors. */
    val placeNames: Boolean = false,
    /** The belfry's own key (belfry.json `nostr.pubkey`): answers `belfry:` buttons with the groups key (SPEC 19.36). */
    val belfryKey: String? = null,
    /** Each channel's address as the belfry answers it (`POST /mail-alias`, SPEC 19.60), by channel id. */
    val mailAddresses: Map<String, String> = emptyMap(),
)

data class Profile(val name: String? = null, val picture: String? = null, val nip05: String? = null, val about: String = "", val at: Long = 0)

enum class ChannelType(val key: String) { CHANNEL("channel"), EVENT("event"), POD("pod"), PROPOSAL("proposal") }

/** Someone asking to join a closed group (their latest 9021, not yet admitted or declined). */
data class Application(val pubkey: String, val answer: String, val at: Long, val ev: Event)

/** A NIP-29 group: a channel, an event's chat, a pod or a proposal's chat. */
data class Group(
    val id: String,
    val name: String,
    val about: String,
    val picture: String?,
    val createdAt: Long,
    val lastActivity: Long,
    val members: Set<String>,
    val admins: Set<String>,
    val type: ChannelType,
    /** For a pod or a proposal: the channel or pod it lives in ("general" when unknown). */
    val parent: String?,
    /** NIP-29 `closed`: joining takes an application (9021) that an admin admits (9000). */
    val closed: Boolean,
    /** The question applicants answer. */
    val prompt: String?,
    val applications: List<Application>,
    /** Its creation is still in my outbox. */
    val pending: Boolean,
    /** Guest mode (SPEC 19.26): who opened a guest link and has not joined; not in [members]. */
    val reading: Set<String> = emptySet(),
    /** SPEC 19.30: `["archived","1"]` on its latest 9002 that says anything about it. In no list, read only. */
    val archived: Boolean = false,
    /** Closed groups accepting by vouches (docs/membership.md): how many members must vouch (0 = its admins decide). */
    val vouches: Int = 0,
    /** Its own name as the metadata says (before the on-brand names of SPEC 19.31). */
    val rawName: String = "",
    /** The admins the relay itself lets moderate: its latest 39001 and the creator (not admins by role). */
    val relayAdmins: Set<String> = emptySet(),
    /** Who made it: the author of its first 9007 (an event's host), or the person a printed card's group was made for (SPEC 19.75). */
    val creator: String? = null,
    /** Its name arrived (39000 / 9002 / a place name); false = [name] is only the kind's word (SPEC 19.39). */
    val named: Boolean = true,
    /** NIP-29 `private` (SPEC 19.59): a Private group; only its members see it ([GroupJoin]). */
    val isPrivate: Boolean = false,
) {
    val isEvent get() = type == ChannelType.EVENT
    val isPod get() = type == ChannelType.POD
    val isProposal get() = type == ChannelType.PROPOSAL
    /** "#", "📅", "⬡" or "📝" (the web chat's channelIcon). */
    val icon: String get() = when (type) { ChannelType.EVENT -> "📅"; ChannelType.POD -> "⬡"; ChannelType.PROPOSAL -> "📝"; else -> "#" }
}

/** SPEC 19.88 (§23): what opens a private group's file (AES-256-GCM, as a kind 15's: vectors/dm-files.json). */
data class SealedMedia(val key: String, val nonce: String, val x: String, val ox: String) {
    /** As [sh.belfry.core.dm.DmFiles.open] reads them. */
    fun tags(): List<sh.belfry.core.nostr.Tag> = listOf(
        listOf("encryption-algorithm", sh.belfry.core.dm.DmFiles.ALGORITHM), listOf("decryption-key", key), listOf("decryption-nonce", nonce), listOf("x", x), listOf("ox", ox),
    )

    /** §26: opened in the app only as a picture, a video, a recording or a PDF; anything else is handed on as a download. */
    companion object {
        fun opensHere(mime: String): Boolean = mime.startsWith("image/") || mime.startsWith("video/") || mime.startsWith("audio/") || mime == "application/pdf"
    }

    override fun toString(): String = "SealedMedia($ox)"
}

data class Media(val url: String, val mime: String, val name: String? = null, val w: Int? = null, val h: Int? = null, val sealed: SealedMedia? = null) {
    /** A picture shown in the message ([Pictures]: by type, else by the URL's extension). */
    val isImage get() = Pictures.isPicture(mime, url) || Pictures.isPicture(mime, name)
}

/** A member's e-mail kept whole on the file server (AES-256-GCM; key and nonce base64). */
data class MailOriginal(val url: String, val key: String, val nonce: String)

/** What kind of post a message is, beyond plain text (SPEC 8, "Special posts"). */
sealed interface Special {
    data object None : Special
    /** Links a calendar event (`a` tag 31922/31923): shown as the event's card. */
    data class EventCard(val coord: String) : Special
    data class PodCard(val podId: String) : Special
    data class ProposalCard(val groupId: String) : Special
    /** The town square's post about a topic started from a printed card (SPEC 19.76): the topic's card. */
    data class TopicCard(val channelId: String) : Special
    /** A member's e-mail to `<channel>@domain`, posted as their message: "via e-mail". */
    data class MemberMail(val subject: String, val original: MailOriginal?) : Special
    /** Shared inbox: an e-mail from anyone, posted by the groups key as a card. */
    data class InboxCard(val from: String, val name: String, val subject: String, val messageId: String?, val verified: Boolean, val original: MailOriginal?) : Special
    /** An answer that went out by e-mail as the channel (posted by the groups key). */
    data class SentAsChannel(val to: String, val subject: String, val by: String) : Special
}

data class Message(
    /** The original event (its id is what reactions, threads and links hang off). */
    val ev: Event,
    /** The text as it reads now (after the author's latest edit), media URLs cut out. */
    val text: String,
    val media: List<Media>,
    val topics: List<String>,
    /** When its author last edited it (kind 1010), else null. */
    val edited: Long? = null,
    val special: Special = Special.None,
    /** Uploaded through a drop box link (`client belfry-drop`): name it "Drop box" when the key has no profile. */
    val viaDropBox: Boolean = false,
    /** The events it shows as cards under its text (its `a` tags and its event URLs, at most three). */
    val eventRefs: List<EventRef> = emptyList(),
    /**
     * Who wrote it (SPEC 19.75, [World.realAuthor]): the person the groups key signed it for, else
     * [pubkey]. The name, the face and the profile it opens; [pubkey] stays who signed (edit, delete).
     */
    val author: String = ev.pubkey,
) {
    val id get() = ev.id
    val pubkey get() = ev.pubkey
    val at get() = ev.createdAt

    /** Text to show: for e-mails, without the leading `**<subject>**` line the bridge adds. */
    val body: String get() {
        val subject = when (special) {
            is Special.MemberMail -> special.subject
            is Special.InboxCard -> special.subject
            else -> ""
        }
        val head = "**$subject**"
        return if (subject.isNotEmpty() && text.startsWith(head)) text.substring(head.length).trimStart() else text
    }

    /** "an event" / "a group" / "a proposal" for the line "Created …" above a card; null otherwise. */
    val created: String? get() = when (special) {
        is Special.EventCard -> "an event"
        is Special.PodCard -> "a group"
        is Special.ProposalCard -> "a proposal"
        else -> null
    }
    /** The grey line above a card: "Created a group" …, or for a topic's card "started a topic from a printed card". */
    val cardLine: String? get() = if (special is Special.TopicCard) "started a topic from a printed card" else created?.let { "Created $it" }
    val isCard get() = cardLine != null
}

data class Reaction(val emoji: String, val who: Set<String>, /** My reaction event (to undo it). */ val mine: Event? = null)

data class ThreadInfo(val count: Int, val last: Long, val who: List<String>)

/** Who joined a channel as its guest (kind 9000 from the belfry with `invited-by`). */
data class GuestJoin(
    val id: String,
    val pubkey: String,
    val by: String?,
    val at: Long,
    val agent: Boolean,
    /** No e-mail on the account yet ("guest · not saved"). */
    val unsaved: Boolean,
    /** A member since (an admin made them one): no longer called a guest. */
    val member: Boolean,
    /** The channel they were let into (its `h`). */
    val group: String = "",
) {
    /** The chip next to their name: "agent", "guest · not saved", "guest"; null once a member. */
    val chip: String? get() = if (member) null else if (agent) "agent" else if (unsaved) "guest · not saved" else "guest"
}

/** Someone who joined the belfry, as the groups key announced it in general (SPEC 19.69). */
data class BelfryJoin(
    val id: String,
    val pubkey: String,
    /** The `name` tag: the name when they have no profile yet. */
    val name: String,
    val agent: Boolean,
    val at: Long,
)

sealed interface FeedItem {
    val at: Long
    val key: String

    data class Msg(val m: Message) : FeedItem {
        override val at get() = m.at
        override val key get() = m.id
    }
    data class Join(val j: GuestJoin) : FeedItem {
        override val at get() = j.at
        override val key get() = "j" + j.id
    }
    /** "Lina Moreau joined Riverside Commons": a line in the main channel (SPEC 19.69). */
    data class BelfryJoined(val j: BelfryJoin) : FeedItem {
        override val at get() = j.at
        override val key get() = "bj" + j.id
    }
    /** An older thread back in the feed at its latest reply. */
    /** A shared inbox's e-mail thread, where its latest activity is (SPEC 19.33). */
    data class MailThread(val t: InboxThread) : FeedItem {
        override val at get() = t.last
        override val key get() = "mail:" + t.id
    }
    data class Activity(val root: Message, val last: Message, override val at: Long) : FeedItem {
        override val key get() = "act:" + root.id
    }
    /** An action about an item, as one line that unfolds into the item's card (Previews.kt). */
    data class Preview(val p: ActivityPreview) : FeedItem {
        override val at get() = p.at
        override val key get() = p.key
    }
    /** A private group in format 3 (SPEC 19.88): who joined, was removed, can read from here or no longer can. */
    data class EncLine(val line: GroupLine) : FeedItem {
        override val at get() = line.at
        override val key get() = "enc:" + line.kind.name + ":" + line.who + ":" + line.at
    }
    /** In an event's chat: someone's answer to the event, at its time ("Lina Moreau is going"; one shape). */
    data class Answer(val pubkey: String, val status: Rsvp, override val at: Long) : FeedItem {
        override val key get() = "rsvp:" + pubkey
    }
}

/** A feed item with what the list needs to lay it out. */
data class FeedRow(
    val item: FeedItem,
    /** A day separator goes before it. */
    val newDay: Boolean,
    /** For a message: starts a block (avatar + name + time). */
    val first: Boolean,
    val announcement: Boolean = false,
)

data class DropLink(val token: String, val groupId: String, val expires: Long, val login: Boolean, val author: String, val ev: Event)

enum class CardStatus(val label: String) { OPEN("Open"), WAITING("Answered"), CLOSED("Closed") }
data class CardState(val status: CardStatus = CardStatus.OPEN, val assignee: String? = null, val answered: Int = 0)

enum class Rsvp(val key: String, val label: String) {
    ACCEPTED("accepted", "Going"), TENTATIVE("tentative", "Maybe"), DECLINED("declined", "Can't go");

    companion object { fun of(key: String?): Rsvp? = entries.firstOrNull { it.key == key } }
}

data class CalEvent(
    /** kind:creator:d */
    val coord: String,
    val d: String,
    /** The version that counts (rule B for Belfry events: the newest by a host). */
    val ev: Event,
    val title: String,
    val description: String,
    val location: String?,
    val allDay: Boolean,
    /** Unix seconds (an all-day event: midnight in the context's zone). */
    val start: Long,
    val end: Long?,
    /** Its own chat (`ev-<d>` for Belfry events, `ws-…` for the older ones). */
    val groupId: String,
    /** Online meeting links (`r` tags, https only). */
    val links: List<String>,
    /** The channels its card was sent in (messages with its `a` tag or its URL); not its own chat. May be empty. */
    val channels: List<String>,
    val rsvps: Map<String, Rsvp>,
    /** Each person's RSVP event (for "Still going?"). */
    val rsvpEvents: Map<String, Event> = emptyMap(),
    /** The time zone it was set in (NIP-52 `start_tzid`). */
    val tz: String? = null,
    /** A Belfry event (`d` = <year>-<6>, group `ev-<d>`, its own URL), not an older session. */
    val isNew: Boolean = false,
    val creator: String = ev.pubkey,
    /** The creator and the admins of its group. */
    val hosts: Set<String> = setOf(creator),
    val cover: String? = null,
    val visibility: Visibility = Visibility.MEMBERS,
    val slug: String? = null,
    val status: EventStatus = EventStatus.SCHEDULED,
    val statusReason: String? = null,
    /** Every host's version seen, oldest first. */
    val versions: List<Event> = listOf(ev),
    /** Who is going: accepted RSVPs, plus (Belfry events) ticket holders who did not say otherwise. */
    val goingKeys: List<String> = rsvps.filterValues { it == Rsvp.ACCEPTED }.keys.toList(),
    /** Read from the belfry's API (a guest can't read the community relay). */
    val remote: Boolean = false,
    /** The count the API gave (remote events), else the size of [goingKeys]. */
    val goingCount: Int = goingKeys.size,
    /**
     * A meeting (web 2026-11-12, core/meetings.ts): a members-only event made from a channel's ＋
     * with a topic, the people invited, a time, a length and a place. Its `t` tag is `meeting`.
     */
    val meeting: Boolean = false,
    /** The people invited to a meeting: its `p` tags marked `invited` (64-hex keys, each once). */
    val invited: List<String> = emptyList(),
    /** The channel a meeting was made from (its `channel` tag). */
    val madeIn: String? = null,
) {
    val going: Int get() = goingCount
    val author: String get() = ev.pubkey
    /** The last part of its URL: its custom name, else its id. */
    val link: String get() = slug ?: d
    /** The first channel its card is in ("general" when none). */
    val channel: String get() = channels.firstOrNull() ?: "general"
}

enum class ProposalStatus(val key: String, val label: String) {
    DRAFT("draft", "Draft"), FEEDBACK("feedback", "Open for feedback"), VOTING("voting", "Open for votes"),
    PASSED("passed", "Passed"), REJECTED("rejected", "Rejected"), CLOSED("closed", "Closed");

    companion object { fun of(key: String?): ProposalStatus? = entries.firstOrNull { it.key == key } }
}

data class Poll(val ev: Event, val version: Int, /** pubkey → option id ("yes", "no", "abstain") */ val votes: Map<String, String>)

data class Proposal(
    val coord: String,
    val d: String,
    val ev: Event,
    val author: String,
    val groupId: String,
    val title: String,
    val summary: String,
    val text: String,
    val version: Int,
    val status: ProposalStatus,
    /** The latest poll on it, with its version and each member's latest answer. */
    val poll: Poll?,
) {
    /** option id → count */
    val tally: Map<String, Int> get() = poll?.votes?.values?.groupingBy { it }?.eachCount() ?: emptyMap()
    /** Votes are open on the current version. */
    val votingNow: Boolean get() = status == ProposalStatus.VOTING && poll != null && poll.version == version

    companion object {
        val VOTE_OPTIONS = listOf("yes" to "Yes", "no" to "No", "abstain" to "Abstain")
    }
}

data class TaskComment(val author: String, val text: String, val at: Long)
data class Task(
    val id: String, val title: String, val author: String, val assignee: String?, val done: Boolean, val pending: Boolean, val comments: List<TaskComment>,
    /** The message it was made from ("Turn into a to-do"): its `q` tag. */
    val source: String? = null,
    /** Its status steps, oldest first: created, claim, working, done, undone. */
    val history: List<TaskStep> = emptyList(),
    /** When its assignee last said "working on it now" (a 2101 `working` heartbeat), Unix seconds. */
    val beat: Long? = null,
    /** When the current working stretch began (its first heartbeat): the ring fills with its minutes. */
    val since: Long? = null,
    /** When it was made. */
    val at: Long = 0,
    /** When it is due (one shape: `["due", unix seconds]` on the 2100 or its author's newest 1010), else null. */
    val due: Long? = null,
    /** When it was last marked done, while it is done. */
    val doneAt: Long? = null,
    /** Its own description (one shape: `["description", Markdown ≤ 2000]` on the 2100 or its author's newest 1010), else null. */
    val description: String? = null,
) {
    /**
     * Being worked on right now: the assignee's heartbeat is under 5 minutes old (agents send one
     * every 2 minutes while busy, `idle` when they stop). One to-do per person at a time.
     */
    fun isWorking(now: Long): Boolean = !done && beat != null && now - beat < BEAT_FRESH

    companion object {
        const val BEAT_FRESH = 300L

        /** "4 min", "1 h 20" (the web's `mins`): at least one minute. */
        fun stretch(seconds: Long): String {
            val m = maxOf(1L, Math.round(seconds / 60.0))
            return if (m < 60) "$m min" else "${m / 60} h" + (if (m % 60 != 0L) " " + (m % 60).toString().padStart(2, '0') else "")
        }
    }
}

data class TaskStep(val at: Long, val who: String, val what: String)

/** A to-do's status steps by one person at about the same time, as one line in its thread. */
data class StepGroup(val task: Task, val who: String, val at: Long, val what: List<String>)

/** The thread's lines for these to-dos (web Chat.tsx stepGroups): steps by the same person within
 *  two minutes merge ("made a to-do and took it"); a second "started working" is dropped. */
object TaskSteps {
    private val WORDS = mapOf("created" to "made a to-do", "claim" to "took it", "assign" to "gave it to someone", "unassign" to "took someone off it", "working" to "started working on it", "done" to "marked it done", "undone" to "reopened it")

    fun groups(tasks: List<Task>): List<StepGroup> {
        val out = ArrayList<StepGroup>()
        for (t in tasks) {
            var started = false
            var cur: MutableList<String>? = null
            var curWho = ""; var curAt = 0L
            for (s in t.history.sortedBy { it.at }) {
                if (s.what !in WORDS) continue
                if (s.what == "working") { if (started) continue; started = true }
                if (s.what == "done" || s.what == "undone") started = false
                if (cur != null && curWho == s.who && s.at - curAt <= 120 && s.what !in cur) { cur.add(s.what); continue }
                cur = mutableListOf(s.what); curWho = s.who; curAt = s.at
                out.add(StepGroup(t, s.who, s.at, cur))
            }
        }
        return out
    }

    /** "made a to-do, took it and started working on it" */
    fun text(what: List<String>): String {
        val w = what.map { WORDS.getValue(it) }
        return if (w.size > 1) w.dropLast(1).joinToString(", ") + " and " + w.last() else w.first()
    }
}

data class Active(val pubkey: String, val at: Long)

data class SearchHit(val m: Message, val groupId: String)

/** Where a member says they are: a city's point from the belfry's list, or only a country. */
data class Place(
    val country: String,
    val id: String? = null,
    val name: String? = null,
    val region: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val near: String? = null,
)

enum class Share(val key: String) { CITY("city"), COUNTRY("country"), NONE("none") }

/** What members say about themselves beyond kind 0 (kind 30078, d = belfry:profile). */
data class Extras(val tags: List<String>, val share: Share, val place: Place?, val at: Long)

data class TagCount(val tag: String, val count: Int)

/** A member row in a channel's Members tab. */
data class MemberRow(val pubkey: String, val name: String, val place: Place?, val guest: GuestJoin?, val labels: List<String>, val isAdmin: Boolean)
