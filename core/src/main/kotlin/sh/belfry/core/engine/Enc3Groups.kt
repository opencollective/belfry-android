package sh.belfry.core.engine

import kotlinx.serialization.json.JsonObject
import sh.belfry.core.enc.Devices
import sh.belfry.core.enc.Enc
import sh.belfry.core.enc.EncException
import sh.belfry.core.enc.EncKeys
import sh.belfry.core.enc.EncMessages
import sh.belfry.core.enc.EncPlan
import sh.belfry.core.enc.EncRules
import sh.belfry.core.enc.EncWrite
import sh.belfry.core.enc.GroupKey
import sh.belfry.core.enc.HoldReason
import sh.belfry.core.enc.Verdict
import sh.belfry.core.net.Filters
import sh.belfry.core.nostr.Event
import sh.belfry.core.nostr.EventTemplate
import sh.belfry.core.sealed.SealedEngine

/** What the private groups' runtime needs of its session (a seam: tests give fakes). */
interface Enc3GroupPorts {
    fun groupsKey(): String?
    fun belfryKey(): String?
    /** The groups relay's connection now (a new number after each reconnect); null: not connected. */
    fun connection(): Int?
    /** FILTER read on the groups relay, paged to the end; null: no answer. */
    suspend fun query(filter: JsonObject, pageMs: Long): SealedEngine.Answer?
    /** Into the session's log (envelopes asked by `#d`, a 39002 read before sending). */
    fun addEvents(events: List<Event>)
    /** H's metadata says private (a group, closed + private: docs/one-shape.md). */
    fun privateMeta(h: String): Boolean
    /** H's members as the world knows them now (39002), or null when the group isn't known here. For display: [Enc3Groups.ready] reads its own. */
    fun members(h: String): Set<String>?
    /** SPEC 19.94: the key the groups relay signs its metadata with (NIP-11 `self`); null: not known, private writes hold. */
    fun relayKey(): String?
    /** SPEC 19.94: the newest 39002 of H signed by [relayKey] that the log holds (its date and members), or null. */
    fun relayMembers(h: String): Pair<Long, Set<String>>? = null
    /** ACCOUNT is a guest (§25: a guest member gets no entry). */
    fun isGuest(account: String): Boolean
    /** Rumors opened from 8572s (no signature), into the log as the group's events. */
    fun opened(rumors: List<Event>)
    fun later(block: suspend () -> Unit)
    fun nowMs(): Long
}

/** One private group's key line for the screens: when KEY was first used, who can read from here and who no longer can (§17.10, §18.4). */
data class KeyChange(val key: String, val at: Long, val added: List<String>, val removed: List<String>, val since: Long = 0)

/** What a group write under format 3 becomes (SPEC 19.88): the key's envelopes first (when they aren't out yet), then the 8572. */
data class GroupSealed(val before: List<EventTemplate>, val message: EventTemplate, val key: String, val rumor: Event)

/**
 * Private groups, format 3 (SPEC 19.88; docs/encrypted.md §4–§6, §8, §16.1, §16.9, §17.2, §17.8, §17.10, §17.12,
 * §18.4, §18.7, §22–§26, §28; a port of the group parts of the web's `src/core/enc/runtime.ts`, branch enc-v3,
 * 039678d … d7acb17). Reading: every 8571 / 8572 of my private groups from the groups relay; a key taken from my
 * entry (opened with the maker device's key), accepted when the maker was on its account's list at the envelope's
 * date, declared once (a key held for a group opens only that group), its claimed `set` checked against the entry
 * keys of every envelope event of that `d` (judged again as more arrive; "Can't check who can read this" while
 * they don't add up); each 8572 opened, its author's device checked, §16.9's clear-list kinds and every
 * `belfry/` label space but pin, inbox, event-status, proposal-status (and a press, and a service key's answer)
 * DROPPED, the rest handed to the log as the group's own events. Writing: [ready] then [seal].
 *
 * Never log a key, a secret or a rumor's text.
 */
