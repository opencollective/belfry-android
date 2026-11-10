package sh.belfry.app.ui.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import sh.belfry.app.ui.kit.BelfrySheet
import sh.belfry.app.ui.kit.BelfryTextField
import sh.belfry.app.ui.kit.BusyButton
import sh.belfry.app.ui.kit.Hairline
import sh.belfry.app.ui.kit.Spinner
import sh.belfry.app.ui.nav.LocalDms
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.dm.DmReader
import sh.belfry.core.dm.LargeMail
import sh.belfry.core.dm.Report
import sh.belfry.core.engine.Session
import sh.belfry.core.mail.Mime
import sh.belfry.core.mail.ParsedMail
import sh.belfry.core.model.MailOriginal
import sh.belfry.core.model.Message
import sh.belfry.core.model.Special

/** An answer to a shared-inbox e-mail, waiting out its 8 seconds of "Undo send". */
internal class PendingReply(
    val channel: String,
    val to: String,
    /** The sender's first name (or address), for the lines we show. */
    val first: String,
    val subject: String,
    val text: String,
    val inReplyTo: String?,
    val cardId: String,
)

/**
 * The one answer on its way out. It lives outside the feed because the card that started it may
 * scroll away during the countdown; the bar that counts down and sends is [UndoSendHost], under
 * the channel's top bar. Leaving the channel during the countdown sends at once (as on the web:
 * nobody should believe it went when it did not).
 */
internal object ReplyQueue {
    const val UNDO_SECONDS = 8

    var pending by mutableStateOf<PendingReply?>(null)
    /** The email-id of the last answer sent (its delivery report is shown until dismissed). */
    var sent by mutableStateOf<Pair<String, String>?>(null) // email-id to first name
    /** A send that failed: shown with Retry. */
    var failed by mutableStateOf<Pair<PendingReply, String>?>(null)
    /** Texts kept per card: an undone or unsent answer comes back in its sheet. */
    val drafts = mutableStateMapOf<String, String>()
    /** A card whose reply sheet should open again (after Undo). */
    var reopen by mutableStateOf<String?>(null)
    /** How many [UndoSendHost]s are on screen. Without one, answers go at once. */
    var hosts by mutableIntStateOf(0)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun queue(session: Session, p: PendingReply) {
        failed = null
        sent = null
        drafts[p.cardId] = p.text
        if (hosts > 0) pending = p else fire(session, p)
    }

    fun undo() {
        val p = pending ?: return
        pending = null
        reopen = p.cardId
    }

    fun fire(session: Session, p: PendingReply) {
        if (pending === p) pending = null
        scope.launch {
            try {
                val id = session.sendAsChannel(p.channel, p.to, p.subject, p.text, p.inReplyTo, p.cardId)
                drafts.remove(p.cardId)
                sent = id to p.first
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // ActionException carries a fixed, displayable message; anything else gets ours
                failed = p to ((e as? sh.belfry.core.engine.ActionException)?.message ?: "The e-mail could not be sent. Try again.")
            }
        }
    }

    /** Tests start clean. */
    fun reset() {
        pending = null; sent = null; failed = null; reopen = null; hosts = 0; drafts.clear()
    }
}

/** Under the channel's top bar: the Undo countdown, then how the e-mail fared. [second] waits one second (tests pass their own). */
@Composable
internal fun UndoSendHost(groupId: String, second: suspend () -> Unit = { delay(1000) }) {
    val session = LocalSession.current
    val dms = LocalDms.current

    DisposableEffect(groupId) {
        ReplyQueue.hosts++
        onDispose {
            ReplyQueue.hosts = (ReplyQueue.hosts - 1).coerceAtLeast(0)
            val p = ReplyQueue.pending
            if (p != null && p.channel == groupId) ReplyQueue.fire(session, p)
        }
    }

    val p = ReplyQueue.pending?.takeIf { it.channel == groupId }
    if (p != null) {
        var left by remember(p) { mutableIntStateOf(ReplyQueue.UNDO_SECONDS) }
        LaunchedEffect(p) {
            while (left > 0) {
                second()
                left--
            }
            if (ReplyQueue.pending === p) ReplyQueue.fire(session, p)
        }
        NoticeBar("Sending to ${p.first} in $left s", "Undo", "undo-send") { ReplyQueue.undo() }
        return
    }

    val failed = ReplyQueue.failed?.takeIf { it.first.channel == groupId }
    if (failed != null) {
        NoticeBar("Not sent: ${failed.second}", "Retry", "undo-retry", danger = true) { ReplyQueue.queue(session, failed.first) }
        return
    }

    val sent = ReplyQueue.sent
    if (sent != null) {
        val r = dms.report(sent.first)
        val text = when (r?.status) {
            Report.DELIVERED -> "E-mail sent to ${sent.second}"
            Report.FAILED -> "Not delivered" + (if (r.details.isNotEmpty()) ": ${r.details}" else "")
            Report.DELAYED -> "Delayed, the provider retries"
            else -> "Sending the e-mail to ${sent.second}…"
        }
        if (r?.status == Report.DELIVERED) {
            LaunchedEffect(sent) {
                delay(4000)
                if (ReplyQueue.sent === sent) ReplyQueue.sent = null
            }
        }
        NoticeBar(text, if (r?.status == Report.QUEUED) null else "OK", "undo-ok", danger = r?.status == Report.FAILED) { ReplyQueue.sent = null }
    }
}

