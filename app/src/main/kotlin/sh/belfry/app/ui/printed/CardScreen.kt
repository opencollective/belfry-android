package sh.belfry.app.ui.printed

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import sh.belfry.app.ui.kit.ActionGroup
import sh.belfry.app.ui.kit.ActionRow
import sh.belfry.app.ui.kit.Avatar
import sh.belfry.app.ui.kit.BackButton
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.BelfryTextField
import sh.belfry.app.ui.kit.BusyButton
import sh.belfry.app.ui.kit.ButtonKind
import sh.belfry.app.ui.kit.Spinner
import sh.belfry.app.ui.kit.rememberBusy
import sh.belfry.app.ui.kit.rememberStarted
import sh.belfry.app.ui.signin.CodeBoxes
import sh.belfry.app.ui.signin.SignedOutPage
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.engine.OnboardingException
import sh.belfry.core.model.CardError
import sh.belfry.core.model.CardFailed
import sh.belfry.core.model.CardForm
import sh.belfry.core.model.CardInfo
import sh.belfry.core.model.CardKind
import sh.belfry.core.model.CardLink
import sh.belfry.core.model.Cards
import sh.belfry.core.model.HashtagInput
import sh.belfry.core.model.HashtagState
import sh.belfry.core.net.NativeSignIn

// A printed card's screens (SPEC 19.72–19.74, 19.89; Cards canvas "Onboarding from a card, improved":
// cards/onb-group-form, onb-topic-form, onb-code, onb-confirmed-elsewhere, claim-signed-in,
// onb-topic-signed-in, states-*; the web's ui/Card.tsx at chat e965481 + 25ff046, whose words these
// are). A free card: "Start a group" / "Start a topic" (signed in: straight in; a visitor: name and
// e-mail, then the 6-digit code or the e-mail's Confirm button). A claimed card is a guest link: its
// invite takes the usual way in. Dead ends say why. Nothing from the address is ever shown.

/** Who is signed in, for the account box under Create (cards/claim-signed-in). */
data class CardAccount(val pubkey: String, val name: String, val picture: String? = null, val address: String = "")

/** What the group someone else just started looks like (the card's guest link says). */
data class CardPreview(val title: String, val about: String, val steward: String)

/**
 * Everything a card's screen asks of the app: the same screens serve a visitor (the calls go
 * through `Onboarding`, the code makes a session) and a signed-in account (through its `Session`).
 */
class CardDoor(
    /** The belfry's name, for "Visit …" and the brand line. */
    val community: String,
    val domain: String,
    /** Null: a visitor (name, e-mail and a code are asked). */
    val signedIn: CardAccount?,
    val info: suspend () -> CardInfo,
    val hashtag: suspend (String) -> HashtagState,
    /** Signed in: the claim. Returns when the group is made and being opened. */
    val claim: suspend (CardKind, CardForm) -> Unit = { _, _ -> },
    /** A visitor: the form to the belfry, the code and the Confirm button to its e-mail. */
    val start: suspend (CardKind, CardForm) -> Unit = { _, _ -> },
    /** A visitor: the code. Returns when this phone is signed in. */
    val verify: suspend (CardKind, CardForm, String) -> Unit = { _, _, _ -> },
    /**
     * The waiting screen's ask (`claim-poll`). True: stop asking (this phone is in, or holds the
     * session another way: say nothing). False: still waiting. Throws a CardFailed (410, 404: stop).
     */
    val poll: suspend () -> Boolean = { false },
    /** The e-mail's Confirm link: true when this phone is in now, false: confirmed for another device. */
    val confirm: suspend (token: String) -> Boolean = { throw CardFailed(CardError.EXPIRED) },
    /** The code was right and the signer did not answer: once more. False: nothing waits any more. */
    val retry: suspend () -> Boolean = { false },
    val keptForm: suspend () -> CardForm? = { null },
    val keepForm: suspend (CardForm) -> Unit = {},
    val dropForm: suspend () -> Unit = {},
    val preview: suspend (invite: String) -> CardPreview? = { null },
    /** A claimed card: its guest link's way in. */
    val join: (kind: CardKind, group: String, invite: String) -> Unit = { _, _, _ -> },
    /** Signed in: "Log out" under Create (to start it as someone else); null: no such row. */
    val logOut: (suspend () -> Unit)? = null,
    val visit: () -> Unit = {},
    val close: () -> Unit = {},
    /** Seconds (the waiting screen's 15 minutes); a seam for tests. */
    val now: () -> Long = { System.currentTimeMillis() / 1000 },
)

