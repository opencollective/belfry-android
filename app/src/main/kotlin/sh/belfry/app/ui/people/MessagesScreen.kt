package sh.belfry.app.ui.people

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.push.AndroidPush
import sh.belfry.app.ui.chat.ChatFormat
import sh.belfry.app.ui.chat.face
import sh.belfry.app.ui.kit.Avatar
import sh.belfry.app.ui.kit.BackButton
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.BusyButton
import sh.belfry.app.ui.kit.ButtonKind
import sh.belfry.app.ui.kit.ChannelGlyph
import sh.belfry.app.ui.kit.ChannelIcon
import sh.belfry.app.ui.kit.EmptyState
import sh.belfry.app.ui.kit.IconAction
import sh.belfry.app.ui.kit.TopBar
import sh.belfry.app.ui.nav.LocalDms
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.nav.Routes
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.dm.DmSnapshot
import sh.belfry.core.dm.DmThread
import sh.belfry.core.markdown.Markdown
import sh.belfry.core.model.World
import sh.belfry.core.model.namedRefs

/**
 * The face of a conversation (canvas home/messages, SPEC 19.59): the other person; for an e-mail
 * thread the correspondent's picture from the belfry, else their initials, with a small envelope
 * on the corner. Never an envelope as the face.
 */
@Composable
internal fun ConvFace(conv: String, dms: DmSnapshot, world: World, size: Dp = 44.dp) {
    if (dms.isMail(conv)) {
        MailFaceView(dms.mailAddress(conv), dms.convName(conv, world), size = size)
    } else {
        val peer = dms.peers(conv).firstOrNull() ?: world.me
        Avatar(world.face(peer), size = size)
    }
}

/**
 * Direct messages and e-mail in one place: members by NIP-17, anyone else by e-mail through the
 * belfry. Your address here is name@domain; mail to it lands here too.
 */
