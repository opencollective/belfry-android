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
) {
    val isEvent get() = type == ChannelType.EVENT
    val isPod get() = type == ChannelType.POD
    val isProposal get() = type == ChannelType.PROPOSAL
    /** "#", "📅", "⬡" or "📝" (the web chat's channelIcon). */
    val icon: String get() = when (type) { ChannelType.EVENT -> "📅"; ChannelType.POD -> "⬡"; ChannelType.PROPOSAL -> "📝"; else -> "#" }
}

data class Media(val url: String, val mime: String, val name: String? = null, val w: Int? = null, val h: Int? = null) {
    val isImage get() = mime.startsWith("image/")
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

    /** "an event" / "a pod" / "a proposal" for the line "Created …" above a card; null otherwise. */
    val created: String? get() = when (special) {
        is Special.EventCard -> "an event"
        is Special.PodCard -> "a pod"
        is Special.ProposalCard -> "a proposal"
        else -> null
    }
    val isCard get() = created != null
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
) {
    /** The chip next to their name: "agent", "guest · not saved", "guest"; null once a member. */
    val chip: String? get() = if (member) null else if (agent) "agent" else if (unsaved) "guest · not saved" else "guest"
}

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
    /** An older thread back in the feed at its latest reply. */
    data class Activity(val root: Message, val last: Message, override val at: Long) : FeedItem {
        override val key get() = "act:" + root.id
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
)

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
