package sh.belfry.app.ui.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.ui.info.LinkBox
import sh.belfry.app.ui.kit.ActionGroup
import sh.belfry.app.ui.kit.ActionRow
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.BelfrySheet
import sh.belfry.app.ui.kit.BelfryTextField
import sh.belfry.app.ui.kit.BusyButton
import sh.belfry.app.ui.kit.BusyIconButton
import sh.belfry.app.ui.kit.BusyRow
import sh.belfry.app.ui.kit.ButtonKind
import sh.belfry.app.ui.kit.IconAction
import sh.belfry.app.ui.kit.Segmented
import sh.belfry.app.ui.kit.SwitchRow
import sh.belfry.app.ui.kit.Tabs
import sh.belfry.app.ui.kit.copyText
import sh.belfry.app.ui.kit.rememberBusy
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.nav.Routes
import sh.belfry.app.ui.signin.openInBrowser
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.model.CalEvent
import sh.belfry.core.model.EventApplication
import sh.belfry.core.model.EventPeople
import sh.belfry.core.model.EventStatus
import sh.belfry.core.model.Rsvp
import sh.belfry.core.model.Ticket
import sh.belfry.core.model.TicketForm
import sh.belfry.core.model.Visibility
import sh.belfry.core.model.eventPeople
import sh.belfry.core.model.eventUrl
import sh.belfry.core.model.goingList
import sh.belfry.core.model.tickets

/** The event's menu: share it; for hosts edit, tickets, guests, co-hosts, cancel; the creator deletes. */
@Composable
internal fun EventMenuSheet(e: CalEvent, host: Boolean, onDismiss: () -> Unit, open: (EventSheet) -> Unit) {
    val world = LocalWorld.current
    val nav = LocalNav.current
    val session = LocalSession.current
    val context = LocalContext.current
    val url = world.eventUrl(e)
    BelfrySheet(onDismiss = onDismiss) {
        Column(Modifier.padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (e.isNew) {
                ActionGroup {
                    ActionRow("Copy URL", onClick = { copyText(context, url, e.title); onDismiss() }, icon = BelfryIcons.Copy)
                    ActionRow("QR code", onClick = { open(EventSheet.Qr) }, icon = BelfryIcons.Qr)
                    if (e.visibility != Visibility.MEMBERS) ActionRow("Open the public page", onClick = { openInBrowser(context, url); onDismiss() }, icon = BelfryIcons.Globe)
                }
            }
            if (host && e.isNew) {
                ActionGroup {
                    ActionRow("Edit the event", onClick = { onDismiss(); nav.go(Routes.editEvent(e.coord)) }, icon = BelfryIcons.Edit)
                    ActionRow("Tickets", onClick = { open(EventSheet.Tickets) }, modifier = Modifier.testTag("menu-tickets"), icon = BelfryIcons.Proposal)
                    ActionRow("Guest list", onClick = { open(EventSheet.Guests) }, icon = BelfryIcons.Users)
                    ActionRow("Co-host invitation link", onClick = { open(EventSheet.CoHost) }, modifier = Modifier.testTag("menu-cohost"), icon = BelfryIcons.Link)
                }
                ActionGroup {
                    if (e.status == EventStatus.CANCELLED) {
                        BusyRow("Reopen the event", onClick = { session.events.setStatus(e, EventStatus.SCHEDULED); onDismiss() }, icon = BelfryIcons.Calendar)
                    } else {
                        ActionRow("Cancel the event", onClick = { open(EventSheet.Cancel) }, icon = BelfryIcons.Close, danger = true)
                    }
                    if (e.creator == world.me) ActionRow("Delete the event", onClick = { open(EventSheet.Delete) }, icon = BelfryIcons.Trash, danger = true)
                }
            }
            if (!e.isNew) {
                ActionGroup { ActionRow("All events", onClick = { onDismiss(); nav.go(Routes.events(null)) }, icon = BelfryIcons.Calendar) }
            }
        }
    }
}

