package sh.belfry.app

import android.view.View
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.belfry.app.ui.people.Enc3FirstScreenHost
import sh.belfry.app.ui.theme.BelfryTheme
import sh.belfry.core.enc.Enc3DeviceState
import sh.belfry.core.enc.FirstRow

/**
 * Xavier on a Pixel with 0.9.1 (2026-11-24), the first screen with his 10 devices: "Can't do anything but removing
 * devices." Continue must always be on screen above the navigation bar however long the list, system back must leave,
 * and Continue must leave.
 */
class FirstScreenTrapTest : DemoShots() {
    private val tenRows = (1..10).map { i -> FirstRow("%064x".format(i), DemoShots.NOW - i * 3_600, name = if (i <= 3) "Belfry for Android" else null, lastActive = DemoShots.NOW - i * 60) }

    private fun show(): Pair<() -> View, () -> androidx.activity.OnBackPressedDispatcher> {
        session.demoEnc3Device(Enc3DeviceState(on = true, firstScreen = tenRows))
        var view: View? = null
        var back: androidx.activity.OnBackPressedDispatcher? = null
        compose.setContent {
            view = LocalView.current
            back = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            BelfryTheme { InDemo { Enc3FirstScreenHost() } }
        }
        compose.waitForIdle()
        return { view!! } to { back!! }
    }

    private fun gone() = assertTrue("the first screen left", compose.onAllNodesWithTag("enc3-first-overlay").fetchSemanticsNodes().isEmpty())

    @Test
    fun continueIsOnScreenAboveTheBarsWithTenDevices() {
        val (view, _) = show()
        compose.runOnUiThread {
            ViewCompat.dispatchApplyWindowInsets(view(), WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 100, 0, 0))
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 150))
                .build())
        }
        compose.waitForIdle()
        compose.onNodeWithTag("enc3-first-continue").assertIsDisplayed()
        val density = view().resources.displayMetrics.density
        val root = compose.onRoot().getBoundsInRoot()
        val cont = compose.onNodeWithTag("enc3-first-continue").getBoundsInRoot()
        assertTrue("Continue ends above the navigation bar", (root.bottom - cont.bottom).value * density >= 150f - 1f)
        compose.onNodeWithTag("enc3-first-continue").performClick()
        compose.waitForIdle()
        gone()
    }

    @Test
    fun systemBackLeaves() {
        val (_, back) = show()
        compose.runOnUiThread { back().onBackPressed() }
        compose.waitForIdle()
        gone()
    }
}
