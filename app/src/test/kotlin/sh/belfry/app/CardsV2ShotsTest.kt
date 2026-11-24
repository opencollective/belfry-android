package sh.belfry.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.onAllNodesWithTag
import kotlinx.coroutines.runBlocking
import org.junit.Test
import sh.belfry.app.ui.chat.ChannelScreen
import sh.belfry.app.ui.printed.CardAccount
import sh.belfry.app.ui.printed.CardCodeContent
import sh.belfry.app.ui.printed.CardConfirmedContent
import sh.belfry.app.ui.printed.CardDeadContent
import sh.belfry.app.ui.printed.CardOpeningContent
import sh.belfry.app.ui.printed.CardStartContent
import sh.belfry.app.ui.printed.CardWelcomeState
import sh.belfry.app.ui.printed.LocalCardWelcome
import sh.belfry.core.engine.Demo
import sh.belfry.core.engine.DemoCards
import sh.belfry.core.engine.cards
import sh.belfry.core.model.CardError
import sh.belfry.core.model.CardFailed
import sh.belfry.core.model.CardForm
import sh.belfry.core.model.CardKind
import sh.belfry.core.model.CardWelcome
import sh.belfry.core.model.Cards
import sh.belfry.core.model.HashtagState
import sh.belfry.core.model.Special

/**
 * The pictures of the printed-card create flow (SPEC 19.89; Cards canvas "Onboarding from a card,
 * improved": onb-group-form, onb-topic-form, onb-code, onb-group-done, onb-topic-done, onb-town-group,
 * onb-town-topic, claim-signed-in, onb-topic-signed-in, onb-confirmed-elsewhere). Only taken when asked:
 * `gh workflow run ci.yml --ref <branch> -f shots='*CardsV2ShotsTest*'`.
 */
class CardsV2ShotsTest : DemoShots() {
    private val sourdough = CardForm(title = "Sourdough starters", about = "Swap starters, share flour, bake together on Saturday mornings.", name = "Lou", email = "lou@example.org")
    private val tools = CardForm(about = "Borrow drills, ladders and the big saw instead of buying them. Say what you need, say what you lend.", hashtag = "tool-library", name = "Dev", email = "dev@example.org")
    private val ana = CardAccount(Demo.me("Ana Silva").pubkey, "Ana Silva", address = "ana@demo.belfry.sh")
    private val free = Cards.hashtagLine(HashtagState.FREE, false)

    @Test fun groupForm() = shot("cards-v2-onb-group-form") {
        CardStartContent(CardKind.GROUP, DemoCards.FREE_GROUP, Demo.NAME, null, sourdough, {}, {}, {}, {}, {}, null, false, null, null, true, {}, {})
    }

    @Test fun topicForm() = shot("cards-v2-onb-topic-form") {
        CardStartContent(CardKind.TOPIC, DemoCards.FREE_TOPIC, Demo.NAME, null, tools, {}, {}, {}, {}, {}, free, false, null, null, true, {}, {})
    }

    @Test fun groupSignedIn() = shot("cards-v2-claim-signed-in") {
        CardStartContent(CardKind.GROUP, DemoCards.FREE_GROUP, Demo.NAME, ana, sourdough, {}, {}, {}, {}, {}, null, false, null, null, true, {}, {}, onLogOut = {})
    }

    @Test fun topicSignedIn() = shot("cards-v2-onb-topic-signed-in") {
        CardStartContent(CardKind.TOPIC, DemoCards.FREE_TOPIC, Demo.NAME, ana, tools, {}, {}, {}, {}, {}, free, false, null, null, true, {}, {}, onLogOut = {})
    }

    @Test fun topicTaken() = shot("cards-v2-onb-topic-taken") {
        CardStartContent(CardKind.TOPIC, DemoCards.FREE_TOPIC, Demo.NAME, null, tools.copy(hashtag = "general"), {}, {}, {}, {}, {}, Cards.hashtagLine(HashtagState.TAKEN, false), true, null, null, false, {}, {})
    }

    @Test fun code() = shot("cards-v2-onb-code") {
        CardCodeContent("lou@example.org", "Sourdough starters", null, null, { false }, {}, {}, initial = "427", resendIn = 24)
    }

    @Test fun codeTopic() = shot("cards-v2-onb-code-topic") {
        CardCodeContent("dev@example.org", "#tool-library", null, null, { false }, {}, {}, resendIn = 0)
    }

    @Test fun codeExpired() = shot("cards-v2-onb-code-expired") {
        CardCodeContent("lou@example.org", "Sourdough starters", Cards.pollErrorText(CardFailed(CardError.EXPIRED)), null, { false }, {}, {}, resendIn = 0)
    }

    @Test fun confirming() = shot("cards-v2-confirming") { CardOpeningContent(Cards.CONFIRMING) }

    @Test fun confirmedElsewhere() = shot("cards-v2-onb-confirmed-elsewhere") { CardConfirmedContent(Demo.NAME) {} }

    @Test fun confirmFailed() = shot("cards-v2-confirm-failed") {
        CardDeadContent(Cards.CONFIRM_FAILED_TITLE, Cards.confirmErrorText(CardFailed(CardError.EXPIRED)), Demo.NAME, null, {}, {})
    }

    @Test fun groupDone() {
        val group = runBlocking { session.cards.claim(DemoCards.FREE_GROUP, CardKind.GROUP, sourdough) }
        settle()
        demoShot("cards-v2-onb-group-done") {
            CompositionLocalProvider(LocalCardWelcome provides CardWelcomeState(CardWelcomed(Demo.DOMAIN, group, CardWelcome.STARTED, DemoCards.FREE_GROUP)) {}) { ChannelScreen(group, onMenu = {}) }
        }
    }

    @Test fun topicDone() {
        val topic = runBlocking { session.cards.claim(DemoCards.FREE_TOPIC, CardKind.TOPIC, tools) }
        settle()
        demoShot("cards-v2-onb-topic-done") {
            CompositionLocalProvider(LocalCardWelcome provides CardWelcomeState(CardWelcomed(Demo.DOMAIN, topic, CardWelcome.STARTED, DemoCards.FREE_TOPIC)) {}) { ChannelScreen(topic, onMenu = {}) }
        }
    }

    @Test fun townGroup() {
        val post = world.messages(Demo.GENERAL).single { it.special == Special.PodCard(Demo.POD_CARD) }
        demoShot("cards-v2-onb-town-group", act = {
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("card-group-card", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            compose.mainClock.advanceTimeBy(3_500)
        }) { ChannelScreen(Demo.GENERAL, onMenu = {}, focusMessage = post.id) }
    }

    @Test fun townTopic() {
        val post = world.messages(Demo.GENERAL).single { it.special is Special.TopicCard }
        demoShot("cards-v2-onb-town-topic", act = {
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("topic-card", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            compose.mainClock.advanceTimeBy(3_500)
        }) { ChannelScreen(Demo.GENERAL, onMenu = {}, focusMessage = post.id) }
    }
}
