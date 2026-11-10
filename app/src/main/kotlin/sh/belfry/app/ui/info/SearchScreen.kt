package sh.belfry.app.ui.info

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.ui.chat.ChatFormat
import sh.belfry.app.ui.chat.authorName
import sh.belfry.app.ui.chat.face
import sh.belfry.app.ui.kit.*
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.crypto.Bech32
import sh.belfry.core.markdown.Markdown
import sh.belfry.core.model.SearchHit
import sh.belfry.core.model.fold
import sh.belfry.core.model.refLabel

/**
 * Search messages: in one channel (from its header) or in all of them. Every word must appear
 * (accents and case ignored) in the text, the author's name or a file name; newest first; a
 * result opens its channel on that message.
 */
@Composable
fun SearchScreen(channel: String?, initialQuery: String = "") {
    val c = Belfry.colors
    val world = LocalWorld.current
    val session = LocalSession.current
    val nav = LocalNav.current
    val here = channel?.let { world.group(it) }
    var q by rememberSaveable { mutableStateOf(initialQuery) }
    var everywhere by rememberSaveable { mutableStateOf(channel == null) }
    val words = remember(q) { fold(q).split(Regex("\\s+")).filter { it.isNotEmpty() } }
    val results = remember(world, q, everywhere, channel) { world.search(q, if (everywhere) null else channel) }
    val now = session.now()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { try { focus.requestFocus() } catch (e: IllegalStateException) { /* not laid out yet */ } }

    Column(Modifier.fillMaxSize().background(c.bg)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            BackButton { nav.back() }
            BelfryTextField(
                q, { q = it },
                Modifier.weight(1f).focusRequester(focus).testTag("search-field"),
                placeholder = if (channel != null && !everywhere) "Search " + channelLabel(here, channel) else "Search all channels",
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                trailing = if (q.isNotEmpty()) ({ IconAction(BelfryIcons.Close, "Clear", { q = "" }) }) else null,
            )
        }
        if (channel != null) {
            Segmented(
                listOf(channelLabel(here, channel), "All channels"), if (everywhere) 1 else 0, { everywhere = it == 1 },
                Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
            )
        }
        Hairline()
        when {
            words.isEmpty() -> EmptyState("Search messages", text = "Words from a message, or a name.", icon = BelfryIcons.Search)
            results.isEmpty() -> EmptyState("Nothing found.", icon = BelfryIcons.Search)
            else -> LazyColumn(Modifier.weight(1f).fillMaxWidth().navigationBarsPadding().imePadding()) {
                item {
                    Hint(
                        (if (results.size >= 200) "200+" else results.size.toString()) + (if (results.size == 1) " result" else " results"),
                        Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
                items(results, key = { it.groupId + "/" + it.m.id }) { hit ->
                    HitRow(hit, words, showChannel = everywhere || channel == null, now = now) { nav.openChannel(hit.groupId, hit.m.id) }
                }
            }
        }
    }
}

@Composable
private fun HitRow(hit: SearchHit, words: List<String>, showChannel: Boolean, now: Long, onOpen: () -> Unit) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val m = hit.m
    val mark = SpanStyle(background = c.orange.copy(alpha = 0.28f), fontWeight = FontWeight.SemiBold)
    val text = remember(m, words) {
        val plain = Markdown.plain(m.text, channel = { world.refLabel(it) }) { npub -> "@" + (Bech32.npubToHex(npub)?.let { world.realName(it).ifEmpty { world.displayName(it) } } ?: "…") }
        snippet(plain.ifEmpty { m.media.joinToString(", ") { it.name ?: "Photo" } }, words, mark)
    }
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 10.dp).testTag("search-hit"), verticalAlignment = Alignment.Top) {
        Avatar(world.face(m.pubkey), size = 32.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(world.authorName(hit.groupId, m), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (showChannel) {
                    Spacer(Modifier.width(6.dp))
                    Text(channelLabel(world.group(hit.groupId), hit.groupId), fontSize = 13.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                }
                Spacer(Modifier.width(8.dp))
                Text(ChatFormat.day(world, m.at, now) + " · " + ChatFormat.time(world, m.at), fontSize = 12.sp, color = c.faint, maxLines = 1)
            }
            Text(text, fontSize = 15.sp, lineHeight = 20.sp, color = c.text, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The text around the first match, the matched words marked. Matching is done on the folded
 * text (no accents, lower case) with a map back to the original characters, so "cafe" marks
 * "café" even when folding changes the length.
 */
internal fun snippet(text: String, words: List<String>, mark: SpanStyle): AnnotatedString {
    // fold character by character, remembering where each folded character came from
    val folded = StringBuilder()
    val origin = ArrayList<Int>()
    text.forEachIndexed { i, ch ->
        val f = fold(ch.toString())
        for (x in f) {
            folded.append(x)
            origin.add(i)
        }
    }
    val f = folded.toString()
    val ranges = ArrayList<IntRange>() // in original indexes
    for (w in words) {
        if (w.isEmpty()) continue
        var i = f.indexOf(w)
        while (i >= 0) {
            ranges.add(origin[i]..origin[i + w.length - 1])
            i = f.indexOf(w, i + w.length)
        }
    }
    ranges.sortBy { it.first }
    val first = ranges.firstOrNull()?.first ?: 0
    val from = maxOf(0, first - 40)
    val to = minOf(text.length, from + 180)
    return buildAnnotatedString {
        if (from > 0) append("…")
        var pos = from
        for (r in ranges) {
            if (r.first < pos || r.last >= to) continue
            append(text.substring(pos, r.first))
            withStyle(mark) { append(text.substring(r.first, r.last + 1)) }
            pos = r.last + 1
        }
        append(text.substring(pos, to))
        if (to < text.length) append("…")
    }
}
