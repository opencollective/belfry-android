package sh.belfry.core.net

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import sh.belfry.core.model.ApiEvent
import sh.belfry.core.model.ApiMe
import sh.belfry.core.model.ApiTicket
import sh.belfry.core.model.EventStatus
import sh.belfry.core.model.Rsvp
import sh.belfry.core.model.SlugCheck
import sh.belfry.core.model.Visibility
import sh.belfry.core.nostr.Event
import sh.belfry.core.nostr.Signer
import sh.belfry.core.nostr.arr
import sh.belfry.core.nostr.bool
import sh.belfry.core.nostr.int
import sh.belfry.core.nostr.obj
import sh.belfry.core.nostr.s

/**
 * Belfry events through the belfry's API (services/onboarding/belfry_onboarding/events.py):
 * `<api base>/events/<link>` (read), `/events/slug`, `PUT /events/<d>/slug`, `POST /events/<d>/join`,
 * `/apply`, `/decide`, and co-host links (`POST /channel-invites` with `role: host`). Every call is
 * signed by the member (NIP-98). Failures are [ApiException]s with fixed messages chosen by the
 * server's `error` key; its own text is never shown.
 */
class EventsApi(private val api: BelfryApi, private val signer: Signer) {
    private val base: String get() = api.community.apiBase + "/events"

    private fun fail(status: Int, body: JsonObject?, fallback: String, own: Map<String, String> = emptyMap()): Nothing {
        val code = body?.s("error")?.ifEmpty { null } ?: if (status == 0) "network" else "http"
        throw ApiException(code, status, own[code] ?: ERRORS[code] ?: fallback)
    }

    /** The event as the belfry tells it to me. Codes: `not_found`, `members_only`. */
    suspend fun event(link: String): ApiEvent {
        val (status, body) = api.signedFetch(signer, "$base/${encodeURIComponent(link)}")
        if (status == 403) throw ApiException("members_only", 403, ERRORS.getValue("members_only"))
        if (status == 404) throw ApiException("not_found", 404, ERRORS.getValue("not_found"))
        if (status !in 200..299 || body == null) fail(status, body, "The event could not be read just now.")
        return parse(body) ?: throw ApiException("http", status, "The event could not be read just now.")
    }

    /** Is that custom URL free? EVENT: the event it is for (its own name counts as free). */
    suspend fun slug(name: String, event: String = ""): SlugCheck {
        val q = "name=" + encodeURIComponent(name) + if (event.isNotEmpty()) "&event=" + encodeURIComponent(event) else ""
        val (status, body) = api.signedFetch(signer, "$base/slug?$q")
        if (status !in 200..299 || body == null) fail(status, body, "Could not check the URL.")
        return SlugCheck(body["free"].bool() ?: false, body.s("suggestion") ?: name, body.s("problem"))
    }

    /** A host sets the event's URL to /events/<slug>. Codes: `taken`, `invalid`, `not_host`. */
    suspend fun claimSlug(d: String, slug: String) {
        val (status, body) = api.signedFetch(signer, "$base/$d/slug", "PUT", buildJsonObject { put("slug", JsonPrimitive(slug)) })
        if (status !in 200..299) fail(status, body, "That URL could not be set.")
    }

    /** Take an open ticket (the belfry checks the places and issues it). Codes: `full`, `needs_application`, `members_only`, `cancelled`, `ended`… */
    suspend fun join(d: String, ticket: String) {
        val (status, body) = api.signedFetch(signer, "$base/$d/join", "POST", buildJsonObject { put("ticket", JsonPrimitive(ticket)) })
        if (status !in 200..299) fail(status, body, "That did not work. Try again.")
    }

    /** A guest's application: their own signed 9021, which the belfry hands to the group. */
    suspend fun apply(d: String, ticket: String, request: Event) {
        val (status, body) = api.signedFetch(signer, "$base/$d/apply", "POST", buildJsonObject {
            put("ticket", JsonPrimitive(ticket))
            put("request", request.toJson())
        })
        if (status !in 200..299) fail(status, body, "Your application did not go through. Try again.")
    }

    /** A host who can't moderate on the relay (a guest co-host): the belfry approves or declines for them. */
    suspend fun decide(d: String, pubkey: String, approve: Boolean, ticket: String?) {
        val (status, body) = api.signedFetch(signer, "$base/$d/decide", "POST", buildJsonObject {
            put("pubkey", JsonPrimitive(pubkey))
            put("action", JsonPrimitive(if (approve) "approve" else "decline"))
            if (ticket != null) put("ticket", JsonPrimitive(ticket))
        })
        if (status !in 200..299) fail(status, body, "That did not work.")
    }

