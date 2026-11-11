package sh.belfry.app.ui.people

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.belfry.app.push.AndroidPush
import sh.belfry.app.ui.chat.ChatFormat
import sh.belfry.app.ui.chat.Composer
import sh.belfry.app.ui.chat.rememberComposerState
import sh.belfry.app.ui.kit.BackButton
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.BelfryTextField
import sh.belfry.app.ui.kit.DaySeparator
import sh.belfry.app.ui.kit.EmptyState
import sh.belfry.app.ui.kit.Hairline
import sh.belfry.app.ui.kit.IconAction
import sh.belfry.app.ui.kit.MarkdownText
import sh.belfry.app.ui.kit.Spinner
import sh.belfry.app.ui.kit.TopBar
import sh.belfry.app.ui.kit.rememberBusy
import sh.belfry.app.ui.nav.LocalDms
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.nav.Routes
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.dm.Dm
import sh.belfry.core.dm.DmSnapshot
import sh.belfry.core.engine.ActionException
import sh.belfry.core.model.World

/** One conversation: direct messages between members, or an e-mail thread with someone outside. */
@Composable
fun ConversationScreen(conv: String) {
    // while this is on screen, a "dm" push is not shown (the person is reading their messages)
    DisposableEffect(Unit) {
        AndroidPush.dmScreens.incrementAndGet()
        onDispose { AndroidPush.dmScreens.decrementAndGet() }
    }
    val c = Belfry.colors
    val world = LocalWorld.current
    val dms = LocalDms.current
    val session = LocalSession.current
    val nav = LocalNav.current
    val list = dms.thread(conv)
    val mail = dms.isMail(conv)
    val newMail = dms.isNewMail(conv)
    val address = if (mail) dms.mailAddress(conv) else ""
    val who = if (mail) dms.convName(conv, world) else ""
    val peers = if (mail) emptyList() else dms.peers(conv)
    val toSelf = !mail && (peers.isEmpty() || peers == listOf(world.me))
    val title = when {
        newMail -> "New e-mail to $who"
        mail -> dms.threadName(conv)
        toSelf -> "Notes to yourself"
        else -> dms.convName(conv, world)
    }
    val sub = when {
        mail -> if (who != address) "$who · $address" else "E-mail · $address"
        toSelf -> "Only you can read them"
        else -> peers.mapNotNull { world.profiles[it]?.nip05?.takeIf { a -> a.isNotEmpty() } }.joinToString(", ")
    }
    val state = rememberLazyListState()
    val composer = rememberComposerState(conv)
    var subject by rememberSaveable(conv) { mutableStateOf("") }
    val now = session.now()

    LaunchedEffect(conv, list.size) {
        session.markRead(conv)
        if (list.isNotEmpty()) state.scrollToItem(list.size - 1)
    }
    if (mail) LaunchedEffect(conv) { session.checkReports() }

    Column(Modifier.fillMaxSize().background(c.bg).imePadding()) {
        TopBar(
            title, subtitle = sub.ifEmpty { null },
            navigation = { BackButton { nav.back() } },
            actions = {
                if (mail && !newMail) IconAction(BelfryIcons.Plus, "New e-mail to $who", { nav.go(Routes.conversation("m:$address")) })
            },
        )
        if (list.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                EmptyState(
                    if (mail) "A new e-mail to $address" else "No messages yet",
                    text = if (mail) {
                        "Its subject names the thread. It goes out from ${world.myAddress ?: "your address here"}; answers land in the thread."
                    } else if (toSelf) {
                        "Write yourself a note. Only you can read it."
                    } else if (peers.size > 1) {
                        "Only the people here can read them."
                    } else {
                        "Only you two can read them."
                    },
                    icon = BelfryIcons.Mail,
                )
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = state, contentPadding = PaddingValues(bottom = 10.dp)) {
                itemsIndexed(list, key = { _, d -> d.rid }) { i, d ->
                    val prev = list.getOrNull(i - 1)
                    if (prev == null || world.dayOf(prev.at) != world.dayOf(d.at)) DaySeparator(ChatFormat.day(world, d.at, now))
                    Bubble(d, dms, world, group = peers.size > 1)
                }
            }
        }
        Hairline()
        if (newMail) {
            BelfryTextField(
                subject, { subject = it.take(200) }, placeholder = "Subject",
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp).testTag("dm-subject"),
            )
        }
        Composer(
            composer,
            placeholder = if (mail) "Write an e-mail" else "Message",
            onSend = { text, _, _, _ ->
                if (newMail && subject.isBlank()) throw ActionException("subject", "Give it a subject: it names the thread.")
                val target = session.sendDm(conv, text, if (newMail) subject.trim() else null)
                subject = ""
                if (target != conv) {
                    // A new e-mail became its thread: show it in this screen's place.
                    nav.back()
                    nav.go(Routes.conversation(target))
                }
            },
            modifier = Modifier.navigationBarsPadding(),
            photos = false,
        )
    }
}

