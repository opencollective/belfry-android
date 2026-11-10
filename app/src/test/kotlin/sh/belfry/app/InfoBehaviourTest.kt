package sh.belfry.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.belfry.app.ui.info.InviteScreen
import sh.belfry.app.ui.info.SearchScreen
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.nav.Nav
import sh.belfry.app.ui.theme.BelfryTheme
import sh.belfry.core.engine.Demo
import sh.belfry.core.net.GuestLink

/** The info screens, driven (demo mode). */
class InfoBehaviourTest : DemoShots() {

    @Test
    fun makingAGuestLinkLocksThePageUntilTheLinkIsThere() {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val url = "https://chat.belfry.example/join/tok-abc"
        compose.setContent {
            BelfryTheme {
                InDemo {
                    InviteScreen(Demo.GARDEN, makeLink = { n, d ->
                        calls++
                        gate.await()
                        GuestLink(url, null, 30, n, d, null)
                    })
                }
            }
        }
        // Before: the member link can be copied.
        compose.onNodeWithTag("link-copy").assertIsEnabled()
        compose.onNodeWithTag("invite-guest").performClick()
        compose.waitForIdle()
        // While the belfry makes the link: nothing to copy, and a second tap does nothing.
        compose.onNodeWithTag("link-copy").assertIsNotEnabled()
        compose.onNodeWithTag("invite-guest").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Making the guest link…").assertIsDisplayed()
        assertEquals("the guest link was asked more than once", 1, calls)
        gate.complete(Unit)
        compose.waitUntil(5_000) { compose.onAllNodesWithText(url).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("link-copy").assertIsEnabled()
    }

    @Test
    fun searchIgnoresAccentsAndAHitOpensItsMessage() {
        class Seen : Nav {
            var channel: String? = null
            var message: String? = null
            override fun go(route: String) {}
            override fun back() {}
            override fun openChannel(id: String, messageId: String?) {
                channel = id
                message = messageId
            }
        }
        val seen = Seen()
        compose.setContent {
            BelfryTheme { InDemo { CompositionLocalProvider(LocalNav provides seen) { SearchScreen(null) } } }
        }
        compose.onNode(hasSetTextAction()).performTextInput("ines")
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("search-hit").fetchSemanticsNodes().isNotEmpty() }
        val hits = world.search("ines")
        assertTrue("nothing found for 'ines'", hits.isNotEmpty())
        // The match is an accented word: "Inès", in a name or a text.
        assertTrue(hits.any { world.realName(it.m.pubkey).contains("Inès") || it.m.text.contains("Inès") })
        compose.onAllNodesWithTag("search-hit").onFirst().performClick()
        compose.waitForIdle()
        assertEquals(hits.first().groupId, seen.channel)
        assertEquals(hits.first().m.id, seen.message)
    }
}