class Enc3Groups(val e3: Enc3, private val ports: Enc3GroupPorts) {
    private val lock = Any()
    /** "h/K" → its envelope events by id (several per d, §4). */
    private val envs = HashMap<String, LinkedHashMap<String, Event>>()
    /** 8572s not settled yet (waits: never marked seen), by id. */
    private val msgs = LinkedHashMap<String, Event>()
    private val done = HashSet<String>()
    /** outer 8572 id → the rumor's id (a push names the outer event). */
    private val rumorOf = HashMap<String, String>()
    /** K → how many envelope events its set was judged against. */
    private val judged = HashMap<String, Int>()
    /** Keys whose entries seen don't add up to the set they claim. */
    private val cantAddUp = HashSet<String>()
    /** "h/K": a complete `#d` read found envelopes and none has an entry for this device. */
    private val noEntry = HashSet<String>()
    private val asked = HashMap<String, Long>()
    /** group → the groups relay connection its 39002 was read on (and the members it said). */
    private val membersOn = HashMap<String, Pair<Int, String>>()
    private val fetched = HashMap<String, Pair<Long, Set<String>>>()
    private val judging = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Something the screens show changed (keys, waits, lines). */
    @Volatile var onChange: (() -> Unit)? = null

    private fun hOf(e: Event): String? = Enc.one(e, "h")?.takeIf { it.isNotEmpty() }

    /** §26: private here = its metadata says so, or a key of it was ever seen on this device. */
    fun privateHere(h: String): Boolean = ports.privateMeta(h) || e3.everEncrypted(Enc3.groupConv(h))

    /** 8571 / 8572 with an `h` among EVENTS kept and judged soon; true when one was new. */
    fun offer(events: List<Event>): Boolean {
        var fresh = false
        synchronized(lock) {
            for (e in events) {
                if (e.kind != Enc.KEY && e.kind != Enc.MSG) continue
                val h = hOf(e) ?: continue
                if (e.kind == Enc.KEY) {
                    val K = Enc.one(e, "d")?.takeIf { Enc.isKey(it) } ?: continue
                    if (envs.getOrPut("$h/$K") { LinkedHashMap() }.putIfAbsent(e.id, e) == null) fresh = true
                } else if (e.id !in done && msgs.putIfAbsent(e.id, e) == null) fresh = true
            }
        }
        if (fresh) later()
        return fresh
    }

    /** Judged again soon (lists arrived, the periodic look). */
    fun poke() = later()

    private val again = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun later() {
        again.set(true)
        if (!judging.compareAndSet(false, true)) return
        ports.later {
            try { while (again.getAndSet(false)) judge() } finally { judging.set(false) }
            if (again.get()) later()
        }
    }

    private sealed interface Look {
        data class Key(val k: ConvKey) : Look
        /** The maker's lists aren't here yet: kept. */
        data object Wait : Look
        /** No entry for this device in what is here (asked by `#d`). */
        data object None : Look
        /** K was declared for another conversation: never opens this group's messages (the review's P1.5). */
        data object Wrong : Look
    }

    private fun envList(h: String, K: String): List<Event> = envs["$h/$K"]?.values?.toList().orEmpty()

    /** P1.3: a group key's `lists` (what [EncKeys.pickKey] adopts by) is its claimed set only while every entry seen adds up to it. */
    private fun judgeSet(rec: ConvKey, list: List<Event>): ConvKey {
        judged[rec.pub] = list.size
        if (rec.mine && list.isEmpty()) return rec // (my own key: its envelopes may not be back yet)
        val match = EncKeys.deviceSet(EncKeys.entryKeys(list)) == rec.claimed
        if (match) cantAddUp.remove(rec.pub) else cantAddUp.add(rec.pub)
        val lists = if (match) rec.claimed else ""
        return if (lists == rec.lists) rec else rec.copy(lists = lists)
    }