@Composable
private fun Bubble(d: Dm, dms: DmSnapshot, world: World, group: Boolean) {
    val c = Belfry.colors
    val session = LocalSession.current
    val retry = rememberBusy()
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
        horizontalArrangement = if (d.mine) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            Modifier
                .widthIn(max = 320.dp)
                .clip(shape)
                .background(if (d.mine) c.surface2 else c.surface)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (!d.mine && !d.isMail && group) {
                Text(world.displayName(d.from), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.text)
            }
            if (d.isMail && !d.mine) {
                val name = d.fromName?.takeIf { it.isNotEmpty() }
                Text(if (name != null) "$name · ${d.from}" else d.from, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.text)
            }
            val subject = d.subject
            if (d.isMail && !subject.isNullOrEmpty()) {
                Text(subject, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.text, modifier = Modifier.padding(bottom = 2.dp))
            }
            if (d.text.isNotEmpty()) {
                MarkdownText(d.text, rich = false, color = if (d.pending) c.muted else c.text)
            } else if (d.attachments.isEmpty()) {
                Text("(empty)", fontSize = 15.sp, color = c.muted)
            }
            if (d.attachments.isNotEmpty()) Attachments(d)
            Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(ChatFormat.time(world, d.at), fontSize = 11.sp, color = c.muted)
                if (d.isMail && !d.mine) {
                    Spacer(Modifier.width(8.dp))
                    if (d.verified == true) {
                        Icon(BelfryIcons.Check, contentDescription = null, tint = c.muted, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("verified sender", fontSize = 11.sp, color = c.muted)
                    } else {
                        Text("unverified sender", fontSize = 11.sp, color = c.orange)
                    }
                }
                val line = dms.stateLine(d) ?: if (d.mine && d.isMail) "Sent" else null
                if (line != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(line, fontSize = 11.sp, color = if (d.failed != null) c.danger else c.muted, modifier = Modifier.weight(1f, fill = false))
                }
                if (d.failed != null) {
                    Spacer(Modifier.width(6.dp))
                    if (retry.busy) {
                        Spinner(size = 12.dp)
                    } else {
                        Text(
                            "Retry", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = c.danger,
                            modifier = Modifier.clickable(role = Role.Button) { retry.run { session.retryDm(d.rid) } }.padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

/** An e-mail's files: a tap asks where to save one (the system's own file saver), then writes it there. */
@Composable
private fun Attachments(d: Dm) {
    val c = Belfry.colors
    val session = LocalSession.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableIntStateOf(-1) }
    var note by remember { mutableStateOf<String?>(null) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val index = saving
        if (uri == null || index < 0) {
            saving = -1
        } else {
            scope.launch {
                note = try {
                    val part = session.attachmentsOf(d).getOrNull(index)
                    if (part == null) {
                        "This file is not on this device."
                    } else {
                        withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(part.data) } }
                        "Saved."
                    }
                } catch (e: Exception) {
                    "Not saved. Try again."
                }
                saving = -1
            }
        }
    }
    Column(Modifier.padding(top = 6.dp)) {
        d.attachments.forEachIndexed { i, a ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(c.bg)
                    .clickable(enabled = saving < 0, role = Role.Button) {
                        saving = i
                        note = null
                        try { saver.launch(a.name.ifEmpty { "attachment" }) } catch (e: Exception) { saving = -1; note = "No app on this device can save files." }
                    }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (saving == i) Spinner(size = 16.dp) else Icon(BelfryIcons.Upload, contentDescription = null, tint = c.muted, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(a.name.ifEmpty { "attachment" }, fontSize = 14.sp, color = c.text, maxLines = 1, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text(fileSize(a.size), fontSize = 12.sp, color = c.muted)
            }
        }
        val n = note
        if (n != null) Text(n, fontSize = 12.sp, color = c.muted, modifier = Modifier.padding(top = 2.dp))
    }
}
