package sh.belfry.app.ui.info

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import sh.belfry.app.ui.kit.*
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.model.Group
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A channel's name as titles show it: `#garden`, or the plain name of a pod, event or proposal. */
fun channelLabel(g: Group?, id: String): String = when {
    g == null -> "#$id"
    g.isPod || g.isEvent || g.isProposal -> g.name
    else -> "#" + g.name
}

private val UNTIL = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.ENGLISH)

/** "12 Nov, 10:30" in the given zone. */
fun untilLabel(at: Long, zone: ZoneId): String = UNTIL.format(Instant.ofEpochSecond(at).atZone(zone))

/** A pushed page: top bar with a back arrow, then the content (scrolling unless [scroll] is off). */
@Composable
fun InfoPage(
    title: String,
    subtitle: String? = null,
    scroll: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val nav = LocalNav.current
    Column(Modifier.fillMaxSize().background(Belfry.colors.bg)) {
        TopBar(title, subtitle = subtitle, navigation = { BackButton { nav.back() } }, actions = actions)
        Hairline()
        val base = Modifier.weight(1f).fillMaxWidth().navigationBarsPadding().imePadding()
        Column(if (scroll) base.verticalScroll(rememberScrollState()).padding(16.dp) else base, content = content)
    }
}

/** The web's `.card`: a bordered box with a small muted label. */
@Composable
fun InfoCard(modifier: Modifier = Modifier, label: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = Belfry.colors
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, c.line, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        if (label != null) Text(label, fontSize = 13.sp, color = c.muted, modifier = Modifier.padding(bottom = 4.dp))
        content()
    }
}

@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, fontSize = 13.sp, lineHeight = 18.sp, color = Belfry.colors.muted, modifier = modifier)
}

@Composable
fun ErrorLine(text: String, modifier: Modifier = Modifier) {
    Text(text, fontSize = 14.sp, lineHeight = 19.sp, color = Belfry.colors.danger, modifier = modifier)
}

/**
 * A link to hand out: its QR code, the address, Copy and Share. While [waiting] (the link is
 * being made, or could not be) the last QR stays, faded, so the page keeps its height; a spinner
 * or the error with Retry sits on top.
 */
@Composable
fun LinkBox(
    url: String,
    shareTitle: String,
    waiting: Boolean = false,
    busy: Boolean = false,
    error: String? = null,
    waitingText: String = "Making the link…",
    onRetry: (suspend () -> Unit)? = null,
) {
    val c = Belfry.colors
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        QrCode(url, Modifier.alpha(if (waiting) 0.12f else 1f), size = 220.dp)
        if (waiting && busy) Spinner(size = 28.dp)
        if (waiting && !busy && error != null) {
            Column(Modifier.padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(error, color = c.danger, fontSize = 14.sp, lineHeight = 19.sp, textAlign = TextAlign.Center)
                if (onRetry != null) {
                    Spacer(Modifier.height(10.dp))
                    BusyButton("Retry", onRetry, small = true, icon = BelfryIcons.Retry)
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.surface).padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            if (waiting) (if (busy) waitingText else "—") else url,
            fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp, color = c.text,
            maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("link-url"),
        )
    }
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BusyButton(
            if (copied) "Copied" else "Copy link",
            onClick = { copyText(context, url, shareTitle); copied = true },
            modifier = Modifier.weight(1f).testTag("link-copy"), enabled = !waiting, icon = BelfryIcons.Copy,
        )
        BusyButton(
            "Share…", onClick = { shareText(context, url, shareTitle) },
            modifier = Modifier.weight(1f), kind = ButtonKind.Ghost, enabled = !waiting, icon = BelfryIcons.Share,
        )
    }
}
