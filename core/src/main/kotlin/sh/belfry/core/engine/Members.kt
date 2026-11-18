package sh.belfry.core.engine

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import sh.belfry.core.crypto.Random
import sh.belfry.core.model.Actions
import sh.belfry.core.model.Agreement
import sh.belfry.core.model.Application
import sh.belfry.core.model.AttendeeList
import sh.belfry.core.model.ApiEvent
import sh.belfry.core.model.ChannelCategory
import sh.belfry.core.model.Charter
import sh.belfry.core.model.CheckInResult
import sh.belfry.core.model.Group
import sh.belfry.core.model.GroupRole
import sh.belfry.core.model.archive
import sh.belfry.core.model.channelLayout
import sh.belfry.core.model.charter
import sh.belfry.core.model.charterGroup
import sh.belfry.core.model.groupMeta
import sh.belfry.core.model.isAgent
import sh.belfry.core.model.GuestMode
import sh.belfry.core.model.isGuest
import sh.belfry.core.model.mayModerate
import sh.belfry.core.model.townChannels
import sh.belfry.core.model.vouch
import sh.belfry.core.net.AgentRequest
import sh.belfry.core.net.applyToJoin
import sh.belfry.core.net.ApplyStatus
import sh.belfry.core.net.BelfryRequest
import sh.belfry.core.net.DirMember
import sh.belfry.core.net.EventsApi
import sh.belfry.core.net.GuestLink
import sh.belfry.core.net.JoinsAs
import sh.belfry.core.net.MembersApi
import sh.belfry.core.net.MembershipSettings
import sh.belfry.core.net.MyApplication
import sh.belfry.core.net.PublicAgreement
import sh.belfry.core.net.Role
import sh.belfry.core.net.RolesInfo
import sh.belfry.core.net.publicCharter
import sh.belfry.core.net.publicEvents
import sh.belfry.core.nostr.obj
import sh.belfry.core.nostr.parseJsonOrNull

/**
 * The belfry's people and its town for one [Session]: roles, membership settings, requests to
 * join (people, agents, vouches), the members directory, member links, a guest's request to
 * become a member, the public charter and events, the charter's actions, channel categories,
 * roles in a group, archiving, check-in. What the belfry answers is kept in the account's store
 * (offline first: a screen draws from the stored copy and refreshes it in the background). Every
 * write goes through [Session.apiCall] (refused in guest mode) or the outbox.
 */
class MembersService internal constructor(private val s: Session) {
    private val world get() = s.world.value
    private val demo: Boolean get() = s.localOnly

    private suspend fun <T> api(write: Boolean = true, block: suspend (MembersApi) -> T): T = s.apiCall(write) { block(MembersApi(it, s.signer)) }
    private suspend fun <T> events(write: Boolean = true, block: suspend (EventsApi) -> T): T = s.apiCall(write) { block(EventsApi(it, s.signer)) }

    private fun stored(key: String): JsonObject? = parseJsonOrNull(s.store.get(key) ?: "").obj()

    private val _roles = MutableStateFlow(stored(K_ROLES)?.let { RolesInfo.parse(it) } ?: RolesInfo())
    /** The belfry's roles and each member's (stored copy first). */
    val roles: StateFlow<RolesInfo> = _roles

    private val _membership = MutableStateFlow(stored(K_MEMBERSHIP)?.let { MembershipSettings.parse(it) } ?: MembershipSettings())
    val membership: StateFlow<MembershipSettings> = _membership

    private val _requests = MutableStateFlow<List<BelfryRequest>>(emptyList())
    /** People asking to join the belfry (stewards; members while it accepts by vouches). */
    val requests: StateFlow<List<BelfryRequest>> = _requests

    private val _agentRequests = MutableStateFlow<List<AgentRequest>>(emptyList())
    val agentRequests: StateFlow<List<AgentRequest>> = _agentRequests

