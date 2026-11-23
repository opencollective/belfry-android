package sh.belfry.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.belfry.app.ui.cards.EventScreen
import sh.belfry.app.ui.kit.LocalNetworkImages
import sh.belfry.app.ui.nav.AccountsUi
import sh.belfry.app.ui.nav.LocalAccounts
import sh.belfry.app.ui.signin.MembersOnlyScreen
import sh.belfry.app.ui.signin.SignedOutFlow
import sh.belfry.app.ui.theme.BelfryTheme
import sh.belfry.core.engine.Demo
import sh.belfry.core.engine.MemoryStores
import sh.belfry.core.engine.MemoryVault
import sh.belfry.core.engine.Onboarding
import sh.belfry.core.net.Community
import sh.belfry.core.net.EventLink
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A members-only event opened by someone who is not a member (SPEC 19.71; canvas
 * events/members-only-join, events/members-only-login). No network: a made-up belfry whose every
 * HTTP call is answered here (the event's address: 403; the log-in's first call: "a code was sent").
 */
class MembersOnlyTest : DemoShots() {
    private val riverside = Community.defaults(Demo.DOMAIN).copy(name = Demo.NAME)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val asked = CopyOnWriteArrayList<String>()
    private val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        val req = chain.request()
        asked += req.method + " " + req.url
        val path = req.url.encodedPath
        val (code, body) = when {
            "/events/" in path -> 403 to """{"error":"members_only"}"""
            path.endsWith("/login/start") -> 202 to """{"sent":true}"""
            else -> 503 to "{}"
        }
        Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("test")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }).build()
    private val link = EventLink(Demo.DOMAIN, Demo.MEMBERS_EVENT)

    @After fun endScope() { scope.cancel() }

    private fun field(tag: String) = compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(tag)))
    private fun waitFor(tag: String) = compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    /** Signed out, the app opened with the event's address. */
    private fun openedSignedOut(): AppState {
        val onboarding = Onboarding(MemoryVault(), MemoryStores(), http, scope, { NOW }, ZoneOffset.UTC, discovery = { riverside })
        val state = AppState(onboarding, scope)
        state.onEventLink(link)
        state.boot()
        compose.setContent { BelfryTheme { CompositionLocalProvider(LocalNetworkImages provides false) { SignedOutFlow(state) } } }
        waitFor("members-signin")
        return state
    }

    @Test fun join() = shot("members-only-join") { MembersOnlyScreen(Demo.NAME, {}, {}) }

    @Test
    fun signInGoesStraightToThisBelfrysEmailLogIn() {
        val state = openedSignedOut()
        // asked as the web asks it signed out: the event's address, unsigned
        assertTrue(asked.toString(), "GET ${riverside.apiBase}/events/${Demo.MEMBERS_EVENT}" in asked)
        compose.onNodeWithText("This event is for members").assertExists()
        compose.onNodeWithText("Members see the details, answer and join the conversation.").assertExists()
        compose.onNodeWithText("Apply to join ${Demo.NAME}").assertExists()

        compose.onNodeWithTag("members-signin").performClick()
        waitFor("wayin-email")
        // the e-mail log-in itself, no Welcome first
        compose.onNodeWithText("Log in").assertExists()
        compose.onNodeWithText("We send a 6-digit code to your e-mail. No password.").assertExists()
        assertTrue(compose.onAllNodesWithTag("welcome-login").fetchSemanticsNodes().isEmpty())
        compose.onRoot().captureRoboImage("$DIR/members-only-login.png")

        // it is that belfry's log-in: the code is asked from the belfry of the link
        field("wayin-email").performTextInput("sam@example.org")
        compose.onNodeWithTag("wayin-send").performClick()
        compose.waitUntil(10_000) { "POST ${riverside.apiBase}/login/start" in asked }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("wayin-email").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Check your e-mail").assertExists()
        // the event waits for the sign-in (AppRoot opens it in a session of its belfry)
        assertEquals(link, state.eventLink.value)
    }

    @Test
    fun backPopsToTheScreenAndApplyOpensTheApplication() {
        val state = openedSignedOut()
        compose.onNodeWithTag("members-signin").performClick()
        waitFor("wayin-email")
        compose.onNodeWithContentDescription("Back").performClick()
        waitFor("members-signin")

        compose.onNodeWithTag("members-apply").performClick()
        waitFor("wayin-email")
        // the application's first screen (its e-mail is checked first), not the log-in
        compose.onNodeWithText("We send a 6-digit code to check it's yours. The stewards' answer comes here too.").assertExists()
        assertEquals(link, state.eventLink.value)
        compose.onNodeWithContentDescription("Back").performClick()
        waitFor("members-signin")
    }

    /** A guest of the belfry (guest mode) opens the event's address: the same words on the event's page. */
    @Test
    fun aGuestIsToldTheSameOnTheEventsPage() {
        session.demoGuestMode(Demo.GENERAL)
        var way: Pair<String?, Boolean>? = null
        demoShot("members-only-guest", act = { waitFor("members-signin") }) {
            CompositionLocalProvider(LocalAccounts provides AccountsUi(wayIn = { event, apply -> way = event to apply })) { EventScreen(Demo.MEMBERS_EVENT) }
        }
        compose.onNodeWithText("This event is for members").assertExists()
        compose.onNodeWithText("Apply to join ${Demo.NAME}").assertExists()
        assertNull(way)
        compose.onNodeWithTag("members-signin").performClick()
        compose.waitUntil(10_000) { way != null }
        assertEquals(Demo.MEMBERS_EVENT to false, way)
        compose.onNodeWithTag("members-apply").performClick()
        compose.waitUntil(10_000) { way?.second == true }
        assertEquals(Demo.MEMBERS_EVENT to true, way)
    }

    /** A member (the demo's own account) is never shown the two ways in. */
    @Test
    fun aMemberSeesNoWayIn() {
        compose.setContent { BelfryTheme { InDemo { EventScreen(Demo.MEMBERS_EVENT) } } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("There is no such event here.").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(compose.onAllNodesWithTag("members-signin").fetchSemanticsNodes().isEmpty())
    }
}