/** Take a ticket in one tap, or answer its question, or join its waitlist. T null: pick one first. */
@Composable
internal fun TakeSheet(e: CalEvent, tks: List<Ticket>, p: EventPeople, t: Ticket?, onPick: (Ticket) -> Unit, onDismiss: () -> Unit) {
    val world = LocalWorld.current
    val session = LocalSession.current
    var answer by remember(t?.d) { mutableStateOf("") }
    var error by remember(t?.d) { mutableStateOf<String?>(null) }
    var sent by remember(t?.d) { mutableStateOf(false) }
    val member = !world.amGuest && !e.remote
    if (t == null) {
        BelfrySheet(onDismiss = onDismiss, title = "Pick a ticket") {
            Column(Modifier.padding(bottom = 16.dp)) { for (x in tks) TicketRow(x, p, onPick = { onPick(x) }) }
        }
        return
    }
    val left = p.placesLeft(t)
    val waitlist = left == 0
    val needs = t.closed || waitlist
    BelfrySheet(onDismiss = onDismiss, title = if (waitlist) "Waitlist: ${t.title}" else if (t.closed) "Apply: ${t.title}" else if (t.implicit) "RSVP" else t.title) {
        Column(Modifier.padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (t.members && !member) {
                Text("This ticket is for members of ${world.ctx.communityName.ifEmpty { "the community" }}.", fontSize = 15.sp, color = Belfry.colors.text)
                BusyButton("OK", onClick = { onDismiss() }, modifier = Modifier.fillMaxWidth())
                return@Column
            }
            if (t.closed && !waitlist) Text(t.prompt.ifEmpty { "Why would you like to come?" }, fontSize = 15.sp, lineHeight = 21.sp, color = Belfry.colors.text, modifier = Modifier.testTag("take-question"))
            if (waitlist) Muted("It is full. Leave a word for the hosts: they may make an exception.")
            if (needs) {
                BelfryTextField(
                    answer, { answer = it.take(2000); error = null }, Modifier.fillMaxWidth().testTag("take-answer"),
                    placeholder = if (waitlist) "Optional" else "Your answer", singleLine = false, minLines = 3, maxLines = 8,
                )
            } else {
                Text(t.summary.ifEmpty { "A free place." } + (if (left != null) " $left left." else ""), fontSize = 15.sp, color = Belfry.colors.text)
            }
            error?.let { ErrorLine(it) }
            BusyButton(
                if (waitlist) "Join the waitlist" else if (t.closed) "Send my application" else "RSVP",
                onClick = {
                    if (needs) session.events.apply(e, t, answer, waitlist) else session.events.takeTicket(e, t)
                    sent = true
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth().testTag("take-submit"),
                enabled = !sent && !(t.closed && !waitlist && answer.isBlank()),
                onError = { error = shownError(it) },
            )
        }
    }
}

/** Ticket types: no price in v1. None = the plain RSVP. */
@Composable
internal fun TicketsSheet(e: CalEvent, onDismiss: () -> Unit) {
    val world = LocalWorld.current
    val session = LocalSession.current
    val list = world.tickets(e).filter { !it.implicit }
    var editing by remember { mutableStateOf<TicketForm?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val form = editing
    if (form != null) {
        BelfrySheet(onDismiss = onDismiss, title = if (form.id != null) "Edit the ticket" else "New ticket") {
            Column(Modifier.padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                BelfryTextField(form.title, { editing = form.copy(title = it.take(120)) }, Modifier.fillMaxWidth().testTag("ticket-name"), label = "Name", placeholder = "e.g. Standard, Speaker, Volunteer")
                BelfryTextField(form.summary, { editing = form.copy(summary = it.take(300)) }, Modifier.fillMaxWidth(), label = "Summary")
                BelfryTextField(
                    form.quantity, { v -> editing = form.copy(quantity = v.filter { it.isDigit() }.take(6)) }, Modifier.fillMaxWidth(),
                    label = "How many places", placeholder = "No limit", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Segmented(listOf("Open: one tap", "Needs an application"), if (form.closed) 1 else 0, { editing = form.copy(closed = it == 1) })
                if (form.closed) BelfryTextField(form.prompt, { editing = form.copy(prompt = it.take(500)) }, Modifier.fillMaxWidth(), label = "The question applicants answer", placeholder = "Why would you like to come?", singleLine = false, minLines = 2)
                ActionGroup { SwitchRow("Members only", form.members, { editing = form.copy(members = it) }, sub = "Shown to everyone, taken by members") }
                error?.let { ErrorLine(it) }
                BusyButton(
                    "Save the ticket",
                    onClick = { session.events.saveTicket(e, form); editing = null },
                    modifier = Modifier.fillMaxWidth().testTag("ticket-save"), enabled = form.title.isNotBlank(), onError = { error = shownError(it) },
                )
            }
        }
        return
    }
    BelfrySheet(onDismiss = onDismiss, title = "Tickets") {
        Column(Modifier.padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (list.isEmpty()) Muted("No tickets: anyone who may see the event RSVPs in one tap, without a limit. Add a ticket for a limit, an application or a members-only place.")
            val p = world.eventPeople(e)
            for (t in list) {
                TicketRow(t, p, trailing = {
                    IconAction(BelfryIcons.Edit, "Edit ${t.title}", {
                        editing = TicketForm(t.id, t.title, t.summary, t.quantity?.toString() ?: "", t.closed, t.prompt, t.members, t.position)
                    })
                    BusyIconButton(BelfryIcons.Trash, "Delete ${t.title}", onClick = { session.events.deleteTicket(t) })
                })
            }
            BusyButton(
                "Add a ticket", onClick = { editing = TicketForm(title = "", position = list.size + 1) },
                modifier = Modifier.fillMaxWidth().testTag("ticket-add"), kind = ButtonKind.Ghost, icon = BelfryIcons.Plus,
            )
        }
    }
}

/** Hosts: who is going, who asks, the waitlist, the rest. Approve, decline, make a co-host. */
@Composable
internal fun GuestListSheet(e: CalEvent, onDismiss: () -> Unit) {
    val world = LocalWorld.current
    val session = LocalSession.current
    val p = world.eventPeople(e)
    val going = goingList(e, p)
    val asking = p.applications.filter { !it.waitlist }
    val waiting = p.applications.filter { it.waitlist }
    val other = e.rsvps.filter { (k, s) -> s != Rsvp.ACCEPTED && k !in going }.keys.toList()
    var tab by remember(e.coord) { mutableIntStateOf(if (asking.isNotEmpty()) 1 else 0) }
    val tks = world.tickets(e)
    fun titleOf(d: String?) = tks.firstOrNull { it.d == d && !it.implicit }?.title ?: ""
    val guests = world.groups.flatMap { world.guestJoins(it.id) }.filter { !it.member }.map { it.pubkey }.toSet()
    var error by remember { mutableStateOf<String?>(null) }

    @Composable
    fun Decide(a: EventApplication) {
        // approve and decline share one lock: one tap decides once
        val busy = rememberBusy()
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BusyButton("Approve", onClick = { session.events.decide(e, a.pubkey, true, a.ticket, a.ev) }, small = true, busy = busy,
                modifier = Modifier.testTag("approve-" + a.pubkey.take(8)), onError = { error = shownError(it) })
            BusyButton("Decline", onClick = { session.events.decide(e, a.pubkey, false) }, small = true, kind = ButtonKind.Ghost, busy = busy,
                modifier = Modifier.testTag("decline-" + a.pubkey.take(8)), onError = { error = shownError(it) })
        }
    }
    fun sub(a: EventApplication) = titleOf(a.ticket) + (if (a.answer.isNotEmpty()) (if (titleOf(a.ticket).isNotEmpty()) " · " else "") + "“" + a.answer.take(200) + "”" else "")

    BelfrySheet(onDismiss = onDismiss, title = "Guest list") {
        Column(Modifier.padding(bottom = 16.dp)) {
            Tabs(listOf("Going ${going.size}", "Asking ${asking.size}", "Waitlist ${waiting.size}", "Others"), tab, { tab = it }, Modifier.testTag("guest-tabs"))
            error?.let { ErrorLine(it, Modifier.padding(top = 8.dp)) }
            Column(Modifier.padding(top = 8.dp)) {
                when (tab) {
                    0 -> if (going.isEmpty()) Muted("Nobody yet.") else for (k in going) {
                        PersonRow(k, titleOf(p.holders[k]), k in e.hosts, k in guests) {
                            if (k !in e.hosts && k !in guests && !e.remote) BusyButton("Make co-host", onClick = { session.events.addCoHost(e, k) }, small = true, kind = ButtonKind.Ghost)
                        }
                    }
                    1 -> if (asking.isEmpty()) Muted("No applications waiting.") else for (a in asking) PersonRow(a.pubkey, sub(a), false, a.pubkey in guests) { Decide(a) }
                    2 -> if (waiting.isEmpty()) Muted("Nobody on the waitlist.") else for (a in waiting) PersonRow(a.pubkey, sub(a), false, a.pubkey in guests) { Decide(a) }
                    else -> {
                        for (k in other) PersonRow(k, e.rsvps[k]?.label, k in e.hosts, k in guests)
                        for (k in p.declined) PersonRow(k, "Declined", false, k in guests)
                        if (other.isEmpty() && p.declined.isEmpty()) Muted("Nobody.")
                    }
                }
            }
        }
    }
}

/** A QR code is a button: it opens full width on white with the URL under it. */
@Composable
internal fun QrSheet(url: String, title: String, onDismiss: () -> Unit) {
    BelfrySheet(onDismiss = onDismiss, title = title) {
        Column(Modifier.padding(bottom = 16.dp)) { LinkBox(url, title) }
    }
}

/** A co-host invitation link: for one person, for a day. Someone new saves an e-mail first. */
@Composable
internal fun CoHostSheet(e: CalEvent, onDismiss: () -> Unit) {
    val session = LocalSession.current
    var url by remember(e.d) { mutableStateOf<String?>(null) }
    var error by remember(e.d) { mutableStateOf<String?>(null) }
    var busy by remember(e.d) { mutableStateOf(true) }
    suspend fun make() {
        busy = true
        error = null
        try { url = session.events.coHostLink(e) } catch (x: Exception) { error = shownError(x) } finally { busy = false }
    }
    LaunchedEffect(e.d) { make() }
    BelfrySheet(onDismiss = onDismiss, title = "Co-host invitation link") {
        Column(Modifier.padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Muted("For one person, for a day. Whoever opens it becomes a host: they edit the event and answer applications. Someone new saves an e-mail first.")
            LinkBox(url ?: "https://", "Co-host invitation", waiting = url == null, busy = busy, error = error, onRetry = { make() })
        }
    }
}

/** Cancel the event, with a line for the guests; it can be reopened. */
@Composable
internal fun CancelSheet(e: CalEvent, onDismiss: () -> Unit) {
    val session = LocalSession.current
    var reason by remember { mutableStateOf("") }
    BelfrySheet(onDismiss = onDismiss, title = "Cancel the event?") {
        Column(Modifier.padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Muted("Everyone with a place or an application is told by e-mail when they gave one. You can reopen it later.")
            BelfryTextField(reason, { reason = it.take(500) }, Modifier.fillMaxWidth().testTag("cancel-reason"), label = "A line for the guests (optional)", singleLine = false, minLines = 2)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BusyButton("Keep it", onClick = { onDismiss() }, modifier = Modifier.weight(1f), kind = ButtonKind.Ghost)
                BusyButton("Cancel the event", onClick = { session.events.setStatus(e, EventStatus.CANCELLED, reason.trim()); onDismiss() }, modifier = Modifier.weight(1f).testTag("cancel-confirm"), kind = ButtonKind.Danger)
            }
        }
    }
}
