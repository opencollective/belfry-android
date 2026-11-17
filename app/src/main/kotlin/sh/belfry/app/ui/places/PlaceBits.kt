package sh.belfry.app.ui.places

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.ui.chat.ChatFormat
import sh.belfry.app.ui.kit.CountBadge
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.model.CalEvent
import sh.belfry.core.model.World
import sh.belfry.core.model.eventStatus
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

private val MONTH = DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH)
private val DAY = DateTimeFormatter.ofPattern("d", Locale.ENGLISH)

/** A grouped surface of rows (a card), as Material lists group them. */
@Composable
internal fun PlaceCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val c = Belfry.colors
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(16.dp)).border(1.dp, c.line, RoundedCornerShape(16.dp)).background(c.bg)) {
        content()
    }
}

/** A row that navigates: an icon in a tinted square, a title, a line, a count, a chevron. */
@Composable
internal fun NavRow(icon: ImageVector, title: String, sub: String?, onClick: () -> Unit, badge: Int = 0, modifier: Modifier = Modifier) {
    val c = Belfry.colors
    Row(modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(c.surface), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = c.text, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!sub.isNullOrEmpty()) Text(sub, fontSize = 14.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (badge > 0) { Spacer(Modifier.width(8.dp)); CountBadge(badge) }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = c.faint)
    }
}

/** A section's title with an optional action on the right ("Calendar"). */
@Composable
internal fun PlaceSection(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    val c = Belfry.colors
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 18.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = c.muted, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) {
            androidx.compose.material3.TextButton(onClick = onAction) { Text(action, color = c.text, fontSize = 15.sp) }
        }
    }
}

/** An event as a row: a date box, its title, "time · where · state in words". No buttons (SPEC 19.31). */
@Composable
internal fun EventRow(world: World, e: CalEvent, now: Long, onClick: () -> Unit) {
    val c = Belfry.colors
    val at = Instant.ofEpochSecond(e.start).atZone(world.ctx.zone)
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(
            Modifier.widthIn(min = 52.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, c.line, RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(MONTH.format(at).uppercase(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = c.text)
            Text(DAY.format(at), fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = c.text, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(e.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val time = if (e.allDay) "All day" else ChatFormat.time(world, e.start)
            Text(listOfNotNull(time, world.eventStatus(e, now)).joinToString(" · "), fontSize = 14.sp, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = c.faint)
    }
}

/** A thin divider inset like the rows' text. */
@Composable
internal fun RowDivider() {
    Box(Modifier.fillMaxWidth().padding(start = 16.dp).size(height = 1.dp, width = 0.dp).background(Belfry.colors.line))
}
