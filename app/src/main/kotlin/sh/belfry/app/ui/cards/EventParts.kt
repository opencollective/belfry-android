package sh.belfry.app.ui.cards

import android.app.Activity
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Directions
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import sh.belfry.core.model.channelCounts
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import sh.belfry.app.ui.chat.ChannelScreen
import sh.belfry.app.ui.chat.ChatFormat
import sh.belfry.app.ui.chat.face
import sh.belfry.app.ui.chat.Photos
import sh.belfry.app.ui.info.FilesTab
import sh.belfry.app.ui.kit.BackButton
import sh.belfry.app.ui.kit.ActionGroup
import sh.belfry.app.ui.kit.Avatar
import sh.belfry.app.ui.kit.IconAction
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.BelfrySheet
import sh.belfry.app.ui.kit.BusyButton
import sh.belfry.app.ui.kit.ButtonKind
import sh.belfry.app.ui.kit.Hairline
import sh.belfry.app.ui.kit.QrCode
import sh.belfry.app.ui.kit.SectionTitle
import sh.belfry.app.ui.kit.TopBar
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.nav.Routes
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.engine.ActionException
import sh.belfry.core.model.ApiMe
import sh.belfry.core.model.Attendee
import sh.belfry.core.model.CalEvent
import sh.belfry.core.model.MyState
import sh.belfry.core.model.TicketScan
import sh.belfry.core.model.eventByKey
import sh.belfry.core.model.eventPeopleList
import sh.belfry.core.model.eventRole
import sh.belfry.core.model.mayCheckIn
import sh.belfry.core.model.myState
import sh.belfry.core.model.ticketLabels
import sh.belfry.core.nostr.bool
import sh.belfry.core.nostr.long
import sh.belfry.core.nostr.obj
import sh.belfry.core.nostr.parseJsonOrNull
import sh.belfry.core.nostr.s

/** The event screen's header levels (SPEC 19.29): up (the bar names it), half (opens there), full. */
enum class EventLevel {
    UP, HALF, FULL;

    fun next(): EventLevel = when (this) { HALF -> FULL; FULL -> UP; UP -> HALF }
    fun up(): EventLevel = when (this) { FULL -> HALF; else -> UP }
    fun down(): EventLevel = when (this) { UP -> HALF; else -> FULL }
}

/**
 * What the belfry told me about myself at this event (`viewer`, else `me`: my ticket's code and QR
 * address, checked in, roles, may check in), kept on the phone per event so the QR shows offline.
 * The demo makes one up from the relay's data.
 */
@Composable
fun rememberViewer(e: CalEvent): ApiMe? {
    val session = LocalSession.current
    val world = LocalWorld.current
    val remote by session.events.remote.collectAsState()
    val key = "viewer:" + e.d
    var kept by remember(e.d) { mutableStateOf(session.store.get(key)?.let { readViewer(it) }) }
    val live = remote[e.link]?.api?.me ?: remote[e.d]?.api?.me
    LaunchedEffect(e.d) { if (!e.remote && e.isNew) try { session.events.fetch(e.link) } catch (e: Exception) { } }
    LaunchedEffect(live) {
        if (live != null && live.ticketCode != null) {
            kept = live
            session.store.set(key, writeViewer(live))
        }
    }
    if (session.localOnly && e.isNew) {
        val st = world.myState(e)
        val code = if (st == MyState.TICKET || st == MyState.HOST) "demo" + e.d.filter { it.isLetter() }.padEnd(12, 'a').take(12) else null
        return ApiMe(
            host = st == MyState.HOST, ticket = null, applied = false, waitlist = false, declined = false, rsvp = null, member = true,
            ticketCode = code, ticketUrl = code?.let { "https://chat.${session.community.domain}/events/${e.link}/t/$it" },
            mayCheckIn = world.mayCheckIn(e),
        )
    }
    return live ?: kept
}

private fun writeViewer(m: ApiMe): String = buildJsonObject {
    m.ticket?.let { put("ticket", JsonPrimitive(it)) }
    m.ticketCode?.let { put("ticket_code", JsonPrimitive(it)) }
    m.ticketUrl?.let { put("ticket_url", JsonPrimitive(it)) }
    m.checkedIn?.let { put("checked_in", JsonPrimitive(it)) }
    put("roles", JsonArray(m.roles.map { JsonPrimitive(it) }))
    put("may_check_in", JsonPrimitive(m.mayCheckIn))
}.toString()