    private fun keyFor(h: String, K: String): Look {
        val list = envList(h, K)
        val held = e3.key(K)
        if (held != null) {
            if (held.conv != Enc3.groupConv(h)) return Look.Wrong
            if (held.group && held.claimed.isNotEmpty() && judged[K] != list.size) {
                val next = judgeSet(held, list)
                if (next != held) e3.replaceKey(next)
                return Look.Key(next)
            }
            return Look.Key(held)
        }
        // §17.12 (the iOS review, 2026-11-26): every envelope of this d that opens is judged, never only the first: a member
        // can publish a decoy with the same d = K and an unlisted maker; a refused one never hides the real key. The first
        // accepted wins; only when none is accepted and one waits for a list does the key wait.
        var g: GroupKey? = null
        val waitFor = LinkedHashSet<String>()
        val sk = e3.secret
        for (env in list) {
            val opened = try { EncKeys.openGroupEnvelope(sk, env, h) } catch (x: Exception) { null } ?: continue // a bad envelope: skipped
            when (EncKeys.acceptGroupKey(opened, e3.devices(opened.account), e3.nowS())) {
                Verdict.OK -> { g = opened; break }
                Verdict.WAIT -> waitFor += opened.account
                Verdict.NO -> {} // refused (a decoy, or a maker not listed then): the next one is tried
            }
        }
        if (g == null) {
            if (waitFor.isEmpty()) return Look.None
            e3.want(waitFor.toList()); return Look.Wait
        }
        val svc = g.account == ports.groupsKey() || g.account == ports.belfryKey()
        val mine = g.account == e3.me && e3.chainOnly(e3.me)?.lists?.any { g.maker in it.devices } == true
        val rec = judgeSet(ConvKey(K, g.secret, Enc3.groupConv(h), g.maker, g.at, mine, svc, group = true, lists = "", claimed = g.set), list)
        if (!e3.putKey(rec)) { // §17.12: declared before: the first stays
            val k = e3.key(K)
            return if (k != null && k.conv == Enc3.groupConv(h)) Look.Key(k) else Look.Wrong
        }
        return Look.Key(rec)
    }

    /**
     * P1.1 / §26 / the ruling of d7acb17: what the relay or the belfry decides never comes from inside an 8572: §16.9's
     * clear-list kinds and 1985s in `belfry/` label spaces are dropped, but a press (`belfry/action`), the service keys'
     * answers to presses (`belfry/action-result`) and pin, inbox, event-status, proposal-status (the model's own admin /
     * author checks apply, the author being the account whose device sealed it).
     */
    fun privileged(r: Event): Boolean {
        val ls = if (r.kind == 1985) Enc.values(r, "L") else emptyList()
        val service = r.pubkey == ports.groupsKey() || r.pubkey == ports.belfryKey()
        if (ls.isNotEmpty() && ls.all { it == RESULT } && service) return false
        if (r.kind == Enc.KEY || r.kind == Enc.MSG || r.kind == Enc.LIST || r.kind in Enc.PACK..Enc.ANSWER) return true
        if (EncRules.clearInGroup(r, ports.groupsKey())) return true
        if (r.kind != 1985) return false
        if (ls.none { it.startsWith("belfry/") }) return false
        if (ls.all { it == PRESS }) return false
        if (ls.all { it in THROUGH }) return false
        return true
    }

    /** The 8572s waiting here opened, judged and handed on; returns the rumors accepted now. */
    fun judge(): List<Event> {
        val out = ArrayList<Event>()
        val asks = ArrayList<Pair<String, String>>()
        val wants = LinkedHashSet<String>()
        synchronized(lock) {
            // P1.3 / P2.3: more envelope events of a held key arrived: its entries judged again against its set
            for ((name, m) in envs) {
                val h = name.substring(0, name.lastIndexOf('/'))
                val held = e3.key(name.substring(name.lastIndexOf('/') + 1)) ?: continue
                if (held.conv != Enc3.groupConv(h) || !held.group || held.claimed.isEmpty() || judged[held.pub] == m.size) continue
                val next = judgeSet(held, m.values.toList())
                if (next != held) e3.replaceKey(next)
            }
            for ((id, e) in msgs.entries.toList()) {
                val h = hOf(e)
                val K = EncMessages.named(e)?.takeIf { Enc.isKey(it) }
                if (h == null || K == null) { settle(id); continue }
                if (!privateHere(h)) continue // the group isn't known here yet: kept
                val k = when (val look = keyFor(h, K)) {
                    Look.Wait -> continue
                    Look.Wrong -> { settle(id); continue }
                    Look.None -> { asks += h to K; continue }
                    is Look.Key -> look.k
                }
                val o = try { EncMessages.openGroupMessage(k.secret, e) } catch (x: Exception) { settle(id); continue }
                val author = o.rumor.pubkey
                when (EncMessages.acceptGroupMessage(e, o.seal, o.rumor, h, e3.devices(author))) {
                    Verdict.NO -> { settle(id); continue }
                    Verdict.WAIT -> {
                        // §28: dropped only once the author's whole chain, read complete on this connection, lists the signer nowhere
                        val c = e3.chainOnly(author)
                        if (c != null && !c.gaps && e3.readHere(author) && c.lists.none { o.seal.pubkey in it.devices }) settle(id)
                        else wants += author
                        continue
                    }
                    Verdict.OK -> {}
                }
                settle(id)
                if (privileged(o.rumor)) continue
                e3.noteEncrypted(Enc3.groupConv(h), k.at)
                rumorOf[id] = o.rumor.id
                out += o.rumor.copy(sig = "") // the group's event (mentions from the rumor only, §18.8)
            }
        }
        if (wants.isNotEmpty()) e3.want(wants)
        for ((h, K) in asks) ask(h, K)
        if (out.isNotEmpty()) ports.opened(out)
        onChange?.invoke()
        return out
    }

