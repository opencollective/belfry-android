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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.belfry.app.ui.chat.ChannelScreen
import sh.belfry.app.ui.kit.LocalNetworkImages
import sh.belfry.app.ui.printed.CardAccount
import sh.belfry.app.ui.printed.CardCodeContent
import sh.belfry.app.ui.printed.CardDeadContent
import sh.belfry.app.ui.printed.CardDoor
import sh.belfry.app.ui.printed.CardOpeningContent
import sh.belfry.app.ui.printed.CardPreview
import sh.belfry.app.ui.printed.CardScreen
import sh.belfry.app.ui.printed.CardSignerContent
import sh.belfry.app.ui.printed.CardStartContent
import sh.belfry.app.ui.printed.CardTakenContent
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Printed cards (SPEC 19.72–19.74; canvas "Belfry · Group cards"): every screen in demo mode, and
 * the rules of the flow (one claim per tap, each refusal in the app's words, what happens to what
 * was typed). No network: the screens talk to a [CardDoor] made here, or to the demo session.
 */
class PrintedCardsTest : DemoShots() {
    private val link = CardLink(Demo.DOMAIN, DemoCards.FREE_GROUP)
    private val topicLink = CardLink(Demo.DOMAIN, DemoCards.FREE_TOPIC)
    private val invite = DemoCards.invite(DemoCards.CLAIMED)
    private val sourdough = CardForm(title = "Sourdough starters", about = "Swap starters, share flour, bake together on Saturday mornings.", name = "Lou", email = "lou@example.org")
    private val tools = CardForm(title = "Tool library", about = "Borrow drills, ladders and the big saw instead of buying them. Say what you need, say what you lend.", hashtag = "tool-library", name = "Dev", email = "dev@example.org")
    private val lina = CardAccount(Demo.me("Lina Moreau").pubkey, "Lina Moreau")