@Composable
fun MessagesScreen(
    /** Navigation v2: the bar's Messages place (a root: ☰ or nothing, never ‹). */
    place: Boolean = false,
    onMenu: (() -> Unit)? = null,
    menuBadge: Int = 0,
) {
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
    val address = world.myAddress
    // Notes to myself first, always (also when empty), then every other conversation (SPEC 19.66)
    val rows = dms.messagesRows
    val now = session.now()
    val seal by session.encryption.state.collectAsState()
    val enc3 by session.enc3State.collectAsState()
    // format 3, part 5 (SPEC 19.85): on a format-3 belfry the version-1 Allow / waiting / Start fresh screens never show
    val dev = rememberEnc3Device()
    Column(Modifier.fillMaxSize().background(c.bg)) {
        // the address small under the title; a tap copies it (home/messages, Home canvas v4)
        val context = androidx.compose.ui.platform.LocalContext.current
        var copied by remember { mutableStateOf(false) }
        androidx.compose.runtime.LaunchedEffect(copied) { if (copied) { kotlinx.coroutines.delay(2_000); copied = false } }
        TopBar(
            "Messages", subtitle = if (copied) "Copied" else address,
            onTitleClick = address?.let { a -> { sh.belfry.app.ui.kit.copyText(context, a, "Address"); copied = true } },
            navigation = when {
                !place -> ({ BackButton { nav.back() } })
                onMenu != null -> ({ IconAction(BelfryIcons.Menu, "Menu", onMenu, badge = menuBadge) })
                else -> null
            },
            actions = { IconAction(BelfryIcons.Plus, "New message", { nav.go(Routes.NEW_CONVERSATION) }) },
        )
        LazyColumn(Modifier.weight(1f).fillMaxWidth().navigationBarsPadding(), contentPadding = PaddingValues(vertical = 6.dp)) {
            // format 3 (SPEC 19.82): this device not on its list after 60 s, or the twenty-first (part 4 draws the final screens)
            if (enc3.line != null) item(key = "enc3-setup") { Enc3SetupLine(enc3) }
            // part 5: the warnings, the card of each device added to my list, the lines said once (on top, until answered)
            if (dev.on) item(key = "enc3-cards") { Enc3DeviceCards() }
            // format 3 (SPEC 19.83, §9): messages whose key isn't here yet, or that have no copy for this device (part 5 adds Ask)
            if (dms.unreadableCount > 0 && !dev.on) item(key = "enc3-unreadable") { Enc3CountLine(sh.belfry.core.enc.EncText.unreadable(dms.unreadableCount), "enc3-unreadable") }
            if (dms.openingCount > 0) item(key = "enc3-opening") { Enc3CountLine(sh.belfry.core.enc.EncText.opening(dms.openingCount), "enc3-opening") }
            // a device of mine added without a prompt here (canvas v183): on top, until answered
            if (!dev.on && (seal.added.isNotEmpty() || seal.removedJoinerAt > 0L)) item(key = "seal-added") { AddedDeviceCards() }
            // a new device (canvas messages/encryption-new-device): earlier messages are locked here; and
            // sealed messages that came but can't be opened here show in no conversation, so the banner
            // says how many wait (Xavier's mail to himself on a phone not yet allowed, 2026-11-22)
            val waitingN = maxOf(dms.lockedCount, seal.demoWaiting)
            if (dev.on) {
                // format 3: version-1 messages this device never had are the same Ask (encrypted/transition), at the bottom
            } else if (seal.waitingFor.isNotEmpty() && !seal.earlierLocked) {
                // an older device, a letterbox behind (canvas Messages v8 messages/encryption-waiting-device, sealed.md
                // §5.7): nothing is locked; the newer messages wait for the device that added itself
                if (waitingN > 0) item(key = "seal-waiting-for") { WaitingForLine(seal, waitingN) }
            } else if (seal.earlierLocked || dms.lockedCount > 0) {
                val title = if (dms.lockedCount > 0) sh.belfry.core.model.SealedText.waitingMessages(dms.lockedCount) else sh.belfry.core.model.SealedText.BANNER_TITLE
                // waiting to be added: the device that holds the key, by name, and when this one asked (Messages v5)
                item(key = "seal-banner") { EarlierLockedBanner(title, seal.takeIf { it.earlierLocked }) { nav.go(Routes.UNLOCK_STEPS) } }
            } else if (seal.waitingKeys) {
                // added, the key on its way (messages/encryption-locked-added): goes by itself
                item(key = "seal-added-here") { AddedHereBanner(seal) }
            }
            items(rows, key = { it.conv }) { r ->
                val t = r.thread
                if (r.notes) NotesRow(r, dms, world, now, sealed = seal.seal(r.conv) != null, locked = r.conv in seal.locked) { nav.go(Routes.conversation(r.conv)) }
                else if (t != null) ThreadRow(t, dms, world, now, locked = t.conv in seal.locked) { nav.go(Routes.conversation(t.conv)) }
            }
            // part 5 (encrypted/new-list, new-asked, transition, lost-all): where the oldest conversations are
            if (dev.on) item(key = "enc3-earlier") { Enc3EarlierLine(EarlierWhere.LIST) }
            if (rows.none { !it.notes }) {
                item(key = "empty") {
                    EmptyState(
                        "No messages yet",
                        text = "Members can write to you here, and anyone can e-mail you at ${address ?: "you@${session.community.domain}"}.",
                        icon = BelfryIcons.Mail,
                    )
                }
            }
            item(key = "new") {
                Box(Modifier.fillMaxWidth().padding(16.dp)) {
                    BusyButton(
                        "New message", onClick = { nav.go(Routes.NEW_CONVERSATION) }, kind = ButtonKind.Ghost, icon = BelfryIcons.Plus,
                        modifier = Modifier.fillMaxWidth().testTag("messages-new"),
                    )
                }
            }
        }
    }
}

/**
 * Notes to myself (docs/one-shape.md, canvas messages/notes-list): the first row, pinned for good. The
 * notes icon, the name with a pin, the last note (a small lock before it when sealed) or "Only you can
 * read these". Never a count: it is never unread.
 */
