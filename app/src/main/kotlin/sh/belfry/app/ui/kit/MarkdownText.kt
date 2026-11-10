package sh.belfry.app.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.nav.LocalWorldOrNull
import sh.belfry.app.ui.nav.Routes
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.app.ui.theme.BelfryColors
import sh.belfry.core.crypto.Bech32
import sh.belfry.core.markdown.Block
import sh.belfry.core.markdown.Inline
import sh.belfry.core.markdown.Markdown
import sh.belfry.core.model.Topics
import sh.belfry.core.model.World

/**
 * Pictures from the network (avatars, photos in messages). Screenshot tests set it to false so
 * renders never depend on the network: a neutral box shows instead.
 */
val LocalNetworkImages = staticCompositionLocalOf { true }

private class MdContext(
    val c: BelfryColors,
    val world: World?,
    val onMention: (String) -> Unit,
    val onTopic: ((String) -> Unit)?,
    val onLink: (String) -> Unit,
)

/**
 * The chat's Markdown subset (SPEC 9), never as HTML. `rich = true` (chat messages) also shows
 * `nostr:npub…` as @Name (highlighted when it is me; a tap opens the profile unless [onMention]
 * is given) and #topics (a tap calls [onTopic] when given). `rich = false` is for descriptions
 * and a profile's "about". Only http(s) and mailto links open.
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    rich: Boolean = true,
    color: Color = Belfry.colors.text,
    fontSize: TextUnit = 15.sp,
    maxLines: Int = Int.MAX_VALUE,
    onMention: ((pubkey: String) -> Unit)? = null,
    onTopic: ((topic: String) -> Unit)? = null,
) {
    val c = Belfry.colors
    val world = LocalWorldOrNull.current
    val nav = LocalNav.current
    val uri = LocalUriHandler.current
    val blocks = remember(text, rich) { Markdown.parse(text, if (rich) Markdown.rich else Markdown.asIs) }
    val ctx = MdContext(
        c, world,
        onMention = onMention ?: { pk -> nav.go(Routes.profile(pk)) },
        onTopic = onTopic,
        onLink = { href -> if (isSafeLink(href)) try { uri.openUri(href) } catch (e: Exception) { } },
    )
    val line = (fontSize.value * 1.4f).sp
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (b in blocks) {
            when (b) {
                is Block.Paragraph -> Text(lines(b.lines, ctx), color = color, fontSize = fontSize, lineHeight = line, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
                is Block.Heading -> Text(
                    buildAnnotatedString { add(b.content, ctx) }, color = c.text, fontWeight = FontWeight.SemiBold,
                    fontSize = (fontSize.value * if (b.level == 1) 1.15f else 1.05f).sp, lineHeight = line, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
                )
                is Block.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    b.items.forEachIndexed { i, item ->
                        Row {
                            Text(if (b.ordered) "${i + 1}." else "•", color = c.muted, fontSize = fontSize, lineHeight = line, modifier = Modifier.width(22.dp))
                            Text(buildAnnotatedString { add(item, ctx) }, color = color, fontSize = fontSize, lineHeight = line, modifier = Modifier.weight(1f))
                        }
                    }
                }
                is Block.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(c.line))
                    Text(lines(b.lines, ctx), color = c.muted, fontSize = fontSize, lineHeight = line, modifier = Modifier.padding(start = 10.dp).weight(1f))
                }
                is Block.CodeBlock -> Text(
                    b.text, color = c.text, fontFamily = FontFamily.Monospace, fontSize = (fontSize.value * 0.9f).sp, lineHeight = line,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c.surface2).padding(10.dp),
                )
            }
        }
    }
}

/** Only these open; anything else stays text (SPEC 15). */
fun isSafeLink(href: String): Boolean {
    val h = href.lowercase()
    return h.startsWith("https://") || h.startsWith("http://") || h.startsWith("mailto:")
}

private fun lines(lines: List<List<Inline>>, ctx: MdContext): AnnotatedString = buildAnnotatedString {
    lines.forEachIndexed { i, l ->
        if (i > 0) append('\n')
        add(l, ctx)
    }
}

private fun AnnotatedString.Builder.add(nodes: List<Inline>, ctx: MdContext) {
    val c = ctx.c
    for (n in nodes) {
        when (n) {
            is Inline.Text -> append(n.text)
            is Inline.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { add(n.children, ctx) }
            is Inline.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { add(n.children, ctx) }
            is Inline.Strike -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { add(n.children, ctx) }
            is Inline.Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = c.surface2)) { append(n.text) }
            is Inline.Link -> {
                if (isSafeLink(n.href)) {
                    withLink(LinkAnnotation.Clickable("link", TextLinkStyles(SpanStyle(textDecoration = TextDecoration.Underline))) { ctx.onLink(n.href) }) { add(n.children, ctx) }
                } else {
                    add(n.children, ctx)
                }
            }
            is Inline.Mention -> {
                val hex = Bech32.npubToHex(n.npub)
                val name = hex?.let { ctx.world?.realName(it) }.orEmpty().ifEmpty { n.npub.take(12) + "…" }
                val mine = hex != null && hex == ctx.world?.me
                val style = SpanStyle(fontWeight = FontWeight.Medium, background = if (mine) c.orange.copy(alpha = 0.22f) else c.surface2)
                if (hex != null) {
                    withLink(LinkAnnotation.Clickable("mention", TextLinkStyles(style)) { ctx.onMention(hex) }) { append("@$name") }
                } else {
                    withStyle(style) { append("@$name") }
                }
            }
            is Inline.Topic -> {
                val style = SpanStyle(fontWeight = FontWeight.Medium, color = c.orange)
                val onTopic = ctx.onTopic
                if (onTopic != null) {
                    withLink(LinkAnnotation.Clickable("topic", TextLinkStyles(style)) { onTopic(Topics.norm(n.name)) }) { append("#${n.name}") }
                } else {
                    withStyle(style) { append("#${n.name}") }
                }
            }
            is Inline.Nsite -> {
                val domain = ctx.world?.ctx?.domain.orEmpty()
                if (domain.isEmpty()) append(n.source) else {
                    val href = "https://preview.$domain/p/${n.npub}" + (n.name?.let { "@$it" } ?: "")
                    withLink(LinkAnnotation.Clickable("nsite", TextLinkStyles(SpanStyle(textDecoration = TextDecoration.Underline))) { ctx.onLink(href) }) { append(n.source) }
                }
            }
        }
    }
}
