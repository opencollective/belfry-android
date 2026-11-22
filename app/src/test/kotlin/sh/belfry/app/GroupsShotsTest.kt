package sh.belfry.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.belfry.app.ui.cards.CreatePodScreen
import sh.belfry.app.ui.info.ChannelPlaceScreen
import sh.belfry.app.ui.info.ChannelSettingsForm
import sh.belfry.app.ui.people.MembersScreen
import sh.belfry.app.ui.places.GroupsPlace
import sh.belfry.app.ui.theme.BelfryTheme
import sh.belfry.core.engine.Demo
import sh.belfry.app.ui.nav.ChannelPlaceKey
import sh.belfry.core.model.GroupJoin
import sh.belfry.core.model.allPods

/** Groups: open, closed, private (SPEC 19.59; canvas home/groups, groups/new, groups/new-pick, groups/settings-closed). */
class GroupsShotsTest : DemoShots() {
    private fun field(tag: String) = compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(tag)))

    /** home/groups: the ＋ top right; All shows the Private group I'm in and never the one I'm not in. */
    @Test fun groupsWithThePlus() {
        assertNull(world.group(Demo.POD_SECRET))
        assertTrue(world.allPods().any { it.id == Demo.POD_PRIVATE })
        demoShot("groups-plus") { GroupsPlace() }
        compose.onNodeWithTag("groups-all").performClick()
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithText("Surprise party").fetchSemanticsNodes().isNotEmpty())
        assertTrue(compose.onAllNodesWithText("Board prep").fetchSemanticsNodes().isEmpty())
    }

    /** groups/new: name, what it works on, who can join (the three choices). */
    @Test fun newGroupForm() {
        demoShot("groups-new", act = {
            field("create-name").performTextInput("Soup Sundays")
            field("create-about").performTextInput("A pot of soup for the neighbourhood every Sunday")
            compose.onNodeWithTag("join-closed").performClick()
            compose.waitForIdle()
        }) { CreatePodScreen(Demo.GENERAL) }
        compose.onNodeWithTag("join-open").assertExistsCompat()
        compose.onNodeWithTag("join-private").assertExistsCompat()
    }

    /** groups/new-pick: the members as faces, three picked. */
    @Test fun newGroupPick() {
        demoShot("groups-new-pick", act = {
            field("create-name").performTextInput("Soup Sundays")
            compose.onNodeWithTag("create-next").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Add people").fetchSemanticsNodes().isNotEmpty() }
            for (p in listOf(Demo.maya, Demo.lena, Demo.kofi)) compose.onNodeWithTag("pick-${p.pubkey}").performScrollTo().performClick()
            compose.waitForIdle()
        }) { CreatePodScreen(Demo.GENERAL) }
        assertTrue(compose.onAllNodesWithText("Add 3 people").fetchSemanticsNodes().isNotEmpty())
    }

    /** Private, two people, Add: the 9007 / 9002 say closed + private, one 9000 each, no announcement. */
    @Test fun aPrivateGroupIsMadeWithItsPeople() {
        started()
        compose.setContent { BelfryTheme { InDemo { CreatePodScreen(Demo.GENERAL) } } }
        field("create-name").performTextInput("Quiet corner")
        compose.onNodeWithTag("join-private").performClick()
        compose.onNodeWithTag("create-next").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Add people").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("pick-${Demo.maya.pubkey}").performScrollTo().performClick()
        compose.onNodeWithTag("pick-${Demo.lena.pubkey}").performScrollTo().performClick()
        compose.onNodeWithTag("pick-add").performClick()
        compose.waitUntil(10_000) { world.groups.any { it.name == "Quiet corner" } }
        val g = world.groups.first { it.name == "Quiet corner" }
        assertEquals(GroupJoin.PRIVATE, GroupJoin.of(g))
        val mine = world.all.filter { it.pubkey == session.me && it.tags.any { t -> t.size >= 2 && t[0] == "h" && t[1] == g.id } }
        assertEquals(2, mine.count { it.kind == 9000 && it.tags.any { t -> t.size >= 3 && t[0] == "p" && t[2] == "member" } })
        assertTrue(world.all.none { it.kind == 9 && it.tags.any { t -> t.size >= 2 && t[0] == "pod" && t[1] == g.id } })
    }

    /** groups/settings-closed: the three choices, and for Closed the question and who accepts. */
    @Test fun settingsForAClosedGroup() {
        val g = world.group(Demo.POD_CLOSED)!!
        demoShot("groups-settings-closed", act = {
            compose.onNodeWithTag("vouch-members").performScrollTo()
            compose.waitForIdle()
        }) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) { ChannelSettingsForm(g, onClose = {}) }
        }
        compose.onNodeWithTag("join-closed").assertExistsCompat()
        compose.onNodeWithTag("settings-prompt").assertExistsCompat()
    }

    /** A Closed group's Settings: Who can join and Who can read its page (Members only / Public), both. */
    @Test fun closedGroupShowsBoth() {
        val g = world.group(Demo.POD_CLOSED)!!
        demoShot("groups-settings-closed-page", act = {
            compose.onNodeWithTag("settings-page").performScrollTo()
            compose.waitForIdle()
        }) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) { ChannelSettingsForm(g, onClose = {}) }
        }
        compose.onNodeWithTag("join-closed").assertExistsCompat()
        compose.onNodeWithTag("access-public").assertExistsCompat()
    }

    /** A Private group's Settings: its page is for members only, no Public. */
    @Test fun privateGroupHasNoPublic() {
        val g = world.group(Demo.POD_PRIVATE)!!
        demoShot("groups-settings-private", act = {
            compose.onNodeWithTag("settings-page").performScrollTo()
            compose.waitForIdle()
        }) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) { ChannelSettingsForm(g, onClose = {}) }
        }
        compose.onNodeWithTag("access-members").assertExistsCompat()
        compose.onNodeWithTag("access-public").assertDoesNotExist()
    }

    /** channels/closed-requests + home/members: Invite is the ＋ top right, no first row. */
    @Test fun channelMembersWithThePlus() {
        demoShot("groups-channel-members-plus") { ChannelPlaceScreen(Demo.GARDEN, ChannelPlaceKey.MEMBERS) }
        compose.onNodeWithTag("members-invite").assertExistsCompat()
        assertTrue(compose.onAllNodesWithText("Invite someone").fetchSemanticsNodes().isEmpty())
    }

    @Test fun belfryMembersWithThePlus() {
        demoShot("groups-members-plus") { MembersScreen() }
        compose.onNodeWithTag("members-invite").assertExistsCompat()
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertExistsCompat() { assertExists() }
}