    private fun settle(id: String) { msgs.remove(id); done.add(id) }

    /** K's envelopes in H asked by `#h` + `#d` (at most once a minute): "can't be read" only when envelopes came and none is for me. */
    private fun ask(h: String, K: String) {
        val now = ports.nowMs()
        val name = "$h/$K"
        synchronized(lock) {
            if (asked[name]?.let { now - it < 60_000 } == true) return
            asked[name] = now
        }
        ports.later {
            val got = ports.query(Filters.of(kinds = listOf(Enc.KEY), limit = MAX_PER_KEY, tags = mapOf("h" to listOf(h), "d" to listOf(K))), 5_000) ?: return@later
            val these = got.events.filter { it.kind == Enc.KEY && hOf(it) == h && Enc.one(it, "d") == K }.take(MAX_PER_KEY)
            if (these.isNotEmpty()) ports.addEvents(these)
            val sk = e3.secret
            val entry = these.any { env -> try { EncKeys.openGroupEnvelope(sk, env, h) != null } catch (x: Exception) { false } }
            synchronized(lock) {
                for (env in these) envs.getOrPut(name) { LinkedHashMap() }.putIfAbsent(env.id, env)
                if (got.complete && these.isNotEmpty() && !entry) noEntry.add(name) else noEntry.remove(name)
            }
            judge()
        }
    }

    // ---- writing (§5, §6, §16.1, §17.8, §17.14, §18.7, §23, §25) ----------------------------------------

    /**
     * A write of H on format 3, checked: its member list from a 39002 SIGNED BY THE GROUPS RELAY'S KEY (SPEC 19.94: its
     * NIP-11 `self`; unknown → held) read on this connection (P2.4: never a stale or empty cache, never the world's
     * list), me in it, then [Enc3.groupReady] (lists read whole, a member with no list after a second read skipped for
     * this message, a guest member no entry, forks hold, over 1000 devices refused). A 39002 by anyone else never
     * counts, newest or not; none by the relay → held.
     */
    suspend fun ready(h: String, pageMs: Long = 3_000): EncWrite {
        val relay = ports.relayKey()?.takeIf { Enc.isKey(it) } ?: return EncWrite.Hold(HoldReason.GROUP_UNKNOWN)
        val conn = ports.connection() ?: return EncWrite.Hold(HoldReason.GROUP_UNKNOWN)
        if (synchronized(lock) { membersOn[h] } != (conn to relay)) {
            val got = ports.query(Filters.of(kinds = listOf(39002), tags = mapOf("d" to listOf(h))), pageMs) ?: return EncWrite.Hold(HoldReason.GROUP_UNKNOWN)
            val lists = got.events.filter { it.kind == 39002 && Enc.one(it, "d") == h && it.pubkey == relay && Enc.valid(it) }
            if (lists.isNotEmpty()) ports.addEvents(lists)
            val newest = lists.maxWithOrNull(compareBy<Event> { it.createdAt }.thenByDescending { it.id })
            val members = newest?.let { n -> n.createdAt to Enc.values(n, "p").filter { Enc.isKey(it) }.toSet() }
            synchronized(lock) { membersOn[h] = conn to relay; if (members != null) fetched[h] = members else fetched.remove(h) }
        }
        // the read's list, or a newer one by the relay that came live since (a removal counts at once)
        val read = synchronized(lock) { fetched[h] } ?: return EncWrite.Hold(HoldReason.GROUP_UNKNOWN)
        val live = ports.relayMembers(h)
        val m = if (live != null && live.first > read.first) live.second else read.second
        if (e3.me !in m) return EncWrite.Hold(HoldReason.GROUP_UNKNOWN)
        return e3.groupReady(m, ports::isGuest)
    }

