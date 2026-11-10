package sh.belfry.app.ui.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.ui.chat.ChatFormat
import sh.belfry.app.ui.kit.BackButton
import sh.belfry.app.ui.kit.Hairline
import sh.belfry.app.ui.kit.TopBar
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.model.CalEvent
import sh.belfry.core.model.ProposalStatus
import sh.belfry.core.model.World
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** How the cards and their screens write times (in the device's zone, which is the world's). */
internal object CardFormat {
    private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
    private val MONTH = DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH)

    fun zoned(world: World, at: Long): ZonedDateTime = Instant.ofEpochSecond(at).atZone(world.ctx.zone)

    /** "All day", "18:00" or "18:00 – 21:00". */
    fun whenText(world: World, e: CalEvent): String {
        if (e.allDay) return "All day"
        val s = TIME.format(zoned(world, e.start))
        val end = e.end ?: return s
        return s + " – " + TIME.format(zoned(world, end))
    }

    /** "Thu 12 Nov · 18:00 – 21:00" */
    fun dayAndWhen(world: World, e: CalEvent, now: Long): String = ChatFormat.day(world, e.start, now) + " · " + whenText(world, e)

    fun month(world: World, at: Long): String = MONTH.format(zoned(world, at))
    fun dayOfMonth(world: World, at: Long): String = zoned(world, at).dayOfMonth.toString()

    fun members(n: Int): String = if (n == 1) "1 member" else "$n members"
}

/** The month over the day number, as on the web's event rows. */
@Composable
internal fun DateChip(world: World, at: Long, modifier: Modifier = Modifier) {
    val c = Belfry.colors
    Column(
        modifier.size(46.dp).clip(RoundedCornerShape(10.dp)).background(c.bg).border(1.dp, c.line, RoundedCornerShape(10.dp)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(CardFormat.month(world, at).uppercase(Locale.ENGLISH), fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold, color = c.orange)
        Text(CardFormat.dayOfMonth(world, at), fontSize = 17.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, color = c.text)
    }
}

/** The square with a line icon that pod and proposal cards start with. */
@Composable
internal fun IconChip(icon: ImageVector, modifier: Modifier = Modifier) {
    val c = Belfry.colors
    Box(
        modifier.size(46.dp).clip(RoundedCornerShape(10.dp)).background(c.bg).border(1.dp, c.line, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = c.muted, modifier = Modifier.size(22.dp)) }
}

/** The frame of a card in the feed (the web's .ev-card): tappable, never wider than 360dp. */
@Composable
internal fun CardFrame(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable RowScope.() -> Unit) {
    val c = Belfry.colors
    val shape = RoundedCornerShape(12.dp)
    val click = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Row(
        modifier
            .padding(top = 4.dp)
            .widthIn(max = 360.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(c.surface)
            .border(1.dp, c.line, shape)
            .then(click)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

internal enum class Tone { Neutral, Good, Warm, Bad }

/** A small status pill. */
@Composable
internal fun Badge(text: String, tone: Tone = Tone.Neutral, modifier: Modifier = Modifier) {
    val c = Belfry.colors
    val fg: Color = when (tone) {
        Tone.Neutral -> c.muted
        Tone.Good -> c.text
        Tone.Warm -> c.orange
        Tone.Bad -> c.danger
    }
    Text(
        text,
        modifier = modifier.clip(CircleShape).border(1.dp, if (tone == Tone.Neutral) c.line else fg.copy(alpha = 0.5f), CircleShape).padding(horizontal = 8.dp, vertical = 2.dp),
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
        color = fg,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

internal fun ProposalStatus.tone(): Tone = when (this) {
    ProposalStatus.DRAFT, ProposalStatus.CLOSED -> Tone.Neutral
    ProposalStatus.FEEDBACK, ProposalStatus.VOTING -> Tone.Warm
    ProposalStatus.PASSED -> Tone.Good
    ProposalStatus.REJECTED -> Tone.Bad
}

/** A pushed screen: top bar with a back arrow (pops only), a hairline, the content. */
@Composable
internal fun CardScreen(
    title: String,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val nav = LocalNav.current
    Column(Modifier.fillMaxSize().background(Belfry.colors.bg)) {
        TopBar(title, subtitle = subtitle, navigation = { BackButton { nav.back() } }, actions = actions)
        Hairline()
        content()
    }
}

/** The scrolling body of a page or form: keyboard-safe, 16dp gutters, never scrolls sideways. */
@Composable
internal fun PageColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 14.dp).navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}

@Composable
internal fun Muted(text: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    Text(text, modifier = modifier, fontSize = 13.sp, lineHeight = 18.sp, color = Belfry.colors.muted, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

@Composable
internal fun ErrorLine(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, fontSize = 13.sp, lineHeight = 18.sp, color = Belfry.colors.danger)
}

/** A bordered box for a block of details on a page (the web's .card). */
@Composable
internal fun Panel(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = Belfry.colors
    val shape = RoundedCornerShape(12.dp)
    val click = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Column(modifier.fillMaxWidth().clip(shape).border(1.dp, c.line, shape).then(click).padding(12.dp), content = content)
}

/** A thin bar under the channel's top bar: text on the left, something small on the right. */
@Composable
internal fun BarRow(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable RowScope.() -> Unit) {
    val click = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Column {
        Row(
            modifier.fillMaxWidth().background(Belfry.colors.bg).then(click).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
        Hairline()
    }
}

@Composable
internal fun Gap(h: Int) = Spacer(Modifier.height(h.dp))

@Composable
internal fun GapW(w: Int) = Spacer(Modifier.width(w.dp))
