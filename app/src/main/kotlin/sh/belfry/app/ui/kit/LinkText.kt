package sh.belfry.app.ui.kit

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import sh.belfry.app.MainActivity
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.nav.LocalWorldOrNull
import sh.belfry.app.ui.nav.Nav
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.model.Links
import sh.belfry.core.model.eventByLink

/**
 * Opens a link someone wrote (SPEC 19.36): this belfry's channel, event and guest links open in
 * the app (a channel in Town; an event or a guest link the way an opened link would), anything
 * else http(s) / mailto in the browser. Nothing else opens.
 */
@Composable
fun rememberOpenLink(): (String) -> Unit {
    val nav = LocalNav.current
    val world = LocalWorldOrNull.current
    val domain = world?.ctx?.domain.orEmpty()
    val uri = LocalUriHandler.current
    val context = LocalContext.current
    return remember(nav, domain, uri, context, world) { { href: String -> openLink(href, domain, nav, uri, context) { l -> world?.eventByLink(l)?.groupId } } }
}

fun openLink(href: String, domain: String, nav: Nav, uri: UriHandler, context: Context, chatOfEvent: (String) -> String? = { null }) {
    if (!isSafeLink(href)) return
    fun inside(url: String) {
        try {
            context.startActivity(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse(url)))
        } catch (e: Exception) {
            try { uri.openUri(url) } catch (e2: Exception) { }
        }
    }
    when (val inApp = Links.inApp(href, domain)) {
        is Links.InApp.Channel -> nav.openChannel(inApp.id, inApp.messageId)
        is Links.InApp.EventMessage -> chatOfEvent(inApp.link)?.let { nav.openChannel(it, inApp.messageId) }
            ?: inside(href.substringBefore("#") + "#/events/" + inApp.link)
        is Links.InApp.Event -> inside(inApp.url)
        is Links.InApp.Join -> inside(inApp.url)
        is Links.InApp.Card -> inside(inApp.url)
        null -> try { uri.openUri(href) } catch (e: Exception) { }
    }
}

/** TEXT with its http(s) addresses as links (underlined, tappable), nothing else marked up. */
fun linkedText(text: String, onLink: (String) -> Unit): AnnotatedString {
    val found = Links.find(text)
    if (found.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var last = 0
        for (f in found) {
            if (f.start > last) append(text.substring(last, f.start))
            withLink(LinkAnnotation.Clickable("link", TextLinkStyles(SpanStyle(textDecoration = TextDecoration.Underline))) { onLink(f.url) }) { append(f.url) }
            last = f.end
        }
        if (last < text.length) append(text.substring(last))
    }
}

/**
 * Plain text whose addresses are links: to-do titles and comments, requests' answers, short
 * descriptions where Markdown isn't shown. Long addresses wrap.
 */
@Composable
fun LinkText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Belfry.colors.text,
    fontSize: TextUnit = 15.sp,
    lineHeight: TextUnit = TextUnit.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    textDecoration: TextDecoration? = null,
) {
    val open = rememberOpenLink()
    val shown = remember(text, open) { linkedText(text, open) }
    Text(shown, modifier, color = color, fontSize = fontSize, lineHeight = lineHeight, maxLines = maxLines, overflow = TextOverflow.Ellipsis, textDecoration = textDecoration)
}