    /** The members' list is read again before the next write (a new connection does it too). */
    fun forgetMembers(h: String) { synchronized(lock) { membersOn.remove(h) } }

    /**
     * TPL (an event of H, its files already encrypted and uploaded) sealed for PLAN: the newest key whose entry set is
     * exactly the plan's device set (mine, or another member's whose entries add up, judged again now; never a service
     * key, under 30 days, not future-dated: [EncKeys.pickKey]); else a new key, kept with its entries BEFORE anything
     * goes, whose envelopes (signed by my account through the signer) go first. The rumor's author is me; the seal is
     * this DEVICE's; the outer 8572 carries `["p", x]` for each person MENTIONS names (§17.14).
     * Throws [EncException] (too long) before any key is made.
     */
    fun seal(h: String, tpl: EventTemplate, plan: EncPlan.Group, mentions: List<String> = emptyList()): GroupSealed = synchronized(lock) {
        val rumor = Enc.withId(Event("", e3.me, tpl.createdAt, tpl.kind, tpl.tags, tpl.content, ""))
        if (sh.belfry.core.enc.EncJson.rumorJson(rumor).toByteArray(Charsets.UTF_8).size > Enc.MAX_RUMOR) throw EncException("the message is too long to encrypt")
        val conv = Enc3.groupConv(h)
        val now = e3.nowS()
        var key = EncKeys.pickKey(e3.held(), conv, plan.set, now)?.let { e3.key(it.pub) }
        // re-read P1-3: another's key judged again right before use; adopted only while its entries add up
        if (key != null && key.claimed.isNotEmpty() && !key.envPending) {
            val next = judgeSet(key, envList(h, key.pub))
            if (next != key) e3.replaceKey(next)
            key = if (!next.mine && next.lists != plan.set) null else next
        }
        if (key == null) {
            val kp = EncKeys.newKey()
            val entries = plan.devices.toSortedSet().toList()
            val k = ConvKey(kp.pub, kp.secret, conv, e3.device.pubkey, now, mine = true, group = true, lists = plan.set, claimed = plan.set,
                entries = entries, envLists = EncKeys.listsHash(plan.lines))
            e3.putKey(k) // kept before anything is sent (its envelopes are made again from it until they are out)
            key = k
        }
        val before = if (key.envPending) EncKeys.groupEnvelopes(e3.secret, key.secret, h, key.entries, key.envLists).map { it.copy(createdAt = now) } else emptyList()
        val message = EncMessages.groupMessage(e3.secret, rumor, key.pub, h, mentions = mentions)
        e3.noteEncrypted(conv, key.at)
        GroupSealed(before, message, key.pub, rumor)
    }

    /** K's envelopes are out (the relay said OK to each): the next message under it goes alone. */
    fun envelopesOut(K: String) {
        val k = e3.key(K) ?: return
        if (k.envPending) e3.replaceKey(k.copy(entries = emptyList(), envLists = ""))
    }

    /** My 8572 OUTER (of RUMOR) is out: settled here, its rumor is the group's event already. */
    fun sent(outer: Event, rumor: Event) {
        synchronized(lock) { msgs.remove(outer.id); done.add(outer.id); rumorOf[outer.id] = rumor.id }
    }

    // ---- reading for the screens -----------------------------------------------------------------------

    /** The rumor an outer 8572 (a push's event) carried, when it was opened here. */
    fun rumorFor(outer: String): String? = synchronized(lock) { rumorOf[outer] }

