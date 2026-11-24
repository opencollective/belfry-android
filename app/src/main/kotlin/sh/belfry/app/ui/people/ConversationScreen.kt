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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.draw.alpha
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
    // replies show under their root, not in line (docs/one-shape.md 3); a reply whose root is unknown does
    val inline = dms.inline(conv)
    val mail = dms.isMail(conv)
    val newMail = dms.isNewMail(conv)
    val address = if (mail) dms.mailAddress(conv) else ""
    val who = if (mail) dms.convName(conv, world) else ""
    val peers = if (mail) emptyList() else dms.peers(conv)
    val toSelf = !mail && (peers.isEmpty() || peers == listOf(world.me))
    val title = when {
        newMail -> "New e-mail to $who"
        mail -> dms.threadName(conv)
        toSelf -> sh.belfry.core.model.Notes.TITLE
        else -> dms.convName(conv, world)
    }
    val sub = when {
        mail -> if (who != address) "$who · $address" else "E-mail · $address"
        toSelf -> ""
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
    // format 3 (SPEC 19.84): the lock next to the name opens Who can read this; on a format-3 belfry the v1 lock never shows
    val enc3 = session.enc3View
    val format3 = enc3?.format3 == true
    val enc3Lock = enc3?.locks(conv, mail, newMail) == true
    val firstEnc = if (enc3 != null && !mail) enc3.firstKeyAt(conv) else null
    var whoOpen by remember(conv) { mutableStateOf(false) }
    val beside = whoBeside()
    // a sealed conversation opened: its people's chains asked again, kept messages tried again (§4)
    LaunchedEffect(conv, seal != null) { if (seal != null) session.sealedOpened(conv) }
    // a device not yet allowed (canvas v84): messages from before the conversation was sealed read
    // as usual, then one amber block in place of the rest; no composer
    // format 3 (SPEC 19.85): the version-1 locked block, waiting lines and Start fresh never show on a format-3 belfry
    val enc3Dev by rememberEnc3Device()
    val v1Ui = !enc3Dev.on
    val lockedHere = v1Ui && seal != null && conv in sealState.locked
    val until = if (lockedHere) seal?.since ?: Long.MIN_VALUE else Long.MAX_VALUE
    // format 3 (SPEC 19.86): version 1's "Shared by" history lines never show on a format-3 belfry (format 1 keeps them)
    val v1Shared = if (format3 || !v1Ui) emptyList() else seal?.shared.orEmpty()
    val rows: List<Any> = remember(inline, seal, until, firstEnc, format3, v1Shared) {
        val shown = DmGroups.collapse(inline.filter { it.at < until })
        // format 3: version 1's device lines never show (other people's devices are a count in Who can read this)
        val merged = if (seal == null || seal.notices.isEmpty() || format3) shown
        else (shown.map { r -> (if (r is DmGroup) r.at else (r as Dm).at) to r } + seal.notices.filter { it.at < until }.map { it.at to it }).sortedBy { it.first }.map { it.second }
        // history someone shared with this device (canvas v86): a "Shared by" line above each block
        val out = ArrayList<Any>()
        var open: SharedBlock? = null
        for (r in merged) {
            val block = when (r) { is Dm -> v1Shared.firstOrNull { it.holds(r.at) }; is DmGroup -> v1Shared.firstOrNull { it.holds(r.at) }; else -> null }
            if (block != null && block != open) out.add(block)
            open = block ?: open.takeIf { r !is Dm && r !is DmGroup }
            out.add(r)
        }
        if (lockedHere) out.add(LockedHere)
        // encrypted/not-before: one line where the unencrypted messages end (format 3)
        withNotBefore(out, firstEnc, { r -> when (r) { is Dm -> r.at; is DmGroup -> r.at; else -> null } }) { r -> (r as? Dm)?.let { it.plain && !it.isMail } ?: ((r as? DmGroup)?.files?.any { it.plain } == true) }
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
    var notEncryptedOpen by remember(conv) { mutableStateOf(false) }
    var plusOpen by remember(conv) { mutableStateOf(false) }
    var recording by remember(conv) { mutableStateOf(false) }

    LaunchedEffect(conv, rows.size) {
        if (rows.isNotEmpty()) state.scrollToItem(rows.size - 1)
    }
    // read state (SPEC 19.27): on screen with the app in the foreground → read up to its newest message
    val started = sh.belfry.app.ui.kit.rememberStarted()
    LaunchedEffect(conv, list.size, started) { if (started) session.markRead(conv) }
    if (mail) LaunchedEffect(conv) { session.checkReports() }

    Row(Modifier.fillMaxSize()) {
    Column(Modifier.weight(1f).fillMaxHeight().background(c.bg).imePadding()) {
        // one-shape header (docs/one-shape.md 2): the name, then ✓ · 📁 · 🖼 (no people count, no
        // face); an e-mail thread's line 2 says who it is with. A tap on one person's name: their profile.
        val dmFiles = list.mapNotNull { it.file }
        val dmPhotos = dmFiles.count { it.mime.startsWith("image/") }
        sh.belfry.app.ui.kit.OneHeader(
            title,
            items = if (mail) listOf(sh.belfry.app.ui.kit.RowItem("lead", BelfryIcons.Mail, sub, "E-mail with $sub", lead = true))
            else sh.belfry.app.ui.chat.dmRowItems(0, dmFiles.size - dmPhotos + list.sumOf { it.attachments.size }, dmPhotos),
            navigation = { BackButton { nav.back() } },
            onPull = peer?.let { p -> { nav.go(Routes.profile(p)) } },
            pullLabel = peer?.let { "Open ${world.displayName(it)}'s profile" },
            // while this device waits to be allowed, every conversation shows the lock (the lead, 2026-11-22):
            // it can't know which are sealed until it can read them
            titleMark = if (enc3Lock) ({ Enc3Lock { whoOpen = !whoOpen } })
            else if (!format3 && (seal != null || (!newMail && (sealState.earlierLocked || sealState.waitingKeys)))) ({ SealLock { nav.go(Routes.encryption(conv)) } }) else null,
            actions = {
                if (mail && !newMail) IconAction(BelfryIcons.Plus, "New e-mail to $who", { nav.go(Routes.conversation("m:$address")) })
            },
        )
        // Notes to myself (docs/one-shape.md, SPEC 19.66): the screen starts with one fixed line; the lock and the
        // "nobody else" sentence only when sealed
        if (toSelf) NotesLine(sealed = enc3Lock || (seal != null && !format3)) { if (enc3Lock) whoOpen = true else nav.go(Routes.encryption(conv)) }
        if (rows.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                EmptyState(
                    if (mail) "A new e-mail to $address" else if (toSelf) sh.belfry.core.model.Notes.TITLE else "No messages yet",
                    text = if (mail) {
                        "Its subject names the thread. It goes out from ${world.myAddress ?: "your address here"}; answers land in the thread."
                    } else if (toSelf) {
                        sh.belfry.core.model.Notes.EMPTY_TEXT
                    } else if (peers.size > 1) {
                        "Only the people here can read them."
                    } else {
                        "Only you two can read them."
                    },
                    icon = if (toSelf) BelfryIcons.Note else BelfryIcons.Mail,
                )
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = state, contentPadding = PaddingValues(bottom = 10.dp + sh.belfry.app.ui.chat.TYPING_ROOM)) {
                // format 3, part 5 (encrypted/new-thread, new-asked, new-arrived, transition, lost-all-thread): above the history
                if (enc3Dev.on && session.encConvHere(conv)) item(key = "enc3-earlier") { Enc3EarlierLine(EarlierWhere.THREAD) }
                itemsIndexed(rows, key = { _, r ->
                    when (r) {
                        is Dm -> r.rid
                        is DmGroup -> "group:" + r.key
                        is DeviceNotice -> "notice:" + r.id
                        is SharedBlock -> "shared:" + r.by + ":" + r.from
                        NotBefore -> "not-before"
                        else -> "locked"
                    }
                }) { i, r ->
                    // the not-before line carries no date: the day separator looks past it
                    val prev = rows.getOrNull(i - 1)?.let { p -> if (p === NotBefore) rows.getOrNull(i - 2) else p }
                    val at = atOf(r)
                    val prevAt = prev?.let { atOf(it) }
                    if (at != null && (prevAt == null || world.dayOf(prevAt) != world.dayOf(at))) DaySeparator(ChatFormat.day(world, at, now))
                    when (r) {
                        is Dm -> {
                            val shared = seal?.shared?.any { it.holds(r.at) } == true
                            val replies = dms.replies(conv, r.rid).size
                            val openReplies = if (replies > 0) ({ nav.go(Routes.dmThread(conv, r.rid)) }) else null
                            if (shared) {
                                SharedRule { Bubble(r, dms, world, group = peers.size > 1, onMenu = { emojiOnly -> acting = r.rid to emojiOnly }, notSealed = if (!format3 && (seal?.isNotSealed(r.at) == true || r.notSealed)) ({ notSealedOpen = true }) else if (r.clear) ({ notEncryptedOpen = true }) else null, onCancelHeld = { session.cancelHeldDm(r.rid)?.let { t -> composer.value = androidx.compose.ui.text.input.TextFieldValue(t); true } ?: false }, replies = replies, onReplies = openReplies) }
                            } else {
                                Bubble(r, dms, world, group = peers.size > 1, onMenu = { emojiOnly -> acting = r.rid to emojiOnly }, notSealed = if (!format3 && (seal?.isNotSealed(r.at) == true || r.notSealed)) ({ notSealedOpen = true }) else if (r.clear) ({ notEncryptedOpen = true }) else null, onCancelHeld = { session.cancelHeldDm(r.rid)?.let { t -> composer.value = androidx.compose.ui.text.input.TextFieldValue(t); true } ?: false }, replies = replies, onReplies = openReplies)
                            }
                        }
                        is DmGroup -> FilesBubble(r, dms, world, group = peers.size > 1, onMenu = { rid, emojiOnly -> acting = rid to emojiOnly }) {
                            Bubble(it, dms, world, group = false, onMenu = { emojiOnly -> acting = it.rid to emojiOnly })
                        }
                        is DeviceNotice -> DeviceNoticeRow(r, world) { openNotice(r) }
                        is SharedBlock -> SharedByRow(r, world) { why = r }
                        NotBefore -> NotBeforeLine()
                        else -> LockedBlock(sealState) { nav.go(Routes.UNLOCK_STEPS) }
                    }
                }
            }
        }
        // canvas v110 messages/typing-dm (never for e-mail, never in Notes to myself)
        if (!mail && !toSelf) {
            val lastDm = remember(list) { HashMap<String, Long>().also { m -> for (d in list) if (d.at > (m[d.from] ?: 0L)) m[d.from] = d.at } }
            sh.belfry.app.ui.chat.TypingLine(sh.belfry.core.typing.TypingPlace.dm(conv)) { lastDm[it] ?: 0L }
        }
        why?.let { b -> SharedWhySheet(b, world) { why = null } }
        if (notSealedOpen) NotSealedSheet { notSealedOpen = false }
        if (notEncryptedOpen) NotSealedSheet(sh.belfry.core.enc.EncText.NOT_ENCRYPTED_WHY) { notEncryptedOpen = false }
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
            if (d == null) acting = null else DmMessageSheet(
                d, dms.reactionsOf(d.rid), emojiOnly, onClose = { acting = null },
                onReply = if (DmThreadRules.canReply(d, lockedHere)) ({ m -> nav.go(Routes.dmThread(conv, m.rid)) }) else null,
            )
        }
        // an older device, a letterbox behind (messages/encryption-waiting-device): the newer messages wait for the
        // device that added itself; a locked wrap does not say which conversation it is for, so the line shows in
        // every sealed conversation while any waits. The composer stays.
        if (v1Ui && seal != null && sealState.waitingFor.isNotEmpty() && !lockedHere && maxOf(dms.lockedCount, sealState.demoWaiting) > 0) WaitingForLine(sealState)
        if (v1Ui && seal != null && sealState.waitingKeys && !lockedHere) {
            // allowed, the key not here yet (canvas v96): clears by itself when it arrives
            // "Asked 9:40 PM · waiting for one of your devices" / "Open ‹device›. Nothing to approve here." (Messages v5)
            WaitingKeysLines(sealState, Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 16.dp).testTag("seal-waiting-keys"))
        } else if (lockedHere) {
            Text(
                sh.belfry.core.model.SealedText.WRITE_LATER, fontSize = 14.sp, color = c.muted,
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 16.dp).testTag("seal-write-later"),
            )
        } else Composer( // format 3 (SPEC 19.83): no link previews in an encrypted conversation, ever (none exist here: keep it so)
            composer,
            placeholder = if (mail && !newMail && seal != null) "Reply by e-mail" else if (mail) "Write an e-mail" else if (toSelf) sh.belfry.core.model.Notes.PLACEHOLDER else "Message",
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
            typingPlace = if (mail || toSelf) null else sh.belfry.core.typing.TypingPlace.dm(conv),
        )
        if (plusOpen) DmPlusSheet(conv, composer, onClose = { plusOpen = false }, onRecord = { recording = true })
        if (recording) DmRecorderSheet(conv) { recording = false }
        if (whoOpen && enc3Lock && !beside) WhoCanReadSheet(conv) { whoOpen = false }
    }
    // encrypted/laptop-who: beside the conversation when the window has room for both
    if (whoOpen && enc3Lock && beside) WhoCanReadPanel(conv) { whoOpen = false }
    }
}