private sealed interface Shown {
    data object Opening : Shown
    data class Info(val info: CardInfo) : Shown
    /** The belfry could not be asked: OFFLINE, TOO_MANY or UNSUPPORTED. */
    data class Dead(val code: CardError) : Shown
}

/** The card LINK, through DOOR. A Confirm link (`?confirm=`) is that and nothing else. */
@Composable
fun CardScreen(door: CardDoor, link: CardLink) {
    val token = link.confirm
    if (token != null && link.known) { ConfirmFlow(door, link, token); return }
    var shown by remember(link) { mutableStateOf<Shown>(Shown.Opening) }
    var tries by remember(link) { mutableIntStateOf(0) }
    LaunchedEffect(link, tries) {
        shown = Shown.Opening
        shown = if (!link.known) Shown.Info(CardInfo.Unknown) else try {
            Shown.Info(door.info())
        } catch (e: CancellationException) {
            throw e
        } catch (e: CardFailed) {
            Shown.Dead(if (e.code == CardError.TOO_MANY || e.code == CardError.UNSUPPORTED) e.code else CardError.OFFLINE)
        } catch (e: Exception) {
            Shown.Dead(CardError.OFFLINE)
        }
    }
    // a claimed card: its guest link; a card that can't be started any more: what was typed for it goes
    val info = (shown as? Shown.Info)?.info
    LaunchedEffect(info) {
        when (info) {
            is CardInfo.Claimed -> { door.dropForm(); door.join(info.kind, info.group, info.invite) }
            is CardInfo.Retired, is CardInfo.Archived, CardInfo.Unknown -> door.dropForm()
            else -> Unit
        }
    }
    BackHandler(onBack = door.close)
    val again: () -> Unit = { tries++ }
    when (val s = shown) {
        Shown.Opening -> CardOpeningContent()
        is Shown.Dead -> when (s.code) {
            CardError.TOO_MANY -> CardDeadContent(Cards.BUSY_TITLE, Cards.BUSY_TEXT, door.community, onAgain = again, onVisit = door.visit, onClose = door.close)
            CardError.UNSUPPORTED -> CardDeadContent(Cards.UNSUPPORTED_TITLE, null, door.community, onAgain = null, onVisit = door.visit, onClose = door.close)
            else -> CardDeadContent(Cards.OFFLINE_TITLE, Cards.OFFLINE_TEXT, door.community, onAgain = again, onVisit = door.visit, onClose = door.close)
        }
        is Shown.Info -> when (val i = s.info) {
            is CardInfo.Claimed -> CardOpeningContent()
            CardInfo.Unknown -> CardDeadContent(Cards.UNKNOWN_TITLE, Cards.unknownText(door.domain), door.community, null, door.visit, door.close)
            is CardInfo.Retired -> CardDeadContent(Cards.retiredTitle(i.kind, i.title), Cards.retiredText(i.kind), door.community, null, door.visit, door.close)
            is CardInfo.Archived -> CardDeadContent(Cards.archivedTitle(i.kind, i.title), Cards.archivedText(i.kind), door.community, null, door.visit, door.close)
            is CardInfo.Unclaimed -> StartFlow(door, link, i.kind, onRetired = { shown = Shown.Info(CardInfo.Retired(i.kind, "")) })
        }
    }
}

private sealed interface Confirming {
    data object Asking : Confirming
    /** This phone is in: the group opens (the card's screen goes by itself). */
    data object In : Confirming
    data object Elsewhere : Confirming
    data class Failed(val f: CardFailed) : Confirming
    data class Signer(val words: String) : Confirming
}

/**
 * The e-mail's Confirm button opened the app (SPEC 19.89): `claim-confirm`, once per open. This
 * phone started the claim: it is signed in and the group opens; else "Confirmed" (onb-confirmed-elsewhere).
 */
