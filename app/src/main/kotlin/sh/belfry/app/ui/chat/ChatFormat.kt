package sh.belfry.app.ui.chat

import sh.belfry.app.ui.kit.ChannelGlyph
import sh.belfry.app.ui.kit.Face
import sh.belfry.core.model.ChannelType
import sh.belfry.core.model.Group
import sh.belfry.core.model.Message
import sh.belfry.core.model.Special
import sh.belfry.core.model.World
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Times and names as the chat shows them. All in the world's zone, so tests are stable. */
object ChatFormat {
    private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
    private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
    private val DAY_YEAR = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH)

    fun time(world: World, at: Long): String = TIME.format(Instant.ofEpochSecond(at).atZone(world.ctx.zone))

    /** "Today", "Yesterday", "Mon 3 Nov", with the year for other years. */
    fun day(world: World, at: Long, now: Long): String {
        val d = world.dayOf(at)
        val today = world.dayOf(now)
        return when {
            d == today -> "Today"
            d == today.minusDays(1) -> "Yesterday"
            d.year == today.year -> DAY.format(d)
            else -> DAY_YEAR.format(d)
        }
    }

    /** Time today, else the day and the time. */
    fun short(world: World, at: Long, now: Long): String =
        if (world.dayOf(at) == world.dayOf(now)) time(world, at) else day(world, at, now) + " " + time(world, at)
}

fun World.face(pubkey: String): Face = Face(pubkey, realName(pubkey).ifEmpty { displayName(pubkey) }, profiles[pubkey]?.picture)

/**
 * Who a message is from, as its header shows it. Cards posted by the belfry's groups key carry
 * their real sender: the person who e-mailed, or the channel that answered.
 */
fun World.authorName(groupId: String, m: Message): String = when (val s = m.special) {
    is Special.InboxCard -> s.name.ifEmpty { s.from }
    is Special.SentAsChannel -> group(groupId)?.name ?: groupId
    else -> nameOver(m)
}

/** The author is a member with a profile to open (not the belfry's own key posting a card). */
fun Message.hasProfile(): Boolean = special !is Special.InboxCard && special !is Special.SentAsChannel

/** WORLD given: a public channel (its page on the web, readable by anyone) shows a globe. */
fun Group.glyph(world: World? = null): ChannelGlyph = when (type) {
    ChannelType.EVENT -> ChannelGlyph.Event
    ChannelType.POD -> ChannelGlyph.Pod
    ChannelType.PROPOSAL -> ChannelGlyph.Proposal
    else -> if (world?.isPublicChannel(this) == true) ChannelGlyph.Public else ChannelGlyph.Channel
}