/**
 * The fixed first line of Notes to myself. Sealed: a lock and "Encrypted for your own devices. Nobody else
 * can read your notes, not the belfry's admins either." (a tap opens Encryption, as the header's lock does).
 * Not sealed: no lock, "Private to your account. Not end-to-end encrypted on this account."
 */
@Composable
internal fun NotesLine(sealed: Boolean, onLock: () -> Unit) {
    val c = Belfry.colors
    Row(
        Modifier.fillMaxWidth()
            .then(if (sealed) Modifier.clickable(role = Role.Button, onClickLabel = "Encryption", onClick = onLock) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag(if (sealed) "notes-line-sealed" else "notes-line-unsealed"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (sealed) {
            Icon(BelfryIcons.Lock, contentDescription = null, tint = c.text, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(sh.belfry.core.model.Notes.line(sealed), fontSize = 14.sp, lineHeight = 19.sp, color = c.text, modifier = Modifier.weight(1f))
    }
    Hairline()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Bubble(d: Dm, dms: DmSnapshot, world: World, group: Boolean, onMenu: (emojiOnly: Boolean) -> Unit, notSealed: (() -> Unit)? = null, onCancelHeld: (suspend () -> Boolean)? = null, replies: Int = 0, onReplies: (() -> Unit)? = null) {
    val c = Belfry.colors
    val session = LocalSession.current
    val retry = rememberBusy()
    val shape = RoundedCornerShape(14.dp)
    val chips = if (d.isMail) emptyList() else dms.reactionsOf(d.rid)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
        horizontalAlignment = if (d.mine) Alignment.End else Alignment.Start,
    ) {
        val waiting = d.heldSince != null
        Column(
            Modifier
                .widthIn(max = 320.dp)
                .alpha(if (waiting) 0.55f else 1f) // faded while it waits (canvas v98)
                .clip(shape)
                .background(if (d.mine) c.surface2 else c.surface)
                .combinedClickable(onClickLabel = null, onLongClickLabel = "Message actions", onLongClick = { if (!d.opening) onMenu(false) }, onClick = {})
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .testTag("dm-bubble"),
        ) {
            if (!d.mine && !d.isMail && group && !d.opening) {
                Text(world.displayName(d.from), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.text)
            }
            // the header names the conversation and its subject: each mail doesn't repeat them
            // (Xavier, 2026-11-11); in a mail thread with several people, who wrote it still shows
            if (d.isMail && !d.mine && group) {
                val name = d.fromName?.takeIf { it.isNotEmpty() }
                Text(name ?: d.from, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.text)
            }
            if (d.opening) {
                // format 3 (§9): its key is here, the author's lists are coming (or offline)
                // encrypted/state-fetching: a spinner while it opens; offline, when it will
                val online = sh.belfry.app.ui.nav.LocalStatus.current.online
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("dm-opening")) {
                    if (online) { Spinner(size = 12.dp); Spacer(Modifier.width(8.dp)) }
                    Text(if (online) sh.belfry.core.enc.EncText.OPENING else sh.belfry.core.engine.WhoText.OPENS_ONLINE, fontSize = 15.sp, color = c.muted)
                }
            } else if (d.text.isNotEmpty()) {
                MarkdownText(d.text, rich = false, color = if (d.pending) c.muted else c.text)
            } else if (d.attachments.isEmpty()) {
                Text("(empty)", fontSize = 15.sp, color = c.muted)
            }
            if (d.attachments.isNotEmpty()) Attachments(d)
            Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(ChatFormat.time(world, d.at), fontSize = 11.sp, color = c.muted)
                // canvas v89: arrived the old way after the conversation was sealed
                if (notSealed != null) NotSealedMark(notSealed, if (d.clear) sh.belfry.core.enc.EncText.NOT_ENCRYPTED else sh.belfry.core.model.SealedText.NOT_SEALED)
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
                // a held message says so under the bubble (canvas v98), not here
                // a direct message not out yet: Sending… → Still sending… · <step> → Not sent yet · Retry (SPEC 19.50)
                val sendLine = if (!waiting && d.mine && d.pending && d.failed == null && !d.isMail) sh.belfry.app.ui.chat.rememberSendLine(d.rid, 0) else null
                val line = if (waiting) null else sendLine?.text ?: dms.stateLine(d) { world.displayName(it) } ?: if (d.mine && d.isMail) "Sent" else null
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
        if (waiting) HeldLine(d, dms, world, onCancelHeld)
        // format 3 (§17.5): sent for the others; these people have no device
        if (d.mine && d.notTo.isNotEmpty() && !waiting) Text(
            sh.belfry.core.enc.EncText.wontGet(d.notTo.map { world.displayName(it) }), fontSize = 12.sp, color = c.muted,
            modifier = Modifier.padding(top = 3.dp, end = 2.dp).testTag("dm-wont-get"),
        )
        if (chips.isNotEmpty()) DmReactionChips(d, chips, onAdd = { onMenu(true) })
        if (replies > 0 && onReplies != null) RepliesLink(replies, onReplies)
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
    // pictures in the message (Xavier, 2026-11-22), the rest as file rows that save the file; a picture
    // that can't be read (a large mail not fetched, an undecodable part) falls back to its row
    val pictures = d.attachments.indices.filter { sh.belfry.core.model.Pictures.isPicture(d.attachments[it].type, d.attachments[it].name) }
    val fileRow: @Composable (Int, Boolean) -> Unit = { i, failed ->
        val a = d.attachments[i]
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
                Text(if (failed) sh.belfry.app.ui.kit.PICTURE_FAILED else fileSize(a.size), fontSize = 12.sp, color = c.muted)
            }
    }
    Column(Modifier.padding(top = 6.dp)) {
        if (pictures.isNotEmpty()) {
            sh.belfry.app.ui.kit.InlinePictures(
                key = d.rid, count = pictures.size, names = pictures.map { d.attachments[it].name.ifEmpty { null } },
                load = { val parts = session.attachmentsOf(d); pictures.map { parts.getOrNull(it)?.data } },
                fallback = { j -> fileRow(pictures[j], true) },
            )
            Spacer(Modifier.height(4.dp))
        }
        for (i in d.attachments.indices) if (i !in pictures) fileRow(i, false)
        val n = note
        if (n != null) Text(n, fontSize = 12.sp, color = c.muted, modifier = Modifier.padding(top = 2.dp))
    }
}

/**
 * canvas v98 messages/encryption-held: one grey line under a message that waits or can't be sent,
 * with Cancel (the text goes back to the message box). Never a technical reason. When the send won
 * the race: "Already sent" for two seconds.
 */
@Composable
private fun HeldLine(d: Dm, dms: DmSnapshot, world: World, onCancel: (suspend () -> Boolean)?) {
    val c = Belfry.colors
    val busy = rememberBusy()
    var alreadySent by remember(d.rid) { mutableStateOf(false) }
    if (alreadySent) LaunchedEffect(Unit) { kotlinx.coroutines.delay(2_000); alreadySent = false }
    val why = d.heldWhy
    val who = if (why != null && why.startsWith("device:")) why.removePrefix("device:") else null
    val text = if (alreadySent) "Already sent" else dms.stateLine(d) { world.displayName(it) } ?: DmSnapshot.heldDevice(if (who != null) world.displayName(who) else "Their")
    // format 3 (SPEC 19.83): the text stays here; Try again decides it again now
    val enc3 = DmSnapshot.enc3Hold(d)
    val session = LocalSession.current
    val again = rememberBusy()
    val tryAgain = !alreadySent && enc3 != null && sh.belfry.core.enc.EncText.canTryAgain(enc3)
    if (tryAgain) {
        // format 3: the words on their own lines, Try again · Cancel under them (never squeezed into the words' row)
        Column(Modifier.padding(top = 3.dp, end = 2.dp).widthIn(max = 300.dp).testTag("dm-held-line"), horizontalAlignment = Alignment.End) {
            Text(text, fontSize = 12.sp, lineHeight = 16.sp, color = c.muted)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (again.busy) Spinner(size = 12.dp) else Text(
                    sh.belfry.core.enc.EncText.TRY_AGAIN, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = c.text,
                    modifier = Modifier.clickable(role = Role.Button) { again.run { session.retryHeldDms() } }.padding(horizontal = 2.dp, vertical = 2.dp).testTag("dm-held-try-again"),
                )
                if (onCancel != null) {
                    Text(" · ", fontSize = 12.sp, color = c.muted)
                    Text(
                        "Cancel", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = c.text,
                        modifier = Modifier.clickable(enabled = !busy.busy, role = Role.Button) { busy.run { if (!onCancel()) alreadySent = true } }
                            .padding(horizontal = 2.dp, vertical = 2.dp).testTag("dm-cancel-held"),
                    )
                }
            }
        }
        return
    }
    Row(Modifier.padding(top = 3.dp, end = 2.dp).testTag("dm-held-line"), verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontSize = 12.sp, lineHeight = 16.sp, color = c.muted, modifier = Modifier.widthIn(max = 280.dp))
        if (!alreadySent && onCancel != null) {
            Text(" · ", fontSize = 12.sp, color = c.muted)
            Text(
                "Cancel", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = c.text,
                modifier = Modifier.clickable(enabled = !busy.busy, role = Role.Button) { busy.run { if (!onCancel()) alreadySent = true } }
                    .padding(horizontal = 2.dp, vertical = 2.dp).testTag("dm-cancel-held"),
            )
        }
    }
}