    private val _directory = MutableStateFlow<List<DirMember>>(emptyList())
    /** Every member of the belfry (from the belfry; empty until asked or on an older belfry). */
    val directory: StateFlow<List<DirMember>> = _directory

    private val _apply = MutableStateFlow(stored(K_APPLY)?.let { ApplyStatus.parse(it) })
    /** A guest's request to become a member, as last known (null: never asked). */
    val apply: StateFlow<ApplyStatus?> = _apply

    private val _publicCharter = MutableStateFlow(
        stored(K_PUBLIC_CHARTER)?.let { PublicAgreement.parseList(it) }?.map { it.agreement } ?: emptyList(),
    )
    /** The charter for those who can't read its group (guests, applicants). */
    val publicCharter: StateFlow<List<Agreement>> = _publicCharter

    private val _publicEvents = MutableStateFlow<List<ApiEvent>>(emptyList())
    /** The belfry's public events (a guest's Calendar). */
    val publicEvents: StateFlow<List<ApiEvent>> = _publicEvents

    /** The agreements to show: the group's own when I can read it, else the public copy. */
    fun charterRules(): List<Agreement> = world.charter().ifEmpty { _publicCharter.value }

    // ---- roles and membership (docs/roles.md, docs/membership.md) ----

    suspend fun refreshRoles(): RolesInfo {
        val r = if (demo) demoRoles() else api(write = false) { it.roles() }
        _roles.value = r
        s.store.set(K_ROLES, r.toJson().toString())
        return r
    }

    suspend fun saveRole(id: String?, label: String, description: String, makeDefault: Boolean?): RolesInfo {
        val r = if (demo) {
            val now = _roles.value
            val rid = id ?: ("role-" + Random.hex(3))
            val list = if (now.roles.any { it.id == rid }) now.roles.map { if (it.id == rid) it.copy(label = label.trim().take(40), description = description.trim().take(200)) else it }
            else now.roles.dropLast(1) + Role(rid, label.trim().take(40), description.trim().take(200)) + now.roles.last()
            val def = if (makeDefault == true) rid else now.defaultRole
            now.copy(roles = list.map { it.copy(isDefault = it.id == def) }, defaultRole = def)
        } else api { it.saveRole(id, label, description, makeDefault) }
        _roles.value = r
        s.store.set(K_ROLES, r.toJson().toString())
        return r
    }

    suspend fun deleteRole(id: String): RolesInfo {
        val r = if (demo) _roles.value.let { it.copy(roles = it.roles.filter { x -> x.id != id }) } else api { it.deleteRole(id) }
        _roles.value = r
        s.store.set(K_ROLES, r.toJson().toString())
        return r
    }

    /** A member's role; a saved guest becomes a member with it. */
    suspend fun setMemberRole(pubkey: String, role: String) {
        if (demo) {
            if (role == Role.ADMIN) s.setAdmin(pubkey, true) else if (pubkey in world.ctx.communityAdmins) s.setAdmin(pubkey, false)
        } else {
            api { it.setMemberRole(pubkey, role) }
            s.refreshCommunityQuietly()
        }
        _roles.value = _roles.value.let { it.copy(members = it.members + (pubkey to role)) }
        s.store.set(K_ROLES, _roles.value.toJson().toString())
    }

    suspend fun refreshMembership(): MembershipSettings {
        val m = if (demo) _membership.value.copy(admin = world.isCommunityAdmin, mayInviteMembers = world.isCommunityAdmin) else api(write = false) { it.membership() }
        _membership.value = m
        s.store.set(K_MEMBERSHIP, m.toJson().toString())
        return m
    }

    suspend fun setMembership(invite: String? = null, accept: String? = null, vouches: Int? = null): MembershipSettings {
        val m = if (demo) _membership.value.let { it.copy(invite = invite ?: it.invite, accept = accept ?: it.accept, vouches = (vouches ?: it.vouches).coerceIn(1, 5)) }
        else api { it.setMembership(invite, accept, vouches) }
        _membership.value = m
        s.store.set(K_MEMBERSHIP, m.toJson().toString())
        return m
    }