@Composable
private fun ConfirmFlow(door: CardDoor, link: CardLink, token: String) {
    var shown by remember(link) { mutableStateOf<Confirming>(Confirming.Asking) }
    var tries by remember(link) { mutableIntStateOf(0) }
    LaunchedEffect(link, tries) {
        shown = Confirming.Asking
        shown = try {
            if (door.confirm(token)) Confirming.In else Confirming.Elsewhere
        } catch (e: CancellationException) {
            throw e
        } catch (e: CardFailed) {
            Confirming.Failed(e)
        } catch (e: OnboardingException) {
            Confirming.Signer(e.message ?: "")
        } catch (e: Exception) {
            Confirming.Failed(CardFailed(CardError.FAILED))
        }
    }
    BackHandler(onBack = door.close)
    when (val s = shown) {
        Confirming.Asking -> CardOpeningContent(Cards.CONFIRMING)
        Confirming.In -> CardOpeningContent()
        Confirming.Elsewhere -> CardConfirmedContent(door.community, onClose = door.close)
        is Confirming.Failed -> CardDeadContent(
            Cards.CONFIRM_FAILED_TITLE, Cards.confirmErrorText(s.f), door.community,
            onAgain = if (Cards.confirmMayRetry(s.f)) ({ tries += 1 }) else null, onVisit = door.visit, onClose = door.close,
        )
        is Confirming.Signer -> CardSignerContent("", s.words, null) {
            try { if (door.retry()) shown = Confirming.In } catch (e: CancellationException) { throw e } catch (e: OnboardingException) { shown = Confirming.Signer(e.message ?: "") } catch (e: Exception) { }
        }
    }
}

private enum class Step { FORM, CODE, TAKEN, SIGNER }