@Composable
private fun NoticeBar(text: String, action: String?, tag: String, danger: Boolean = false, onAction: () -> Unit) {
    val c = Belfry.colors
    Column {
        Row(
            Modifier.fillMaxWidth().background(c.accent).padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp).heightIn(min = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, Modifier.weight(1f).padding(vertical = 6.dp), fontSize = 14.sp, lineHeight = 19.sp, color = c.onAccent, fontWeight = if (danger) FontWeight.SemiBold else FontWeight.Normal)
            if (action != null) {
                Text(
                    action,
                    Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onAction).testTag(tag).padding(horizontal = 12.dp, vertical = 8.dp),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = c.onAccent,
                )
            }
        }
        Hairline()
    }
}

/** Who answers a shared inbox by e-mail: the channel's admins (the bridge checks it again). */
internal const val REPLY_NOT_ALLOWED = "Only this channel's admins can answer by e-mail as the channel."

/** "E-mail to <first>": To, From, Subject, the text, Send (then 8 seconds to undo). */
@Composable
internal fun ReplySheet(groupId: String, m: Message, card: Special.InboxCard, onDismiss: () -> Unit) {
    val session = LocalSession.current
    val world = LocalWorld.current
    val who = if (card.name.isNotEmpty()) "${card.name} <${card.from}>" else card.from
    val first = card.name.split(' ').firstOrNull { it.isNotEmpty() } ?: card.from
    val subject = if (card.subject.startsWith("re:", ignoreCase = true)) card.subject else "Re: " + card.subject.ifEmpty { "(no subject)" }
    val channelName = world.group(groupId)?.name ?: groupId
    val myName = world.realName(world.me)
    var text by remember(m.id) { mutableStateOf(ReplyQueue.drafts[m.id] ?: "") }

    BelfrySheet(onDismiss = onDismiss, title = "E-mail to $first") {
        Column(Modifier.padding(bottom = 16.dp)) {
            HeaderLine("To", who)
            HeaderLine("From", world.mailAddress(groupId), "Signed “" + (if (myName.isNotEmpty()) "$myName for $channelName" else channelName) + "”")
            HeaderLine("Subject", subject)
            Gap(10)
            BelfryTextField(
                text,
                { text = it; ReplyQueue.drafts[m.id] = it },
                Modifier.fillMaxWidth().testTag("reply-text"),
                placeholder = "Write to $first…",
                singleLine = false,
                minLines = 5,
                maxLines = 10,
            )
            Gap(8)
            Muted("$first will receive exactly this. The thread stays inside the belfry.")
            Gap(12)
            BusyButton(
                "Send e-mail",
                onClick = {
                    ReplyQueue.queue(session, PendingReply(groupId, card.from, first, subject, text.trim(), card.messageId, m.id))
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth().testTag("reply-send"),
                enabled = text.isNotBlank(),
            )
        }
    }
}

@Composable
private fun HeaderLine(label: String, value: String, sub: String? = null) {
    val c = Belfry.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, Modifier.width(68.dp), fontSize = 13.sp, color = c.muted)
        Column(Modifier.weight(1f)) {
            Text(value, fontSize = 14.sp, lineHeight = 19.sp, color = c.text)
            if (sub != null) Text(sub, fontSize = 12.sp, lineHeight = 16.sp, color = c.muted)
        }
    }
}

private val mailHttp: OkHttpClient by lazy { OkHttpClient() }

/** Fetches and opens a whole e-mail kept on the file server. Null when it cannot be opened. */
private suspend fun openOriginal(o: MailOriginal): ParsedMail? = withContext(Dispatchers.IO) {
    try {
        if (!o.url.startsWith("https://")) return@withContext null
        mailHttp.newCall(Request.Builder().url(o.url).build()).execute().use { res ->
            if (!res.isSuccessful) return@use null
            val bytes = res.body?.bytes() ?: return@use null
            Mime.parseMail(DmReader.decryptLarge(bytes, LargeMail(o.url, o.key, o.nonce)))
        }
    } catch (e: Exception) {
        null
    }
}

/** The whole e-mail, headers and quoted history included, as plain text. */
@Composable
internal fun FullMailSheet(subject: String, original: MailOriginal, onDismiss: () -> Unit) {
    val session = LocalSession.current
    val c = Belfry.colors
    var mail by remember(original) { mutableStateOf<ParsedMail?>(null) }
    var failed by remember(original) { mutableStateOf(false) }
    LaunchedEffect(original) {
        val got = if (session.localOnly) null else openOriginal(original)
        if (got == null) failed = true else mail = got
    }
    BelfrySheet(onDismiss = onDismiss, title = subject.ifEmpty { "E-mail" }) {
        Column(Modifier.padding(bottom = 16.dp)) {
            val got = mail
            when {
                failed -> ErrorLine("The e-mail could not be opened.")
                got == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Spinner()
                    GapW(10)
                    Muted("Opening…")
                }
                else -> {
                    HeaderLine("From", got.from)
                    got.headers["to"]?.let { HeaderLine("To", it) }
                    got.headers["date"]?.let { HeaderLine("Date", it) }
                    Gap(8)
                    Text(
                        got.full,
                        Modifier.fillMaxWidth().heightIn(max = 420.dp).clip(RoundedCornerShape(8.dp)).background(c.surface).verticalScroll(rememberScrollState()).padding(10.dp),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = c.text,
                    )
                    if (got.attachments.isNotEmpty()) {
                        Gap(8)
                        Muted("${got.attachments.size} attachment(s), shown in the message.")
                    }
                }
            }
        }
    }
}
