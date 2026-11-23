package sh.belfry.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.belfry.app.ui.info.InviteToCommunitySheet
import sh.belfry.app.ui.people.MembersScreen
import sh.belfry.app.ui.theme.BelfryTheme
import sh.belfry.core.engine.ActionException
import sh.belfry.core.net.GuestLink
import sh.belfry.core.net.JoinsAs
import sh.belfry.core.net.Role

/** "Invite to <community>": the sheet the ＋ of Members opens (canvas inviting/sheet-from-members, SPEC 19.70). */
class InviteSheetTest : DemoShots() {
    private val url = "https://chat.belfry.example/join/tok-abc"

    private fun waitForLink() {
        compose.waitUntil(5_000) { compose.onAllNodesWithText("https://", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun waitForTag(tag: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    // ---- what it looks like ----

    /** The ＋ of Members opens the sheet over the list; the demo's link is there. */
    @Test fun fromThePlusOfMembers() {
        demoScreenShot("invite-members-sheet", act = {
            compose.onNodeWithTag("members-invite").performClick()
            waitForLink()
        }) { MembersScreen() }
        compose.onNodeWithText("Invite to Riverside Commons").assertIsDisplayed()
        compose.onNodeWithTag("link-copy").assertIsEnabled()
        // names, never ids
        assertTrue(compose.onAllNodesWithText("general", substring = true).fetchSemanticsNodes().isEmpty())
    }

    @Test fun whileTheLinkIsMade() = demoScreenShot("invite-members-sheet-making") {
        InviteToCommunitySheet(onDismiss = {}, makeLink = { _, _, _ -> CompletableDeferred<GuestLink>().await() })
    }

    @Test fun whenTheBelfryCannotBeReached() {
        demoScreenShot("invite-members-sheet-failed", act = { waitForText("No connection to the belfry") }) {
            InviteToCommunitySheet(onDismiss = {}, makeLink = { _, _, _ -> throw ActionException("network", "No connection to the belfry. Check your connection and try again.") })
        }
        compose.onNodeWithTag("link-copy").assertIsNotEnabled()
        compose.onNodeWithText("Retry").assertIsDisplayed()
    }

    private fun waitForText(text: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    @Test fun joinsAs() = demoScreenShot("invite-members-sheet-joins", act = {
        waitForLink()
        compose.onNodeWithTag("invite-joins").performScrollTo().performClick()
        waitForTag("invite-term")
    }) { InviteToCommunitySheet(onDismiss = {}) }

    @Test fun link() = demoScreenShot("invite-members-sheet-link", act = {
        waitForLink()
        compose.onNodeWithTag("invite-link").performScrollTo().performClick()
        waitForTag("invite-people-50")
    }) { InviteToCommunitySheet(onDismiss = {}) }

    // ---- what it does ----

    private class Asked(val who: JoinsAs, val people: Int, val days: Int)

    @Test
    fun openingAsksForOneMemberLinkAndLocksUntilItIsThere() {
        val gate = CompletableDeferred<Unit>()
        val asked = ArrayList<Asked>()
        compose.setContent {
            BelfryTheme {
                InDemo {
                    InviteToCommunitySheet(onDismiss = {}, makeLink = { w, n, d -> asked.add(Asked(w, n, d)); gate.await(); GuestLink(url, null, 0, n, d, null) })
                }
            }
        }
        compose.waitUntil(5_000) { asked.size == 1 }
        compose.waitForIdle()
        // the safest choice: one person, a day, the default role for good
        assertEquals(JoinsAs.Member(Role.MEMBER, "permanent"), asked[0].who)
        assertEquals(1, asked[0].people)
        assertEquals(1, asked[0].days)
        compose.onNodeWithText("Making the link…").assertIsDisplayed()
        compose.onNodeWithTag("link-copy").assertIsNotEnabled()
        compose.onNodeWithTag("link-share").assertIsNotEnabled()
        // the rows are locked meanwhile: a tap opens nothing and asks nothing
        compose.onNodeWithTag("invite-joins").assertIsNotEnabled()
        compose.onNodeWithTag("invite-link").assertIsNotEnabled()
        gate.complete(Unit)
        compose.waitUntil(5_000) { compose.onAllNodesWithText(url).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("link-copy").assertIsEnabled()
        compose.onNodeWithText("They join Riverside Commons as Member with their name and e-mail address.").assertExists()
        assertEquals("the link was asked more than once", 1, asked.size)
    }

    @Test
    fun aPickGoesBackAndMakesOneNewLink() {
        val asked = ArrayList<Asked>()
        compose.setContent {
            BelfryTheme {
                InDemo {
                    InviteToCommunitySheet(onDismiss = {}, makeLink = { w, n, d -> asked.add(Asked(w, n, d)); GuestLink("$url-${asked.size}", null, 0, n, d, null) })
                }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("$url-1").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()

        // Link → Up to 50 people: back on the first page, one call for 50
        compose.onNodeWithTag("invite-link").performScrollTo().performClick()
        waitForTag("invite-people-50")
        compose.onNodeWithTag("invite-people-50").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("$url-2").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        assertEquals(2, asked.size)
        assertEquals(50, asked[1].people)
        compose.onNodeWithText("Up to 50 people, the link works for a day").assertExists()

        // Link → 1 week
        compose.onNodeWithTag("invite-link").performScrollTo().performClick()
        waitForTag("invite-days-7")
        compose.onNodeWithTag("invite-days-7").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("$url-3").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        assertEquals(3, asked.size)
        assertEquals(50, asked[2].people)
        assertEquals(7, asked[2].days)

        // Joins as → Steward: a Steward link is for one person, whatever "Link" said
        compose.onNodeWithTag("invite-joins").performScrollTo().performClick()
        waitForTag("invite-role-admin")
        compose.onNodeWithTag("invite-role-admin").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("$url-4").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        assertEquals(4, asked.size)
        assertEquals(JoinsAs.Member(Role.ADMIN, "permanent"), asked[3].who)
        assertEquals(1, asked[3].people)
        assertEquals(7, asked[3].days)
        compose.onNodeWithText("One person, the link works for a week").assertExists()
        compose.onNodeWithText("They join Riverside Commons as Steward with their name and e-mail address.").assertExists()
    }

    @Test
    fun myOwnAgentGetsItsInstructionsLink() {
        val asked = ArrayList<Asked>()
        val agentUrl = "https://belfry.belfry.example/invite/tok-agent.md"
        compose.setContent {
            BelfryTheme {
                InDemo {
                    InviteToCommunitySheet(onDismiss = {}, makeLink = { w, n, d -> asked.add(Asked(w, n, d)); GuestLink(url, agentUrl, 0, n, d, null, w is JoinsAs.Agent) })
                }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText(url).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.onNodeWithTag("invite-joins").performScrollTo().performClick()
        waitForTag("invite-own-agent")
        compose.onNodeWithTag("invite-own-agent").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(agentUrl).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(2, asked.size)
        assertEquals(JoinsAs.Agent, asked[1].who)
        assertEquals(1, asked[1].people)
    }
}
