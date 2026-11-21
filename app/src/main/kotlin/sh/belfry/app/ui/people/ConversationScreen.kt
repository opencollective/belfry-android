package sh.belfry.app.ui.people

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import sh.belfry.app.ui.chat.face
import sh.belfry.app.ui.kit.Avatar
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
import sh.belfry.core.dm.DmNewFile
import sh.belfry.core.dm.DmSnapshot
import sh.belfry.core.model.DeviceNotice
import sh.belfry.core.model.SharedBlock
import androidx.compose.runtime.collectAsState
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
    // a long-press on a bubble (or its ☺+): the menu with the reactions row
    var acting by remember(conv) { mutableStateOf<Pair<String, Boolean>?>(null) }
    // one person: their face and name at the top open their profile (canvas home/conversation)
    val peer = if (!mail && !toSelf && peers.size == 1) peers[0] else null
    // sealed (canvas messages/encryption-*): a lock by the name and device lines among the messages;
    // a conversation that isn't sealed shows neither. Channels never have a lock.
    val sealState by session.encryption.state.collectAsState()
    val seal = if (newMail) null else sealState.seal(conv)
    val openNotice = rememberNoticeOpener(conv)
    // a device not yet allowed (canvas v84): messages from before the conversation was sealed read
    // as usual, then one amber block in place of the rest; no composer
    val lockedHere = seal != null && conv in sealState.locked
    val until = if (lockedHere) seal?.since ?: Long.MIN_VALUE else Long.MAX_VALUE
    val rows: List<Any> = remember(list, seal, until) {
        val shown = DmGroups.collapse(list.filter { it.at < until })
        val merged = if (seal == null || seal.notices.isEmpty()) shown
        else (shown.map { r -> (if (r is DmGroup) r.at else (r as Dm).at) to r } + seal.notices.filter { it.at < until }.map { it.at to it }).sortedBy { it.first }.map { it.second }
        // history someone shared with this device (canvas v86): a "Shared by" line above each block
        val out = ArrayList<Any>()
        var open: SharedBlock? = null
        for (r in merged) {
            val block = when (r) { is Dm -> seal?.shared?.firstOrNull { it.holds(r.at) }; is DmGroup -> seal?.shared?.firstOrNull { it.holds(r.at) }; else -> null }
            if (block != null && block != open) out.add(block)
            open = block ?: open.takeIf { r !is Dm && r !is DmGroup }
            out.add(r)
        }
        if (lockedHere) out.add(LockedHere)
        out
    }
    fun atOf(r: Any): Long? = when (r) {
        is Dm -> r.at
        is DmGroup -> r.at
        is DeviceNotice -> r.at
        is SharedBlock -> r.from
        else -> null
    }
    var why by remember(conv) { mutableStateOf<SharedBlock?>(null) }
    var notSealedOpen by remember(conv) { mutableStateOf(false) }
    var plusOpen by remember(conv) { mutableStateOf(false) }
    var recording by remember(conv) { mutableStateOf(false) }

    LaunchedEffect(conv, rows.size) {
        if (rows.isNotEmpty()) state.scrollToItem(rows.size - 1)
    }
    // read state (SPEC 19.27): on screen with the app in the foreground → read up to its newest message
    val started = sh.belfry.app.ui.kit.rememberStarted()
    LaunchedEffect(conv, list.size, started) { if (started) session.markRead(conv) }
    if (mail) LaunchedEffect(conv) { session.checkReports() }

    Column(Modifier.fillMaxSize().background(c.bg).imePadding()) {
        TopBar(
            title, subtitle = sub.ifEmpty { null },
            navigation = { BackButton { nav.back() } },
            onTitleClick = peer?.let { p -> { nav.go(Routes.profile(p)) } },
            titleLeading = peer?.let { p -> { Avatar(world.face(p), size = 34.dp) } },
            titleMark = if (seal != null) ({ SealLock { nav.go(Routes.encryption(conv)) } }) else null,
            actions = {
                if (mail && !newMail) IconAction(BelfryIcons.Plus, "New e-mail to $who", { nav.go(Routes.conversation("m:$address")) })
            },
        )
        if (rows.isEmpty()) {
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
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = state, contentPadding = PaddingValues(bottom = 10.dp + sh.belfry.app.ui.chat.TYPING_ROOM)) {
                itemsIndexed(rows, key = { _, r ->
                    when (r) {
                        is Dm -> r.rid
                        is DmGroup -> "group:" + r.key
                        is DeviceNotice -> "notice:" + r.id
                        is SharedBlock -> "shared:" + r.by + ":" + r.from
                        else -> "locked"
                    }
                }) { i, r ->
                    val prev = rows.getOrNull(i - 1)
                    val at = atOf(r)
                    val prevAt = prev?.let { atOf(it) }
                    if (at != null && (prevAt == null || world.dayOf(prevAt) != world.dayOf(at))) DaySeparator(ChatFormat.day(world, at, now))
                    when (r) {
                        is Dm -> {
                            val shared = seal?.shared?.any { it.holds(r.at) } == true
                            if (shared) {
                                SharedRule { Bubble(r, dms, world, group = peers.size > 1, onMenu = { emojiOnly -> acting = r.rid to emojiOnly }, notSealed = if (seal?.isNotSealed(r.at) == true) ({ notSealedOpen = true }) else null) }
                            } else {
                                Bubble(r, dms, world, group = peers.size > 1, onMenu = { emojiOnly -> acting = r.rid to emojiOnly }, notSealed = if (seal?.isNotSealed(r.at) == true) ({ notSealedOpen = true }) else null)
                            }
                        }
                        is DmGroup -> FilesBubble(r, dms, world, group = peers.size > 1, onMenu = { rid, emojiOnly -> acting = rid to emojiOnly }) {
                            Bubble(it, dms, world, group = false, onMenu = { emojiOnly -> acting = it.rid to emojiOnly })
                        }
                        is DeviceNotice -> DeviceNoticeRow(r, world) { openNotice(r) }
                        is SharedBlock -> SharedByRow(r, world) { why = r }
                        else -> LockedBlock(seal?.since, world, now) { nav.go(Routes.UNLOCK_STEPS) }
                    }
                }
            }
        }
        // canvas v110 messages/typing-dm (never for e-mail)
        if (!mail) {
            val lastDm = remember(list) { HashMap<String, Long>().also { m -> for (d in list) if (d.at > (m[d.from] ?: 0L)) m[d.from] = d.at } }
            sh.belfry.app.ui.chat.TypingLine(sh.belfry.core.typing.TypingPlace.dm(conv)) { lastDm[it] ?: 0L }
        }
        why?.let { b -> SharedWhySheet(b, world) { why = null } }
        if (notSealedOpen) NotSealedSheet { notSealedOpen = false }
        Hairline()
        if (newMail) {
            BelfryTextField(
                subject, { subject = it.take(200) }, placeholder = "Subject",
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp).testTag("dm-subject"),
            )
        }
        acting?.let { (rid, emojiOnly) ->
            val d = list.firstOrNull { it.rid == rid }
            if (d == null) acting = null else DmMessageSheet(d, dms.reactionsOf(d.rid), emojiOnly, onClose = { acting = null })
        }
        if (lockedHere) {
            Text(
                sh.belfry.core.model.SealedText.WRITE_LATER, fontSize = 14.sp, color = c.muted,
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 16.dp).testTag("seal-write-later"),
            )
        } else Composer(
            composer,
            placeholder = if (mail && !newMail && seal != null) "Reply by e-mail" else if (mail) "Write an e-mail" else "Message",
            onSend = { text, files, _, _ ->
                if (files.isNotEmpty() && !mail) {
                    // pictures with the text as their caption (canvas v109)
                    session.sendDmFiles(conv, files.map { DmNewFile(it.name, it.mime, it.bytes, it.width, it.height) }, text.takeIf { it.isNotBlank() })
                    return@Composer
                }
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
            photos = !mail,
            onPlus = if (!mail) ({ plusOpen = true }) else null,
            typingPlace = if (mail) null else sh.belfry.core.typing.TypingPlace.dm(conv),
        )
        if (plusOpen) DmPlusSheet(conv, composer, onClose = { plusOpen = false }, onRecord = { recording = true })
        if (recording) DmRecorderSheet(conv) { recording = false }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(d: Dm, dms: DmSnapshot, world: World, group: Boolean, onMenu: (emojiOnly: Boolean) -> Unit, notSealed: (() -> Unit)? = null) {
    val c = Belfry.colors
    val session = LocalSession.current
    val retry = rememberBusy()
    val shape = RoundedCornerShape(14.dp)
    val chips = if (d.isMail) emptyList() else dms.reactionsOf(d.rid)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
        horizontalAlignment = if (d.mine) Alignment.End else Alignment.Start,
    ) {
        Column(
            Modifier
                .widthIn(max = 320.dp)
                .clip(shape)
                .background(if (d.mine) c.surface2 else c.surface)
                .combinedClickable(onClickLabel = null, onLongClickLabel = "Message actions", onLongClick = { onMenu(false) }, onClick = {})
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .testTag("dm-bubble"),
        ) {
            if (!d.mine && !d.isMail && group) {
                Text(world.displayName(d.from), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.text)
            }
            // the header names the conversation and its subject: each mail doesn't repeat them
            // (Xavier, 2026-11-11); in a mail thread with several people, who wrote it still shows
            if (d.isMail && !d.mine && group) {
                val name = d.fromName?.takeIf { it.isNotEmpty() }
                Text(name ?: d.from, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.text)
            }
            if (d.text.isNotEmpty()) {
                MarkdownText(d.text, rich = false, color = if (d.pending) c.muted else c.text)
            } else if (d.attachments.isEmpty()) {
                Text("(empty)", fontSize = 15.sp, color = c.muted)
            }
            if (d.attachments.isNotEmpty()) Attachments(d)
            Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(ChatFormat.time(world, d.at), fontSize = 11.sp, color = c.muted)
                // canvas v89: arrived the old way after the conversation was sealed
                if (notSealed != null) NotSealedMark(notSealed)
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
                // a direct message not out yet: Sending… → Still sending… · <step> → Not sent yet · Retry (SPEC 19.50)
                val sendLine = if (d.mine && d.pending && d.failed == null && !d.isMail) sh.belfry.app.ui.chat.rememberSendLine(d.rid, 0) else null
                val line = sendLine?.text ?: dms.stateLine(d) ?: if (d.mine && d.isMail) "Sent" else null
                if (line != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(line, fontSize = 11.sp, color = if (d.failed != null) c.danger else c.muted, modifier = Modifier.weight(1f, fill = false))
                }
                if (sendLine?.retry == true) {
                    Spacer(Modifier.width(6.dp))
                    if (retry.busy) {
                        Spinner(size = 12.dp)
                    } else {
                        Text(
                            "Retry", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = c.text,
                            modifier = Modifier.clickable(role = Role.Button) { retry.run { session.sendNow() } }.padding(horizontal = 4.dp, vertical = 2.dp).testTag("dm-send-retry"),
                        )
                    }
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
        if (chips.isNotEmpty()) DmReactionChips(d, chips, onAdd = { onMenu(true) })
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
