package sh.belfry.core.net

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import sh.belfry.core.crypto.Hex
import sh.belfry.core.model.Agreement
import sh.belfry.core.nostr.Signer
import sh.belfry.core.nostr.arr
import sh.belfry.core.nostr.bool
import sh.belfry.core.nostr.int
import sh.belfry.core.nostr.obj
import sh.belfry.core.nostr.s
import sh.belfry.core.nostr.str

// Roles, member links, the belfry's membership settings, requests to join, the members directory,
// a guest's request to become a member, the public charter and events (collective-belfry
// docs/roles.md, docs/membership.md, docs/guests.md, docs/charter.md, docs/agents.md; web
// roles.ts, invite.ts, requests.ts, apply.ts). Errors are fixed words per code, never the server's.

data class Role(
    val id: String,
    val label: String,
    val description: String = "",
    val builtin: Boolean = false,
    val powers: Boolean = false,
    val isDefault: Boolean = false,
) {
    companion object {
        const val MEMBER = "member"
        const val ADMIN = "admin"
        /** What a belfry without the roles API answers (the two built-ins). */
        val BUILTINS = listOf(
            Role(MEMBER, "Member", "Joins any open channel, invites guests, votes.", builtin = true, isDefault = true),
            Role(ADMIN, "Steward", "Runs the belfry: settings, roles, the charter.", builtin = true, powers = true),
        )
    }
}

data class RolesInfo(val roles: List<Role> = Role.BUILTINS, val defaultRole: String = Role.MEMBER, val members: Map<String, String> = emptyMap()) {
    fun role(id: String?): Role? = id?.let { i -> roles.firstOrNull { it.id == i } }

    /** The label to show next to a member (null = no role to show: a guest, an agent, or plain Member). */
    fun label(pubkey: String): String? {
        val id = members[pubkey] ?: return null
        if (id == Role.MEMBER) return null
        return role(id)?.label
    }

    fun toJson(): JsonObject = buildJsonObject {
        put("roles", JsonArray(roles.map { r ->
            buildJsonObject {
                put("id", JsonPrimitive(r.id)); put("label", JsonPrimitive(r.label)); put("description", JsonPrimitive(r.description))
                put("builtin", JsonPrimitive(r.builtin)); put("powers", JsonPrimitive(r.powers)); put("default", JsonPrimitive(r.isDefault))
            }
        }))
        put("default", JsonPrimitive(defaultRole))
        put("members", JsonObject(members.mapValues { JsonPrimitive(it.value) }))
    }

    companion object {
        /** `GET /roles`, cleaned (the built-ins always there, member first, admin last; the built-in admin reads Steward). */
        fun parse(b: JsonObject): RolesInfo {
            val list = ArrayList<Role>()
            for (o in b["roles"].arr()?.mapNotNull { it.obj() } ?: emptyList()) {
                val id = o.s("id")?.takeIf { it.isNotEmpty() } ?: continue
                val label = o.s("label")?.takeIf { it.isNotEmpty() } ?: continue
                if (list.any { it.id == id }) continue
                val shown = if (id == Role.ADMIN && label.lowercase() == "admin") "Steward" else label
                list.add(Role(id, shown.take(40), (o.s("description") ?: "").take(200),
                    o["builtin"].bool() ?: (id == Role.MEMBER || id == Role.ADMIN), o["powers"].bool() ?: (id == Role.ADMIN), o["default"].bool() ?: false))
            }
            for (r in Role.BUILTINS) if (list.none { it.id == r.id }) list.add(r)
            val ordered = list.filter { it.id == Role.MEMBER } + list.filter { it.id != Role.MEMBER && it.id != Role.ADMIN } + list.filter { it.id == Role.ADMIN }
            var def = b.s("default") ?: Role.MEMBER
            if (ordered.none { it.id == def } || def == Role.ADMIN) def = Role.MEMBER
            val members = HashMap<String, String>()
            for ((k, v) in b["members"].obj() ?: JsonObject(emptyMap())) { val r = v.str(); if (Hex.isKey(k) && r != null) members[k] = r }
            return RolesInfo(ordered.map { it.copy(isDefault = it.id == def) }, def, members)
        }
    }
}

