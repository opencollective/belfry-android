package sh.belfry.app

import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.belfry.app.ui.chat.ChannelScreen
import sh.belfry.app.ui.info.ChannelPlaceScreen
import sh.belfry.app.ui.nav.ChannelPlaceKey
import sh.belfry.app.ui.nav.Routes
import sh.belfry.app.ui.theme.BelfryTheme
import sh.belfry.core.engine.Demo
import sh.belfry.core.model.Special

/**
 * Printed cards in the town (SPEC 19.75 / 19.76; canvas "Belfry · Group cards": cards/townsquare-new-topic,
 * cards/steward). Demo: Sam's group Sourdough starters and Lena's topic #bike-repair, both written by
 * the groups key as the belfry writes them at a claim.
 */
class TownCardsTest : DemoShots() {
    private fun waitTag(tag: String) = compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    private fun waitText(text: String) = compose.waitUntil(10_000) { compose.onAllNodesWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    private fun topicPost() = world.messages(Demo.GENERAL).single { it.special is Special.TopicCard }
    private fun groupPost() = world.messages(Demo.GENERAL).single { it.special == Special.PodCard(Demo.POD_CARD) }

    /** The demo's events are the server's: signed by the groups key, the person in `by`. */
    @Test fun theDemoIsShapedAsTheServerWrites() {
        val gk = Demo.groups.pubkey
        assertEquals(gk, groupPost().pubkey)
        assertEquals(Demo.sam.pubkey, groupPost().author)
        assertEquals(listOf(listOf("h", "general"), listOf("pod", Demo.POD_CARD), listOf("by", Demo.sam.pubkey)), groupPost().ev.tags)
        assertTrue(groupPost().ev.content.startsWith("⬡ New group: Sourdough starters · "))
        assertEquals(gk, topicPost().pubkey)
        assertEquals(Demo.lena.pubkey, topicPost().author)
        assertEquals(listOf(listOf("h", "general"), listOf("t", "new-topic"), listOf("channel", Demo.TOPIC_CARD), listOf("by", Demo.lena.pubkey)), topicPost().ev.tags)
        assertTrue(topicPost().ev.content.startsWith("# New topic: #bike-repair · Bike repair · "))
        assertEquals(Demo.sam.pubkey, world.group(Demo.POD_CARD)?.creator)
        assertEquals(Demo.lena.pubkey, world.group(Demo.TOPIC_CARD)?.creator)
        assertEquals("Bike repair · Flat tyres, squeaky brakes: ask here, or say when you can help.", world.group(Demo.TOPIC_CARD)?.about)
        // the groups key is in nobody's member list for having signed
        assertTrue(gk !in world.group(Demo.POD_CARD)!!.members)
        assertTrue(gk !in world.group(Demo.TOPIC_CARD)!!.members)
    }

    /** onb-town-topic (SPEC 19.89): the topic's card under "created the topic #bike-repair", and the group's card above it. */
    @Test fun townsquareCards() {
        demoShot("cards-townsquare", act = {
            waitTag("topic-card")
            compose.mainClock.advanceTimeBy(3_500)
        }) { ChannelScreen(Demo.GENERAL, onMenu = {}, focusMessage = topicPost().id) }
        // the topic's card: New topic · #hashtag · its description · Steward: … · N following · Open
        compose.onNodeWithText("NEW TOPIC", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("#bike-repair", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Bike repair · Flat tyres", substring = true, useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Steward: Lena Fischer · 3 following", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("created the topic #bike-repair", useUnmergedTree = true).assertExists()
        // no #new-topic chip, never the post's own text, never the groups key as its author
        assertTrue(compose.onAllNodesWithText("#new-topic", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("# New topic:", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        // Open shows the topic
        compose.onNodeWithTag("topic-card-open").performClick()
        compose.waitForIdle()
        assertTrue(nav.went.toString(), "<channel:${Demo.TOPIC_CARD}>" in nav.went)
    }

    /** The server's "⬡ New group" post: the group's card as the chat's own, by the person who claimed the card. */
    @Test fun newGroupPostIsTheClaimers() {
        compose.setContent { BelfryTheme { InDemo { ChannelScreen(Demo.GENERAL, onMenu = {}, focusMessage = groupPost().id) } } }
        waitText("Sourdough starters")
        // onb-town-group (SPEC 19.89): "created the group Sourdough starters", NEW GROUP, Steward: Sam Carter · N people
        assertTrue(compose.onAllNodesWithText("created the group Sourdough starters", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithTag("card-group-card", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("NEW GROUP", useUnmergedTree = true).assertExists()
        assertTrue(compose.onAllNodesWithText("Steward: Sam Carter · ", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithTag("msg-" + groupPost().id).assertExists()
        // the header over it names Sam, and opens Sam's profile
        compose.onAllNodesWithText("Sam Carter", useUnmergedTree = true).fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
        assertTrue(compose.onAllNodesWithText("⬡ New group:", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    /** The first line in the group: an activity line in our words; it opens the person's profile. */
    @Test fun firstLineInTheGroup() {
        demoShot("cards-group-first-line") { ChannelScreen(Demo.POD_CARD, onMenu = {}) }
        compose.onNodeWithText("Sam Carter created the group from a printed card", useUnmergedTree = true).assertExists()
        assertEquals(1, compose.onAllNodesWithTag("card-started", useUnmergedTree = true).fetchSemanticsNodes().size)
        compose.onNodeWithTag("card-started").performClick()
        assertTrue(nav.went.toString(), Routes.profile(Demo.sam.pubkey) in nav.went)
    }

    @Test fun firstLineInTheTopic() {
        demoShot("cards-topic-first-line") { ChannelScreen(Demo.TOPIC_CARD, onMenu = {}) }
        compose.onNodeWithText("Lena Fischer created the topic from a printed card", useUnmergedTree = true).assertExists()
    }

    /** cards/steward: "The card" in the group's Settings, for its stewards and the belfry's admins. */
    @Test fun stewardSection() {
        demoShot("cards-steward", act = {
            waitTag("card-section")
            compose.onNodeWithTag("card-retire").performScrollTo()
        }) { ChannelPlaceScreen(Demo.POD_CARD, ChannelPlaceKey.SETTINGS) }
        compose.onNodeWithText("Card K7Q2MX", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Started by Sam Carter · 8 people joined by scanning it", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Retire this card", useUnmergedTree = true).assertExists()
    }

    /** Retire asks first; after it the section says the card is retired, and the belfry answers "no card" from then on. */
    @Test fun retireAsksFirst() {
        demoScreenShot("cards-steward-retire", act = {
            waitTag("card-section")
            compose.onNodeWithTag("card-retire").performScrollTo().performClick()
            waitTag("card-retire-sheet")
        }) { ChannelPlaceScreen(Demo.POD_CARD, ChannelPlaceKey.SETTINGS) }
        compose.onNodeWithText("Retire this card?", useUnmergedTree = true).assertExists()
        // nothing was retired by opening the sheet
        assertTrue(kotlinx.coroutines.runBlocking { session.groupCards.forGroup(Demo.POD_CARD) } != null)
        compose.onNodeWithTag("card-retire-confirm").performClick()
        waitTag("card-retired")
        assertTrue(compose.onAllNodesWithTag("card-retire", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        assertEquals(null, kotlinx.coroutines.runBlocking { session.groupCards.forGroup(Demo.POD_CARD) })
    }

    /** "Keep it" closes the sheet and retires nothing. */
    @Test fun keepIt() {
        compose.setContent { BelfryTheme { InDemo { ChannelPlaceScreen(Demo.POD_CARD, ChannelPlaceKey.SETTINGS) } } }
        waitTag("card-section")
        compose.onNodeWithTag("card-retire").performScrollTo().performClick()
        waitTag("card-retire-sheet")
        compose.onNodeWithText("Keep it").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("card-retire-sheet").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("card-retire").assertExists()
        assertTrue(kotlinx.coroutines.runBlocking { session.groupCards.forGroup(Demo.POD_CARD) } != null)
    }

    /** A group that was not started from a card (the belfry's 404): no section at all. */
    @Test fun noSectionWithoutACard() {
        compose.setContent { BelfryTheme { InDemo { ChannelPlaceScreen(Demo.POD_CLOSED, ChannelPlaceKey.SETTINGS) } } }
        compose.waitForIdle()
        assertEquals(null, kotlinx.coroutines.runBlocking { session.groupCards.forGroup(Demo.POD_CLOSED) })
        assertTrue(compose.onAllNodesWithTag("card-section", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("The card", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    /** The topic's Settings have the section too, in the topic's words. */
    @Test fun stewardSectionOfATopic() {
        demoShot("cards-steward-topic", act = {
            waitTag("card-section")
            compose.onNodeWithTag("card-retire").performScrollTo()
        }) { ChannelPlaceScreen(Demo.TOPIC_CARD, ChannelPlaceKey.SETTINGS) }
        compose.onNodeWithText("Started by Lena Fischer · 3 people joined by scanning it", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Its QR then says the topic is closed to new people", useUnmergedTree = true).assertExists()
    }

    /** The activity log: the person who claimed the card created the group, not the belfry's key. */
    @Test fun theLogNamesTheClaimer() {
        demoShot("cards-log") { ChannelPlaceScreen(Demo.POD_CARD, ChannelPlaceKey.LOG) }
        compose.onNodeWithText("Sam Carter created the group", useUnmergedTree = true).assertExists()
    }
}
