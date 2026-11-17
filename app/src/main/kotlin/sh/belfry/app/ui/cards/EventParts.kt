package sh.belfry.app.ui.cards

import android.app.Activity
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
import sh.belfry.app.ui.chat.Photos
import sh.belfry.app.ui.info.FilesTab
import sh.belfry.app.ui.kit.BackButton
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
fun EventTabs(e: CalEvent, modifier: Modifier = Modifier) {
    val c = Belfry.colors
    var tab by rememberSaveable(e.coord) { mutableIntStateOf(0) }
    Column(modifier.fillMaxWidth()) {
        PrimaryTabRow(selectedTabIndex = tab, containerColor = c.bg, contentColor = c.text) {
            listOf("Chat", "Photos", "People").forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, fontSize = 15.sp) }, selectedContentColor = c.text, unselectedContentColor = c.muted, modifier = Modifier.testTag("event-tab-$i"))
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

@Composable
private fun EventPeoplePane(e: CalEvent) {
    val world = LocalWorld.current
    val nav = LocalNav.current
    val (hosts, going, maybe) = world.eventPeopleList(e)
    val checked = world.ticketLabels(e.groupId)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding()) {
        if (world.mayCheckIn(e)) {
            Spacer(Modifier.height(8.dp))
            BusyButton("Check people in", onClick = { nav.go(Routes.checkIn(e.coord)) }, kind = ButtonKind.Ghost, icon = BelfryIcons.Check, modifier = Modifier.fillMaxWidth())
        }
        for ((title, list) in listOf("Hosts" to hosts, "Going · ${going.size}" to going, "Maybe · ${maybe.size}" to maybe)) {
            if (list.isEmpty()) continue
            SectionTitle(title)
            for (pk in list) {
                val role = world.eventRole(e, pk)
                val sub = listOfNotNull(
                    if (role != sh.belfry.core.model.EventRole.ATTENDEE) role.label else null,
                    if (checked[pk]?.first == true) "checked in" else null,
                ).joinToString(" · ").ifEmpty { null }
                Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { nav.go(Routes.profile(pk, e.groupId)) }) {
                    PersonRow(pk, sub, host = false, guest = false) {
                        if (pk != world.me) BusyButton("Message", onClick = { nav.go(Routes.conversation("n:$pk")) }, kind = ButtonKind.Ghost, small = true)
                    }
                }
            }
        }
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
    var camera by remember { mutableStateOf(false) }
    LaunchedEffect(key) {
        if (e == null) return@LaunchedEffect
        if (session.localOnly) list = demoAttendees(world, e) else try { list = session.members.attendees(e.d) } catch (x: ActionException) { error = x.message }
    }
    LaunchedEffect(result) { if (result != null) { delay(2000); result = null } }
    fun check(code: String?, pubkey: String?, undo: Boolean = false) {
        if (e == null) return
        scope.launch {
            try {
                val r = if (session.localOnly) sh.belfry.core.model.CheckInResult(list?.firstOrNull { it.pubkey == pubkey } ?: Attendee(pubkey ?: "", "Someone", "", "Ticket", null, null, "member"), false)
                else session.members.checkIn(e.d, code, pubkey, undo)
                val who = r.person.name.ifEmpty { world.displayName(r.person.pubkey) }
                result = if (r.already && !undo) ("Already checked in at " + (r.person.checkedIn?.let { ChatFormat.time(world, it) } ?: "")) to 1
                else (if (undo) "$who · not checked in" else "$who · ${r.person.ticketTitle.ifEmpty { "ticket" }} · checked in") to 0
                list = list?.map { if (it.pubkey == r.person.pubkey) it.copy(checkedIn = if (undo) null else (r.person.checkedIn ?: session.now())) else it }
            } catch (x: ActionException) {
                result = (if (x.code == "no_ticket") "Not a ticket for this event" else x.message ?: "That did not work.") to 2
            }
        }
    }
    Column(Modifier.fillMaxSize().background(c.bg).testTag("check-in")) {
        TopBar("Check people in", subtitle = e?.title, navigation = { BackButton { nav.back() } })
        if (e == null) { Text("This event is not here.", modifier = Modifier.padding(16.dp)); return@Column }
        result?.let { (text, tone) ->
            val bg = when (tone) { 0 -> androidx.compose.ui.graphics.Color(0xFF15803D); 1 -> androidx.compose.ui.graphics.Color(0xFFB45309); else -> c.danger }
            // the words say it too: colour alone means nothing on an e-ink screen
            Text((when (tone) { 0 -> "✓ "; 1 -> "! "; else -> "✕ " }) + text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.fillMaxWidth().background(bg).padding(16.dp).testTag("check-result"))
        }
        if (camera && sh.belfry.app.scan.cameraGranted(context)) {
            Box(Modifier.fillMaxWidth().height(280.dp)) {
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
            }
        } else {
            val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> camera = ok; if (!ok) error = "The camera is off for Belfry: allow it in the settings to scan tickets." }
            BusyButton("Scan a ticket", onClick = { if (sh.belfry.app.scan.cameraGranted(context)) camera = true else launcher.launch(android.Manifest.permission.CAMERA) },
                icon = BelfryIcons.Qr, modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("check-scan"))
        }
        error?.let { Text(it, color = c.danger, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp)) }
        Hairline()
        val people = list
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            if (people == null) { Text("Loading…", color = c.muted, modifier = Modifier.padding(16.dp)); return@Column }
            val inside = people.count { it.checkedIn != null }
            Text("$inside of ${people.size} checked in", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text, modifier = Modifier.padding(16.dp))
            for (a in people.sortedBy { (it.name.ifEmpty { world.displayName(it.pubkey) }).lowercase() }) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(a.name.ifEmpty { world.displayName(a.pubkey) }, fontSize = 16.sp, color = c.text)
                        Text(listOfNotNull(a.ticketTitle.ifEmpty { null }, a.checkedIn?.let { "in at " + ChatFormat.time(world, it) }).joinToString(" · "), fontSize = 13.sp, color = c.muted)
                    }
                    if (a.checkedIn == null) BusyButton("Check in", onClick = { check(null, a.pubkey) }, small = true)
                    else BusyButton("Undo", onClick = { check(null, a.pubkey, undo = true) }, kind = ButtonKind.Ghost, small = true)
                }
            }
        }
    }
}

private fun demoAttendees(world: sh.belfry.core.model.World, e: CalEvent): List<Attendee> {
    val checked = world.ticketLabels(e.groupId)
    val (hosts, going, _) = world.eventPeopleList(e)
    return (hosts + going).distinct().map { pk -> Attendee(pk, world.displayName(pk), "", if (pk in hosts) "Host" else "Ticket", "accepted", checked[pk]?.takeIf { it.first }?.second, "member") }
}