@Composable
private fun StartFlow(door: CardDoor, link: CardLink, kind: CardKind, onRetired: () -> Unit) {
    var f by remember(link) { mutableStateOf(CardForm()) }
    var loaded by remember(link) { mutableStateOf(false) }
    var dropped by remember(link) { mutableStateOf(false) }
    var step by remember(link) { mutableStateOf(Step.FORM) }
    var error by remember(link) { mutableStateOf<String?>(null) }
    var note by remember(link) { mutableStateOf<String?>(null) }
    var tag by remember(link) { mutableStateOf<HashtagState?>(null) }
    var checking by remember(link) { mutableStateOf(false) }
    var recheck by remember(link) { mutableIntStateOf(0) }
    var taken by remember(link) { mutableStateOf<CardFailed?>(null) }
    var preview by remember(link) { mutableStateOf<CardPreview?>(null) }
    var signerWords by remember(link) { mutableStateOf("") }
    var verifying by remember(link) { mutableStateOf(false) }
    var waiting by remember(link) { mutableStateOf(true) }
    var polls by remember(link) { mutableIntStateOf(0) }
    val visitor = door.signedIn == null

    // what was typed comes back (a closed app, the code's e-mail read in another app) and is kept as it changes;
    // a code asked less than 15 minutes ago: the waiting screen again (its Confirm button may still come)
    LaunchedEffect(link) {
        val kept = try { door.keptForm() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        if (kept != null && f == CardForm()) {
            f = kept
            if (visitor && kept.sentAt > 0 && door.now() - kept.sentAt < Cards.CLAIM_SECONDS) step = Step.CODE
        }
        loaded = true
    }
    LaunchedEffect(f, loaded) {
        if (!loaded || dropped || f == CardForm()) return@LaunchedEffect
        delay(400)
        if (!dropped) door.keepForm(f)
    }
    // the hashtag's live check (a topic card), on the cleaned value, never on a trailing dash
    val slug = HashtagInput.done(f.hashtag)
    LaunchedEffect(slug, kind, recheck) {
        if (kind != CardKind.TOPIC) return@LaunchedEffect
        if (slug.length < 2) { checking = false; tag = if (f.hashtag.isBlank()) null else HashtagState.INVALID; return@LaunchedEffect }
        checking = true
        delay(350)
        tag = door.hashtag(slug)
        checking = false
    }
    // the waiting screen (onb-code): claim-poll every 3 s while it is shown and the app in front; it stops on
    // leaving it, on 410 / 404, once this phone is in, and after the claim's 15 minutes
    val started = rememberStarted()
    LaunchedEffect(step, started, f.sentAt, waiting) {
        if (step != Step.CODE || !visitor || !started || !waiting || f.sentAt <= 0) return@LaunchedEffect
        while (polls < Cards.POLL_MAX && door.now() - f.sentAt < Cards.CLAIM_SECONDS) {
            delay(Cards.POLL_MS)
            if (verifying) continue
            polls++
            try {
                if (door.poll()) { waiting = false; return@LaunchedEffect }
            } catch (e: CancellationException) {
                throw e
            } catch (e: CardFailed) {
                if (Cards.pollStops(e)) { if (!verifying) error = Cards.pollErrorText(e); waiting = false; return@LaunchedEffect }
            } catch (e: OnboardingException) {
                // the session came, its signer did not answer: kept; Try again opens it
                signerWords = e.message ?: ""; step = Step.SIGNER; waiting = false; return@LaunchedEffect
            } catch (e: Exception) {
                // no connection, a busy belfry: asked again at the next tick
            }
        }
        waiting = false
        if (step == Step.CODE && error == null) error = Cards.pollErrorText(CardFailed(CardError.EXPIRED))
    }

    suspend fun drop() { dropped = true; door.dropForm() }
    suspend fun fail(e: Throwable, afterCode: Boolean) {
        val x = e as? CardFailed ?: CardFailed(CardError.FAILED)
        when {
            x.code == CardError.RETIRED -> { drop(); onRetired() }
            x.code == CardError.CLAIMED && x.group != null && x.invite != null -> {
                drop(); taken = x; step = Step.TAKEN
                preview = try { door.preview(x.invite!!) } catch (c: CancellationException) { throw c } catch (c: Exception) { null }
            }
            x.code == CardError.HASHTAG_TAKEN -> { tag = HashtagState.TAKEN; checking = false; step = Step.FORM; error = Cards.errorText(x, kind) }
            else -> error = Cards.errorText(x, kind, afterCode)
        }
    }
    suspend fun sendCode() {
        // the form as it goes: a topic's hashtag without its trailing dash
        val sent = f.copy(hashtag = HashtagInput.done(f.hashtag))
        door.start(kind, sent)
        f = sent.copy(sentAt = door.now())
        polls = 0; waiting = true
    }
    val recheckNow: () -> Unit = { recheck++ }
    val ready = Cards.ready(kind, f.copy(hashtag = slug), !visitor, if (checking) null else tag)

    when (step) {
        Step.FORM -> CardStartContent(
            kind = kind, cardId = link.id, community = door.community, signedIn = door.signedIn, form = f,
            onTitle = { v -> f = f.copy(title = v.take(80)); error = null },
            onHashtag = { v -> f = f.copy(hashtag = HashtagInput.typed(v)); error = null },
            onHashtagDone = { f = f.copy(hashtag = HashtagInput.done(f.hashtag)) },
            onAbout = { v -> f = f.copy(about = v.take(500)) },
            onName = { v -> f = f.copy(name = v.take(100)) },
            onEmail = { v -> f = f.copy(email = v.take(200)); error = null },
            tagLine = Cards.hashtagLine(tag, checking), tagBad = !checking && (tag == HashtagState.TAKEN || tag == HashtagState.INVALID),
            onRecheck = recheckNow.takeIf { !checking && tag == HashtagState.UNKNOWN },
            error = error, ready = ready, onClose = door.close, onLogOut = door.logOut,
            onStart = {
                error = null
                if (ready) try {
                    if (!visitor) door.claim(kind, f.copy(hashtag = slug)) else { sendCode(); note = null; step = Step.CODE }
                } catch (e: CancellationException) { throw e } catch (e: Exception) { fail(e, false) }
            },
        )
        Step.CODE -> {
            BackHandler { step = Step.FORM; error = null; note = null }
            CardCodeContent(
                email = f.email.trim(), what = Cards.what(kind, f), error = error, note = note,
                resendIn = (30 - (door.now() - f.sentAt)).coerceIn(0, 30).toInt(),
                onVerify = { code ->
                    error = null; note = null
                    verifying = true
                    try {
                        door.verify(kind, f, code)
                        false
                    } catch (e: CancellationException) { throw e } catch (e: OnboardingException) {
                        // the code was right, the group is made: only the signer is missing (no new code)
                        signerWords = e.message ?: ""; step = Step.SIGNER
                        false
                    } catch (e: Exception) {
                        fail(e, true)
                        e !is CardFailed || Cards.clearsCode(e.code)
                    } finally { verifying = false }
                },
                onResend = {
                    error = null
                    try { sendCode(); note = Cards.NEW_CODE_SENT } catch (e: CancellationException) { throw e } catch (e: Exception) { fail(e, false) }
                },
                onOther = { step = Step.FORM; error = null; note = null; waiting = false },
            )
        }
        Step.TAKEN -> {
            val t = taken
            CardTakenContent(kind, preview, onJoin = { if (t?.group != null && t.invite != null) door.join(kind, t.group!!, t.invite!!) })
        }
        Step.SIGNER -> CardSignerContent(Cards.what(kind, f), signerWords, error) {
            error = null
            try {
                if (!door.retry()) { step = Step.FORM; error = Cards.errorText(CardFailed(CardError.FAILED), kind) }
            } catch (e: CancellationException) { throw e } catch (e: OnboardingException) { signerWords = e.message ?: "" } catch (e: Exception) { error = Cards.errorText(CardFailed(CardError.FAILED), kind) }
        }
    }
}

// ---- what is drawn (no state of the flow: the screenshot tests draw these) --------------------------

@Composable
private fun Heading(text: String, intro: String?) {
    val c = Belfry.colors
    Text(text, fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, color = c.text)
    if (intro != null) { Spacer(Modifier.height(8.dp)); Text(intro, fontSize = 16.sp, lineHeight = 22.sp, color = c.muted) }
    Spacer(Modifier.height(22.dp))
}

@Composable
private fun ErrorBox(error: String?) {
    val c = Belfry.colors
    if (error == null) return
    Spacer(Modifier.height(12.dp))
    Text(
        error, fontSize = 15.sp, lineHeight = 20.sp, color = c.danger,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(1.dp, c.danger, RoundedCornerShape(10.dp)).padding(12.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }.testTag("card-error"),
    )
}

/** The belfry's mark and name over a card's screens (the boards' brand line). */
@Composable
private fun Brand(community: String) {
    val c = Belfry.colors
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("card-brand")) {
        Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(c.accent), contentAlignment = Alignment.Center) {
            Icon(BelfryIcons.Shield, contentDescription = null, tint = c.onAccent, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(community, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.text)
    }
}

/** The card's chip: "Card K7Q2MX", "Card X2M7KE · topic" (a bordered label, never a colour alone). */
@Composable
private fun Chip(text: String) {
    val c = Belfry.colors
    Row(
        Modifier.clip(RoundedCornerShape(50)).border(1.dp, c.line, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp).testTag("card-chip"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(BelfryIcons.Qr, contentDescription = null, tint = c.muted, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = c.muted)
    }
}

/** "Opening the card…" (or another WORDS while something is asked). */
@Composable
fun CardOpeningContent(words: String = Cards.OPENING) {
    Box(Modifier.fillMaxSize().background(Belfry.colors.bg), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("card-opening")) {
            Spinner()
            Spacer(Modifier.width(10.dp))
            Text(words, fontSize = 16.sp, color = Belfry.colors.muted)
        }
    }
}

/** onb-group-form, onb-topic-form, claim-signed-in, onb-topic-signed-in: the form of a card nobody has started. */
@Composable
fun CardStartContent(
    kind: CardKind, cardId: String, community: String, signedIn: CardAccount?, form: CardForm,
    onTitle: (String) -> Unit, onHashtag: (String) -> Unit, onAbout: (String) -> Unit, onName: (String) -> Unit, onEmail: (String) -> Unit,
    /** The line under the hashtag (its live check); [tagBad]: it can't be used as it is. */
    tagLine: String?, tagBad: Boolean, onRecheck: (() -> Unit)?,
    error: String?, ready: Boolean, onClose: () -> Unit, onStart: suspend () -> Unit,
    /** The hashtag field was left: its trailing dash goes. */
    onHashtagDone: () -> Unit = {},
    /** Signed in: "Log out" in the account box; null: no such button. */
    onLogOut: (suspend () -> Unit)? = null,
) {
    val c = Belfry.colors
    var askLogOut by remember { mutableStateOf(false) }
    SignedOutPage {
        Box(Modifier.padding(bottom = 8.dp)) { BackButton(onClose) }
        Brand(community)
        Spacer(Modifier.height(16.dp))
        Chip(Cards.chip(kind, cardId))
        Spacer(Modifier.height(12.dp))
        Heading(Cards.startTitle(kind), Cards.startIntro(kind))
        if (kind == CardKind.TOPIC) {
            // the hashtag is the topic's name: asked directly, cleaned as it is typed (SPEC 19.89)
            BelfryTextField(
                form.hashtag, onHashtag, Modifier.fillMaxWidth().testTag("card-hashtag").onFocusChanged { if (!it.isFocused) onHashtagDone() },
                label = Cards.HASHTAG, placeholder = "tool-library",
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
                leading = { Text("#", fontSize = 17.sp, color = c.muted) },
            )
            if (tagLine != null) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                    Text(tagLine, fontSize = 13.sp, lineHeight = 18.sp, color = if (tagBad) c.danger else c.muted, fontWeight = if (tagBad) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f, fill = false).testTag("card-tag"))
                    if (onRecheck != null) TextButton(onClick = onRecheck, modifier = Modifier.testTag("card-recheck")) { Text("Try again", fontSize = 14.sp, color = c.text, fontWeight = FontWeight.SemiBold) }
                }
            }
        } else {
            BelfryTextField(
                form.title, onTitle, Modifier.fillMaxWidth().testTag("card-title"), label = Cards.GROUP_NAME, hint = Cards.TITLE_HINT,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
        }
        Spacer(Modifier.height(14.dp))
        BelfryTextField(
            form.about, onAbout, Modifier.fillMaxWidth().testTag("card-about"), label = Cards.DESCRIPTION,
            singleLine = false, minLines = 3, maxLines = 8,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        )
        if (signedIn == null) {
            Spacer(Modifier.height(14.dp))
            BelfryTextField(form.name, onName, Modifier.fillMaxWidth().testTag("card-name"), label = Cards.YOUR_NAME, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words))
            Spacer(Modifier.height(14.dp))
            BelfryTextField(
                form.email, onEmail, Modifier.fillMaxWidth().testTag("card-email"), label = Cards.YOUR_EMAIL, placeholder = "you@example.org",
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Email),
            )
        }
        ErrorBox(error)
        Spacer(Modifier.height(18.dp))
        BusyButton(Cards.startButton(kind), onClick = onStart, enabled = ready, modifier = Modifier.fillMaxWidth().testTag("card-start"))
        if (signedIn != null) {
            // who you are, below Create, with Log out (to start it as someone else)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface).padding(horizontal = 14.dp, vertical = 10.dp).testTag("card-who"), verticalAlignment = Alignment.CenterVertically) {
                Avatar(signedIn.pubkey, signedIn.name, picture = signedIn.picture, size = 36.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(signedIn.name, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                    if (signedIn.address.isNotEmpty() && signedIn.address != signedIn.name) Text(signedIn.address, fontSize = 13.sp, lineHeight = 18.sp, color = c.muted)
                }
                if (onLogOut != null) TextButton(onClick = { askLogOut = true }, modifier = Modifier.testTag("card-log-out")) {
                    Text(Cards.LOG_OUT, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(Cards.startFoot(kind, signedIn != null), fontSize = 13.sp, lineHeight = 18.sp, color = c.muted, modifier = Modifier.testTag("card-foot"))
    }
    if (askLogOut && onLogOut != null && signedIn != null) {
        // signing out is not undone by a tap: Android asks first (Material's dialog)
        AlertDialog(
            onDismissRequest = { askLogOut = false },
            title = { Text("Log out of ${signedIn.name}?") },
            text = { Text("The card stays open here, to start it as someone else. You can log in again any time.") },
            confirmButton = { BusyButton(Cards.LOG_OUT, onClick = { onLogOut(); askLogOut = false }, small = true, modifier = Modifier.testTag("card-log-out-confirm")) },
            dismissButton = { TextButton(onClick = { askLogOut = false }) { Text("Cancel", color = c.text) } },
        )
    }
}

/** onb-code: six boxes (the sixth digit sends), or the e-mail's Confirm button: this screen moves on by itself. */
@Composable
fun CardCodeContent(
    email: String, what: String, error: String?, note: String?,
    /** Returns true when the boxes are to be emptied. */
    onVerify: suspend (String) -> Boolean, onResend: suspend () -> Unit, onOther: () -> Unit,
    /** For screenshots: digits typed already. */
    initial: String = "",
    /** Seconds before "Send a new code" can be tapped (30 after each code). */
    resendIn: Int = 30,
) {
    val c = Belfry.colors
    var digits by remember { mutableStateOf(initial) }
    var wait by remember { mutableIntStateOf(resendIn) }
    var sends by remember { mutableIntStateOf(0) }
    val busy = rememberBusy()
    val focus = remember { FocusRequester() }
    LaunchedEffect(sends) { if (sends > 0) wait = 30; while (wait > 0) { delay(1000); wait-- } }
    SignedOutPage {
        Box(Modifier.padding(bottom = 12.dp)) { BackButton(onOther) }
        Heading(Cards.CODE_TITLE, null)
        Text(buildAnnotatedString {
            val lead = Cards.codeLead(what, email)
            val a = lead.indexOf(what)
            val b = lead.lastIndexOf(email)
            append(lead.substring(0, a))
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = c.text)) { append(what) }
            append(lead.substring(a + what.length, b))
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = c.text)) { append(email) }
            append(lead.substring(b + email.length))
        }, fontSize = 16.sp, lineHeight = 22.sp, color = c.muted, modifier = Modifier.testTag("card-code-lead"))
        Spacer(Modifier.height(20.dp))
        CodeBoxes(digits, { raw ->
            val d = NativeSignIn.next(digits, raw)
            if (d != digits && !busy.busy) {
                digits = d
                // one claim per code typed: the boxes are locked until the belfry answered
                if (d.length == 6) busy.run { if (onVerify(d)) digits = "" }
            }
        }, enabled = !busy.busy, error = error != null, focus = focus)
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.Top, modifier = Modifier.testTag("card-or-confirm")) {
            Icon(BelfryIcons.Mail, contentDescription = null, tint = c.muted, modifier = Modifier.padding(top = 1.dp).size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(buildAnnotatedString {
                val t = Cards.CODE_OR_CONFIRM
                val i = t.indexOf("Confirm")
                append(t.substring(0, i))
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = c.text)) { append("Confirm") }
                append(t.substring(i + "Confirm".length))
            }, fontSize = 15.sp, lineHeight = 20.sp, color = c.muted)
        }
        if (error != null) ErrorBox(error) else if (busy.busy || note != null) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("card-code-line")) {
                if (busy.busy) { Spinner(size = 16.dp); Spacer(Modifier.width(8.dp)) }
                Text(if (busy.busy) Cards.creating(what) else note ?: "", fontSize = 14.sp, lineHeight = 19.sp, color = c.muted)
            }
        }
        Spacer(Modifier.height(16.dp))
        ActionGroup {
            ActionRow(
                Cards.resend(wait),
                onClick = { if (wait == 0) busy.run { onResend(); digits = ""; sends++ } },
                icon = BelfryIcons.Mail, enabled = wait == 0 && !busy.busy, modifier = Modifier.testTag("card-resend"),
            )
            ActionRow(Cards.OTHER_ADDRESS, onClick = onOther, icon = BelfryIcons.Back, enabled = !busy.busy)
        }
        Spacer(Modifier.height(22.dp))
        Text(Cards.SPAM, fontSize = 13.sp, lineHeight = 18.sp, color = c.muted, modifier = Modifier.testTag("card-spam"))
    }
}

