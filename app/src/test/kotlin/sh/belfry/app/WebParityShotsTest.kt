package sh.belfry.app

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.belfry.app.ui.cards.CreateEventScreen
import sh.belfry.app.ui.cards.EventScreen
import sh.belfry.core.engine.Demo
import sh.belfry.core.model.CalEvent
import sh.belfry.core.model.EventDetails
import sh.belfry.core.model.Rsvp

/**
 * What the web shipped after chat 6497883 (SPEC 19.93; Event form canvas evform/evview-mid, -join-now, -not-going,
 * -qr, evform-new / -fetched): the event's three links (Join online · Event website · Our page), the QR sheet with the
 * address on one line and a copy icon, the form's Event website that fills in the rest.
 */
class WebParityShotsTest : DemoShots() {
    private fun ev(d: String): CalEvent = world.calendar.first { it.d == d }
    private fun has(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun hasText(t: String) = compose.onAllNodesWithText(t, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun coverIn() {
        compose.waitUntil(30_000) { has("cover-loaded") }
        compose.waitForIdle()
    }

    /** evview-mid: a public event with its own site: Event website ↗ and Our page (Copy, QR) under the answer. */
    @Test fun linksMid() {
        demoShot("wp-event-links-mid", act = {
            coverIn()
            compose.onNodeWithTag("event-url").performScrollTo()
            compose.waitForIdle()
        }) { EventScreen(ev(Demo.SWAP_D).coord) }
        assertTrue(has("event-website") && has("event-website-open") && has("event-url") && has("event-copy") && has("event-qr"))
        assertTrue(hasText("seedlibrary.example.org/swap"))
        assertTrue(!has("event-online"))
    }

    /** evview-join-now: going, the call happening now: a Join button and how long ago it started. */
    @Test fun joinNow() {
        val call = ev(Demo.CALL_D)
        runBlocking { session.events.rsvp(call, Rsvp.ACCEPTED) }
        demoShot("wp-event-join-now") { EventScreen(ev(Demo.CALL_D).coord) }
        assertTrue(has("event-online") && has("event-join"))
        assertTrue(hasText("Started 5 min ago"))
    }

    /** evview-not-going: not going: Join online says how to get the link, and there is no link. */
    @Test fun notGoing() {
        demoShot("wp-event-not-going") { EventScreen(ev(Demo.CALL_D).coord) }
        assertTrue(has("event-online") && !has("event-join") && !has("event-join-off"))
        assertTrue(hasText("For people going: answer Going to get the link"))
        assertTrue(!hasText("meet.google.com"))
    }

    /** evview-qr: the QR sheet: the address on one line without https://, a copy icon beside it, the line under it. */
    @Test fun qrSheet() {
        demoScreenShot("wp-event-qr-sheet", act = {
            coverIn()
            compose.onNodeWithTag("event-qr").performScrollTo().performClick()
            compose.waitForIdle()
        }) { EventScreen(ev(Demo.SWAP_D).coord) }
        assertTrue(has("qr-sheet") && has("qr-address") && has("qr-copy"))
        assertTrue(hasText("Show it to someone: their camera opens the page."))
    }

    /** evform-fetched: the Event website pasted: the demo belfry reads it and fills in what was empty. */
    @Test fun formFetched() {
        demoShot("wp-event-form-fetched", act = {
            compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("event-website"))).performTextInput(EventDetails.DEMO_SITE)
            compose.waitUntil(15_000) { has("event-fetched") }
            compose.waitForIdle()
        }) { CreateEventScreen(Demo.GENERAL) }
        assertTrue(hasText(EventDetails.filledFrom("riverside-openday.example")))
        assertTrue(hasText("Riverside Open Day 2027") && hasText("Community hall, Rue Haute 12"))
    }
}
