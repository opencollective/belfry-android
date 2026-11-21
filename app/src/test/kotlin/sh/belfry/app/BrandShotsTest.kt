package sh.belfry.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test
import sh.belfry.app.ui.kit.BusyButton
import sh.belfry.app.ui.nav.BrandScope
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.app.ui.theme.DarkBelfryColors
import sh.belfry.app.ui.theme.LightBelfryColors
import sh.belfry.app.ui.theme.branded
import sh.belfry.core.net.BrandColors
import sh.belfry.core.net.BrandMode
import sh.belfry.core.net.Community

/**
 * Xavier's OSV screenshots in dark mode (2026-11-20): a dark brand primary used raw vanished on
 * the dark page. With `branding.colors` the accent and Material's primary follow the mode.
 */
class BrandShotsTest : Shots() {
    private val osv = Community.defaults("opensourcevillage.org").copy(
        brandPrimary = "#2b402e", brandSecondary = "#ad6e21",
        brandColors = BrandColors(BrandMode("#2b402e", "#ffffff"), BrandMode("#608f67", "#111111")),
    )

    @Test fun accentPerMode() {
        assertEquals(Color(0xFF2B402E), LightBelfryColors.branded(osv.brandPrimary, osv.brandSecondary, osv.brandColors).accent)
        assertEquals(Color(0xFF608F67), DarkBelfryColors.branded(osv.brandPrimary, osv.brandSecondary, osv.brandColors).accent)
        assertEquals(Color.White, LightBelfryColors.branded(osv.brandPrimary, null, osv.brandColors).onAccent)
        assertEquals(Color(0xFF111111), DarkBelfryColors.branded(osv.brandPrimary, null, osv.brandColors).onAccent)
        // no colors: the raw primary, as before
        assertEquals(Color(0xFF2B402E), DarkBelfryColors.branded(osv.brandPrimary, null, null).accent)
    }

    /** Both modes (the shot flips to dark live): button, text button, switch and a link-coloured line. */
    @Test fun osvBothModes() = shot("brand-osv") {
        BrandScope(osv) {
            val c = Belfry.colors
            Column(Modifier.fillMaxWidth().background(c.bg).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                BusyButton("Join the event", onClick = {})
                TextButton(onClick = {}) { Text("Show how") }
                Switch(checked = true, onCheckedChange = {})
                Text("A link in the accent", color = MaterialTheme.colorScheme.primary)
                Text("A mention in the accent", color = c.accent)
            }
        }
    }
}
