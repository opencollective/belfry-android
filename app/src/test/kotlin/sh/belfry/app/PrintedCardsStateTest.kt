package sh.belfry.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import sh.belfry.core.engine.Demo
import sh.belfry.core.engine.DemoCards
import sh.belfry.core.engine.MemoryStores
import sh.belfry.core.engine.MemoryVault
import sh.belfry.core.engine.Onboarding
import sh.belfry.core.model.CardError
import sh.belfry.core.model.CardFailed
import sh.belfry.core.model.CardForm
import sh.belfry.core.model.CardInfo
import sh.belfry.core.model.CardKind
import sh.belfry.core.model.CardLink
import sh.belfry.core.model.CardWelcome
import sh.belfry.core.net.Community
import sh.belfry.core.net.InviteLink
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Where a printed card's address goes in the app (SPEC 19.72–19.74), on the plain JVM. No
 * network: a made-up belfry whose HTTP calls are answered here, and the demo's own cards.
 */
class PrintedCardsStateTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val asked = CopyOnWriteArrayList<String>()
    private val invite = "AbCdEfGhIjKlMnOpQrStUvWx_-12"
    @Volatile private var cardAnswer = 200 to """{"kind":"group","state":"unclaimed"}"""
    @Volatile private var startAnswer = 202 to """{"sent":true}"""
    @Volatile private var verifyAnswer = 400 to """{"error":"wrong_code","tries_left":4}"""
    private val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        val req = chain.request()
        asked += req.method + " " + req.url.encodedPath + (if (req.header("Authorization") != null) " signed" else "")
        val path = req.url.encodedPath
        val (code, body) = when {
            path.endsWith("/claim-start") -> startAnswer
            path.endsWith("/claim-verify") -> verifyAnswer
            "/cards/hashtag/" in path -> 503 to """{"error":"relay"}"""
            "/cards/" in path -> cardAnswer
            else -> 503 to "{}"
        }
        Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("test").body(body.toResponseBody("application/json".toMediaType())).build()
    }).build()
    private val vault = MemoryVault()
    private val riverside = Community.defaults("riverside.example").copy(name = "Riverside Commons")
    private val onboarding = Onboarding(vault, MemoryStores(), http, scope, { 1_800_000_000L }, ZoneOffset.UTC, discovery = { riverside })
    private val state = AppState(onboarding, scope)
    private val link = CardLink("riverside.example", "K7Q2MX")
    private val form = CardForm(title = "Sourdough starters", about = "Swap starters.", name = "Lou", email = "lou@example.org")

    @After fun tearDown() { state.session.value?.stop(); scope.cancel() }

    private fun until(what: String, test: () -> Boolean) {
        repeat(400) { if (test()) return; Thread.sleep(25) }
        throw AssertionError("never: $what")
    }

    private fun signedOut() {
        state.boot()
        until("signed out") { state.phase.value is Phase.SignedOut }
    }

    @Test
    fun aVisitorsCardIsAskedWithNoKeyAndItsCodeWithThisPhonesKey() = runBlocking<Unit> {
        signedOut()
        state.onCardLink(link)
        assertEquals(link, state.card.value)
        assertEquals(link, onboarding.pendingCard)
        assertEquals(CardInfo.Unclaimed(CardKind.GROUP), state.cardInfo(link))
        assertEquals(listOf("GET /api/v1/cards/K7Q2MX"), asked.toList())
        assertNull("nothing is made before a code is asked", vault.get(Onboarding.V_APPLICANT + ":riverside.example"))

        state.cardStart(link, "lou@example.org")
        assertEquals("POST /api/v1/cards/K7Q2MX/claim-start signed", asked.last())
        assertNotNull(vault.get(Onboarding.V_APPLICANT + ":riverside.example"))

        // a wrong code: in words by its code, nobody signed in, the card's screen stays
        try { state.cardVerify(link, CardKind.GROUP, form, "111111"); fail("a wrong code signed in") } catch (e: CardFailed) {
            assertEquals(CardError.WRONG_CODE, e.code); assertEquals(4, e.triesLeft)
        }
        assertEquals("POST /api/v1/cards/K7Q2MX/claim-verify signed", asked.last())
        assertTrue(state.phase.value is Phase.SignedOut)
        assertEquals(link, state.card.value)

        // someone was first, seen after the code: nobody is signed in; their group is joined through its guest link
        verifyAnswer = 409 to """{"error":"claimed","group":"pod-sourdough-ab12","invite":"$invite","message":"x"}"""
        val lost = try { state.cardVerify(link, CardKind.GROUP, form, "123456"); null } catch (e: CardFailed) { e }!!
        assertEquals(CardError.CLAIMED, lost.code)
        assertNull(state.session.value)
        state.cardJoin(link, lost.group!!, lost.invite!!)
        assertNull(state.card.value)
        assertNull(onboarding.pendingCard)
        assertEquals(Phase.Joining(InviteLink("riverside.example", invite)), state.phase.value)
        assertEquals(CardWelcomed("riverside.example", "pod-sourdough-ab12", CardWelcome.JOINED), state.cardWelcome.value)
    }

    @Test
    fun whatWasTypedIsKeptForItsCardAndACardThatWaitedComesBack() = runBlocking<Unit> {
        signedOut()
        state.onCardLink(link)
        state.keepCardForm(link, form)
        assertEquals(form, state.cardForm(link))
        assertNull(state.cardForm(CardLink("riverside.example", "T9PC4H")))
        // the app was closed with the card open: its screen and its form are back
        val again = AppState(Onboarding(vault, MemoryStores(), http, scope, { 1_800_000_000L }, ZoneOffset.UTC, discovery = { riverside }), scope)
        again.boot()
        until("the card is back") { again.card.value == link }
        assertEquals(form, again.cardForm(link))
        again.forgetCard()
        assertNull(onboarding.pendingCard)
        // an address whose id is not one still opens the card's screen (it says the card isn't known), and nothing is asked
        val bad = CardLink.parse("https://chat.riverside.example/g/nope")!!
        state.onCardLink(bad)
        assertEquals(bad, state.card.value)
        assertEquals(CardInfo.Unknown, state.cardInfo(bad))
        assertTrue(asked.none { "nope" in it })
    }

    @Test
    fun theDemosCardStartsAGroupAndOpensItWithItsWelcome() = runBlocking<Unit> {
        signedOut()
        val demo = CardLink(Demo.DOMAIN, DemoCards.FREE_GROUP)
        state.onCardLink(demo)
        assertEquals(CardInfo.Unclaimed(CardKind.GROUP), state.cardInfo(demo))
        state.cardStart(demo, "lou@example.org")
        state.cardVerify(demo, CardKind.GROUP, form, DemoCards.CODE)
        assertTrue(asked.isEmpty()) // the demo asks no belfry
        val s = state.session.value!!
        assertTrue(s.localOnly)
        assertTrue(state.phase.value is Phase.SignedIn)
        assertNull(state.card.value)
        val (domain, group) = state.openAfter.value!!
        assertEquals(Demo.DOMAIN, domain)
        assertTrue(group, group.startsWith("pod-sourdough-starters"))
        assertEquals(CardWelcomed(Demo.DOMAIN, group, CardWelcome.STARTED), state.cardWelcome.value)
        until("the group is there") { s.world.value.group(group) != null }
        assertEquals("Sourdough starters", s.world.value.group(group)!!.name)

        // signed in to the demo, another of its cards: over the app, in this account
        val topic = CardLink(Demo.DOMAIN, DemoCards.FREE_TOPIC)
        state.onCardLink(topic)
        assertEquals(topic, state.card.value)
        assertTrue(state.phase.value is Phase.SignedIn)
        state.cardStarted(Demo.DOMAIN, "tool-library")
        assertNull(state.card.value)
        assertEquals(CardWelcomed(Demo.DOMAIN, "tool-library", CardWelcome.STARTED), state.cardWelcome.value)
        state.clearCardWelcome()
        assertNull(state.cardWelcome.value)

        // a card of a belfry with no account on this phone: its screen over the app, as a visitor
        state.onCardLink(link)
        assertEquals(link, state.card.value)
        assertEquals(s, state.session.value)
    }
}