/** onb-confirmed-elsewhere: the Confirm button was opened here, the claim was started on another device. */
@Composable
fun CardConfirmedContent(community: String, onClose: () -> Unit) {
    val c = Belfry.colors
    SignedOutPage {
        Brand(community)
        Spacer(Modifier.height(40.dp))
        Column(Modifier.fillMaxWidth().testTag("card-confirmed"), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(64.dp).clip(CircleShape).border(2.dp, c.text, CircleShape), contentAlignment = Alignment.Center) {
                Icon(BelfryIcons.Check, contentDescription = null, tint = c.text, modifier = Modifier.size(32.dp))
            }
            Spacer(Modifier.height(20.dp))
            Text(Cards.CONFIRMED_TITLE, fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, color = c.text)
            Spacer(Modifier.height(8.dp))
            Text(Cards.CONFIRMED_TEXT, fontSize = 16.sp, lineHeight = 22.sp, color = c.muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        Spacer(Modifier.height(28.dp))
        BusyButton("Close", onClick = { onClose() }, kind = ButtonKind.Ghost, modifier = Modifier.fillMaxWidth().testTag("card-close"))
    }
}

/** cards/states-taken: someone else's claim came first while this person was writing. */
@Composable
fun CardTakenContent(kind: CardKind, preview: CardPreview?, onJoin: suspend () -> Unit) {
    val c = Belfry.colors
    SignedOutPage {
        Heading(Cards.takenTitle(kind), Cards.TAKEN_TEXT)
        if (preview != null) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, c.line, RoundedCornerShape(14.dp)).padding(14.dp).testTag("card-preview")) {
                Text(preview.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                if (preview.about.isNotEmpty()) { Spacer(Modifier.height(4.dp)); Text(preview.about, fontSize = 15.sp, lineHeight = 20.sp, color = c.muted) }
                if (preview.steward.isNotEmpty()) { Spacer(Modifier.height(8.dp)); Text("Steward: ${preview.steward}", fontSize = 14.sp, color = c.text) }
            }
            Spacer(Modifier.height(18.dp))
        }
        BusyButton(Cards.takenButton(kind), onClick = onJoin, modifier = Modifier.fillMaxWidth().testTag("card-join"))
    }
}