    /** 8572s of H waiting: OPENING (key coming or lists coming) and UNREADABLE (no entry for this device). */
    fun waiting(h: String): Pair<Int, Int> = synchronized(lock) {
        var opening = 0
        var unreadable = 0
        for (e in msgs.values) {
            if (hOf(e) != h) continue
            val K = EncMessages.named(e) ?: continue
            if ("$h/$K" in noEntry) unreadable++ else opening++
        }
        opening to unreadable
    }

    /** H's keys held here, oldest first (service keys aside: the belfry's answers and mail). */
    private fun keysOf(h: String): List<ConvKey> = e3.keysOf(Enc3.groupConv(h)).filter { it.group && !it.service }.sortedWith(compareBy<ConvKey> { it.at }.thenBy { it.pub })

    /** "Can't check who can read this" (group-who-cant-check): H's newest key's entries seen don't add up to its set. */
    fun cantCheck(h: String): Boolean = synchronized(lock) { keysOf(h).lastOrNull()?.let { it.pub in cantAddUp } == true }

    /** The device keys with an entry in K (in H), from its envelope events here (my own unsent key: its entries). */
    fun entriesOf(h: String, K: String): List<String> {
        val k = e3.key(K)
        if (k != null && k.envPending) return k.entries
        return EncKeys.entryKeys(synchronized(lock) { envList(h, K) })
    }

    /** The account whose current or earlier list holds DEVICE among CANDIDATES (a service key and a `self` key: itself). */
    private fun ownerOf(device: String, candidates: Collection<String>): String? {
        for (a in candidates) {
            when (val d = e3.devices(a)) {
                Devices.Service -> if (device == a) return a
                is Devices.Listed -> if (d.chain.lists.any { device in it.devices || device in it.readers }) return a
                Devices.Unknown -> {}
            }
        }
        return null
    }

    /**
     * §17.10 / §18.4: "‹name› can read from here" / "can no longer read", from the difference between one key's entry
     * keys and the next's (mapped to people through the lists; CANDIDATES: the accounts to look among, the group's members
     * and those of before). Keys whose entries aren't known here are skipped.
     */
    fun keyChanges(h: String, candidates: Collection<String>): List<KeyChange> {
        val out = ArrayList<KeyChange>()
        var before: Set<String>? = null
        var beforeAt = 0L
        for (k in keysOf(h)) {
            val entries = entriesOf(h, k.pub)
            if (entries.isEmpty()) continue
            val people = entries.mapNotNull { ownerOf(it, candidates) }.toSet()
            val was = before
            if (was != null) {
                val added = (people - was).sorted()
                val removed = (was - people).sorted()
                if (added.isNotEmpty() || removed.isNotEmpty()) out += KeyChange(k.pub, k.at, added, removed, beforeAt)
            }
            before = people
            beforeAt = k.at
        }
        return out
    }

    /**
     * §17.2 (the web's store refusal): a signed event of the private group H dated at or after its first key, outside
     * §16.9's clear list, never counts. FIRST: group → its first key's date ([firstKeys]).
     */
    fun refuses(e: Event, first: Map<String, Long>): Boolean {
        if (first.isEmpty() || e.sig.isEmpty() || e.kind == Enc.KEY || e.kind == Enc.MSG) return false
        val h = hOf(e) ?: return false
        val f = first[h] ?: return false
        if (e.createdAt < f) return false
        return !EncRules.clearInGroup(e, ports.groupsKey())
    }

    /** Private groups (by their metadata) with a first key known here → its date. */
    fun firstKeys(groups: Collection<String>): Map<String, Long> {
        val out = HashMap<String, Long>()
        for (h in groups) if (ports.privateMeta(h)) e3.firstKeyAt(Enc3.groupConv(h))?.let { out[h] = it }
        return out
    }

    companion object {
        const val PRESS = "belfry/action"
        const val RESULT = "belfry/action-result"
        /** The ruling of d7acb17 (§26): label spaces that count from inside an 8572. */
        val THROUGH = setOf("belfry/pin", "belfry/inbox", "belfry/event-status", "belfry/proposal-status")
        const val MAX_PER_KEY = 50
    }
}