private fun readViewer(s: String): ApiMe? {
    val o = parseJsonOrNull(s).obj() ?: return null
    val code = o.s("ticket_code")?.takeIf { TicketScan.isCode(it) }
    return ApiMe(false, o.s("ticket"), false, false, false, null, true, code, o.s("ticket_url")?.takeIf { code != null && it.startsWith("https://") }, o["checked_in"].long(),
        emptyList(), o["may_check_in"].bool() ?: false)
}

/** My ticket: its QR (the ticket link, never shown as text), the screen at full brightness meanwhile. */
@Composable
fun TicketSheet(e: CalEvent, url: String, checkedIn: Long?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val world = LocalWorld.current
    DisposableEffect(Unit) {
        val w = (context as? Activity)?.window
        val before = w?.attributes?.screenBrightness
        w?.let { it.attributes = it.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL } }
        onDispose { if (w != null && before != null) w.attributes = w.attributes.apply { screenBrightness = before } }
    }
    BelfrySheet(onDismiss = onDismiss, title = "My ticket", modifier = Modifier.testTag("ticket-sheet")) {
        Column(Modifier.fillMaxWidth().padding(bottom = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(e.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Belfry.colors.text)
            Text(ChatFormat.day(world, e.start, world.dayOf(e.start).atStartOfDay(world.ctx.zone).toEpochSecond()), fontSize = 15.sp, color = Belfry.colors.muted)
            Spacer(Modifier.height(16.dp))
            Box(Modifier.clip(RoundedCornerShape(16.dp)).background(androidx.compose.ui.graphics.Color.White).padding(16.dp)) { QrCode(url, size = 240.dp) }
            Spacer(Modifier.height(12.dp))
            Text(if (checkedIn != null) "Checked in at " + ChatFormat.time(world, checkedIn) else "Show this at the door", fontSize = 16.sp, color = Belfry.colors.text)
        }
    }
}

