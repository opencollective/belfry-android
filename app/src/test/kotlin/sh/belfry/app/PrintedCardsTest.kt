package sh.belfry.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.belfry.app.ui.chat.ChannelScreen
import sh.belfry.app.ui.kit.LocalNetworkImages
import sh.belfry.app.ui.printed.CardAccount
import sh.belfry.app.ui.printed.CardDoor
import sh.belfry.app.ui.printed.CardPreview
import sh.belfry.app.ui.printed.CardScreen
import sh.belfry.app.ui.printed.CardWelcomeState
import sh.belfry.app.ui.printed.LocalCardWelcome
import sh.belfry.app.ui.theme.BelfryTheme
import sh.belfry.core.engine.Demo
import sh.belfry.core.engine.DemoCards
import sh.belfry.core.engine.OnboardingException
import sh.belfry.core.engine.cards
import sh.belfry.core.model.CardError
import sh.belfry.core.model.CardFailed
import sh.belfry.core.model.CardForm
import sh.belfry.core.model.CardInfo
import sh.belfry.core.model.CardKind
import sh.belfry.core.model.CardLink
import sh.belfry.core.model.CardWelcome
import sh.belfry.core.model.Cards
import sh.belfry.core.model.HashtagState
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Printed cards (SPEC 19.72–19.74, 19.89; Cards canvas "Onboarding from a card, improved"): the rules
 * of the flow (one claim per tap, each refusal in the app's words, what happens to what was typed,
 * the waiting screen's polling, the Confirm link). No network: the screens talk to a [CardDoor] made
 * here, or to the demo session. The pictures are `CardsV2ShotsTest`.
 */
class PrintedCardsTest : DemoShots() {
    private val link = CardLink(Demo.DOMAIN, DemoCards.FREE_GROUP)
    private val topicLink = CardLink(Demo.DOMAIN, DemoCards.FREE_TOPIC)
    private val invite = DemoCards.invite(DemoCards.CLAIMED)
    private val sourdough = CardForm(title = "Sourdough starters", about = "Swap starters, share flour, bake together on Saturday mornings.", name = "Lou", email = "lou@example.org")
    private val tools = CardForm(about = "Borrow drills, ladders and the big saw instead of buying them. Say what you need, say what you lend.", hashtag = "tool-library", name = "Dev", email = "dev@example.org")
    private val lina = CardAccount(Demo.me("Lina Moreau").pubkey, "Lina Moreau", address = "lina@demo.belfry.sh")

    private fun field(tag: String) = compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(tag)))
    private fun waitFor(tag: String) = compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun waitText(text: String) = compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun show(door: CardDoor, l: CardLink = link) {
        compose.setContent { BelfryTheme { CompositionLocalProvider(LocalNetworkImages provides false) { CardScreen(door, l) } } }
    }

    /** onb-group-done: the demo's group card claimed by the demo's account; the group opens with its facts and welcome. */
    @Test
    fun claimDone() {
        val group = runBlocking { session.cards.claim(DemoCards.FREE_GROUP, CardKind.GROUP, sourdough) }
        settle()
        assertEquals("Sourdough starters", world.group(group)!!.name)
        var dismissed = 0
        demoShot("printed-card-claim-done") {
            CompositionLocalProvider(LocalCardWelcome provides CardWelcomeState(CardWelcomed(Demo.DOMAIN, group, CardWelcome.STARTED, DemoCards.FREE_GROUP)) { dismissed++ }) { ChannelScreen(group) }
        }
        compose.onNodeWithTag("card-welcome").assertExists()
        compose.onNodeWithText("Steward: you · 1 person").assertExists()
        compose.onNodeWithText("Card K7Q2MX: anyone who scans it joins here").assertExists()
        compose.onNodeWithText("Welcome to Sourdough starters!", substring = true).assertExists()
        compose.onNodeWithText("You created it and you look after it. Say hi and share something to get it going.", substring = true).assertExists()
        // the first line, as the belfry writes it (the demo signs it with its groups key)
        compose.onNodeWithText("You created the group from a printed card", useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription("Close the welcome").performClick()
        compose.waitUntil(10_000) { dismissed == 1 }
    }

    /** The first open after joining a topic from its card: the topic's welcome, in no other channel. */
    @Test
    fun topicJoinedWelcome() {
        val topic = runBlocking { session.cards.claim(DemoCards.FREE_TOPIC, CardKind.TOPIC, tools) }
        settle()
        assertEquals("tool-library", topic)
        demoShot("printed-card-topic-joined") {
            CompositionLocalProvider(LocalCardWelcome provides CardWelcomeState(CardWelcomed(Demo.DOMAIN, topic, CardWelcome.JOINED)) {}) { ChannelScreen(topic) }
        }
        compose.onNodeWithText("No introductions needed: share what you know.", substring = true).assertExists()
    }

    @Test
    fun theWelcomeIsOnlyInItsOwnGroup() {
        compose.setContent {
            BelfryTheme { InDemo { CompositionLocalProvider(LocalCardWelcome provides CardWelcomeState(CardWelcomed(Demo.DOMAIN, Demo.POD_OPEN, CardWelcome.STARTED)) {}) { ChannelScreen(Demo.GARDEN) } } }
        }
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithTag("card-welcome").fetchSemanticsNodes().isEmpty())
    }

    // ---- the rules ---------------------------------------------------------------------------------------

    /** Signed in: one tap, one claim, however often the button is tapped while the belfry answers. */
    @Test
    fun oneClaimPerTap() {
        val claims = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        var sent: CardForm? = null
        show(CardDoor(Demo.NAME, Demo.DOMAIN, lina, info = { CardInfo.Unclaimed(CardKind.GROUP) }, hashtag = { HashtagState.FREE },
            claim = { _, f -> claims.incrementAndGet(); sent = f; gate.await() }))
        waitFor("card-start")
        compose.onNodeWithText("Start a group").assertExists()
        compose.onNodeWithText("Card K7Q2MX").assertExists()
        // who you are, below Create, with Log out
        compose.onNodeWithText("Lina Moreau").assertExists()
        compose.onNodeWithText("lina@demo.belfry.sh").assertExists()
        compose.onNodeWithText("Create the group").assertExists()
        // signed in: no name, no e-mail asked
        assertTrue(compose.onAllNodesWithTag("card-email").fetchSemanticsNodes().isEmpty())
        // a group card has no summary by e-mail
        assertTrue(compose.onAllNodesWithTag("card-summary").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag("card-start").assertIsNotEnabled()
        field("card-title").performTextInput("Sourdough starters")
        compose.onNodeWithTag("card-start").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("card-start").performClick()
        compose.waitUntil(10_000) { claims.get() == 1 }
        compose.onNodeWithTag("card-start").assertIsNotEnabled()
        compose.onNodeWithTag("card-start").performClick()
        compose.onNodeWithTag("card-start").performClick()
        gate.complete(Unit)
        compose.waitForIdle()
        assertEquals(1, claims.get())
        assertEquals("Sourdough starters", sent!!.title)
        assertEquals("""{"title":"Sourdough starters","about":""}""", Cards.claimBody(CardKind.GROUP, sent!!).toString())
    }

    /** A refusal is said in the app's words and the form stays as typed; "someone was first" and "retired" have their own screens. */
    @Test
    fun refusalsInWordsAndTheFormKept() {
        var answer: CardFailed? = CardFailed(CardError.NO_PASS)
        val kept = CopyOnWriteArrayList<CardForm>()
        val drops = AtomicInteger()
        val joined = CopyOnWriteArrayList<String>()
        show(CardDoor(Demo.NAME, Demo.DOMAIN, lina, info = { CardInfo.Unclaimed(CardKind.GROUP) }, hashtag = { HashtagState.FREE },
            claim = { _, _ -> answer?.let { throw it } },
            keepForm = { kept += it }, dropForm = { drops.incrementAndGet() },
            preview = { CardPreview("Sourdough starters", "Swap starters.", "Ben Okafor") },
            join = { _, group, token -> joined += "$group $token" }))
        waitFor("card-start")
        field("card-title").performTextInput("Sourdough starters")
        field("card-about").performTextInput("Swap starters.")
        // what is typed is kept on the device as it changes
        compose.waitUntil(10_000) { kept.lastOrNull()?.about == "Swap starters." }
        for ((code, words) in listOf(
            CardError.NO_PASS to "Only members of this belfry can start a group while signed in. Sign out to start it as a guest with your e-mail.",
            CardError.GUEST_NOT_SAVED to "Save your account with your e-mail first, then scan the card again.",
            CardError.UNVERIFIED to "Confirm your e-mail address first, then scan the card again.",
            CardError.AGENT to "An AI agent can’t start it. Use a person’s account.",
            CardError.SIGNER to "The belfry is busy. Try again in a minute.",
            CardError.TOO_MANY to "Too many tries for now. Try again in a few minutes.",
            CardError.OFFLINE to "No connection. Try again.",
            CardError.FAILED to "That did not work. Try again.",
        )) {
            answer = CardFailed(code)
            compose.onNodeWithTag("card-start").performScrollTo().performClick()
            waitText(words)
            // the form is as it was typed, and can be sent again
            compose.onNodeWithText("Sourdough starters").assertExists()
            compose.onNodeWithText("Swap starters.").assertExists()
            compose.onNodeWithTag("card-start").assertIsEnabled()
        }
        assertEquals(0, drops.get())

        // someone was first: their group, and Join takes the card's guest link
        answer = CardFailed(CardError.CLAIMED, group = "pod-sourdough-ab12", invite = invite)
        compose.onNodeWithTag("card-start").performScrollTo().performClick()
        waitFor("card-join")
        compose.onNodeWithText("Someone just started this group").assertExists()
        compose.onNodeWithText("Your text wasn’t used; you can join theirs.").assertExists()
        waitFor("card-preview")
        compose.onNodeWithText("Steward: Ben Okafor").assertExists()
        assertEquals(1, drops.get())
        compose.onNodeWithTag("card-join").performClick()
        compose.waitUntil(10_000) { joined.isNotEmpty() }
        assertEquals(listOf("pod-sourdough-ab12 $invite"), joined.toList())
    }

    @Test
    fun aCardRetiredWhileWritingSaysSo() {
        show(CardDoor(Demo.NAME, Demo.DOMAIN, lina, info = { CardInfo.Unclaimed(CardKind.GROUP) }, hashtag = { HashtagState.FREE }, claim = { _, _ -> throw CardFailed(CardError.RETIRED) }))
        waitFor("card-start")
        field("card-title").performTextInput("Sourdough starters")
        compose.onNodeWithTag("card-start").performScrollTo().performClick()
        waitFor("card-dead")
        compose.onNodeWithText("This group is closed to new people").assertExists()
        compose.onNodeWithText("The card was retired. People already in the group carry on there.").assertExists()
        compose.onNodeWithText("Visit ${Demo.NAME}").assertExists()
    }

    /** What the belfry says of a card picks the screen: its guest link, a dead end with its reason, or the belfry's page. */
    @Test
    fun eachStateOfACard() {
        val joined = CopyOnWriteArrayList<String>()
        val drops = AtomicInteger()
        val door = CardDoor(Demo.NAME, Demo.DOMAIN, null, info = { CardInfo.Claimed(CardKind.TOPIC, "tool-library", "tool-library", invite) }, hashtag = { HashtagState.FREE },
            dropForm = { drops.incrementAndGet() }, join = { kind, group, token -> joined += "${kind.word} $group $token" })
        // a claimed card is a guest link: taken at once, nothing to tap
        show(door)
        compose.waitUntil(10_000) { joined.isNotEmpty() }
        assertEquals(listOf("topic tool-library $invite"), joined.toList())
        compose.onNodeWithTag("card-opening").assertExists()
    }

    @Test
    fun deadEnds() {
        val asks = AtomicInteger()
        var failure: CardFailed? = CardFailed(CardError.OFFLINE)
        val closed = AtomicInteger()
        show(CardDoor(Demo.NAME, Demo.DOMAIN, null, info = { asks.incrementAndGet(); failure?.let { throw it }; CardInfo.Archived(CardKind.GROUP, "Book club") }, hashtag = { HashtagState.FREE },
            close = { closed.incrementAndGet() }))
        waitFor("card-again")
        compose.onNodeWithText("No connection").assertExists()
        compose.onNodeWithText("Belfry needs the internet to open the card. What you typed stays here.").assertExists()
        // the belfry says "too many": its own words, never "no connection"
        failure = CardFailed(CardError.TOO_MANY)
        compose.onNodeWithTag("card-again").performClick()
        waitText("Too many tries for now")
        assertEquals(2, asks.get())
        // Try again asks again
        failure = null
        compose.onNodeWithTag("card-again").performClick()
        waitText("Book club has ended")
        compose.onNodeWithText("The group was archived.").assertExists()
        assertTrue(compose.onAllNodesWithTag("card-again").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag("card-close").performClick()
        compose.waitUntil(10_000) { closed.get() == 1 }
    }

    @Test
    fun anAddressThatIsNoCardIsNeverAsked() {
        val asks = AtomicInteger()
        show(CardDoor(Demo.NAME, Demo.DOMAIN, null, info = { asks.incrementAndGet(); CardInfo.Unclaimed(CardKind.GROUP) }, hashtag = { HashtagState.FREE }), CardLink(Demo.DOMAIN, ""))
        waitFor("card-dead")
        compose.onNodeWithText("This card isn’t known here").assertExists()
        compose.onNodeWithText("Check that you scanned a card of ${Demo.DOMAIN}.").assertExists()
        assertEquals(0, asks.get())
    }

    /** onb-topic-form: the hashtag asked directly (cleaned as typed), checked live, no topic-in-words field, no summary. */
    @Test
    fun aTopicsHashtag() {
        val checked = CopyOnWriteArrayList<String>()
        var down = false
        var sent: CardForm? = null
        show(CardDoor(Demo.NAME, Demo.DOMAIN, lina, info = { CardInfo.Unclaimed(CardKind.TOPIC) },
            hashtag = { h -> checked += h; if (down) HashtagState.UNKNOWN else if (h == "tool-library" || h == "general") HashtagState.TAKEN else HashtagState.FREE },
            claim = { _, f -> sent = f }), topicLink)
        waitFor("card-start")
        compose.onNodeWithText("Start a topic").assertExists()
        compose.onNodeWithText("Card T9PC4H · topic").assertExists()
        compose.onNodeWithText("Nobody has scanned this card yet. The topic starts with you.").assertExists()
        // no topic in words, no group name
        assertTrue(compose.onAllNodesWithTag("card-title").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag("card-start").assertIsNotEnabled()
        // typed as words: spaces become dashes, capitals and punctuation go (Xavier, 2026-11-24)
        field("card-hashtag").performTextInput("Tool Library!")
        compose.onNodeWithText("tool-library").assertExists()
        waitText("Taken: try another one")
        compose.onNodeWithTag("card-start").assertIsNotEnabled()
        // nothing else is offered: the person picks another
        field("card-hashtag").performTextReplacement("Café  Réparé")
        compose.onNodeWithText("cafe-repare").assertExists()
        waitText("✓ Available · this is the channel’s name")
        compose.onNodeWithTag("card-start").performScrollTo().assertIsEnabled()
        field("card-hashtag").performTextReplacement("x")
        waitText("Letters, digits and dashes, 2 to 30")
        compose.onNodeWithTag("card-start").assertIsNotEnabled()

        // the belfry can't check: never "available"; Try again asks again
        down = true
        field("card-hashtag").performTextReplacement("drills ")
        compose.onNodeWithText("drills-").assertExists()
        waitText("Couldn’t check just now")
        // the check never ran on the trailing dash
        assertTrue(checked.toString(), checked.none { it.endsWith("-") })
        compose.onNodeWithTag("card-start").assertIsNotEnabled()
        down = false
        compose.onNodeWithTag("card-recheck").performClick()
        waitText("✓ Available · this is the channel’s name")
        // no summary by e-mail on the start form (Xavier, Cards v80): it's in the topic's settings
        assertTrue(compose.onAllNodesWithText("Summary by e-mail").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("You’ll look after this topic.").assertExists()
        compose.onNodeWithTag("card-start").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("card-start").performClick()
        compose.waitUntil(10_000) { sent != null }
        // sent without the trailing dash, and without a title (the belfry names it by its hashtag)
        assertEquals("""{"about":"","hashtag":"drills"}""", Cards.claimBody(CardKind.TOPIC, sent!!).toString())
    }

    private fun visitorDoor(
        starts: MutableList<CardForm> = CopyOnWriteArrayList(), codes: MutableList<String> = CopyOnWriteArrayList(),
        verify: suspend (String) -> Unit = {}, poll: suspend () -> Boolean = { false }, now: () -> Long = { 1_800_000_000L },
        kept: CardForm? = null, retry: suspend () -> Boolean = { false },
    ) = CardDoor(Demo.NAME, Demo.DOMAIN, null, info = { CardInfo.Unclaimed(CardKind.GROUP) }, hashtag = { HashtagState.FREE },
        start = { _, f -> starts += f }, verify = { _, _, code -> codes += code; verify(code) }, poll = poll, now = now, retry = retry,
        keptForm = { kept })

    private fun toTheCode() {
        waitFor("card-start")
        field("card-title").performTextInput("Sourdough starters")
        field("card-name").performTextInput("Lou")
        field("card-email").performTextInput("lou@example.org")
        compose.onNodeWithTag("card-start").performScrollTo().performClick()
        waitFor("code-boxes")
    }

    /** A visitor: name and e-mail, the code screen (onb-code), each refusal of the code in words; what was typed is there on the way back. */
    @Test
    fun aVisitorsCode() {
        val starts = CopyOnWriteArrayList<CardForm>()
        val codes = CopyOnWriteArrayList<String>()
        var answer: Exception? = CardFailed(CardError.WRONG_CODE, triesLeft = 3)
        show(visitorDoor(starts, codes, verify = { answer?.let { throw it } }, retry = { answer?.let { throw it }; true }, kept = CardForm(title = "Sourdough starters")))
        waitFor("card-start")
        // what was typed before comes back
        compose.onNodeWithText("Sourdough starters").assertExists()
        compose.onNodeWithText("You’ll look after this group. We’ll send a 6-digit code to confirm your e-mail.").assertExists()
        compose.onNodeWithTag("card-start").assertIsNotEnabled()
        field("card-name").performTextInput("Lou")
        field("card-email").performTextInput("lou@example")
        compose.onNodeWithTag("card-start").assertIsNotEnabled()
        field("card-email").performTextReplacement("lou@example.org")
        compose.onNodeWithTag("card-start").performScrollTo().performClick()
        waitFor("code-boxes")
        // the whole form goes with the request for a code (the Confirm button creates it from there)
        assertEquals(listOf("lou@example.org"), starts.map { it.email })
        assertEquals("Lou", starts.single().name)
        assertEquals("Sourdough starters", starts.single().title)
        compose.onNodeWithText("Check your e-mail").assertExists()
        compose.onNodeWithText("To create Sourdough starters, enter the 6-digit code we sent to lou@example.org.").assertExists()
        compose.onNodeWithText("or tap Confirm in the e-mail. This screen moves on by itself once you have.").assertExists()
        compose.onNodeWithText("Not there? Look in your spam folder.").assertExists()

        // the sixth digit sends, once
        compose.onNodeWithTag("code-boxes").performTextInput("111111")
        waitText("That code is not right. 3 tries left.")
        assertEquals(listOf("111111"), codes.toList())
        var n = 0
        for ((failure, words) in listOf(
            CardFailed(CardError.EXPIRED) to "That code has expired. Send a new one.",
            CardFailed(CardError.KEY_TAKEN) to "That did not work. Send a new code and try again.",
            CardFailed(CardError.SIGNER) to "The belfry is busy. Send a new code in a minute and try again.",
            CardFailed(CardError.OWN_KEY) to "This address has an account that signs in with its own key. Sign in with it, then scan the card again.",
            CardFailed(CardError.AGENT) to "An AI agent can’t start it. Use a person’s account.",
            CardFailed(CardError.INVALID, field = "name") to "Please give your name.",
        )) {
            answer = failure
            compose.onNodeWithTag("code-boxes").performTextInput((200000 + ++n).toString())
            waitText(words)
            assertEquals(1 + n, codes.size)
        }
        // "Use another address": the form again, as typed
        compose.onNodeWithText("Use another address").performClick()
        waitFor("card-start")
        compose.onNodeWithText("Sourdough starters").assertExists()
        compose.onNodeWithText("Lou").assertExists()
        compose.onNodeWithText("lou@example.org").assertExists()
        compose.onNodeWithTag("card-start").performScrollTo().performClick()
        waitFor("code-boxes")
        assertEquals(2, starts.size)

        // the code was right but this phone could not reach the signer: no new code, Try again
        answer = OnboardingException("signer", "The belfry's signer did not answer. Check your connection and try again.")
        compose.onNodeWithTag("code-boxes").performTextInput("333333")
        waitFor("card-retry")
        compose.onNodeWithText("Sourdough starters is created").assertExists()
        answer = null
        compose.onNodeWithTag("card-retry").performClick()
        compose.waitForIdle()
        assertEquals(2, starts.size)
        assertEquals("333333", codes.last())
    }

    // ---- the waiting screen: claim-poll (SPEC 19.89) ----------------------------------------------------

    /** It asks while the code screen is shown, one ask at a time, and stops when the screen is left. */
    @Test
    fun theWaitingScreenPollsWhileShownAndStopsOnLeaving() {
        val polls = AtomicInteger()
        val answers = Channel<Boolean>(Channel.UNLIMITED)
        var visible by mutableStateOf(true)
        val door = visitorDoor(poll = { polls.incrementAndGet(); answers.receive() })
        compose.setContent { BelfryTheme { CompositionLocalProvider(LocalNetworkImages provides false) { if (visible) CardScreen(door, link) } } }
        toTheCode()
        compose.waitUntil(10_000) { polls.get() == 1 }
        // one ask at a time: nothing more until the belfry answered
        Thread.sleep(300); compose.mainClock.advanceTimeBy(10_000)
        assertEquals(1, polls.get())
        answers.trySend(false) // 202: still waiting
        compose.waitUntil(10_000) { polls.get() == 2 }
        // no word on the screen while waiting
        assertTrue(compose.onAllNodesWithTag("card-error").fetchSemanticsNodes().isEmpty())
        // the screen goes: the asking stops
        compose.runOnIdle { visible = false }
        compose.waitForIdle()
        answers.trySend(false)
        Thread.sleep(300); compose.mainClock.advanceTimeBy(30_000); compose.waitForIdle()
        assertEquals(2, polls.get())
    }

    /** "Use another address" stops it too; the form step never asks. */
    @Test
    fun theFormNeverPolls() {
        val polls = AtomicInteger()
        show(visitorDoor(poll = { polls.incrementAndGet(); false }))
        waitFor("card-start")
        Thread.sleep(300); compose.mainClock.advanceTimeBy(30_000); compose.waitForIdle()
        assertEquals(0, polls.get())
    }

    /** 410: the claim ran out; said in the app's words, and nothing more is asked. */
    @Test fun aPollsExpiredStopsAndSaysSo() = pollStops(CardFailed(CardError.EXPIRED), "The code and the Confirm button have expired. Send a new code.")

    /** 404: the belfry knows no claim of this key for this card any more. */
    @Test fun aPollsNotFoundStopsAndSaysSo() = pollStops(CardFailed(CardError.UNKNOWN), "This request isn’t known any more. Send a new code.")

    private fun pollStops(failure: CardFailed, words: String) {
        val polls = AtomicInteger()
        val answers = Channel<CardFailed?>(Channel.UNLIMITED)
        show(visitorDoor(poll = { polls.incrementAndGet(); answers.receive()?.let { throw it }; false }))
        toTheCode()
        compose.waitUntil(10_000) { polls.get() == 1 }
        // a busy belfry or no connection: asked again, nothing said
        answers.trySend(CardFailed(CardError.SIGNER))
        compose.waitUntil(10_000) { polls.get() == 2 }
        answers.trySend(CardFailed(CardError.OFFLINE))
        compose.waitUntil(10_000) { polls.get() == 3 }
        assertTrue(compose.onAllNodesWithTag("card-error").fetchSemanticsNodes().isEmpty())
        answers.trySend(failure)
        waitText(words)
        Thread.sleep(300); compose.mainClock.advanceTimeBy(30_000); compose.waitForIdle()
        assertEquals(3, polls.get())
    }

    /** The lead's rule: this phone holds the session another way (its own Confirm link): the poll stops and says nothing. */
    @Test
    fun aPollThatFindsTheSessionHereSaysNothing() {
        val polls = AtomicInteger()
        show(visitorDoor(poll = { polls.incrementAndGet(); true }))
        toTheCode()
        compose.waitUntil(10_000) { polls.get() == 1 }
        Thread.sleep(300); compose.mainClock.advanceTimeBy(30_000); compose.waitForIdle()
        assertEquals(1, polls.get())
        assertTrue(compose.onAllNodesWithTag("card-error").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("expired", substring = true).fetchSemanticsNodes().isEmpty())
    }

    /** After the claim's 15 minutes nothing more is asked, and the screen says the code has expired. */
    @Test
    fun theWaitingStopsAfterFifteenMinutes() {
        val clock = AtomicLong(1_800_000_000L)
        val polls = AtomicInteger()
        val answers = Channel<Boolean>(Channel.UNLIMITED)
        show(visitorDoor(poll = { polls.incrementAndGet(); answers.receive() }, now = { clock.get() }))
        toTheCode()
        compose.waitUntil(10_000) { polls.get() == 1 }
        clock.addAndGet(Cards.CLAIM_SECONDS)
        answers.trySend(false)
        waitText("The code and the Confirm button have expired. Send a new code.")
        Thread.sleep(300); compose.mainClock.advanceTimeBy(30_000); compose.waitForIdle()
        assertEquals(1, polls.get())
    }

    /** A card opened again less than 15 minutes after its code was asked: the waiting screen again, asking. */
    @Test
    fun aCardReopenedWhileItsCodeLivesWaitsAgain() {
        val polls = AtomicInteger()
        show(visitorDoor(poll = { polls.incrementAndGet(); false }, kept = sourdough.copy(sentAt = 1_800_000_000L - 60)))
        waitFor("code-boxes")
        compose.onNodeWithText("To create Sourdough starters, enter the 6-digit code we sent to lou@example.org.").assertExists()
        compose.waitUntil(10_000) { polls.get() >= 1 }
    }

    // ---- the e-mail's Confirm link (SPEC 19.89) --------------------------------------------------------------

    private val token = "Tk_" + "c".repeat(40)
    private val confirmLink = CardLink(Demo.DOMAIN, DemoCards.FREE_GROUP, token)

    /** The same phone: claim-confirm once with the token; it is in, the card asks nothing else. */
    @Test
    fun aConfirmLinkOnThisPhoneGoesIn() {
        val sent = CopyOnWriteArrayList<String>()
        val infos = AtomicInteger()
        show(CardDoor(Demo.NAME, Demo.DOMAIN, null, info = { infos.incrementAndGet(); CardInfo.Unclaimed(CardKind.GROUP) }, hashtag = { HashtagState.FREE },
            confirm = { sent += it; true }), confirmLink)
        compose.waitUntil(10_000) { sent.isNotEmpty() }
        compose.waitForIdle()
        assertEquals(listOf(token), sent.toList())
        assertEquals(0, infos.get())
        assertTrue(compose.onAllNodesWithTag("card-start").fetchSemanticsNodes().isEmpty())
    }

    /** Another phone's claim: "Confirmed" with the web's words, and Close. */
    @Test
    fun aConfirmLinkForAnotherDeviceSaysConfirmed() {
        val closed = AtomicInteger()
        show(CardDoor(Demo.NAME, Demo.DOMAIN, null, info = { CardInfo.Unclaimed(CardKind.GROUP) }, hashtag = { HashtagState.FREE },
            confirm = { false }, close = { closed.incrementAndGet() }), confirmLink)
        waitFor("card-confirmed")
        compose.onNodeWithText("Confirmed").assertExists()
        compose.onNodeWithText("It’s created. Go back to the page where you scanned the card: it opens there by itself.").assertExists()
        compose.onNodeWithTag("card-close").performClick()
        compose.waitUntil(10_000) { closed.get() == 1 }
    }

    /** A link that didn't work says why; a busy belfry can be tried again with the same link. */
    @Test
    fun aConfirmLinkThatDidNotWork() {
        val tries = AtomicInteger()
        var answer: CardFailed = CardFailed(CardError.SIGNER)
        show(CardDoor(Demo.NAME, Demo.DOMAIN, null, info = { CardInfo.Unclaimed(CardKind.GROUP) }, hashtag = { HashtagState.FREE },
            confirm = { tries.incrementAndGet(); throw answer }), confirmLink)
        waitText("This link didn’t work")
        compose.onNodeWithText("The belfry is busy. Try again in a minute: the link still works.").assertExists()
        answer = CardFailed(CardError.EXPIRED)
        compose.onNodeWithTag("card-again").performClick()
        waitText("It has expired, or it was used already. Scan the card again to start over.")
        assertEquals(2, tries.get())
        assertTrue(compose.onAllNodesWithTag("card-again").fetchSemanticsNodes().isEmpty())
    }

    /** Signed in: Log out asks first, then signs out (the card stays, to start it as someone else). */
    @Test
    fun logOutAsksFirst() {
        val outs = AtomicInteger()
        show(CardDoor(Demo.NAME, Demo.DOMAIN, lina, info = { CardInfo.Unclaimed(CardKind.GROUP) }, hashtag = { HashtagState.FREE }, logOut = { outs.incrementAndGet() }))
        waitFor("card-log-out")
        compose.onNodeWithTag("card-log-out").performScrollTo().performClick()
        waitFor("card-log-out-confirm")
        assertEquals(0, outs.get())
        compose.onNodeWithTag("card-log-out-confirm").performClick()
        compose.waitUntil(10_000) { outs.get() == 1 }
    }
}
