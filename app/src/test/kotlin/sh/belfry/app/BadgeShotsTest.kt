package sh.belfry.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.CountBadge
import sh.belfry.app.ui.kit.IconAction
import sh.belfry.app.ui.kit.TopBar

/**
 * The count badge on a bar's button (Xavier's tablet, 2026-11-12: the "2" on the ☰ was cut into
 * a blob). The badge used to be drawn inside Material's IconButton, which clips its content to
 * a 40 dp circle. These tests render it on a tablet at large font sizes and check that what is
 * drawn is the whole badge: its visible bounds equal its size, and one digit is a circle.
 */
class BadgeShotsTest : Shots() {
    @Composable
    private fun Scaled(fontScale: Float, content: @Composable () -> Unit) {
        val d = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale), content = content)
    }

    @Composable
    private fun Bars() {
        Column(Modifier.fillMaxSize()) {
            for (n in listOf(2, 12, 150)) {
                TopBar("general", subtitle = "12 active members this month", navigation = { IconAction(BelfryIcons.Menu, "Channels", {}, badge = n) }) {
                    IconAction(BelfryIcons.Search, "Search", {})
                    IconAction(BelfryIcons.Bell, "Follow", {}, badge = n)
                }
            }
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                for (n in listOf(2, 12, 150)) {
                    CountBadge(n, Modifier.testTag("row-badge"))
                    Spacer(Modifier.width(12.dp))
                }
            }
        }
    }

    private fun check() {
        for (tag in listOf("icon-badge", "row-badge")) {
            val nodes = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
            assertTrue("no $tag", nodes.isNotEmpty())
            for (n in nodes) {
                // boundsInRoot is what is left after every parent's clip; size is the whole badge
                assertEquals("$tag is cut (width)", n.size.width.toFloat(), n.boundsInRoot.width, 0.5f)
                assertEquals("$tag is cut (height)", n.size.height.toFloat(), n.boundsInRoot.height, 0.5f)
                assertTrue("$tag is narrower than tall", n.size.width >= n.size.height)
            }
            // one digit: a circle
            assertEquals(nodes[0].size.width, nodes[0].size.height)
        }
    }

    @Config(qualifiers = "w800dp-h1280dp-port-240dpi")
    @Test fun tabletLargeFont() {
        shot("badge-tablet-font-1.5") { Scaled(1.5f) { Bars() } }
        check()
    }

    @Config(qualifiers = "w800dp-h1280dp-port-240dpi")
    @Test fun tabletHugeFont() {
        shot("badge-tablet-font-2.0") { Scaled(2f) { Bars() } }
        check()
    }

    /** An e-ink tablet's low density with a big display size. */
    @Config(qualifiers = "w600dp-h800dp-port-160dpi")
    @Test fun lowDensityLargeFont() {
        shot("badge-eink-font-1.3") { Scaled(1.3f) { Bars() } }
        check()
    }

    @Test fun phoneDefault() {
        shot("badge-phone") { Bars() }
        check()
    }
}