/** Chat · Photos · People under the event's header (all three kept, the chosen one shown). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun EventTabs(e: CalEvent, modifier: Modifier = Modifier, tab: Int = 0, onTab: (Int) -> Unit = {}) {
    val c = Belfry.colors
    Column(modifier.fillMaxWidth()) {
        PrimaryTabRow(selectedTabIndex = tab, containerColor = c.bg, contentColor = c.text) {
            listOf("Chat", "Photos", "People").forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { onTab(i) }, text = { Text(t, fontSize = 15.sp) }, selectedContentColor = c.text, unselectedContentColor = c.muted, modifier = Modifier.testTag("event-tab-$i"))
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                0 -> ChannelScreen(e.groupId, embedded = true)
                1 -> EventPhotos(e)
                else -> EventPeoplePane(e)
            }
        }
    }
}

@Composable
private fun EventPhotos(e: CalEvent) {
    val session = LocalSession.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(6)) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            val files = uris.mapIndexedNotNull { i, u -> Photos.load(context, u, i)?.attachment }
            try { session.postMessage(e.groupId, "", files) } catch (x: ActionException) { error = x.message }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).navigationBarsPadding()) {
        BusyButton("Add photos", onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, kind = ButtonKind.Ghost, icon = BelfryIcons.Image, modifier = Modifier.fillMaxWidth().testTag("event-add-photos"))
        error?.let { Text(it, color = Belfry.colors.danger, fontSize = 14.sp) }
        Spacer(Modifier.height(12.dp))
        FilesTab(e.groupId)
    }
}

/** events/people: Hosts (Host, Co-host, Door), Going, Maybe; a tap opens the profile, where hosts set Co-host or Door. */
@Composable
private fun EventPeoplePane(e: CalEvent) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val nav = LocalNav.current
    val (hosts, going, maybe) = world.eventPeopleList(e)
    val checked = world.ticketLabels(e.groupId)
    val doors = world.eventPeopleList(e).let { (h, g, m) -> (g + m).filter { world.eventRole(e, it) == sh.belfry.core.model.EventRole.STEWARD } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding().testTag("event-people")) {
        val staff = (hosts + doors).distinct()
        for ((title, list) in listOf("Hosts" to staff, "Going · ${going.size - going.count { it in staff }}" to going.filter { it !in staff }, "Maybe · ${maybe.size}" to maybe.filter { it !in staff })) {
            if (list.isEmpty()) continue
            SectionTitle(title)
            ActionGroup {
                list.forEachIndexed { i, pk ->
                    if (i > 0) Hairline()
                    val role = world.eventRole(e, pk)
                    val sub = listOfNotNull(
                        if (role != sh.belfry.core.model.EventRole.ATTENDEE) role.label else null,
                        if (checked[pk]?.first == true) "checked in" else null,
                    ).joinToString(" · ").ifEmpty { null }
                    Row(
                        Modifier.fillMaxWidth().background(c.surface).clickable(role = Role.Button) { nav.go(Routes.profile(pk, e.groupId)) }.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(world.face(pk), size = 36.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if (pk == world.me) "You" else world.displayName(pk), fontSize = 16.sp, color = c.text, maxLines = 1)
                            if (sub != null) Text(sub, fontSize = 13.sp, color = c.muted)
                        }
                        if (pk != world.me && role != sh.belfry.core.model.EventRole.ATTENDEE) {
                            IconAction(BelfryIcons.Thread, "Message ${world.displayName(pk)}", { nav.go(Routes.conversation("n:$pk")) })
                        } else Icon(BelfryIcons.Chevron, contentDescription = null, tint = c.faint, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        Text("Tap someone to open their profile. Hosts set Co-host or Door there, under \u201cIn ${e.title}\u201d.", fontSize = 13.sp, color = c.muted, modifier = Modifier.padding(top = 12.dp))
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * Checking people in (SPEC 19.29): the camera reads a ticket's QR (only its code is sent; the same
 * text is ignored for 3 s) and says who it is, green / amber / red; the list below has everyone
 * with a ticket, checked in or not, with a tap to check in or undo. An older belfry: "This belfry
 * needs an update."
 */
@Composable
fun CheckInScreen(key: String) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val session = LocalSession.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val e = world.eventByKey(key)
    var list by remember { mutableStateOf<List<Attendee>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<Pair<String, Int>?>(null) } // text, 0 green 1 amber 2 red
    var lastText by remember { mutableStateOf("" to 0L) }
    // events/check-in: the camera is on from the start when Belfry may use it
    var camera by remember { mutableStateOf(sh.belfry.app.scan.cameraGranted(context)) }
    // the one just checked in, with Undo / Keep (also for a tick on someone already in)
    var undoFor by remember { mutableStateOf<Attendee?>(null) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(key) {
        if (e == null) return@LaunchedEffect
        if (session.localOnly) list = demoAttendees(world, e) else try { list = session.members.attendees(e.d).people } catch (x: ActionException) { error = x.message }
    }
    LaunchedEffect(result) { if (result != null) { delay(4000); result = null } }
    LaunchedEffect(undoFor) { if (undoFor != null) { delay(8000); undoFor = null } }
    fun check(code: String?, pubkey: String?, undo: Boolean = false) {
        if (e == null) return
        scope.launch {
            try {
                val r = if (session.localOnly) sh.belfry.core.model.CheckInResult(list?.firstOrNull { it.pubkey == pubkey } ?: Attendee(pubkey ?: "", "Someone", "", "Ticket", null, null, "member"), false)
                else session.members.checkIn(e.d, code, pubkey, undo)
                val who = r.person.name.ifEmpty { world.displayName(r.person.pubkey) }
                result = if (r.already && !undo) ("$who · already in at " + (r.person.checkedIn?.let { ChatFormat.time(world, it) } ?: "")) to 1
                else (if (undo) "$who · not checked in" else "$who · checked in") to 0
                val at = if (undo) null else (r.person.checkedIn ?: session.now())
                list = list?.map { if (it.pubkey == r.person.pubkey) it.copy(checkedIn = at) else it }
                undoFor = if (undo || r.already) null else r.person.copy(checkedIn = at)
            } catch (x: ActionException) {
                result = (if (x.code == "no_ticket") "Not a ticket for this event" else x.message ?: "That did not work.") to 2
            }
        }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> camera = ok; if (!ok) error = "The camera is off for Belfry: allow it in the settings to scan tickets." }
    Column(Modifier.fillMaxSize().background(c.bg).testTag("check-in")) {
        TopBar("Check people in", subtitle = e?.title, navigation = { BackButton { nav.back() } })
        if (e == null) { Text("This event is not here.", modifier = Modifier.padding(16.dp)); return@Column }
        // the camera (or what turns it on), the last result across its foot
        Box(Modifier.fillMaxWidth().height(260.dp).background(androidx.compose.ui.graphics.Color.Black)) {
            if (camera && sh.belfry.app.scan.cameraGranted(context)) {
                sh.belfry.app.scan.CameraPreview(
                    onText = { text ->
                        val now = System.currentTimeMillis()
                        if (text != lastText.first || now - lastText.second > 3000) {
                            lastText = text to now
                            val code = TicketScan.code(text)
                            if (code == null) result = "Not a ticket for this event" to 2 else check(code, null)
                        }
                    },
                    onFailed = { camera = false; error = "The camera could not start." },
                )
            } else {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(BelfryIcons.Qr, contentDescription = null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(10.dp))
                    BusyButton("Turn on the camera", onClick = { launcher.launch(android.Manifest.permission.CAMERA) }, small = true, modifier = Modifier.testTag("check-scan"))
                }
            }
            if (camera) Text("Point the camera at a ticket", fontSize = 15.sp, color = androidx.compose.ui.graphics.Color.White, modifier = Modifier.align(Alignment.Center).padding(top = 90.dp))
            result?.let { (text, tone) ->
                val bg = when (tone) { 0 -> androidx.compose.ui.graphics.Color(0xFF15803D); 1 -> androidx.compose.ui.graphics.Color(0xFFB45309); else -> c.danger }
                // the words and a mark say it too: colour alone means nothing on an e-ink screen
                Row(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(12.dp)).background(bg).padding(horizontal = 14.dp, vertical = 12.dp).testTag("check-result"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(when (tone) { 0 -> "✓"; 1 -> "!"; else -> "✕" }, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = androidx.compose.ui.graphics.Color.White)
                    Spacer(Modifier.width(10.dp))
                    Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = androidx.compose.ui.graphics.Color.White)
                }
            }
        }
        error?.let { Text(it, color = c.danger, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
        val people = list
        if (people == null) { Text("Loading…", color = c.muted, modifier = Modifier.padding(16.dp)); return@Column }
        val inside = people.count { it.checkedIn != null }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("$inside of ${people.size} checked in", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.text, modifier = Modifier.weight(1f).testTag("check-count"))
            androidx.compose.material3.LinearProgressIndicator(
                progress = { if (people.isEmpty()) 0f else inside.toFloat() / people.size },
                modifier = Modifier.width(96.dp).height(6.dp).clip(RoundedCornerShape(3.dp)), color = c.text, trackColor = c.surface2,
            )
        }
        undoFor?.let { u ->
            Row(Modifier.fillMaxWidth().background(c.surface).padding(horizontal = 16.dp, vertical = 10.dp).testTag("check-undo"), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Undo check-in?", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                    Text(u.name.ifEmpty { world.displayName(u.pubkey) } + (u.checkedIn?.let { " · in at " + ChatFormat.time(world, it) } ?: ""), fontSize = 13.sp, color = c.muted)
                }
                BusyButton("Undo", onClick = { check(null, u.pubkey, undo = true); undoFor = null }, kind = ButtonKind.Danger, small = true)
                Spacer(Modifier.width(8.dp))
                BusyButton("Keep", onClick = { undoFor = null }, kind = ButtonKind.Ghost, small = true)
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            val q = query.trim().lowercase()
            for (a in people.sortedBy { (it.name.ifEmpty { world.displayName(it.pubkey) }).lowercase() }.filter { q.isEmpty() || (it.name.ifEmpty { world.displayName(it.pubkey) }).lowercase().contains(q) }) {
                val name = if (a.pubkey == world.me) "You" else a.name.ifEmpty { world.displayName(a.pubkey) }
                val on = a.checkedIn != null
                Row(
                    Modifier.fillMaxWidth().semantics { selected = on }
                        .clickable(role = Role.Checkbox, onClickLabel = if (on) "Undo the check-in" else "Check in") { if (on) undoFor = a else check(null, a.pubkey) }
                        .padding(horizontal = 16.dp, vertical = 10.dp).testTag("check-row"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(world.face(a.pubkey), size = 36.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(name, fontSize = 16.sp, color = c.text)
                        if (a.ticketTitle.isNotEmpty()) Text(a.ticketTitle, fontSize = 13.sp, color = c.muted)
                    }
                    a.checkedIn?.let { Text(ChatFormat.time(world, it), fontSize = 14.sp, color = c.muted); Spacer(Modifier.width(10.dp)) }
                    // a round tick: filled with a check when in, an outline when not
                    Box(
                        Modifier.size(26.dp).clip(CircleShape).then(if (on) Modifier.background(c.text) else Modifier.border(2.dp, c.muted, CircleShape)),
                        contentAlignment = Alignment.Center,
                    ) { if (on) Icon(BelfryIcons.Check, contentDescription = "Checked in", tint = c.bg, modifier = Modifier.size(16.dp)) }
                }
                Hairline(Modifier.padding(start = 64.dp))
            }
            Spacer(Modifier.height(8.dp))
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).navigationBarsPadding()) {
            sh.belfry.app.ui.kit.BelfryTextField(query, { query = it.take(80) }, Modifier.fillMaxWidth().testTag("check-search"), placeholder = "Search ticket holders",
                trailing = { Icon(BelfryIcons.Search, contentDescription = null, tint = c.muted, modifier = Modifier.size(18.dp)) })
        }
    }
}

private fun demoAttendees(world: sh.belfry.core.model.World, e: CalEvent): List<Attendee> {
    val checked = world.ticketLabels(e.groupId)
    val (hosts, going, _) = world.eventPeopleList(e)
    return (hosts + going).distinct().map { pk -> Attendee(pk, world.displayName(pk), "", if (pk in hosts) "Host" else "Ticket", "accepted", checked[pk]?.takeIf { it.first }?.second, "member") }
}


/**
 * The card a ticket link opens for those who may check people in (SPEC 19.29): who it is and
 * whether they are in (asked with `peek`: nothing is recorded by opening a link), then "Check in"
 * → "Checked in at …" with Undo. An older belfry without peek: the card shows "Check in" only.
 */
@Composable
fun TicketLinkCard(e: CalEvent, code: String, onClose: () -> Unit = {}) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val session = LocalSession.current
    var person by remember(code) { mutableStateOf<Attendee?>(null) }
    var error by remember(code) { mutableStateOf<String?>(null) }
    LaunchedEffect(code) {
        // the demo: the first ticket holder not in yet
        if (session.localOnly) { person = demoAttendees(world, e).firstOrNull { it.checkedIn == null && it.pubkey != world.me }; return@LaunchedEffect }
        try { person = session.members.checkIn(e.d, code = code, peek = true).person } catch (x: ActionException) { if (x.code == "no_ticket") error = "Not a ticket for this event" }
    }
    // events/ticket-card: who it is and whether they are in; nobody is checked in by opening the link
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, c.line, RoundedCornerShape(16.dp)).background(c.bg).padding(16.dp).testTag("ticket-link-card"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val p = person
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(BelfryIcons.Qr, contentDescription = null, tint = c.text, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("A ticket for this event", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text, modifier = Modifier.weight(1f))
            IconAction(BelfryIcons.Close, "Close", onClose)
        }
        Text(p?.let { it.name.ifEmpty { world.displayName(it.pubkey) } } ?: "A ticket", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = c.text)
        if (p != null) Text(listOfNotNull(p.ticketTitle.ifEmpty { null }, p.checkedIn?.let { "checked in at " + ChatFormat.time(world, it) } ?: "not checked in yet").joinToString(" · "), fontSize = 15.sp, color = c.muted)
        error?.let { Text("✕ $it", fontSize = 15.sp, color = c.danger) }
        if (error == null) {
            Spacer(Modifier.height(4.dp))
            if (p?.checkedIn == null) BusyButton("Check in", onClick = {
                try {
                    person = if (session.localOnly) p?.copy(checkedIn = session.now()) else session.members.checkIn(e.d, code = code).person
                } catch (x: ActionException) { error = x.message }
            }, modifier = Modifier.fillMaxWidth().testTag("ticket-checkin"))
            else BusyButton("Undo", onClick = {
                try {
                    person = if (session.localOnly) p.copy(checkedIn = null) else session.members.checkIn(e.d, pubkey = p.pubkey, undo = true).person
                } catch (x: ActionException) { error = x.message }
            }, kind = ButtonKind.Ghost, modifier = Modifier.fillMaxWidth())
        }
    }
}

// ---- one shape: the event screen's three states (SPEC 19.54; canvas one-shape/event-*) ----------------

/** The answer's green ("✓ Going"): also semi-bold and with a tick, never colour alone (e-ink). */
@Composable
internal fun goingColor(): androidx.compose.ui.graphics.Color =
    if (Belfry.colors.isDark) androidx.compose.ui.graphics.Color(0xFF7FD69C) else androidx.compose.ui.graphics.Color(0xFF1E6B3A)

/** The tiny header's icon row: my answer leads, then the date (blue), people, open to-dos, files, photos. */
@Composable
internal fun eventRowItems(e: CalEvent, st: MyState, going: Int): List<sh.belfry.app.ui.kit.RowItem> {
    val world = LocalWorld.current
    val now = LocalSession.current.now()
    val counts = world.channelCounts(e.groupId, now)
    val day = ChatFormat.day(world, e.start, now)
    val time = if (e.allDay) "" else ChatFormat.time(world, e.start)
    val out = ArrayList<sh.belfry.app.ui.kit.RowItem>()
    if (st.label != null && st != MyState.NONE) {
        val ok = st == MyState.TICKET || st == MyState.HOST
        out.add(sh.belfry.app.ui.kit.RowItem("answer", if (ok) BelfryIcons.Check else null, st.label ?: "", st.line ?: st.label ?: "",
            tint = if (ok) goingColor() else Belfry.colors.text))
    }
    out.add(sh.belfry.app.ui.kit.RowItem("lead", BelfryIcons.Calendar, if (time.isEmpty()) day else "$day · $time", if (time.isEmpty()) day else "$day at $time", lead = true))
    out.add(sh.belfry.app.ui.kit.RowItem("people", BelfryIcons.Users, going.toString(), "$going going"))
    val open = counts.todos - counts.todosDone
    if (open > 0) out.add(sh.belfry.app.ui.kit.RowItem("todos", BelfryIcons.Check, open.toString(), "$open open to-do" + if (open == 1) "" else "s"))
    if (counts.files > 0) out.add(sh.belfry.app.ui.kit.RowItem("files", androidx.compose.material.icons.Icons.Outlined.Folder, counts.files.toString(), "${counts.files} file" + if (counts.files == 1) "" else "s"))
    if (counts.photos > 0) out.add(sh.belfry.app.ui.kit.RowItem("photos", BelfryIcons.Image, counts.photos.toString(), "${counts.photos} photo" + if (counts.photos == 1) "" else "s"))
    return out
}

/** Tiny (and full): ‹, the title over the icon row (a tap opens mid), •••. Two lines at most. */
@Composable
internal fun EventTinyHeader(e: CalEvent, items: List<sh.belfry.app.ui.kit.RowItem>, onBack: () -> Unit, onMore: () -> Unit, onTap: () -> Unit) {
    val c = Belfry.colors
    Row(
        Modifier.fillMaxWidth().background(c.bg).windowInsetsPadding(WindowInsets.statusBars).heightIn(min = 56.dp).padding(horizontal = 4.dp).testTag("event-tiny-header"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BackButton(onBack)
        Column(
            Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClickLabel = "Show more about the event", onClick = onTap).padding(horizontal = 4.dp, vertical = 4.dp),
        ) {
            Text(e.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.text, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            sh.belfry.app.ui.kit.IconRow(items)
        }
        IconAction(BelfryIcons.More, "More", onMore, Modifier.testTag("event-more"))
    }
}

/** Mid with a cover: the picture across the full width at 16:9 (never cropped sideways), ‹ and ••• floating on it. */
@Composable
internal fun EventCoverTop(cover: String, onBack: () -> Unit, onMore: () -> Unit) {
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clipToBounds().background(Belfry.colors.surface2).testTag("event-cover")) {
        EventCover(cover, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.FillWidth)
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FloatingRound(BelfryIcons.Back, "Back", onBack, Modifier.testTag("event-back"))
            Spacer(Modifier.weight(1f))
            FloatingRound(BelfryIcons.More, "More", onMore, Modifier.testTag("event-more"))
        }
    }
}

