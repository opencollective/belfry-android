package sh.belfry.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.app.ui.theme.BelfryTheme

/**
 * Base of the screenshot tests (Robolectric native graphics + Roborazzi; run by
 * `./gradlew :app:recordRoborazziDebug`, PNGs in app/build/outputs/roborazzi/).
 *
 * - [shot] renders a composable in [BelfryTheme] at phone size (411 x 891 dp) and saves
 *   `<name>.png` (light) and `<name>-dark.png`.
 * - [screenShot] does the same but captures every window of the screen: use it when the content
 *   opens a ModalBottomSheet or a Dialog, which live in their own window and are missing from
 *   `onRoot().captureRoboImage()`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
abstract class Shots {
    /**
     * A screen that never lets Compose go idle (CardsShotsTest.createEvent: "Compose did not get
     * idle", CI 44568908965) fails its test after 20 s, not 60: a stuck class ends in minutes.
     */
    @org.junit.Before
    fun shortIdleTimeout() {
        androidx.test.espresso.IdlingPolicies.setMasterPolicyTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
    }

    @get:Rule
    val compose: ComposeContentTestRule = createComposeRule()

    private var dark by mutableStateOf(false)

    private fun set(content: @Composable () -> Unit) {
        dark = false
        compose.setContent {
            BelfryTheme(dark = dark) {
                Box(Modifier.fillMaxSize().background(Belfry.colors.bg)) { content() }
            }
        }
        compose.waitForIdle()
    }

    /** [act]: taps and the like before the pictures (the light one and the dark one show the same state). */
    fun shot(name: String, act: (() -> Unit)? = null, content: @Composable () -> Unit) {
        set(content)
        if (act != null) { act(); compose.waitForIdle() }
        compose.onRoot().captureRoboImage("$DIR/$name.png")
        dark = true
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$DIR/$name-dark.png")
    }

    @OptIn(ExperimentalRoborazziApi::class)
    fun screenShot(name: String, act: (() -> Unit)? = null, content: @Composable () -> Unit) {
        set(content)
        if (act != null) { act(); compose.waitForIdle() }
        captureScreenRoboImage("$DIR/$name.png")
        dark = true
        compose.waitForIdle()
        captureScreenRoboImage("$DIR/$name-dark.png")
    }

    companion object {
        const val DIR = "build/outputs/roborazzi"
    }
}
