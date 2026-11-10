package sh.belfry.core.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import sh.belfry.core.crypto.Random
import sh.belfry.core.model.ApiEvent
import sh.belfry.core.model.CalEvent
import sh.belfry.core.model.EventActions
import sh.belfry.core.model.EventForm
import sh.belfry.core.model.EventLinks
import sh.belfry.core.model.EventStatus
import sh.belfry.core.model.Rsvp
import sh.belfry.core.model.SlugCheck
import sh.belfry.core.model.Ticket
import sh.belfry.core.model.TicketForm
import sh.belfry.core.model.eventByLink
import sh.belfry.core.model.tickets
import sh.belfry.core.net.EventsApi
import sh.belfry.core.nostr.Event
import sh.belfry.core.nostr.EventTemplate

/** What the belfry said about an event read through its API (cached a minute). */
data class RemoteEvent(val api: ApiEvent? = null, val error: String? = null, val at: Long = 0)

/**
 * Belfry events for one [Session] (PROTOCOL.md; the web chat's src/core/events.ts): making and
 * changing events, tickets, RSVPs, applications, hosts' decisions, co-host links, and the
 * belfry's answer for events this device can't read from the community relay (a guest).
 * Every write goes through the outbox, except the calls the belfry itself answers (a ticket
 * it issues, a guest's application, a guest co-host's decision, the custom URL).
 */
class EventsService internal constructor(private val s: Session) {
    private val _remote = MutableStateFlow<Map<String, RemoteEvent>>(emptyMap())
    /** link → what the belfry said. */
    val remote: StateFlow<Map<String, RemoteEvent>> = _remote

    private val world get() = s.world.value
    private val amGuest: Boolean get() = world.amGuest

    private suspend fun <T> api(block: suspend (EventsApi) -> T): T = s.apiCall { block(EventsApi(it, s.signer)) }

    /** The coordinate of an event the belfry told us about (for the `a` tag of a message). */
    fun knownCoord(link: String): String? = _remote.value[link]?.api?.coord

    /**
     * Asks the belfry for an event this device doesn't have (cached a minute; FORCE asks again).
     * The demo has no belfry: unknown events stay unknown (`not_found`).
     */
    suspend fun fetch(link: String, force: Boolean = false): RemoteEvent {
        val had = _remote.value[link]
        if (!force && had != null && s.now() - had.at < 60) return had
        val got = if (s.localOnly) RemoteEvent(error = "not_found", at = s.now()) else try {
            RemoteEvent(api = api { it.event(link) }, at = s.now())
        } catch (e: ActionException) {
            RemoteEvent(error = e.code, at = s.now())
        }
        _remote.update { it + (link to got) }
        return got
    }

    private fun forget(e: CalEvent) = _remote.update { it - e.d - e.link }

    // ---- making and changing ------------------------------------------------------------------------

    /** A new event (its group, the NIP-52 event, a ticket when it takes applications). Returns its link. */
    suspend fun create(n: EventForm): String {
        val d = EventLinks.newD(s.now())
        s.send(EventActions.create(n, d, s.me, s.community.groupsRelay, s.community.mainRelay))
        if (n.slug.isNotEmpty()) try { claimSlug(d, n.slug) } catch (e: ActionException) { return d }
        return n.slug.ifEmpty { d }
    }

    /** A host's new version; a new custom URL is claimed first (it may be taken). Returns the link. */
    suspend fun edit(e: CalEvent, n: EventForm): String {
        if (n.slug.isNotEmpty() && n.slug != e.slug) claimSlug(e.d, n.slug)
        s.send(EventActions.edit(e, n, world.group(e.groupId), s.community.groupsRelay))
        forget(e)
        return n.slug.ifEmpty { e.d }
    }

    /** Is that custom URL free? The demo checks the events it knows. */
    suspend fun slugFree(name: String, d: String = ""): SlugCheck {
        val problem = EventLinks.slugProblem(name)
        if (s.localOnly) {
            val other = world.calendar.any { it.slug == name && it.d != d }
            return SlugCheck(problem == null && !other, name, problem)
        }
        if (problem != null) return SlugCheck(false, name, problem)
        return api { it.slug(name, d) }
    }

    suspend fun claimSlug(d: String, slug: String) {
        if (s.localOnly) return
        api { it.claimSlug(d, slug) }
    }

