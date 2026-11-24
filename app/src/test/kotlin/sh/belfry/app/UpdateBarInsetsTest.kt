package sh.belfry.app

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.belfry.app.ui.theme.BelfryTheme
import sh.belfry.app.update.UnderUpdateBar
import sh.belfry.app.update.UpdateBarState
import sh.belfry.app.update.UpdateText
import sh.belfry.app.update.Updater
import sh.belfry.core.update.Release

/**
 * Xavier on a Pixel (2026-11-24): "The update available notification is not greatly visible on a pixel phone
 * because of the camera hole." The bar starts below the status bar and the cutout, and the screen under it
 * doesn't add the status bar again.
 */
class UpdateBarInsetsTest : Shots() {
    @After fun clean() { Updater.showForTest(null); UpdateBarState.hiddenNow.value = null }

    @Test
    fun theBarStartsBelowTheStatusBarAndTheCameraCutout() {
        Updater.showForTest(Release("9.9.9", "https://github.com/opencollective/belfry-android/releases/download/v9.9.9/belfry-9.9.9.apk", null))
        UpdateBarState.hiddenNow.value = ""
        var view: View? = null
        compose.setContent {
            view = LocalView.current
            BelfryTheme {
                UnderUpdateBar {
                    // a screen's top bar pads by the status bar itself, as TopBar / OneHeader do
                    Box(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).height(56.dp).testTag("below"))
                }
            }
        }
        compose.runOnUiThread {
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 100, 0, 0))
                .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(0, 130, 0, 0))
                .build()
            ViewCompat.dispatchApplyWindowInsets(view!!, insets)
        }
        compose.waitForIdle()
        val density = view!!.resources.displayMetrics.density
        val text = compose.onNodeWithText(UpdateText.available("9.9.9")).getBoundsInRoot()
        assertTrue("the bar's words start below the cutout (130 px), at ${text.top.value * density} px", text.top.value * density >= 130f)
        val bar = compose.onNodeWithTag("update-bar").getBoundsInRoot()
        val below = compose.onNodeWithTag("below").getBoundsInRoot()
        assertTrue("the screen under the bar doesn't pad by the status bar again: ${below.top} vs ${bar.bottom}", (below.top - bar.bottom).value < 1f)
        assertTrue("its own height is kept (56 dp)", (below.bottom - below.top).value >= 55f)
    }
}