/** The code was right and the group is made; this phone could not reach the signer yet. */
@Composable
fun CardSignerContent(title: String, words: String, error: String?, onRetry: suspend () -> Unit) {
    SignedOutPage {
        Heading("${title.ifEmpty { "It" }} is created", "Your e-mail is confirmed. $words".trim())
        ErrorBox(error)
        Spacer(Modifier.height(6.dp))
        BusyButton("Try again", onClick = onRetry, modifier = Modifier.fillMaxWidth().testTag("card-retry"))
    }
}

/** cards/states-closed, -archived, -offline and a card nobody knows: why, and the belfry's page. */
@Composable
fun CardDeadContent(title: String, text: String?, community: String, onAgain: (() -> Unit)?, onVisit: () -> Unit, onClose: () -> Unit, icon: ImageVector = BelfryIcons.Qr) {
    val c = Belfry.colors
    SignedOutPage {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(c.surface), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = c.text, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(20.dp))
        Column(Modifier.testTag("card-dead")) { Heading(title, text) }
        if (onAgain != null) {
            BusyButton("Try again", onClick = { onAgain() }, modifier = Modifier.fillMaxWidth().testTag("card-again"))
            Spacer(Modifier.height(8.dp))
        }
        BusyButton("Visit $community", onClick = { onVisit() }, kind = if (onAgain != null) ButtonKind.Ghost else ButtonKind.Primary, modifier = Modifier.fillMaxWidth().testTag("card-visit"))
        Spacer(Modifier.height(8.dp))
        BusyButton("Close", onClick = { onClose() }, kind = ButtonKind.Ghost, modifier = Modifier.fillMaxWidth().testTag("card-close"))
    }
}