    // ---- requests to join (people, agents) ----

    /** Both lists, as far as I may see them (best effort: a member who can't see one gets it empty). */
    suspend fun refreshRequests() {
        if (demo) {
            if (_requests.value.isEmpty() && world.isCommunityAdmin) _requests.value = DEMO_REQUESTS
            if (_agentRequests.value.isEmpty() && world.isCommunityAdmin) _agentRequests.value = DEMO_AGENTS
            return
        }
        try { _requests.value = api(write = false) { it.applications() } } catch (e: ActionException) { if (e.code == "not_admin" || e.code == "not_vouching" || e.code == "not_here") _requests.value = emptyList() }
        if (world.isCommunityAdmin) try { _agentRequests.value = api(write = false) { it.agentRequests() } } catch (e: ActionException) { }
    }

    suspend fun decide(r: BelfryRequest, approve: Boolean) {
        if (!demo) api { it.decideApplication(r.id, approve) }
        _requests.value = _requests.value.filter { it.id != r.id }
    }

    /** Vouch (or take it back); true when they got in by it. */
    suspend fun vouch(r: BelfryRequest, on: Boolean): Boolean {
        val accepted = if (demo) false else api { it.vouchApplication(r.id, on) }
        _requests.value = if (accepted) _requests.value.filter { it.id != r.id }
        else _requests.value.map { if (it.id == r.id) it.copy(vouched = on, vouches = (it.vouches + if (on) 1 else -1).coerceAtLeast(0)) else it }
        return accepted
    }

    suspend fun decideAgent(r: AgentRequest, approve: Boolean) {
        if (!demo) api { it.decideAgentRequest(r.id, approve) }
        _agentRequests.value = _agentRequests.value.filter { it.id != r.id }
    }

    /**
     * Accept or decline someone asking to join closed group G, through the belfry (every admin,
     * admins by role too; round 6 channels/closed-requests). The demo writes the 9000 / 9001 itself.
     */
    suspend fun decideGroupRequest(g: Group, pubkey: String, approve: Boolean) {
        if (demo) { s.send(if (approve) Actions.admit(g.id, pubkey) else Actions.decline(g.id, pubkey)); return }
        api { it.decideGroupRequest(g.id, pubkey, approve) }
    }

    /** Vouch for a request to join a closed group (a label in the group), then ask the belfry to count. */
    suspend fun vouchInGroup(g: Group, a: Application, on: Boolean) {
        s.send(Actions.vouch(g.id, a.ev.id, a.pubkey, on))
        if (!demo) api { it.checkGroupRequests(g.id) }
    }

    // ---- the directory ----

    suspend fun refreshDirectory(): List<DirMember> {
        val w = world
        val list = if (demo) {
            val people = (w.group("general")?.members ?: emptySet()).filter { !w.isGuest(it) && !w.isAgent(it) }
            people.map { pk ->
                DirMember(pk, w.realName(pk), if (pk in w.ctx.communityAdmins) Role.ADMIN else Role.MEMBER,
                    w.ctx.agents.filterValues { it == pk }.keys.sorted().map { it to w.realName(it) })
            }
        } else api(write = false) { it.directory() }
        _directory.value = list
        return list
    }

    // ---- links (docs/roles.md: guests for 1 / 7 / 30 days, my agent, members with a role) ----

    suspend fun createInvite(channel: String, who: JoinsAs, people: Int, linkDays: Int): GuestLink {
        if (demo) {
            s.checkWrite()
            val token = "demo-" + Random.hex(12)
            val d = s.community.domain
            return GuestLink("https://chat.$d/join/$token", "https://belfry.$d/invite/$token.md",
                (who as? JoinsAs.Guest)?.days ?: 0, who.people(people), linkDays, null, who is JoinsAs.Agent)
        }
        return api { it.createInvite(channel, who, people, linkDays) }
    }