/** A round translucent button over a picture (48 dp to touch, a 40 dp dark disc, a white line icon). */
@Composable
private fun FloatingRound(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = label, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(22.dp))
        }
    }
}

/** Opens a place in the phone's maps app (geo:), else OpenStreetMap in the browser. */
internal fun openInMaps(context: android.content.Context, place: String) {
    val geo = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("geo:0,0?q=" + android.net.Uri.encode(place)))
    try { context.startActivity(geo) } catch (x: Exception) {
        sh.belfry.app.ui.signin.openInBrowser(context, "https://www.openstreetmap.org/search?query=" + android.net.Uri.encode(place))
    }
}

/**
 * Where (SPEC 19.93; Event form canvas evview-mid): the place (a tap opens maps; full: Directions) with
 * " · and online" when it has a call, or "Online" alone. The call itself is the Join online row of [EventLinkRows].
 */
@Composable
internal fun EventWhere(e: CalEvent, directions: Boolean = false, online: Boolean = e.links.isNotEmpty()) {
    val c = Belfry.colors
    val context = LocalContext.current
    val place = e.location?.trim().orEmpty()
    if (place.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().testTag("event-place")) {
        Icon(BelfryIcons.MapPin, contentDescription = "Where", tint = c.text, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                place, fontSize = 16.sp, color = c.text, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                modifier = Modifier.weight(1f, fill = false).clickable(role = Role.Button, onClickLabel = "Open in maps") { openInMaps(context, place) }.padding(vertical = 4.dp),
            )
            if (online) Text(" · and online", fontSize = 16.sp, color = c.text, maxLines = 1)
        }
        if (directions) {
            Spacer(Modifier.width(8.dp))
            BusyButton("Directions", onClick = { openInMaps(context, place) }, kind = ButtonKind.Ghost, small = true, icon = androidx.compose.material.icons.Icons.Outlined.Directions, modifier = Modifier.testTag("event-directions"))
        }
    } else if (online) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().testTag("event-online-where")) {
        Icon(BelfryIcons.Link, contentDescription = "Where", tint = c.text, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Online", fontSize = 16.sp, color = c.text)
    }
}

