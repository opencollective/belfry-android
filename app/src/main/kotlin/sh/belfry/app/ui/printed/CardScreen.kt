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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import sh.belfry.core.model.HashtagState
import sh.belfry.core.net.NativeSignIn

// A printed card's screens (SPEC 19.72–19.74; canvas "Belfry · Group cards": cards/claim-*,
// confirm-code, states-*; the web's ui/Card.tsx at chat f10e6b2, whose words these are). A free
// card: "Start this group" / "Start this topic" (signed in: straight in; a visitor: name and
// e-mail, the 6-digit code, then in). A claimed card is a guest link: its invite takes the usual
// way in. Dead ends say why. Nothing from the address is ever shown.

/** Who is signed in, for "Signed in as …". */
data class CardAccount(val pubkey: String, val name: String, val picture: String? = null)

/** What the group someone else just started looks like (the card's guest link says). */
data class CardPreview(val title: String, val about: String, val steward: String)

/**
 * Everything a card's screen asks of the app: the same screens serve a visitor (the calls go
 * through `Onboarding`, the code makes a session) and a signed-in account (through its `Session`).
 */
class CardDoor(
    /** The belfry's name, for "Visit …" and the hint under the e-mail. */
    val community: String,
    val domain: String,
    /** Null: a visitor (name, e-mail and a code are asked). */
    val signedIn: CardAccount?,
    val info: suspend () -> CardInfo,
    val hashtag: suspend (String) -> HashtagState,
    /** Signed in: the claim. Returns when the group is made and being opened. */
    val claim: suspend (CardKind, CardForm) -> Unit = { _, _ -> },
    /** A visitor: the code goes to this address. */
    val start: suspend (String) -> Unit = {},
    /** A visitor: the code. Returns when this phone is signed in. */
    val verify: suspend (CardKind, CardForm, String) -> Unit = { _, _, _ -> },
    /** The code was right and the signer did not answer: once more. False: nothing waits any more. */
    val retry: suspend () -> Boolean = { false },
    val keptForm: suspend () -> CardForm? = { null },
    val keepForm: suspend (CardForm) -> Unit = {},
    val dropForm: suspend () -> Unit = {},
    val preview: suspend (invite: String) -> CardPreview? = { null },
    /** A claimed card: its guest link's way in. */
    val join: (kind: CardKind, group: String, invite: String) -> Unit = { _, _, _ -> },
    val visit: () -> Unit = {},
    val close: () -> Unit = {},
)

private sealed interface Shown {
    data object Opening : Shown
    data class Info(val info: CardInfo) : Shown
    /** The belfry could not be asked: OFFLINE, TOO_MANY or UNSUPPORTED. */
    data class Dead(val code: CardError) : Shown
}