    // ---- a guest asks to become a member (guestapply.py) ----

    suspend fun guestApply(answer: String, agreed: List<String>): ApplyStatus {
        val st = if (demo) {
            s.checkWrite(); delay(300)
            ApplyStatus(MyApplication(1, "new", 0, if (_membership.value.accept == "vouches") _membership.value.vouches else 0, emptyList()), null, _membership.value.accept)
        } else try {
            api { it.guestApply(answer, agreed) }
        } catch (e: ActionException) {
            // `not_guest`: this key holds no guest pass. Unless the register says I am a guest
            // or nobody at all, that is a member (a member link, an admin): no error, the belfry opens
            if (e.code != "not_guest") throw e
            if (s.registerSaysMember() == false) throw ActionException("gone", MembersApi.APPLY_ERRORS.getValue("gone"))
            s.becameMember(null)
            throw ActionException(GuestMode.ALREADY_MEMBER, GuestMode.ALREADY_MEMBER_TEXT)
        }
        keepApply(st)
        return st
    }

    suspend fun refreshApply(): ApplyStatus? {
        if (demo) return _apply.value
        val st = api(write = false) { it.applyStatus() }
        keepApply(st)
        return st
    }

    private fun keepApply(st: ApplyStatus) {
        _apply.value = st
        s.store.set(K_APPLY, st.toJson().toString())
    }

    // ---- the public charter and events (no auth) ----

    /**
     * Logged in with an address that isn't a member's (onboarding/no-link, as the web's
     * MembershipScreen): the charter to tick, the e-mail the belfry knows for me (null when it
     * says none), and `POST /apply`. The demo answers itself.
     */
    suspend fun charterToApply(): List<sh.belfry.core.net.PublicAgreement> =
        if (demo) world.charter().mapIndexed { i, a -> sh.belfry.core.net.PublicAgreement(a.id, a.title, a.about, i, 0, 0) }
        else s.apiCall(write = false) { it.publicCharter() }