/** Membership settings (`GET /membership`). */
data class MembershipSettings(
    /** admins | members */
    val invite: String = "admins",
    /** admins | vouches */
    val accept: String = "admins",
    val vouches: Int = 2,
    val mayInviteMembers: Boolean = false,
    val admin: Boolean = false,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("invite", JsonPrimitive(invite)); put("accept", JsonPrimitive(accept)); put("vouches", JsonPrimitive(vouches))
        put("may_invite_members", JsonPrimitive(mayInviteMembers)); put("admin", JsonPrimitive(admin))
    }

    companion object {
        fun parse(b: JsonObject) = MembershipSettings(
            invite = if (b.s("invite") == "members") "members" else "admins",
            accept = if (b.s("accept") == "vouches") "vouches" else "admins",
            vouches = (b["vouches"].int() ?: 2).coerceIn(1, 5),
            mayInviteMembers = b["may_invite_members"].bool() ?: false,
            admin = b["admin"].bool() ?: false,
        )
    }
}

/** Who a link brings in (the invite sheet's "Joins as"). */
sealed interface JoinsAs {
    /** A guest of the channel for 1, 7 or 30 days. */
    data class Guest(val days: Int) : JoinsAs
    /** My own agent. */
    data object Agent : JoinsAs
    /** A member of the belfry with a role, for a month, a year or for good. */
    data class Member(val role: String, val membership: String) : JoinsAs

    /** "Guest for a day" / "My own agent" / "<Role> · one year", and the line under it. */
    fun line(channel: String, community: String, roles: RolesInfo): Pair<String, String> = when (this) {
        is Guest -> "Guest for ${daysWord(days)}" to "$channel only, for ${daysWord(days)}"
        Agent -> "My own agent" to "Your AI agent: a member for as long as you are one"
        is Member -> "${roles.role(role)?.label ?: "Member"} · ${membershipWord(membership)}" to "A member of $community, starting in $channel"
    }

    /** An agent's or a steward's link is for one person, whatever "Link" says. */
    fun people(asked: Int): Int = when {
        this is Agent -> 1
        this is Member && role == Role.ADMIN -> 1
        else -> if (asked == 50) 50 else 1
    }

    /** The body of `POST /channel-invites` (web makeInviteLink). */
    fun body(channel: String, asked: Int, linkDays: Int): JsonObject = buildJsonObject {
        put("channel", JsonPrimitive(channel))
        put("people", JsonPrimitive(this@JoinsAs.people(asked)))
        put("link_days", JsonPrimitive(if (linkDays in setOf(1, 7, 30)) linkDays else 1))
        when (val w = this@JoinsAs) {
            is Guest -> put("guest_days", JsonPrimitive(if (w.days in setOf(1, 7, 30)) w.days else 1))
            Agent -> put("agent", JsonPrimitive(true))
            is Member -> {
                put("as", JsonPrimitive("member")); put("role", JsonPrimitive(w.role))
                put("membership", JsonPrimitive(if (w.membership in MEMBERSHIPS) w.membership else "permanent"))
            }
        }
    }

    companion object {
        val MEMBERSHIPS = listOf("month", "year", "permanent")
        fun daysWord(d: Int): String = when (d) { 7 -> "a week"; 30 -> "a month"; else -> "a day" }
        fun membershipWord(m: String): String = when (m) { "month" -> "one month"; "year" -> "one year"; else -> "permanent" }
    }
}

/** Someone asking to join the belfry (`/applications` list). */
data class BelfryRequest(
    val id: Int,
    val name: String,
    val motivation: String,
    val created: String,
    val vouches: Int,
    val vouched: Boolean,
    val email: String?,
    /** A guest asking from inside (guestapply.py): `guest`, their channels, who invited them, their key and first guest day. */
    val kind: String = "",
    val channels: List<String> = emptyList(),
    val inviter: String = "",
    val pubkey: String? = null,
    val since: String = "",
) {
    val isGuest: Boolean get() = kind == "guest"
}

/** An agent asking to join (`/agent-requests` list). */
data class AgentRequest(val id: Int, val pubkey: String, val name: String, val about: String, val created: String, val forMe: Boolean)

