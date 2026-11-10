package sh.belfry.app.ui.people

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.ui.kit.BackButton
import sh.belfry.app.ui.kit.TopBar
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.crypto.Bech32
import sh.belfry.core.engine.ActionException

/** A pushed screen of this package: a top bar with a back arrow (pops only) over a scrolling page. */
@Composable
internal fun Page(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val nav = LocalNav.current
    Column(Modifier.fillMaxSize().background(Belfry.colors.bg).imePadding()) {
        TopBar(title, subtitle = subtitle, navigation = { BackButton { if (onBack != null) onBack() else nav.back() } }, actions = actions)
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            content = content,
        )
    }
}

@Composable
internal fun ErrorLine(text: String?, modifier: Modifier = Modifier) {
    if (!text.isNullOrEmpty()) Text(text, fontSize = 14.sp, lineHeight = 19.sp, color = Belfry.colors.danger, modifier = modifier.padding(top = 8.dp))
}

@Composable
internal fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, fontSize = 13.sp, lineHeight = 18.sp, color = Belfry.colors.muted, modifier = modifier)
}

/** The fixed text of a failed action (never the server's own words). */
internal fun Throwable.shown(fallback: String = "That did not work. Try again."): String =
    (this as? ActionException)?.message?.takeIf { it.isNotEmpty() } ?: fallback

/** A key as people see it: short, copied whole. */
internal fun shortNpub(pubkey: String): String {
    val npub = npubOf(pubkey)
    return if (npub.length > 20) npub.take(10) + "…" + npub.takeLast(6) else npub
}

internal fun npubOf(pubkey: String): String = try { Bech32.npub(pubkey) } catch (e: Exception) { pubkey }

internal fun fileSize(n: Long): String =
    if (n > 1_000_000) String.format(java.util.Locale.ROOT, "%.1f MB", n / 1_000_000.0) else "${maxOf(1, Math.round(n / 1000.0))} kB"