    suspend fun myEmail(): String? = try { s.follows().email.trim().ifEmpty { null } } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }

    suspend fun applyToJoin(email: String, motivation: String, agreed: List<String>) {
        if (demo) return
        s.apiCall(write = false) { it.applyToJoin(email, motivation, agreed) }
    }


    suspend fun refreshPublicCharter(): List<Agreement> {
        if (demo) return _publicCharter.value.ifEmpty { world.charter().also { _publicCharter.value = it } }
        val list = s.apiCall(write = false) { it.publicCharter() }
        _publicCharter.value = list.map { it.agreement }
        s.store.set(K_PUBLIC_CHARTER, JsonObject(mapOf("agreements" to JsonArray(list.map { a ->
            buildJsonObject {
                put("id", JsonPrimitive(a.id)); put("title", JsonPrimitive(a.title)); put("about", JsonPrimitive(a.about))
                a.order?.let { put("order", JsonPrimitive(it)) }; put("added", JsonPrimitive(a.added)); put("text_at", JsonPrimitive(a.textAt))
            }
        }))).toString())
        return _publicCharter.value
    }

    suspend fun refreshPublicEvents(): List<ApiEvent> {
        if (demo) return _publicEvents.value
        val list = s.apiCall(write = false) { it.publicEvents(s.now() - 86400) }.mapNotNull { EventsApi.parse(it) }
        _publicEvents.value = list
        return list
    }

    // ---- the charter (SPEC 19.32) ----

    /** A steward writes an agreement; the first one makes the charter's group. */
    suspend fun addAgreement(title: String, about: String) {
        val w = world
        val drafts = ArrayList<sh.belfry.core.model.Draft>()
        if (w.charterGroup == null) drafts.addAll(Charter.create())
        val order = (w.charter().map { if (it.order == Int.MAX_VALUE) 0 else it.order }.maxOrNull() ?: 0) + 1
        drafts.add(Charter.add(title, about, order))
        s.send(drafts)
    }

    suspend fun editAgreement(a: Agreement, title: String, about: String, order: Int = a.order) {
        s.send(Charter.edit(a.id, title, about, if (order == Int.MAX_VALUE) 0 else order))
    }

    suspend fun removeAgreement(a: Agreement) { s.send(Charter.remove(a.id)) }

    suspend fun agree(a: Agreement, on: Boolean) { s.send(Charter.agree(a.id, on)) }

    /** Moves agreement A up (-1) or down (+1): edits with the new orders of the two that swap. */
    suspend fun moveAgreement(a: Agreement, by: Int) {
        val list = world.charter()
        val i = list.indexOfFirst { it.id == a.id }
        val j = i + by
        if (i < 0 || j !in list.indices) return
        val b = list[j]
        s.send(listOf(Charter.edit(a.id, a.title, a.about, j + 1), Charter.edit(b.id, b.title, b.about, i + 1)))
    }

    // ---- channel categories (SPEC 19.34) ----

    /** A belfry admin's new arrangement of the town's channels: the order event at once. */
    suspend fun setChannelLayout(cats: List<ChannelCategory>) {
        val w = world
        s.send(Actions.channelLayout(cats, w.channelLayoutRead, w.townChannels().map { it.id }.toSet()))
    }

    // ---- archiving and roles in a group (SPEC 19.29, 19.30) ----

    /**
     * Archive / unarchive G. Those the relay lets edit publish the 9002 themselves; an admin by
     * role asks the belfry, then the change is looked for for 5 s (or trusted from its answer).
     * Throws ActionException `update_needed` when a belfry from before archiving changed nothing.
     */
    suspend fun setArchived(g: Group, on: Boolean) {
        if (world.mayModerate(g) || demo) {
            s.send(Actions.archive(g, on))
            return
        }
        val said = events { it.groupMeta(g.id, archived = on) }
        if (said == on) return
        repeat(10) {
            delay(500)
            if (world.group(g.id)?.archived == on) return
        }
        throw ActionException("update_needed", "This belfry can’t archive yet: it needs an update.")
    }

    /** Deletes an empty channel (the belfry publishes the 9008; the demo writes it itself). */
    suspend fun deleteGroup(groupId: String) {
        if (demo) { s.checkWrite(); s.addLocalEvents(listOf(demoEvent(world.ctx.groupsKey ?: s.me, 9008, listOf(listOf("h", groupId))))); return }
        api { it.deleteGroup(groupId) }
        s.checkPresence(force = true)
    }

    /**
     * A group's name / description / access, kept with every other setting. Returns at once
     * (SPEC 19.37): those the relay lets edit queue the 9002; an admin by role queues
     * `POST /group-meta` in the outbox. Both show the change at once; a final refusal shows in
     * the channel's header ([World.failedSettings]).
     */
    suspend fun saveGroupMeta(g: Group, name: String? = null, about: String? = null, closed: Boolean? = null, prompt: String? = null, vouches: Int? = null) {
        val d = Actions.groupMeta(g, name = name, about = about, closed = closed, prompt = prompt, vouches = vouches)
        if (world.mayModerate(g) || demo) { s.send(d); return }
        val body = kotlinx.serialization.json.buildJsonObject {
            put("channel", kotlinx.serialization.json.JsonPrimitive(g.id))
            if (name != null) put("name", kotlinx.serialization.json.JsonPrimitive(name))
            if (about != null) put("about", kotlinx.serialization.json.JsonPrimitive(about))
            if (closed != null) put("closed", kotlinx.serialization.json.JsonPrimitive(closed))
            if (prompt != null) put("prompt", kotlinx.serialization.json.JsonPrimitive(prompt))
        }
        s.queueApi("/group-meta", body, d.kind, d.tags)
    }

    /** PUBKEY's role in a channel, group or event (`POST /group-roles`); the demo writes what the belfry would. */
    suspend fun setGroupRole(groupId: String, pubkey: String, role: GroupRole): GroupRole {
        if (demo) {
            s.checkWrite()
            val key = world.ctx.groupsKey
            val ev = when (role) {
                GroupRole.ADMIN -> demoEvent(key ?: s.me, 9000, listOf(listOf("h", groupId), listOf("p", pubkey, "admin")))
                GroupRole.MEMBER -> demoEvent(key ?: s.me, 9000, listOf(listOf("h", groupId), listOf("p", pubkey), listOf("role", "member")))
                GroupRole.STEWARD -> demoEvent(key ?: s.me, 1985, listOf(listOf("h", groupId), listOf("L", "belfry/group-role"), listOf("l", "steward", "belfry/group-role"), listOf("p", pubkey), listOf("by", s.me)))
            }
            val extra = if (role != GroupRole.STEWARD) listOf(demoEvent(key ?: s.me, 1985, listOf(listOf("h", groupId), listOf("L", "belfry/group-role"), listOf("l", "none", "belfry/group-role"), listOf("p", pubkey), listOf("by", s.me)))) else emptyList()
            s.addLocalEvents(listOf(ev) + extra)
            return role
        }
        return events { it.setGroupRole(groupId, pubkey, role) }
    }

    /** Take PUBKEY out of a group: through the belfry, or (an older belfry) my own 9001. */
    suspend fun removeFromGroup(groupId: String, pubkey: String) {
        if (demo) { s.send(Actions.decline(groupId, pubkey)); return }
        try {
            events { it.removeFromGroup(groupId, pubkey) }
        } catch (e: ActionException) {
            if (e.code == "update_needed") s.send(Actions.decline(groupId, pubkey)) else throw e
        }
    }

    // ---- check-in (SPEC 19.29) ----

    suspend fun attendees(d: String): AttendeeList = events(write = false) { it.attendees(d) }

    suspend fun checkIn(d: String, code: String? = null, pubkey: String? = null, undo: Boolean = false, peek: Boolean = false): CheckInResult =
        events(write = !peek) { it.checkIn(d, code, pubkey, undo, peek) }

    private var demoN = 0
    private fun demoEvent(pubkey: String, kind: Int, tags: List<List<String>>): sh.belfry.core.nostr.Event {
        demoN += 1
        return sh.belfry.core.nostr.Event("demo-role-" + Random.hex(6), pubkey, s.now() + demoN, kind, tags, "")
    }

    private fun demoRoles(): RolesInfo {
        val w = world
        val members = HashMap<String, String>()
        for (pk in w.group("general")?.members ?: emptySet()) if (!w.isGuest(pk) && !w.isAgent(pk)) members[pk] = if (pk in w.ctx.communityAdmins) Role.ADMIN else Role.MEMBER
        val gardener = Role("gardener-1a2b", "Gardener", "Looks after the shared garden.")
        return RolesInfo(listOf(Role.BUILTINS[0], gardener, Role.BUILTINS[1]), Role.MEMBER, members)
    }

    companion object {
        const val K_ROLES = "roles"
        const val K_MEMBERSHIP = "membership"
        const val K_APPLY = "apply-state"
        const val K_PUBLIC_CHARTER = "public-charter"

        val DEMO_REQUESTS = listOf(
            BelfryRequest(11, "Lena Fischer", "I run the repair café two streets away and would love to bring our tools here.", "2026-11-15", 1, false, null),
            BelfryRequest(12, "Omar", "Ana invited me to the garden: I'd like to stay and help.", "2026-11-16", 0, false, null, kind = "guest", channels = listOf("garden"), inviter = "Ana"),
        )
        val DEMO_AGENTS = listOf(
            AgentRequest(21, "7".repeat(64), "Minutes bot", "Writes the minutes of the assembly.", "2026-11-16", false),
        )
    }
}