/**
 * The event's three links, in this order, under the answer (SPEC 19.93; Event form canvas evview-mid, -full, -join-now,
 * -not-going, -qr; web 649c8cf): **Join online** (people going: the line says when, and from 1 hour before until the
 * end a Join button opens the call; anyone else: how to get it, never the link), **Event website** (its address, ↗
 * opens it outside the app; the address is the tag's, nothing is read from the page), **Our page** (the belfry's
 * page: Copy, and QR opens [onQr]). Each row says itself in words (e-ink: no state by colour alone).
 */
@Composable
internal fun EventLinkRows(e: CalEvent, online: Boolean, holds: Boolean, url: String?, onQr: () -> Unit) {
    val c = Belfry.colors
    val context = LocalContext.current
    val now = LocalSession.current.now()
    val call = e.links.firstOrNull { sh.belfry.app.ui.kit.isSafeLink(it) }
    val site = e.website?.takeIf { sh.belfry.app.ui.kit.isSafeLink(it) }
    if (!online && site == null && url == null) return
    @Composable
    fun LinkRow(title: String, sub: String, tag: String, trailing: @Composable () -> Unit) {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                Text(sub, fontSize = 14.sp, color = c.muted, maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            trailing()
        }
    }
    Column(Modifier.fillMaxWidth().testTag("event-links")) {
        if (online) {
            val open = holds && call != null && sh.belfry.core.model.EventShape.joinOpen(e, now)
            LinkRow("Join online", sh.belfry.core.model.EventShape.joinWord(e, now, holds), "event-online") {
                // the call only for people going, from 1 hour before: Join; before that, a clock in words (e-ink)
                if (open) BusyButton(
                    "Join", onClick = { sh.belfry.app.ui.signin.openInBrowser(context, call!!) }, small = true,
                    icon = androidx.compose.material.icons.Icons.Outlined.Videocam, modifier = Modifier.testTag("event-join"),
                ) else if (holds && call != null) Icon(BelfryIcons.Clock, contentDescription = "Not yet", tint = c.muted, modifier = Modifier.padding(horizontal = 13.dp).size(22.dp).testTag("event-join-off"))
            }
        }
        if (site != null) LinkRow("Event website", sh.belfry.core.model.EventDetails.shown(site), "event-website") {
            IconAction(
                androidx.compose.material.icons.Icons.AutoMirrored.Outlined.OpenInNew, "Open the event website",
                { sh.belfry.app.ui.signin.openInBrowser(context, site) }, Modifier.testTag("event-website-open"),
            )
        }
        if (url != null) LinkRow("Our page", url.removePrefix("https://"), "event-url") {
            sh.belfry.app.ui.kit.CopyIconButton(url, e.title, Modifier.testTag("event-copy"))
            IconAction(BelfryIcons.Qr, "Show the link as a QR code", onQr, Modifier.testTag("event-qr"))
        }
    }
}