    private fun field(tag: String) = compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(tag)))
    private fun waitFor(tag: String) = compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun waitText(text: String) = compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun show(door: CardDoor, l: CardLink = link) {
        compose.setContent { BelfryTheme { CompositionLocalProvider(LocalNetworkImages provides false) { CardScreen(door, l) } } }
    }

    // ---- every screen ----------------------------------------------------------------------------------

    @Test fun claimVisitor() = shot("printed-card-claim-visitor") {
        CardStartContent(CardKind.GROUP, Demo.NAME, null, sourdough, {}, {}, {}, {}, {}, null, false, null, null, true, {}, {})
    }

    @Test fun claimSignedIn() = shot("printed-card-claim-signed-in") {
        CardStartContent(CardKind.GROUP, Demo.NAME, lina, sourdough, {}, {}, {}, {}, {}, null, false, null, null, true, {}, {})
    }

    @Test fun claimTopic() = shot("printed-card-claim-topic") {
        CardStartContent(CardKind.TOPIC, Demo.NAME, null, tools, {}, {}, {}, {}, {}, Cards.hashtagLine(HashtagState.FREE, false, false), false, null, null, true, {}, {})
    }

    @Test fun claimTopicTaken() = shot("printed-card-claim-topic-taken") {
        CardStartContent(CardKind.TOPIC, Demo.NAME, lina, tools.copy(hashtag = "general", summary = Cards.MONTHLY), {}, {}, {}, {}, {}, Cards.hashtagLine(HashtagState.TAKEN, false, true), true, null,
            Cards.errorText(CardFailed(CardError.HASHTAG_TAKEN), CardKind.TOPIC), false, {}, {})
    }

    @Test fun claimTopicNotChecked() = shot("printed-card-claim-topic-not-checked") {
        CardStartContent(CardKind.TOPIC, Demo.NAME, lina, tools, {}, {}, {}, {}, {}, Cards.hashtagLine(HashtagState.UNKNOWN, false, false), false, {}, null, false, {}, {})
    }

    @Test fun confirmCode() = shot("printed-card-confirm-code") {
        CardCodeContent("lou@example.org", "Sourdough starters", null, null, { false }, {}, {}, initial = "427")
    }

    @Test fun confirmCodeWrong() = shot("printed-card-confirm-code-wrong") {
        CardCodeContent("lou@example.org", "Sourdough starters", Cards.errorText(CardFailed(CardError.WRONG_CODE, triesLeft = 3)), null, { false }, {}, {})
    }

    @Test fun taken() = shot("printed-card-taken") {
        CardTakenContent(CardKind.GROUP, CardPreview("Sourdough starters", "Swap starters, share flour, bake together on Saturday mornings.", "Ben Okafor")) {}
    }

    @Test fun closed() = shot("printed-card-closed") {
        CardDeadContent(Cards.retiredTitle(CardKind.GROUP, "Sourdough starters"), Cards.retiredText(CardKind.GROUP), Demo.NAME, null, {}, {})
    }

    @Test fun archived() = shot("printed-card-archived") {
        CardDeadContent(Cards.archivedTitle(CardKind.GROUP, "Book club"), Cards.archivedText(CardKind.GROUP), Demo.NAME, null, {}, {})
    }

    @Test fun offline() = shot("printed-card-offline") { CardDeadContent(Cards.OFFLINE_TITLE, Cards.OFFLINE_TEXT, Demo.NAME, {}, {}, {}) }

    @Test fun unknown() = shot("printed-card-unknown") { CardDeadContent(Cards.UNKNOWN_TITLE, Cards.unknownText(Demo.DOMAIN), Demo.NAME, null, {}, {}) }

    @Test fun tooMany() = shot("printed-card-too-many") { CardDeadContent(Cards.BUSY_TITLE, Cards.BUSY_TEXT, Demo.NAME, {}, {}, {}) }

    @Test fun signerNotReached() = shot("printed-card-signer") {
        CardSignerContent("Sourdough starters", "The belfry's signer did not answer. Check your connection and try again.", null) {}
    }

    @Test fun opening() = shot("printed-card-opening") { CardOpeningContent() }

    /** cards/claim-done: the demo's group card claimed by the demo's account; the group opens with its welcome. */
    @Test
    fun claimDone() {
        val group = runBlocking { session.cards.claim(DemoCards.FREE_GROUP, CardKind.GROUP, sourdough) }
        settle()
        assertEquals("Sourdough starters", world.group(group)!!.name)
        var dismissed = 0
        demoShot("printed-card-claim-done") {
            CompositionLocalProvider(LocalCardWelcome provides CardWelcomeState(CardWelcomed(Demo.DOMAIN, group, CardWelcome.STARTED)) { dismissed++ }) { ChannelScreen(group) }
        }
        compose.onNodeWithTag("card-welcome").assertExists()
        compose.onNodeWithText("Welcome to Sourdough starters!", substring = true).assertExists()
        compose.onNodeWithText("You started it. Say hi and share something to get it going: a photo, a tip, a question.", substring = true).assertExists()
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
        compose.onNodeWithText("Start this group").assertExists()
        compose.onNodeWithText("Signed in as Lina Moreau").assertExists()
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

    /** A topic card: the topic first, the hashtag suggested from it and checked live, the next free one when taken, editable. */
    @Test
    fun aTopicsHashtag() {
        val checked = CopyOnWriteArrayList<String>()
        var down = false
        var sent: CardForm? = null
        show(CardDoor(Demo.NAME, Demo.DOMAIN, lina, info = { CardInfo.Unclaimed(CardKind.TOPIC) },
            hashtag = { h -> checked += h; if (down) HashtagState.UNKNOWN else if (h == "tool-library" || h == "tool-library-2" || h == "general") HashtagState.TAKEN else HashtagState.FREE },
            claim = { _, f -> sent = f }), topicLink)
        waitFor("card-start")
        compose.onNodeWithText("Start this topic").assertExists()
        compose.onNodeWithText("You found a new topic card. No introductions needed: people come and go and share what they know.").assertExists()
        field("card-title").performTextInput("Tool library")
        // suggested from the topic; taken, and -2 too: the next free one is offered and said available
        waitText("✓ Available · suggested from the topic, you can change it")
        compose.onNodeWithText("tool-library-3").assertExists()
        assertEquals(listOf("tool-library", "tool-library-2", "tool-library-3", "tool-library-3"), checked.toList())
        compose.onNodeWithTag("card-start").performScrollTo().assertIsEnabled()

        // changed by hand to a taken one: said so, nothing else is offered, and it can't be started
        field("card-hashtag").performTextReplacement("General")
        waitText("Taken: try another one")
        compose.onNodeWithText("General").assertExists()
        compose.onNodeWithTag("card-start").assertIsNotEnabled()
        field("card-hashtag").performTextReplacement("x")
        waitText("Letters, digits and dashes, 2 to 30")
        compose.onNodeWithTag("card-start").assertIsNotEnabled()

        // the belfry can't check: never "available"; Try again asks again
        down = true
        field("card-hashtag").performTextReplacement("drills")
        waitText("Couldn’t check just now")
        compose.onNodeWithTag("card-start").assertIsNotEnabled()
        down = false
        compose.onNodeWithTag("card-recheck").performClick()
        waitText("✓ Available")
        // no summary by e-mail on the start form (Xavier, Cards v80): it's in the topic's settings
        assertTrue(compose.onAllNodesWithText("Summary by e-mail").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithTag("card-summary").fetchSemanticsNodes().isEmpty())
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

    /** A topic card: the topic first, the hashtag suggested from it and checked live, the next free one when taken, editable. */
    @Test
    fun aTopicsHashtag() {
        val checked = CopyOnWriteArrayList<String>()
        var down = false
        var sent: CardForm? = null
        show(CardDoor(Demo.NAME, Demo.DOMAIN, lina, info = { CardInfo.Unclaimed(CardKind.TOPIC) },
            hashtag = { h -> checked += h; if (down) HashtagState.UNKNOWN else if (h == "tool-library" || h == "tool-library-2" || h == "general") HashtagState.TAKEN else HashtagState.FREE },
            claim = { _, f -> sent = f }), topicLink)
        waitFor("card-start")
        compose.onNodeWithText("Start this topic").assertExists()
        compose.onNodeWithText("You found a new topic card. No introductions needed: people come and go and share what they know.").assertExists()
        field("card-title").performTextInput("Tool library")
        // suggested from the topic; taken, and -2 too: the next free one is offered and said available
        waitText("✓ Available · suggested from the topic, you can change it")
        compose.onNodeWithText("tool-library-3").assertExists()
        assertEquals(listOf("tool-library", "tool-library-2", "tool-library-3", "tool-library-3"), checked.toList())
        compose.onNodeWithTag("card-start").performScrollTo().assertIsEnabled()

        // changed by hand to a taken one: said so, nothing else is offered, and it can't be started
        field("card-hashtag").performTextReplacement("General")
        waitText("Taken: try another one")
        compose.onNodeWithText("General").assertExists()
        compose.onNodeWithTag("card-start").assertIsNotEnabled()
        field("card-hashtag").performTextReplacement("x")
        waitText("Letters, digits and dashes, 2 to 30")
        compose.onNodeWithTag("card-start").assertIsNotEnabled()

        // the belfry can't check: never "available"; Try again asks again
        down = true
        field("card-hashtag").performTextReplacement("drills")
        waitText("Couldn’t check just now")
        compose.onNodeWithTag("card-start").assertIsNotEnabled()
        down = false
        compose.onNodeWithTag("card-recheck").performClick()
        waitText("✓ Available")
        // the summary by e-mail: Weekly unless Monthly is picked; the choice is said, not only coloured
        compose.onNodeWithText("Summary by e-mail").performScrollTo().assertExists()
        compose.onNodeWithTag("card-summary-weekly").assertIsSelected()
        compose.onNodeWithTag("card-summary-monthly").assertIsNotSelected()
        compose.onNodeWithText("What was said, once a week. Change it any time.").assertExists()
        compose.onNodeWithTag("card-summary-monthly").performScrollTo().performClick()
        compose.onNodeWithTag("card-summary-monthly").assertIsSelected()
        compose.onNodeWithTag("card-summary-weekly").assertIsNotSelected()
        compose.onNodeWithText("What was said, once a month. Change it any time.").assertExists()
        compose.onNodeWithTag("card-start").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("card-start").performClick()
        compose.waitUntil(10_000) { sent != null }
        assertEquals("""{"title":"Tool library","about":"","hashtag":"drills"}""", Cards.claimBody(CardKind.TOPIC, sent!!).toString())
        assertEquals("drills", Cards.hashtagSlug(sent!!.hashtag))
        assertEquals("Tool library", sent!!.title)
    }

    /** A visitor: name and e-mail, the code screen, each refusal of the code in words; what was typed is there on the way back. */
    @Test
    fun aVisitorsCode() {
        val starts = CopyOnWriteArrayList<String>()
        val codes = CopyOnWriteArrayList<String>()
        var answer: Exception? = CardFailed(CardError.WRONG_CODE, triesLeft = 3)
        var verified: CardForm? = null
        show(CardDoor(Demo.NAME, Demo.DOMAIN, null, info = { CardInfo.Unclaimed(CardKind.GROUP) }, hashtag = { HashtagState.FREE },
            start = { starts += it },
            verify = { _, f, code -> codes += code; answer?.let { throw it }; verified = f },
            retry = { answer?.let { throw it }; true },
            keptForm = { CardForm(title = "Sourdough starters") }))
        waitFor("card-start")
        // what was typed before comes back
        compose.onNodeWithText("Sourdough starters").assertExists()
        compose.onNodeWithText("We’ll send a 6-digit code to confirm it. Already have an account on ${Demo.NAME}? Use its e-mail address.").assertExists()
        compose.onNodeWithTag("card-start").assertIsNotEnabled()
        field("card-name").performTextInput("Lou")
        field("card-email").performTextInput("lou@example")
        compose.onNodeWithTag("card-start").assertIsNotEnabled()
        field("card-email").performTextReplacement("lou@example.org")
        compose.onNodeWithTag("card-start").performScrollTo().performClick()
        waitFor("code-boxes")
        assertEquals(listOf("lou@example.org"), starts.toList())
        compose.onNodeWithText("Check your e-mail").assertExists()
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
            // (a code that stays in the boxes is typed over: six new digits replace it)
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
        compose.onNodeWithText("Sourdough starters is started").assertExists()
        answer = null
        compose.onNodeWithTag("card-retry").performClick()
        compose.waitForIdle()
        assertEquals(2, starts.size)
        assertEquals("333333", codes.last())
    }
}
