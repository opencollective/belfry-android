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
import sh.belfry.core.engine.Session
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
                    InviteScreen(Demo.GARDEN, makeLink = { n, d, _ ->
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
        compose.onNodeWithText("Making the link…").assertIsDisplayed()
        assertEquals("the guest link was asked more than once", 1, calls)
        gate.complete(Unit)
        compose.waitUntil(5_000) { compose.onAllNodesWithText(url).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("link-copy").assertIsEnabled()
    }

    @Test
    fun invitingSomeoneHereLocksTheButtonUntilItIsDone() {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        compose.setContent {
            BelfryTheme {
                InDemo {
                    InviteScreen(Demo.KITCHEN, invite = { _ -> calls++; gate.await(); Session.Invited.SENT })
                }
            }
        }
        compose.onNode(hasSetTextAction()).performTextInput("tom")
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("invite-send").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Not in this channel", substring = true).assertIsDisplayed()
        val send = compose.onAllNodesWithTag("invite-send").onFirst()
        send.performClick()
        send.performClick()
        compose.waitForIdle()
        send.assertIsNotEnabled()
        assertEquals("the invite went out more than once", 1, calls)
        gate.complete(Unit)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Link sent").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun invitingAMemberSendsOneDirectMessageWithTheChannelsLink() {
        started()
        compose.setContent { BelfryTheme { InDemo { InviteScreen(Demo.KITCHEN) } } }
        val conv = "n:" + Demo.tomas.pubkey
        val before = session.dms.value.thread(conv).size
        compose.onNode(hasSetTextAction()).performTextInput("tomás")
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("invite-send").fetchSemanticsNodes().isNotEmpty() }
        val send = compose.onAllNodesWithTag("invite-send").onFirst()
        send.performClick()
        send.performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Link sent").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(10_000) { session.dms.value.thread(conv).size > before }
        Thread.sleep(300)
        val sent = session.dms.value.thread(conv).drop(before)
        assertEquals("one direct message", 1, sent.size)
        assertTrue(sent[0].text, sent[0].text.endsWith("invited you to # kitchen: https://chat.belfry.example/#/c/kitchen-71c2"))
    }

    @Test
    fun theOwnAgentSwitchAsksForAnAgentLinkAndShowsItsInstructions() {
        val asked = ArrayList<Triple<Int, Int, Boolean>>()
        val agentUrl = "https://belfry.belfry.example/invite/tok-agent.md"
        compose.setContent {
            BelfryTheme {
                InDemo {
                    InviteScreen(Demo.GARDEN, makeLink = { n, d, agent ->
                        asked.add(Triple(n, d, agent))
                        GuestLink("https://chat.belfry.example/join/tok-agent", agentUrl, 0, 1, d, null, agent)
                    })
                }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("invite-own-agent").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("invite-own-agent").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(agentUrl).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf(Triple(1, 1, true)), asked)
        compose.onNodeWithText("It works once", substring = true).assertIsDisplayed()
        // switching to a guest link turns the agent one off and asks for a plain guest link
        compose.onNodeWithTag("invite-guest").performClick()
        compose.waitUntil(5_000) { asked.size == 2 }
        assertEquals(Triple(1, 1, false), asked[1])
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