/** The card LINK, through DOOR. */
@Composable
fun CardScreen(door: CardDoor, link: CardLink) {
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

private enum class Step { FORM, CODE, TAKEN, SIGNER }

@Composable
private fun StartFlow(door: CardDoor, link: CardLink, kind: CardKind, onRetired: () -> Unit) {
    var f by remember(link) { mutableStateOf(CardForm()) }
    var loaded by remember(link) { mutableStateOf(false) }
    var dropped by remember(link) { mutableStateOf(false) }
    // the hashtag is suggested from the topic's words until it is changed by hand
    var edited by remember(link) { mutableStateOf(false) }
    var step by remember(link) { mutableStateOf(Step.FORM) }
    var error by remember(link) { mutableStateOf<String?>(null) }
    var note by remember(link) { mutableStateOf<String?>(null) }
    var tag by remember(link) { mutableStateOf<HashtagState?>(null) }
    var checking by remember(link) { mutableStateOf(false) }
    var recheck by remember(link) { mutableIntStateOf(0) }
    var taken by remember(link) { mutableStateOf<CardFailed?>(null) }
    var preview by remember(link) { mutableStateOf<CardPreview?>(null) }
    var signerWords by remember(link) { mutableStateOf("") }

    // what was typed comes back (a closed app, the code's e-mail read in another app) and is kept as it changes
    LaunchedEffect(link) {
        val kept = try { door.keptForm() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        if (kept != null && f == CardForm()) { f = kept; edited = Cards.hashtagEdited(kept) }
        loaded = true
    }
    LaunchedEffect(f, loaded) {
        if (!loaded || dropped || f == CardForm()) return@LaunchedEffect
        delay(400)
        if (!dropped) door.keepForm(f)
    }
    // the hashtag's live check (a topic card): taken and still the suggestion → the next free one
    val slug = Cards.hashtagSlug(f.hashtag)
    LaunchedEffect(slug, kind, recheck) {
        if (kind != CardKind.TOPIC) return@LaunchedEffect
        if (slug.length < 2) { checking = false; tag = if (f.hashtag.isBlank()) null else HashtagState.INVALID; return@LaunchedEffect }
        checking = true
        delay(350)
        val s = door.hashtag(slug)
        if (s == HashtagState.TAKEN && !edited) {
            val next = Cards.nextFree(slug, door.hashtag)
            if (next != null) { f = f.copy(hashtag = next); return@LaunchedEffect }
        }
        tag = s
        checking = false
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
    val recheckNow: () -> Unit = { recheck++ }
    val ready = Cards.ready(kind, f, door.signedIn != null, if (checking) null else tag)

    when (step) {
        Step.FORM -> CardStartContent(
            kind = kind, community = door.community, signedIn = door.signedIn, form = f,
            onTitle = { v -> f = f.copy(title = v.take(80), hashtag = if (kind == CardKind.TOPIC && !edited) Cards.hashtagSlug(v) else f.hashtag); error = null },
            onHashtag = { v -> edited = true; f = f.copy(hashtag = v.take(31)); error = null },
            onAbout = { v -> f = f.copy(about = v.take(500)) },
            onName = { v -> f = f.copy(name = v.take(100)) },
            onEmail = { v -> f = f.copy(email = v.take(200)); error = null },
            tagLine = Cards.hashtagLine(tag, checking, edited), tagBad = !checking && (tag == HashtagState.TAKEN || tag == HashtagState.INVALID),
            onRecheck = recheckNow.takeIf { !checking && tag == HashtagState.UNKNOWN },
            error = error, ready = ready, onClose = door.close,
            onStart = {
                error = null
                if (ready) try {
                    if (door.signedIn != null) door.claim(kind, f) else { door.start(f.email); note = null; step = Step.CODE }
                } catch (e: CancellationException) { throw e } catch (e: Exception) { fail(e, false) }
            },
        )
        Step.CODE -> {
            BackHandler { step = Step.FORM; error = null; note = null }
            CardCodeContent(
                email = f.email.trim(), title = f.title.trim(), error = error, note = note,
                onVerify = { code ->
                    error = null; note = null
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
                    }
                },
                onResend = {
                    error = null
                    try { door.start(f.email); note = "A new code is on its way to your e-mail." } catch (e: CancellationException) { throw e } catch (e: Exception) { fail(e, false) }
                },
                onOther = { step = Step.FORM; error = null; note = null },
            )
        }
        Step.TAKEN -> {
            val t = taken
            CardTakenContent(kind, preview, onJoin = { if (t?.group != null && t.invite != null) door.join(kind, t.group!!, t.invite!!) })
        }
        Step.SIGNER -> CardSignerContent(f.title.trim(), signerWords, error) {
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

/** "Opening the card…". */
@Composable
fun CardOpeningContent() {
    Box(Modifier.fillMaxSize().background(Belfry.colors.bg), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("card-opening")) {
            Spinner()
            Spacer(Modifier.width(10.dp))
            Text(Cards.OPENING, fontSize = 16.sp, color = Belfry.colors.muted)
        }
    }
}

/** cards/claim-visitor, claim-signed-in, claim-channel: the form of a card nobody has started. */
@Composable
fun CardStartContent(
    kind: CardKind, community: String, signedIn: CardAccount?, form: CardForm,
    onTitle: (String) -> Unit, onHashtag: (String) -> Unit, onAbout: (String) -> Unit, onName: (String) -> Unit, onEmail: (String) -> Unit,
    /** The line under the hashtag (its live check); [tagBad]: it can't be used as it is. */
    tagLine: String?, tagBad: Boolean, onRecheck: (() -> Unit)?,
    error: String?, ready: Boolean, onClose: () -> Unit, onStart: suspend () -> Unit,
) {
    val c = Belfry.colors
    SignedOutPage {
        Box(Modifier.padding(bottom = 12.dp)) { BackButton(onClose) }
        Heading(Cards.startTitle(kind), Cards.startIntro(kind))
        if (signedIn != null) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface).padding(12.dp).testTag("card-who"), verticalAlignment = Alignment.CenterVertically) {
                Avatar(signedIn.pubkey, signedIn.name, picture = signedIn.picture, size = 32.dp)
                Spacer(Modifier.width(10.dp))
                Text(buildAnnotatedString {
                    append("Signed in as ")
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = c.text)) { append(signedIn.name) }
                }, fontSize = 15.sp, color = c.muted)
            }
            Spacer(Modifier.height(18.dp))
        }
        BelfryTextField(
            form.title, onTitle, Modifier.fillMaxWidth().testTag("card-title"), label = Cards.titleLabel(kind), placeholder = Cards.TITLE_HINT,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        )
        if (kind == CardKind.TOPIC) {
            Spacer(Modifier.height(14.dp))
            Text("Hashtag", fontSize = 13.sp, color = c.muted)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(BelfryIcons.Hash, contentDescription = null, tint = c.text, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                BelfryTextField(
                    form.hashtag, onHashtag, Modifier.weight(1f).testTag("card-hashtag"), placeholder = "gluten-free-baking",
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
                )
            }
            if (tagLine != null) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                    Text(tagLine, fontSize = 13.sp, lineHeight = 18.sp, color = if (tagBad) c.danger else c.muted, fontWeight = if (tagBad) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f, fill = false).testTag("card-tag"))
                    if (onRecheck != null) TextButton(onClick = onRecheck, modifier = Modifier.testTag("card-recheck")) { Text("Try again", fontSize = 14.sp, color = c.text, fontWeight = FontWeight.SemiBold) }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        BelfryTextField(
            form.about, onAbout, Modifier.fillMaxWidth().testTag("card-about"), label = "Description (optional)",
            placeholder = if (kind == CardKind.TOPIC) "What people share here" else null, singleLine = false, minLines = 3, maxLines = 8,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        )
        if (signedIn == null) {
            Spacer(Modifier.height(14.dp))
            BelfryTextField(form.name, onName, Modifier.fillMaxWidth().testTag("card-name"), label = "Your name", keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words))
            Spacer(Modifier.height(14.dp))
            BelfryTextField(
                form.email, onEmail, Modifier.fillMaxWidth().testTag("card-email"), label = "E-mail", placeholder = "you@example.org",
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Email),
            )
            Spacer(Modifier.height(8.dp))
            Text(Cards.codeHint(community), fontSize = 13.sp, lineHeight = 18.sp, color = c.muted)
        }
        ErrorBox(error)
        Spacer(Modifier.height(18.dp))
        BusyButton(Cards.startButton(kind), onClick = onStart, enabled = ready, modifier = Modifier.fillMaxWidth().testTag("card-start"))
    }
}

