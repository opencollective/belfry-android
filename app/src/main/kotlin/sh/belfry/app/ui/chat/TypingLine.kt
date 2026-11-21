package sh.belfry.app.ui.chat

import android.provider.Settings
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.typing.Typing
import sh.belfry.core.typing.TypingPlace

/** Room left under every feed's last row for the typing line, so it never covers a message (nor moves the feed). */
val TYPING_ROOM = 22.dp

/**
 * canvas v110 messages/typing-channel, typing-dm, typing-thread: "Lina is typing…" under the last
 * message, above the composer (docs/typing.md §3). It takes no room of its own: it is drawn over
 * the feed's bottom [TYPING_ROOM], which the feeds always leave empty, so nothing moves or hides. [lastMessageAt] = the
 * created_at of a person's latest message in this place (their message ends their typing).
 */
@Composable
fun TypingLine(place: TypingPlace, lastMessageAt: (String) -> Long) {
    val session = LocalSession.current
    val world = LocalWorld.current
    val state by session.typing.collectAsState()
    val who = state.who(place, lastMessageAt)
    if (who.isEmpty()) return
    val h = 22.dp
    Box(
        Modifier
            .fillMaxWidth()
            .layout { m, c ->
                val p = m.measure(c.copy(minHeight = 0, maxHeight = h.roundToPx()))
                layout(p.width, 0) { p.place(0, -p.height) } // no height of its own: over the feed's bottom
            },
    ) {
        TypingRow(who.map { world.displayName(it) })
    }
}

/** The line itself (also the screenshot tests' and the gallery's): dots, then the words, one line. */
@Composable
fun TypingRow(names: List<String>, modifier: Modifier = Modifier) {
    val c = Belfry.colors
    val text = Typing.line(names) ?: return
    val bold = if (names.size in 1..2) names else emptyList()
    val annotated = buildAnnotatedString {
        var rest = text
        for (n in bold) {
            val i = rest.indexOf(n)
            if (i < 0) continue
            append(rest.substring(0, i))
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(n) }
            rest = rest.substring(i + n.length)
        }
        append(rest)
    }
    Row(
        modifier
            .fillMaxWidth()
            .height(22.dp)
            .background(c.bg)
            .padding(horizontal = 16.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag("typing-line"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TypingDots(c.muted)
        Spacer(Modifier.width(6.dp))
        Text(annotated, fontSize = 13.sp, lineHeight = 18.sp, color = c.muted, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
    }
}

/** Three dots lit in turn over 1.2 s; still when the system's animations are off (Remove animations). */
@Composable
fun TypingDots(color: Color) {
    val context = LocalContext.current
    val still = remember {
        try { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f } catch (e: Exception) { false }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        if (still) {
            repeat(3) { Box(Modifier.size(5.dp).clip(CircleShape).alpha(0.7f).background(color)) }
        } else {
            val t = rememberInfiniteTransition(label = "typing")
            repeat(3) { i ->
                val a by t.animateFloat(
                    initialValue = 0.35f, targetValue = 0.35f,
                    animationSpec = infiniteRepeatable(
                        keyframes {
                            durationMillis = 1200
                            0.35f at 0
                            0.35f at i * 200
                            1f at i * 200 + 360
                            0.35f at i * 200 + 720
                        },
                        RepeatMode.Restart,
                    ),
                    label = "dot$i",
                )
                Box(Modifier.offset(y = (-2 * (a - 0.35f) / 0.65f).dp).size(5.dp).clip(CircleShape).alpha(a).background(color))
            }
        }
    }
}

/** While on screen, the groups relay sends [groupId]'s typing events. */
@Composable
fun WatchTyping(groupId: String) {
    val session = LocalSession.current
    androidx.compose.runtime.DisposableEffect(session, groupId) {
        val w = session.watchTyping(groupId)
        onDispose { w.close() }
    }
}

/** A channel's line: typing events without `e`; its group's typing events are asked for while it shows. */
@Composable
fun ChannelTyping(groupId: String) {
    val world = LocalWorld.current
    WatchTyping(groupId)
    val messages = world.messages(groupId)
    val last = remember(messages) { HashMap<String, Long>().also { m -> for (x in messages) if (x.at > (m[x.pubkey] ?: 0L)) m[x.pubkey] = x.at } }
    TypingLine(TypingPlace.channel(groupId)) { last[it] ?: 0L }
}