/** One member of the directory (`POST /whois {"directory": true}`) and their own agents. */
data class DirMember(val pubkey: String, val name: String, val role: String?, val agents: List<Pair<String, String>>)

/** A guest's request to become a member (guestapply.py). */
data class MyApplication(val id: Int, val state: String, val vouches: Int, val needed: Int, val vouchedBy: List<Pair<String, String>>) {
    val isOpen: Boolean get() = state == "new" || state == "invited"
}

data class ApplyStatus(val application: MyApplication?, val until: Long?, val accept: String) {
    /** The request's state in words for the guest's Home. */
    val line: String? get() {
        val a = application ?: return null
        return when (a.state) {
            "accepted" -> "You're a member now"
            "declined" -> "Not this time"
            else -> if (accept == "vouches" && a.needed > 0) "${minOf(a.vouches, a.needed)} of ${a.needed} ${if (a.needed == 1) "vouch" else "vouches"}" else "Waiting for a steward"
        }
    }

    fun toJson(): JsonObject = buildJsonObject {
        application?.let { a ->
            put("application", buildJsonObject {
                put("id", JsonPrimitive(a.id)); put("state", JsonPrimitive(a.state)); put("vouches", JsonPrimitive(a.vouches)); put("needed", JsonPrimitive(a.needed))
                put("vouched_by", JsonArray(a.vouchedBy.map { (pk, n) -> buildJsonObject { put("pubkey", JsonPrimitive(pk)); put("name", JsonPrimitive(n)) } }))
            })
        }
        until?.let { put("until", JsonPrimitive(it)) }
        put("accept", JsonPrimitive(accept))
    }

    companion object {
        fun parse(d: JsonObject): ApplyStatus {
            val app = d["application"].obj()?.let { a ->
                val vb = a["vouched_by"].arr()?.mapNotNull { it.obj() }?.mapNotNull { v -> v.s("pubkey")?.let { it to (v.s("name") ?: "") } } ?: emptyList()
                MyApplication(a["id"].int() ?: 0, a.s("state") ?: "new", a["vouches"].int() ?: 0, a["needed"].int() ?: 0, vb)
            }
            return ApplyStatus(app, d["until"]?.let { (it as? JsonPrimitive)?.content?.toLongOrNull() }, if (d.s("accept") == "vouches") "vouches" else "admins")
        }
    }
}

/** An agreement as `GET /charter` gives it to people who can't read the group. */
data class PublicAgreement(val id: String, val title: String, val about: String, val order: Int?, val added: Long, val textAt: Long) {
    /** As the app shows agreements (nobody's ticks: the public read has none). */
    val agreement: Agreement get() = Agreement(id, title, about, order ?: Int.MAX_VALUE, "", added, maxOf(textAt, added))