/** cards/confirm-code (the code only, as the web shipped it): six boxes, the sixth digit sends. */
@Composable
fun CardCodeContent(
    email: String, title: String, error: String?, note: String?,
    /** Returns true when the boxes are to be emptied. */
    onVerify: suspend (String) -> Boolean, onResend: suspend () -> Unit, onOther: () -> Unit,
    /** For screenshots: digits typed already. */
    initial: String = "",
) {
    val c = Belfry.colors
    var digits by remember { mutableStateOf(initial) }
    var wait by remember { mutableIntStateOf(30) }
    var sends by remember { mutableIntStateOf(0) }
    val busy = rememberBusy()
    val focus = remember { FocusRequester() }
    LaunchedEffect(sends) { wait = 30; while (wait > 0) { delay(1000); wait-- } }
    SignedOutPage {
        Box(Modifier.padding(bottom = 12.dp)) { BackButton(onOther) }
        Heading("Check your e-mail", null)
        Text(buildAnnotatedString {
            append("We sent a 6-digit code to ")
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = c.text)) { append(email) }
            append(". It can take a minute.")
        }, fontSize = 16.sp, lineHeight = 22.sp, color = c.muted)
        Spacer(Modifier.height(20.dp))
        CodeBoxes(digits, { raw ->
            val d = NativeSignIn.next(digits, raw)
            if (d != digits && !busy.busy) {
                digits = d
                // one claim per code typed: the boxes are locked until the belfry answered
                if (d.length == 6) busy.run { if (onVerify(d)) digits = "" }
            }
        }, enabled = !busy.busy, error = error != null, focus = focus)
        if (error != null) ErrorBox(error) else {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("card-code-line")) {
                if (busy.busy) { Spinner(size = 16.dp); Spacer(Modifier.width(8.dp)) }
                Text(if (busy.busy) "Starting $title…" else note ?: "Not there? Look in your spam folder.", fontSize = 14.sp, lineHeight = 19.sp, color = c.muted)
            }
        }
        Spacer(Modifier.height(16.dp))
        ActionGroup {
            ActionRow(
                if (wait > 0) "Send a new code (in $wait s)" else "Send a new code",
                onClick = { if (wait == 0) busy.run { onResend(); digits = ""; sends++ } },
                icon = BelfryIcons.Mail, enabled = wait == 0 && !busy.busy, modifier = Modifier.testTag("card-resend"),
            )
            ActionRow("Use another address", onClick = onOther, icon = BelfryIcons.Back, enabled = !busy.busy)
        }
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
        Heading("${title.ifEmpty { "It" }} is started", "Your code was right. $words".trim())
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