    suspend fun setStatus(e: CalEvent, status: EventStatus, reason: String = "") {
        s.send(EventActions.setStatus(e, status, reason))
    }

    suspend fun saveTicket(e: CalEvent, t: TicketForm) {
        s.send(EventActions.saveTicket(e, t, world.tickets(e), world.group(e.groupId), s.community.mainRelay))
    }

    suspend fun deleteTicket(t: Ticket) {
        EventActions.deleteTicket(t)?.let { s.send(it) }
    }

    /** The creator deletes it (it disappears for everyone; its chat stays). */
    suspend fun delete(e: CalEvent) {
        s.send(sh.belfry.core.model.Actions.deleteEvent(e))
    }

    // ---- coming --------------------------------------------------------------------------------------

    /** Going / Maybe / Can't go: a member's on the community relay; a guest's in the event's group. */
    suspend fun rsvp(e: CalEvent, status: Rsvp, ticketD: String? = null) {
        s.send(EventActions.rsvp(e, status, s.me, ticketD, onGroups = amGuest || e.remote, eventPending = world.isPending(e.ev.id)))
    }

    /**
     * Take an open ticket. A member on the implicit free ticket simply RSVPs; otherwise the belfry
     * checks the places and issues it (a 9000 by the groups key). Throws [ActionException].
     */
    suspend fun takeTicket(e: CalEvent, t: Ticket) {
        if (t.implicit && !amGuest && !e.remote) return rsvp(e, Rsvp.ACCEPTED)
        if (s.localOnly) {
            demoIssue(e, s.me, t.d)
            rsvp(e, Rsvp.ACCEPTED, t.d)
            return
        }
        api { it.join(e.d, t.d) }
        forget(e)
        fetch(e.link, force = true)
    }

    /**
     * Apply (or join the waitlist): a member sends the 9021 to the group; a guest signs it and the
     * belfry hands it in (the relay takes it from people who are not members, zooid patch 0004).
     */
    suspend fun apply(e: CalEvent, t: Ticket, answer: String, waitlist: Boolean) {
        val draft = EventActions.apply(e, t, answer.trim(), waitlist)
        if (!amGuest && !e.remote) {
            s.send(draft)
            return
        }
        val request = try {
            s.signer.sign(EventTemplate(draft.kind, draft.content, draft.tags, s.now()))
        } catch (x: Exception) {
            throw ActionException("signer", "Your signer did not answer. Check your connection and try again.")
        }
        if (!s.localOnly) api { it.apply(e.d, t.d, request) }
        s.addLocal(listOf(request))
        forget(e)
    }

    suspend fun withdraw(e: CalEvent) {
        s.send(EventActions.withdraw(e))
    }

    /**
     * A host approves an application (the ticket: a 9000 with `ticket`) or declines it. A guest
     * co-host can't moderate on the relay: the belfry does it for them (POST /events/<d>/decide).
     */
    suspend fun decide(e: CalEvent, pubkey: String, approve: Boolean, ticketD: String? = null, application: Event? = null) {
        if (amGuest && !s.localOnly) {
            api { it.decide(e.d, pubkey, approve, ticketD) }
            return
        }
        s.send(EventActions.decide(e, pubkey, approve, ticketD, application))
    }

    suspend fun addCoHost(e: CalEvent, pubkey: String) {
        s.send(EventActions.addCoHost(e, pubkey))
    }

    /** A co-host invitation link (a channel invite with role host, guests.py). */
    suspend fun coHostLink(e: CalEvent): String {
        if (s.localOnly) return "https://chat.${s.community.domain}/join/demo-cohost-${e.d}"
        return api { it.coHostLink(e.groupId) }
    }

    /** The event by its URL's last part: this device's, or null (ask [fetch]). */
    fun local(link: String): CalEvent? = world.eventByLink(link)

    // ---- the demo: what the belfry would publish --------------------------------------------------

    private fun demoIssue(e: CalEvent, pubkey: String, ticketD: String) {
        val ev = Demo.groups.signNow(EventTemplate(9000, "", listOf(
            listOf("h", e.groupId), listOf("p", pubkey, "member"), listOf("ticket", ticketD), listOf("paid", "free"), listOf("a", e.coord),
        ), s.now()))
        s.addLocal(listOf(ev))
    }

    companion object {
        /** A ticket id for a new ticket type. */
        fun newTicketId(): String = Random.hex(3)
    }
}
