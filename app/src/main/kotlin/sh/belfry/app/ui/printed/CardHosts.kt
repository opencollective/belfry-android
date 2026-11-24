package sh.belfry.app.ui.printed

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.AppState
import sh.belfry.app.CardWelcomed
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.IconAction
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.signin.openInBrowser
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.engine.cards
import sh.belfry.core.model.CardLink
import sh.belfry.core.model.Cards
import sh.belfry.core.net.Community

private suspend fun preview(state: AppState, link: CardLink, invite: String): CardPreview? =
    state.cardPreview(link, invite)?.takeIf { it.isOpen }?.let { CardPreview(Cards.plainLabel(it.channelName), it.about, Cards.plainLabel(it.inviter)) }

/**
 * A card's screen for someone with no account in its belfry on this phone (SPEC 19.73): signed
 * out (in place of the first screen), or over the app when the open account is another belfry's.
 */
@Composable
fun VisitorCard(state: AppState, link: CardLink) {
    val context = LocalContext.current
    var community by remember(link.domain) { mutableStateOf<Community?>(null) }
    LaunchedEffect(link.domain) { community = try { state.cardCommunity(link.domain) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { Community.defaults(link.domain) } }
    val c = community
    if (c == null) { CardOpeningContent(); return }
    val door = remember(link, c) {
        CardDoor(
            community = c.name.ifEmpty { c.domain }, domain = c.domain, signedIn = null,
            info = { state.cardInfo(link) },
            hashtag = { state.cardHashtag(link, it) },
            start = { kind, f -> state.cardStart(link, kind, f) },
            verify = { kind, f, code -> state.cardVerify(link, kind, f, code) },
            poll = { state.cardPoll(link) },
            confirm = { token -> state.cardConfirm(link, token) },
            retry = { state.cardRetry() },
            keptForm = { state.cardForm(link) }, keepForm = { state.keepCardForm(link, it) }, dropForm = { state.dropCardForm() },
            preview = { invite -> preview(state, link, invite) },
            join = { _, group, invite -> state.cardJoin(link, group, invite) },
            visit = { openInBrowser(context, "https://${c.domain}") },
            close = { state.forgetCard() },
        )
    }
    CardScreen(door, link)
}

/**
 * Signed in and a card's address was opened (SPEC 19.72): its screen over the app. The open
 * account's own belfry: "Signed in as …" and the claim by this account. Another belfry's card
 * (no account there on this phone): the visitor's screens.
 */
@Composable
fun CardHost(state: AppState) {
    val link = state.card.collectAsState().value ?: return
    val session = LocalSession.current
    val world = LocalWorld.current
    val nav = LocalNav.current
    val context = LocalContext.current
    Box(Modifier.fillMaxSize().background(Belfry.colors.bg).pointerInput(Unit) { detectTapGestures { } }.testTag("card-over")) {
        if (link.domain != session.community.domain) { VisitorCard(state, link); return@Box }
        val me = world.me
        val accountsView by state.accounts.collectAsState()
        val address = accountsView.list.accounts.firstOrNull { it.id == session.accountId }?.address?.ifEmpty { null } ?: world.profiles[me]?.nip05 ?: ""
        val account = CardAccount(me, world.realName(me), world.profiles[me]?.picture, address)
        val signOut = sh.belfry.app.ui.nav.LocalSignOut.current
        val community = world.ctx.communityName.ifEmpty { session.community.name }.ifEmpty { session.community.domain }
        val door = remember(link, session, account, community, signOut) {
            CardDoor(
                community = community, domain = session.community.domain, signedIn = account,
                info = { session.cards.info(link.id) },
                hashtag = { session.cards.hashtag(it) },
                claim = { kind, f ->
                    val group = session.cards.claim(link.id, kind, f)
                    state.dropCardForm()
                    state.cardStarted(session.community.domain, group, link.id)
                    nav.openChannel(group)
                },
                // the e-mail's Confirm button: always this phone's own way (the applicant key), never this account's
                confirm = { token -> state.cardConfirm(link, token) },
                retry = { state.cardRetry() },
                // to start it as someone else: this account signs out, the card stays and shows as a visitor's
                logOut = { signOut() },
                keptForm = { state.cardForm(link) }, keepForm = { state.keepCardForm(link, it) }, dropForm = { state.dropCardForm() },
                preview = { invite -> preview(state, link, invite) },
                join = { _, group, invite ->
                    // already in it: the group as usual (canvas cards/join-member); else the card's guest link
                    if (session.world.value.group(group)?.members?.contains(me) == true) { state.forgetCard(); nav.openChannel(group) }
                    else state.cardJoin(link, group, invite)
                },
                visit = { openInBrowser(context, "https://${session.community.domain}") },
                close = { state.forgetCard() },
            )
        }
        CardScreen(door, link)
    }
}

/** What the first open of a card's group says, and how it is put away ([AppRoot] provides it). */
class CardWelcomeState(val welcomed: CardWelcomed?, val dismiss: () -> Unit)

val LocalCardWelcome = staticCompositionLocalOf<CardWelcomeState?> { null }

/**
 * The first open of a group or topic after its card (onb-group-done, onb-topic-done; the web opens
 * the header half pulled down, which this app's channel header does not have): under the top bar,
 * once, its description, "Steward: you · 1 person", the card's line, and the welcome; put away
 * with its ✕. Words and a border, never a colour alone. The "+" (Add someone) is not built: draft.
 */
@Composable
fun CardWelcomeBar(groupId: String) {
    val w = LocalCardWelcome.current ?: return
    val welcomed = w.welcomed ?: return
    val session = LocalSession.current
    if (welcomed.group != groupId || welcomed.domain != session.community.domain) return
    val world = LocalWorld.current
    val g = world.group(groupId) ?: return
    val c = Belfry.colors
    val started = welcomed.welcome == sh.belfry.core.model.CardWelcome.STARTED
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).clip(RoundedCornerShape(14.dp)).background(c.surface)
            .border(1.dp, c.text, RoundedCornerShape(14.dp)).padding(start = 14.dp, top = 6.dp, bottom = 10.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }.testTag("card-welcome"),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(top = 6.dp)) {
                if (started && g.about.isNotEmpty()) {
                    Text(g.about, fontSize = 15.sp, lineHeight = 21.sp, color = c.text, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.testTag("card-done-about"))
                    androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 8.dp))
                }
                if (started) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("card-done-steward")) {
                        sh.belfry.app.ui.kit.Avatar(world.me, world.realName(world.me), picture = world.profiles[world.me]?.picture, size = 24.dp)
                        androidx.compose.foundation.layout.Spacer(Modifier.padding(start = 8.dp))
                        Text(Cards.stewardFacts(g.isPod, "you", g.members.size.coerceAtLeast(1)), fontSize = 14.sp, color = c.text)
                    }
                    welcomed.cardId?.let { id ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp).testTag("card-done-card")) {
                            androidx.compose.material3.Icon(BelfryIcons.Qr, contentDescription = null, tint = c.muted, modifier = Modifier.padding(horizontal = 3.dp).size(18.dp))
                            androidx.compose.foundation.layout.Spacer(Modifier.padding(start = 8.dp))
                            Text(Cards.cardFacts(g.isPod, id), fontSize = 14.sp, lineHeight = 19.sp, color = c.muted)
                        }
                    }
                    androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 10.dp))
                }
            }
            IconAction(BelfryIcons.Close, "Close the welcome", w.dismiss)
        }
        Row(Modifier.padding(end = 14.dp), verticalAlignment = Alignment.Top) {
            androidx.compose.material3.Icon(BelfryIcons.Check, contentDescription = null, tint = c.text, modifier = Modifier.padding(top = 2.dp).size(18.dp))
            androidx.compose.foundation.layout.Spacer(Modifier.padding(start = 8.dp))
            Text(buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(Cards.welcomeTitle(g.isPod, g.name, welcomed.welcome)) }
                append(" ")
                append(Cards.welcomeText(g.isPod, welcomed.welcome))
            }, fontSize = 15.sp, lineHeight = 21.sp, color = c.text)
        }
    }
}