@Composable
private fun NotesRow(row: sh.belfry.core.dm.MessagesRow, dms: DmSnapshot, world: World, now: Long, sealed: Boolean, locked: Boolean, onClick: () -> Unit) {
    val c = Belfry.colors
    val last = row.thread?.last
    val preview = if (last == null) sh.belfry.core.model.Notes.EMPTY_PREVIEW
    else dms.fileLine(last) ?: Markdown.plain(world.namedRefs(last.text)).lineSequence().firstOrNull { it.isNotBlank() } ?: ""
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp).testTag("notes-row"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(c.text), contentAlignment = Alignment.Center) {
            androidx.compose.material3.Icon(BelfryIcons.Note, contentDescription = null, tint = c.bg, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(sh.belfry.core.model.Notes.TITLE, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(6.dp))
                androidx.compose.material3.Icon(BelfryIcons.Pin, contentDescription = sh.belfry.core.model.Notes.PINNED, tint = c.muted, modifier = Modifier.size(14.dp).testTag("notes-pin"))
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = if (locked) Modifier.testTag("seal-locked-row") else Modifier) {
                if (locked || (sealed && last != null)) {
                    androidx.compose.material3.Icon(BelfryIcons.Lock, contentDescription = if (locked) null else sh.belfry.core.model.Notes.LOCK, tint = c.muted, modifier = Modifier.size(14.dp).testTag("notes-row-lock"))
                    Spacer(Modifier.width(5.dp))
                }
                Text(if (locked) sh.belfry.core.model.SealedText.LOCKED_HERE else preview, fontSize = 14.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (last != null) {
            Spacer(Modifier.width(10.dp))
            Text(ChatFormat.short(world, last.at, now), fontSize = 12.sp, color = c.muted, maxLines = 1)
        }
    }
}

@Composable
private fun ThreadRow(t: DmThread, dms: DmSnapshot, world: World, now: Long, locked: Boolean = false, onClick: () -> Unit) {
    val c = Belfry.colors
    val mail = dms.isMail(t.conv)
    // every row: a face, the name in bold, one line: the subject for an e-mail thread, else the last message
    val title = if (mail) dms.convName(t.conv, world) else if (dms.peers(t.conv).let { it.isEmpty() || it == listOf(world.me) }) sh.belfry.core.model.Notes.TITLE else dms.convName(t.conv, world)
    val first = LocalDms.current.fileLine(t.last) ?: Markdown.plain(world.namedRefs(t.last.text)).lineSequence().firstOrNull { it.isNotBlank() } ?: if (t.last.attachments.isNotEmpty()) "Attachment" else ""
    val preview = if (mail) dms.threadName(t.conv) else (if (t.last.mine) "You: " else "") + first
    val typing by sh.belfry.app.ui.nav.LocalSession.current.typing.collectAsState()
    val oneToOne = !mail && dms.peers(t.conv).let { it.size == 1 && it[0] != world.me }
    val typingHere = oneToOne && typing.who(sh.belfry.core.typing.TypingPlace.dm(t.conv)) { pk -> if (t.last.from == pk) t.last.at else 0L }.isNotEmpty()
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ConvFace(t.conv, dms, world)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = if (t.unread > 0) FontWeight.Bold else FontWeight.SemiBold, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (locked) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("seal-locked-row")) {
                    androidx.compose.material3.Icon(BelfryIcons.Lock, contentDescription = null, tint = c.muted, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(sh.belfry.core.model.SealedText.LOCKED_HERE, fontSize = 14.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else if (typingHere) {
                // canvas v110 messages/typing-list: a one-to-one DM says "typing…" in place of its preview
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("dm-row-typing")) {
                    sh.belfry.app.ui.chat.TypingDots(c.muted)
                    Spacer(Modifier.width(6.dp))
                    Text(sh.belfry.core.typing.Typing.LIST_WORD, fontSize = 14.sp, color = c.muted, maxLines = 1)
                }
            } else {
                Text(preview, fontSize = 14.sp, color = if (t.unread > 0) c.text else c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(ChatFormat.short(world, t.last.at, now), fontSize = 12.sp, color = c.muted, maxLines = 1)
            if (t.unread > 0) {
                Spacer(Modifier.height(4.dp))
                sh.belfry.app.ui.kit.CountBadge(t.unread, color = c.orange, onColor = c.bg)
            }
        }
    }
}