/** "Hosted by Lina Moreau and Ben Okafor". */
@Composable
internal fun EventHostedBy(e: CalEvent, hosts: List<String>, api: sh.belfry.core.model.ApiEvent?) {
    val world = LocalWorld.current
    val names = (api?.hostedBy?.map { it.second.ifEmpty { world.displayName(it.first) } }?.ifEmpty { null } ?: hosts.ifEmpty { e.hosts.toList() }.map { if (it == world.me) "you" else world.displayName(it) })
    if (names.isEmpty()) return
    Text("Hosted by " + sh.belfry.core.model.EventShape.names(names), fontSize = 15.sp, color = Belfry.colors.muted, modifier = Modifier.testTag("event-hosted-by"))
}

/** Full: "2 open to-dos · 1 file · 3 photos", each a link to its list in the event's chat. */
@Composable
internal fun EventContentsLine(e: CalEvent) {
    val world = LocalWorld.current
    val nav = LocalNav.current
    val c = Belfry.colors
    val counts = world.channelCounts(e.groupId, LocalSession.current.now())
    val open = counts.todos - counts.todosDone
    val parts = listOfNotNull(
        if (open > 0) Triple(BelfryIcons.Check, "$open open to-do" + (if (open == 1) "" else "s"), Routes.channelPlace(e.groupId, sh.belfry.app.ui.nav.ChannelPlaceKey.TODOS)) else null,
        if (counts.files > 0) Triple(androidx.compose.material.icons.Icons.Outlined.Folder, "${counts.files} file" + (if (counts.files == 1) "" else "s"), Routes.files(e.groupId)) else null,
        if (counts.photos > 0) Triple(BelfryIcons.Image, "${counts.photos} photo" + (if (counts.photos == 1) "" else "s"), Routes.channelPlace(e.groupId, sh.belfry.app.ui.nav.ChannelPlaceKey.PHOTOS)) else null,
    )
    if (parts.isEmpty()) return
    Column(Modifier.fillMaxWidth().testTag("event-contents")) {
        for ((icon, label, route) in parts) Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { nav.go(route) }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = c.text, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, fontSize = 15.sp, color = c.text, modifier = Modifier.weight(1f))
            Icon(BelfryIcons.Chevron, contentDescription = null, tint = c.faint, modifier = Modifier.size(18.dp))
        }
    }
}