    /** A co-host invitation link: for one person, for a day. Whoever opens it becomes a host. */
    suspend fun coHostLink(groupId: String): String {
        val (status, body) = api.signedFetch(signer, api.community.channelInvites, "POST", buildJsonObject {
            put("channel", JsonPrimitive(groupId))
            put("role", JsonPrimitive("host"))
            put("people", JsonPrimitive(1))
            put("link_days", JsonPrimitive(1))
        })
        val url = body?.s("url")
        if (status !in 200..299 || url == null || !url.startsWith("https://")) fail(status, body, "The link could not be made.", mapOf("invalid" to "This event can’t have co-host links.", "not_allowed" to "You can’t invite here.", "guest" to "Guests can’t make co-host links."))
        return url
    }

    companion object {
        /** The API's answer, or null when it is not an event. Text fields are cut to sane lengths. */
        fun parse(o: JsonObject): ApiEvent? {
            val d = o.s("d") ?: return null
            val coord = o.s("coord") ?: return null
            val group = o.s("group") ?: return null
            val me = o["me"].obj()?.let { m ->
                ApiMe(
                    host = m["host"].bool() ?: false, ticket = m.s("ticket"), applied = m["applied"].bool() ?: false,
                    waitlist = m["waitlist"].bool() ?: false, declined = m["declined"].bool() ?: false,
                    rsvp = Rsvp.of(m.s("rsvp")), member = m["member"].bool() ?: false,
                )
            }
            return ApiEvent(
                d = d, coord = coord, kind = o["kind"].int() ?: 31923, group = group, link = o.s("link") ?: d, url = o.s("url") ?: "",
                title = (o.s("title") ?: "Untitled").take(200), description = (o.s("description") ?: "").take(20_000),
                allDay = o["all_day"].bool() ?: false, start = o.s("start"), end = o.s("end"), tz = o.s("tz") ?: "",
                location = (o.s("location") ?: "").take(300), online = o["online"].bool() ?: false,
                links = o["links"].arr()?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.takeIf { u -> u.startsWith("https://") } } ?: emptyList(),
                cover = o.s("cover")?.takeIf { it.startsWith("https://") },
                visibility = Visibility.of(o.s("visibility")),
                status = if (o.s("status") == "cancelled") EventStatus.CANCELLED else EventStatus.SCHEDULED,
                statusReason = (o.s("status_reason") ?: "").take(500),
                hosts = o["hosts"].arr()?.mapNotNull { h -> h.obj()?.let { x -> x.s("pubkey")?.let { it to (x.s("name") ?: "").take(100) } } } ?: emptyList(),
                tickets = o["tickets"].arr()?.mapNotNull { t ->
                    t.obj()?.let { x ->
                        val td = x.s("d") ?: return@let null
                        ApiTicket(
                            td, x.s("id") ?: td.substringAfter(':'), (x.s("title") ?: "Ticket").take(120), (x.s("summary") ?: "").take(300),
                            x["quantity"].int(), x["left"].int(), x["closed"].bool() ?: false, (x.s("prompt") ?: "").take(500),
                            x["members"].bool() ?: false, x["position"].int() ?: 0, x["implicit"].bool() ?: false,
                        )
                    }
                } ?: emptyList(),
                going = o["going"].int() ?: 0, community = (o.s("community") ?: "").take(100), me = me,
            )
        }

        private val ERRORS = mapOf(
            "not_found" to "There is no such event here.",
            "members_only" to "This event is for members.",
            "too_many" to "Too many requests for now. Try again in a minute.",
            "cancelled" to "This event is cancelled.",
            "ended" to "This event is over.",
            "no_ticket" to "Pick one of the event’s tickets.",
            "needs_application" to "This ticket takes an application.",
            "full" to "This ticket is full: join the waitlist.",
            "use_join" to "This ticket is open: take it.",
            "name_needed" to "Tell us your name.",
            "email_needed" to "An application needs your e-mail address.",
            "member_email" to "This address belongs to a member: sign in with it instead.",
            "publish_yourself" to "That did not work. Try again.",
            "request_needed" to "Your application did not go through. Try again.",
            "bad_request" to "Your application did not go through. Try again.",
            "relay" to "The event could not be reached. Try again.",
            "failed" to "That did not work just now. Try again.",
            "not_host" to "Only the event’s hosts can do this.",
            "taken" to "Another event has that URL.",
            "invalid" to "That URL can’t be used.",
            "unsupported" to "This belfry does not have event guests yet.",
            "unauthorized" to "Your session was not accepted. Sign in again.",
            "no_pass" to "Only members can do this.",
            "network" to "No connection to the belfry.",
        )
    }
}