    companion object {
        fun parseList(d: JsonObject): List<PublicAgreement> = d["agreements"].arr()?.mapNotNull { it.obj() }?.mapNotNull { o ->
            val id = o.s("id")?.takeIf { Hex.isKey(it) } ?: return@mapNotNull null
            val t = o.s("title")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            PublicAgreement(id, t.take(300), (o.s("about") ?: "").take(200), o["order"].int(),
                (o["added"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0, (o["text_at"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0)
        } ?: emptyList()
    }
}

/**
 * The calls of the belfry's membership API, signed by the member (NIP-98). Every failure is an
 * [ApiException] with a fixed message per `error` code.
 */
class MembersApi(private val api: BelfryApi, private val signer: Signer) {
    private val base: String get() = api.community.apiBase

    private fun fail(status: Int, body: JsonObject?, messages: Map<String, String>, fallback: String): Nothing {
        val code = body?.s("error")?.ifEmpty { null } ?: if (status == 0) "network" else "http"
        throw ApiException(code, status, messages[code] ?: fallback)
    }

    private suspend fun post(path: String, body: JsonObject, messages: Map<String, String>, fallback: String): JsonObject {
        val (status, b) = api.signedFetch(signer, base + path, "POST", body)
        if (status !in 200..299 || b == null) fail(status, b, messages, fallback)
        return b
    }

    /** `POST /channel-invites` for WHO. */
    suspend fun createInvite(channel: String, who: JoinsAs, people: Int, linkDays: Int): GuestLink {
        val (status, b) = api.signedFetch(signer, api.community.channelInvites, "POST", who.body(channel, people, linkDays))
        val url = b?.s("url")
        if (status !in 200..299 || b == null || url == null || !url.startsWith("https://")) fail(status, b, LINK_ERRORS, "The link could not be made. Try again.")
        return GuestLink(url, b.s("agent_url")?.takeIf { it.startsWith("https://") }, b["days"].int() ?: 0, b["people"].int() ?: who.people(people),
            b["link_days"].int() ?: linkDays, b.s("expires_at"), b["own_agent"].bool() ?: (who is JoinsAs.Agent))
    }

    /** `GET /roles`. An older belfry (404) answers the two built-ins. */
    suspend fun roles(): RolesInfo {
        val (status, b) = api.signedFetch(signer, "$base/roles")
        if (status == 404 && b?.s("error").isNullOrEmpty()) return RolesInfo()
        if (status !in 200..299 || b == null) fail(status, b, ROLE_ERRORS, "Could not read the roles. Try again.")
        return RolesInfo.parse(b)
    }

    /** `POST /roles` (new) or `/roles/<id>` (change). Returns the roles after. */
    suspend fun saveRole(id: String?, label: String, description: String, makeDefault: Boolean?): RolesInfo {
        val path = if (id == null) "/roles" else "/roles/" + encodeURIComponent(id)
        return RolesInfo.parse(post(path, buildJsonObject {
            put("label", JsonPrimitive(label)); put("description", JsonPrimitive(description))
            if (makeDefault != null) put("default", JsonPrimitive(makeDefault))
        }, ROLE_ERRORS, "That did not work. Try again."))
    }

    suspend fun deleteRole(id: String): RolesInfo =
        RolesInfo.parse(post("/roles/" + encodeURIComponent(id) + "/delete", JsonObject(emptyMap()), ROLE_ERRORS, "That did not work. Try again."))

    /** `POST /member-roles`: a member's role (a saved guest becomes a member with it). */
    suspend fun setMemberRole(pubkey: String, role: String) {
        post("/member-roles", buildJsonObject { put("pubkey", JsonPrimitive(pubkey)); put("role", JsonPrimitive(role)) }, ROLE_ERRORS, "That did not work. Try again.")
    }

    /** `GET /membership`. An older belfry (404): the defaults. */
    suspend fun membership(): MembershipSettings {
        val (status, b) = api.signedFetch(signer, "$base/membership")
        if (status == 404 && b?.s("error").isNullOrEmpty()) return MembershipSettings()
        if (status !in 200..299 || b == null) fail(status, b, ROLE_ERRORS, "Could not read the membership settings.")
        return MembershipSettings.parse(b)
    }

    suspend fun setMembership(invite: String? = null, accept: String? = null, vouches: Int? = null): MembershipSettings =
        MembershipSettings.parse(post("/membership", buildJsonObject {
            if (invite != null) put("invite", JsonPrimitive(invite))
            if (accept != null) put("accept", JsonPrimitive(accept))
            if (vouches != null) put("vouches", JsonPrimitive(vouches))
        }, ROLE_ERRORS, "That did not work. Try again."))

    /** People asking to join the belfry (admins; members while it accepts by vouches). */
    suspend fun applications(): List<BelfryRequest> =
        parseRequests(post("/applications", buildJsonObject { put("list", JsonPrimitive(true)) }, REQUEST_ERRORS, "That did not work. Try again.")["applications"].arr())

    suspend fun decideApplication(id: Int, approve: Boolean) {
        post("/applications", buildJsonObject { put("decide", JsonPrimitive(id)); put("approve", JsonPrimitive(approve)) }, REQUEST_ERRORS, "That did not work. Try again.")
    }

    /** Vouch (or take it back); true when they got in by it. */
    suspend fun vouchApplication(id: Int, on: Boolean): Boolean =
        post("/applications", buildJsonObject { put("vouch", JsonPrimitive(id)); put("on", JsonPrimitive(on)) }, REQUEST_ERRORS, "That did not work. Try again.")["accepted"].bool() ?: false

    /** Agents asking to join (belfry admins). */
    suspend fun agentRequests(): List<AgentRequest> =
        parseAgentRequests(post("/agent-requests", buildJsonObject { put("list", JsonPrimitive(true)) }, REQUEST_ERRORS, "That did not work. Try again.")["requests"].arr())

    suspend fun decideAgentRequest(id: Int, approve: Boolean) {
        post("/agent-requests", buildJsonObject { put("decide", JsonPrimitive(id)); put("approve", JsonPrimitive(approve)) }, REQUEST_ERRORS, "That did not work. Try again.")
    }

    /** Ask the belfry to count a group's vouches at once (it also does every few minutes). Best effort. */
    suspend fun checkGroupRequests(channel: String) {
        try { api.signedFetch(signer, "$base/group-requests/check", "POST", buildJsonObject { put("channel", JsonPrimitive(channel)) }) } catch (e: Exception) { }
    }

    /**
     * Accept or decline a request to join a closed channel or group (`POST /group-requests/decide`,
     * Belfry 161d4d7 docs/membership.md): any admin of it (relay list, creator, by role) or a belfry
     * admin; the belfry publishes the 9000 / 9001. `already` (someone answered) counts as done.
     */
    suspend fun decideGroupRequest(channel: String, pubkey: String, approve: Boolean) {
        post("/group-requests/decide", buildJsonObject {
            put("channel", JsonPrimitive(channel)); put("pubkey", JsonPrimitive(pubkey)); put("action", JsonPrimitive(if (approve) "approve" else "decline"))
        }, GROUP_REQUEST_ERRORS, "That did not work. Try again.")
    }

    /**
     * Deletes a channel with no content (`POST /group-delete`, a group admin or belfry admin; the
     * belfry publishes the 9008). Codes: 409 `not_empty`, 409 `always_open` (general), 422 (events,
     * proposals), `not_allowed`.
     */
    suspend fun deleteGroup(channel: String) {
        post("/group-delete", buildJsonObject { put("channel", JsonPrimitive(channel)) }, DELETE_ERRORS, "The channel could not be deleted.")
    }

    /** Every member of the belfry, their own agents under them (members only). */
    suspend fun directory(): List<DirMember> =
        parseDirectory(post("/whois", buildJsonObject { put("directory", JsonPrimitive(true)) }, WHOIS_ERRORS, "The list could not be loaded."))

    // ---- a guest asks to become a member (guestapply.py) ----

    private suspend fun applyCall(body: JsonObject): ApplyStatus {
        val (status, b) = api.signedFetch(signer, "$base/applications", "POST", body)
        if (status == 409 && b?.s("error") == "pending") return ApplyStatus.parse(b)
        if (status == 404 || status == 405) throw ApiException("unsupported", status, APPLY_ERRORS.getValue("unsupported"))
        if (status !in 200..299 || b == null) fail(status, b, APPLY_ERRORS, "That did not work. Try again.")
        return ApplyStatus.parse(b)
    }

    /** Send the request: the answer and the agreements ticked on the way. */
    suspend fun guestApply(answer: String, agreed: List<String>): ApplyStatus = applyCall(buildJsonObject {
        put("apply", buildJsonObject {
            put("answer", JsonPrimitive(answer.trim().take(2000)))
            put("agreed", JsonArray(agreed.filter { Hex.isKey(it) }.map { JsonPrimitive(it) }))
        })
    })

    /** My latest request (or none) and how long I stay a guest. */
    suspend fun applyStatus(): ApplyStatus = applyCall(buildJsonObject { put("apply", JsonPrimitive("status")) })

    companion object {
        val ROLE_ERRORS = mapOf(
            "not_admin" to "Only stewards of the belfry can do this.",
            "not_here" to "Only people of this belfry can see its roles.",
            "unknown_role" to "That role doesn’t exist any more.",
            "invalid" to "A label of 1 to 40 characters; a description of up to 200.",
            "taken" to "There is a role with that label already.",
            "builtin" to "Member and Steward are built in: they can’t be deleted.",
            "admin_default" to "Steward can’t be the default role.",
            "not_member" to "Only members have a role.",
            "guest_not_saved" to "This guest has no e-mail yet. Once they save their account, you can make them a member.",
            "agent" to "An agent has no role.",
            "last_admin" to "A belfry needs at least one steward.",
            "too_many" to "A belfry has at most 50 roles.",
            "unsupported" to "This belfry can’t keep roles yet.",
            "network" to "No connection to the belfry.",
        )
        val LINK_ERRORS = mapOf(
            "not_admin" to "Only stewards of the belfry invite members.",
            "not_allowed" to "You can’t invite people to this channel.",
            "guest" to "Guests can’t invite.",
            "agent" to "Agents can’t invite.",
            "unknown_role" to "That role doesn’t exist any more. Pick another.",
            "limit" to "You have your agent already.",
            "unsupported" to "This belfry does not take guests.",
            "no_channel" to "This channel is not known to the belfry yet.",
            "invalid" to "The link could not be made. Try again.",
            "archived" to "This group is archived: nothing changes in it until it is unarchived.",
            "network" to "No connection to the belfry.",
        )
        val REQUEST_ERRORS = mapOf(
            "not_admin" to "Only stewards of the belfry can do this.",
            "not_here" to "Only members of the belfry can do this.",
            "not_vouching" to "Requests here are accepted by the stewards.",
            "not_found" to "This request is gone.",
            "decided" to "Someone answered this request already.",
            "invalid" to "That did not work. Try again.",
            "unsupported" to "This belfry can’t keep requests yet.",
            "limit" to "You have as many agents as you can have.",
            "network" to "No connection to the belfry.",
        )
        val GROUP_REQUEST_ERRORS = mapOf(
            "not_allowed" to "Only the channel’s admins and the stewards can answer requests.",
            "no_request" to "This request is gone.",
            "archived" to "This channel is archived: unarchive it first.",
            "event" to "Requests for an event are answered on the event.",
            "invalid" to "That did not work. Try again.",
            "relay" to "The belfry couldn’t reach its relay. Try again.",
            "network" to "No connection to the belfry. Try again.",
            "http" to "This belfry can’t answer requests from the app yet: it needs an update.",
        )
        val DELETE_ERRORS = mapOf(
            "not_empty" to "This channel has messages: archive it instead.",
            "always_open" to "The Townsquare can’t be deleted.",
            "invalid" to "Events and proposals are not deleted here.",
            "not_allowed" to "Only the channel’s admins and the stewards can delete it.",
            "not_admin" to "Only the channel’s admins and the stewards can delete it.",
            "network" to "No connection to the belfry.",
            "http" to "This belfry can’t delete channels yet: it needs an update.",
        )
        val WHOIS_ERRORS = mapOf(
            "not_here" to "Only members of the belfry see its members.",
            "invalid" to "The list could not be loaded.",
            "network" to "No connection to the belfry.",
        )
        val APPLY_ERRORS = mapOf(
            "guest_not_saved" to "Save your e-mail first: the stewards need a way to reach you.",
            "not_guest" to "Only guests apply from here.",
            "pending" to "Your request is waiting already.",
            "invalid" to "Write a few words (up to 2000 characters).",
            "unsupported" to "This belfry can’t take a guest’s request from the app yet. Ask the person who invited you, or a steward.",
            "gone" to "You are no longer a guest here.",
            "network" to "No connection to the belfry.",
        )

        fun parseRequests(x: JsonArray?): List<BelfryRequest> = x?.mapNotNull { it.obj() }?.mapNotNull { r ->
            val id = r["id"].int() ?: return@mapNotNull null
            BelfryRequest(
                id, (r.s("name") ?: "").take(100), (r.s("motivation") ?: "").take(2000), r.s("created") ?: "", r["vouches"].int() ?: 0,
                r["vouched"].bool() ?: false, r.s("email"), r.s("kind") ?: "", r["channels"].arr()?.mapNotNull { it.str() } ?: emptyList(),
                (r.s("inviter") ?: "").take(100), r.s("pubkey")?.takeIf { Hex.isKey(it) }, r.s("since") ?: "",
            )
        } ?: emptyList()

        fun parseAgentRequests(x: JsonArray?): List<AgentRequest> = x?.mapNotNull { it.obj() }?.mapNotNull { r ->
            val id = r["id"].int() ?: return@mapNotNull null
            val pk = r.s("pubkey")?.takeIf { Hex.isKey(it) } ?: return@mapNotNull null
            AgentRequest(id, pk, (r.s("name") ?: "").take(100), (r.s("about") ?: "").take(500), r.s("created") ?: "", r["for_me"].bool() ?: false)
        } ?: emptyList()

        fun parseDirectory(b: JsonObject): List<DirMember> {
            val agents = b["agents"].obj() ?: JsonObject(emptyMap())
            val names = b["names"].obj() ?: JsonObject(emptyMap())
            return b["members"].arr()?.mapNotNull { it.obj() }?.mapNotNull { m ->
                val pk = m.s("pubkey")?.takeIf { Hex.isKey(it) } ?: return@mapNotNull null
                val mine = agents.filter { (a, o) -> Hex.isKey(a) && o.str() == pk }.keys.sorted().map { it to (names[it].str() ?: "").take(100) }
                DirMember(pk, (m.s("name") ?: "").take(100), m.s("role"), mine)
            } ?: emptyList()
        }
    }
}

/** The charter for people who can't read its group (no auth). */
suspend fun BelfryApi.publicCharter(): List<PublicAgreement> {
    val (status, b) = plainFetch(community.apiBase + "/charter")
    if (status !in 200..299 || b == null) throw ApiException(b?.s("error") ?: "http", status, "The charter could not be read just now.")
    return PublicAgreement.parseList(b)
}

/** The belfry's public events from FROM on (no auth), soonest first, at most 100. */
suspend fun BelfryApi.publicEvents(from: Long): List<JsonObject> {
    val (status, b) = plainFetch(community.apiBase + "/events?visibility=public&from=$from")
    if (status !in 200..299 || b == null) throw ApiException(b?.s("error") ?: "http", status, "The events could not be read just now.")
    return b["events"].arr()?.mapNotNull { it.obj() } ?: emptyList()
}

/**
 * Applying to join with no link and no account (onboarding/apply-form; Belfry 8b0331d `POST
 * /apply`, no auth): e-mail, why, every current agreement of `GET /charter` ticked. No session
 * comes back: the answer reaches the applicant by e-mail.
 */
object ApplyForm {
    const val MISSING = "Please give your e-mail and a few words on why you would like to join."
    const val UNTICKED = "Please tick each agreement of the charter: joining means agreeing to them."
    const val NETWORK = "No connection to the belfry. Try again."
    const val BUSY = "Too many requests just now. Try again in a minute."
    const val OTHER = "Your request didn't go through. Try again."

    fun body(email: String, motivation: String, agreed: List<String>): JsonObject = buildJsonObject {
        put("email", JsonPrimitive(email.trim().take(200)))
        put("motivation", JsonPrimitive(motivation.trim().take(4000)))
        put("agreed", kotlinx.serialization.json.JsonArray(agreed.map { JsonPrimitive(it) }))
        put("website", JsonPrimitive("")) // the form's honeypot, always empty
    }

    /**
     * The line to show for a refused request. `refused` is the register's own words to the
     * applicant (a bad address…): shown as they come, as plain text, cut to 300 characters. Every
     * other code has fixed words.
     */
    fun error(status: Int, b: JsonObject?): String = when {
        status == 429 -> BUSY
        b?.s("error") == "refused" -> b?.s("message")?.trim()?.take(300)?.ifEmpty { null } ?: OTHER
        b?.s("error") == "unticked" -> UNTICKED
        b?.s("error") == "missing" || b?.s("error") == "bad_request" -> MISSING
        else -> OTHER
    }
}

/** Sends the application. Throws [ApiException] with [ApplyForm.error]'s words. */
suspend fun BelfryApi.applyToJoin(email: String, motivation: String, agreed: List<String>) {
    val (status, b) = try { plainPost(community.apiBase + "/apply", ApplyForm.body(email, motivation, agreed)) } catch (e: ApiException) {
        throw ApiException(e.code, e.status, if (e.code == "network") ApplyForm.NETWORK else ApplyForm.OTHER)
    }
    if (status !in 200..299 || b?.get("ok").bool() != true) throw ApiException(b?.s("error") ?: "http", status, ApplyForm.error(status, b))
}