/** An answer in the event's chat: "✓ You're going" (green, bold) or "Lina Moreau is going", and its time. */
@Composable
fun AnswerLine(item: sh.belfry.core.model.FeedItem.Answer) {
    val world = LocalWorld.current
    val nav = LocalNav.current
    val c = Belfry.colors
    val mine = item.pubkey == world.me
    val going = item.status == sh.belfry.core.model.Rsvp.ACCEPTED
    val tint = if (going) goingColor() else c.muted
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button) { nav.go(Routes.profile(item.pubkey)) }.padding(horizontal = 16.dp, vertical = 6.dp).testTag("answer-line"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (going) BelfryIcons.Check else BelfryIcons.Calendar, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            sh.belfry.core.model.EventShape.answerLine(if (mine) null else world.displayName(item.pubkey), item.status),
            fontSize = 14.sp, color = if (going) tint else c.text, fontWeight = if (mine || going) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f),
        )
        Text(ChatFormat.time(world, item.at), fontSize = 12.sp, color = c.faint)
    }
}

/** "Xavier invited Lina Moreau and Ben Okafor" in an event's chat (names, never ids), and its time. */
@Composable
fun InvitedLine(by: String, who: List<String>, at: Long) {
    val world = LocalWorld.current
    val c = Belfry.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).testTag("invited-line"), verticalAlignment = Alignment.CenterVertically) {
        Icon(BelfryIcons.Users, contentDescription = null, tint = c.muted, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            (if (by == world.me) "You" else world.displayName(by)) + " invited " + sh.belfry.core.model.EventShape.names(who.map { if (it == world.me) "you" else world.displayName(it) }),
            fontSize = 14.sp, color = c.text, modifier = Modifier.weight(1f),
        )
        Text(ChatFormat.time(world, at), fontSize = 12.sp, color = c.faint)
    }
}

/** Who comes, pushed over the event (from "15 going · 3 maybe ›"): Hosts · Going · Maybe. Back returns. */
@Composable
internal fun EventPeopleScreen(e: CalEvent, onBack: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(Belfry.colors.bg)) {
        TopBar("People", subtitle = e.title, navigation = { BackButton(onBack) })
        Box(Modifier.weight(1f).fillMaxWidth()) { EventPeoplePane(e) }
    }
}
