package sh.belfry.core.engine

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import sh.belfry.core.crypto.Hex
import sh.belfry.core.net.Community
import sh.belfry.core.nostr.arr
import sh.belfry.core.nostr.bool
import sh.belfry.core.nostr.int
import sh.belfry.core.nostr.obj
import sh.belfry.core.nostr.parseJsonOrNull
import sh.belfry.core.nostr.s
import sh.belfry.core.store.MemoryStore
import sh.belfry.core.store.Store

// Several accounts on one device (SPEC 19.24): two identities in one belfry, or accounts in
// different belfries. An account is one identity in one belfry: its id is "<pubkey>@<domain>".
// Everything it keeps is its own, named after its SLOT: the saved session (session key + bunker
// URL) and the push device key in the vault, and a store of its own (event log, outbox, drafts,
// settings). The account that existed before accounts did has the empty slot, whose names are
// the old ones: nothing had to be moved for it.

/** One account on this device, as the switcher shows it. No secret in here. */
data class AccountRecord(
    val id: String,
    /** Where its things are kept: "" (the first account: the names from before accounts) or "a2", "a3"… */
    val slot: String,
    val domain: String,
    val pubkey: String,
    /** The person's name, their address (`name@domain`), the community's name, their picture: as last seen. */
    val name: String = "",
    val address: String = "",
    val community: String = "",
    val picture: String = "",
    val demo: Boolean = false,
    val guest: Boolean = false,
) {
    /** The second line of the switcher's row. */
    val line: String get() = when {
        demo -> "Demo: nothing leaves this device"
        address.isNotEmpty() -> address
        guest && community.isNotEmpty() -> "Guest · $community"
        community.isNotEmpty() -> community
        else -> domain
    }

    fun toJson(): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(id)); put("slot", JsonPrimitive(slot))
        put("domain", JsonPrimitive(domain)); put("pubkey", JsonPrimitive(pubkey))
        put("name", JsonPrimitive(name)); put("address", JsonPrimitive(address))
        put("community", JsonPrimitive(community)); put("picture", JsonPrimitive(picture))
        if (demo) put("demo", JsonPrimitive(true))
        if (guest) put("guest", JsonPrimitive(true))
    }

    companion object {
        fun fromJson(o: JsonObject): AccountRecord? {
            val domain = o.s("domain")?.takeIf { it.isNotEmpty() && it.length <= 253 } ?: return null
            val pubkey = o.s("pubkey")?.takeIf(Hex::isKey) ?: return null
            val slot = o.s("slot")?.takeIf(Slots::isSlot) ?: return null
            return AccountRecord(
                id = AccountList.id(domain, pubkey), slot = slot, domain = domain, pubkey = pubkey,
                name = (o.s("name") ?: "").take(200), address = (o.s("address") ?: "").take(320),
                community = (o.s("community") ?: "").take(200), picture = (o.s("picture") ?: "").take(2000),
                demo = o["demo"].bool() ?: false, guest = o["guest"].bool() ?: false,
            )
        }
    }
}

/** The names of an account's things. */
object Slots {
    private val SLOT = Regex("^(a[0-9]{1,9})?$")
    fun isSlot(s: String): Boolean = SLOT.matches(s)

    /** A vault name for this slot: `session` for the first account, `session@a2` for another. */
    fun key(name: String, slot: String): String = if (slot.isEmpty()) name else "$name@$slot"

    /** The database file of this slot: `belfry.db` for the first account, `belfry-a2.db` for another. */
    fun database(slot: String): String = if (slot.isEmpty()) "belfry.db" else "belfry-$slot.db"
}

/** What a link to a belfry (an invitation, an event) meets on this device. */
sealed interface LinkAccounts {
    /** No account in that belfry: the link adds one (or cannot be opened). */
    data object None : LinkAccounts
    /** One account there (or the current one is the only one there): use it. */
    data class One(val account: AccountRecord) : LinkAccounts
    /** Several: ask which. The current one first when it is among them. */
    data class Several(val accounts: List<AccountRecord>) : LinkAccounts
}

/** The accounts on this device and which one is open. Immutable: every change makes a new list. */
data class AccountList(
    val current: String? = null,
    val accounts: List<AccountRecord> = emptyList(),
    /** The number of the next numbered slot (a numbered slot is never given twice). */
    val nextSlot: Int = 2,
) {
    val currentAccount: AccountRecord? get() = accounts.firstOrNull { it.id == current }
    operator fun get(id: String?): AccountRecord? = accounts.firstOrNull { it.id == id }

    /**
     * Signing in: the account of this identity in this belfry, made current. An account that is
     * here already keeps its slot (and so its store: signing in again loses nothing); a new one
     * gets a free slot, whose store the caller empties first. Third: was it new?
     */
    fun signedIn(domain: String, pubkey: String, demo: Boolean = false, guest: Boolean = false): Triple<AccountList, AccountRecord, Boolean> {
        val id = id(domain, pubkey)
        val had = this[id]
        if (had != null) {
            val rec = had.copy(guest = guest)
            return Triple(copy(current = id, accounts = accounts.map { if (it.id == id) rec else it }), rec, false)
        }
        val used = accounts.map { it.slot }.toSet()
        // the first slot when it is free (a device with one account keeps the names it always had)
        if ("" !in used) {
            val rec = AccountRecord(id, "", domain.lowercase(), pubkey, demo = demo, guest = guest)
            return Triple(copy(current = id, accounts = accounts + rec), rec, true)
        }
        var n = nextSlot.coerceAtLeast(2)
        while ("a$n" in used) n++
        val rec = AccountRecord(id, "a$n", domain.lowercase(), pubkey, demo = demo, guest = guest)
        return Triple(copy(current = id, accounts = accounts + rec, nextSlot = n + 1), rec, true)
    }

    /** Opens another account. Unknown id: unchanged. */
    fun select(id: String): AccountList = if (this[id] != null) copy(current = id) else this

    /**
     * Signing out of one account: it leaves the list. When it was the current one, the next is
     * the one after it in the list, else the one before (null when it was the last).
     */
    fun without(id: String): AccountList {
        val i = accounts.indexOfFirst { it.id == id }
        if (i < 0) return this
        val rest = accounts.filterIndexed { j, _ -> j != i }
        val next = if (current != id) current else rest.getOrNull(i)?.id ?: rest.lastOrNull()?.id
        return copy(current = next, accounts = rest)
    }

    /** What the switcher shows about an account, as its session last saw it. */
    fun described(id: String, name: String, address: String, community: String, picture: String, guest: Boolean): AccountList =
        copy(accounts = accounts.map { if (it.id == id) it.copy(name = name, address = address, community = community, picture = picture, guest = guest) else it })

    /** The accounts in this belfry (never the demo), the current one first. */
    fun onDomain(domain: String): List<AccountRecord> {
        val d = domain.lowercase()
        return accounts.filter { !it.demo && it.domain == d }.sortedBy { if (it.id == current) 0 else 1 }
    }

    /** Which account a link to this belfry uses. */
    fun forLink(domain: String): LinkAccounts {
        val on = onDomain(domain)
        return when (on.size) {
            0 -> LinkAccounts.None
            1 -> LinkAccounts.One(on[0])
            else -> LinkAccounts.Several(on)
        }
    }

    /**
     * Who registers this device for push. A belfry keeps one owner per device token, so in each
     * belfry one account only: the current one when it is there, else the first in the list.
     * The current account last, so its registration is the one that stays.
     */
    fun pushOwners(): List<AccountRecord> {
        val out = LinkedHashMap<String, AccountRecord>()
        for (a in accounts) if (!a.demo && a.domain !in out) out[a.domain] = a
        currentAccount?.let { if (!it.demo) out[it.domain] = it }
        return out.values.sortedBy { if (it.id == current) 1 else 0 }
    }

    fun toJsonString(): String = buildJsonObject {
        put("v", JsonPrimitive(1))
        if (current != null) put("current", JsonPrimitive(current))
        put("next", JsonPrimitive(nextSlot))
        put("accounts", JsonArray(accounts.map { it.toJson() }))
    }.toString()

    companion object {
        val EMPTY = AccountList()

        fun id(domain: String, pubkey: String): String = pubkey.lowercase() + "@" + domain.lowercase()

        /** Null when this is not a list (a damaged value). Damaged or doubled entries are left out. */
        fun fromJsonString(text: String): AccountList? {
            val o = parseJsonOrNull(text) as? JsonObject ?: return null
            val arr = o["accounts"].arr() ?: return null
            val seen = HashSet<String>()
            val slots = HashSet<String>()
            val list = arr.mapNotNull { it.obj()?.let(AccountRecord::fromJson) }.filter { seen.add(it.id) && slots.add(it.slot) }
            val current = o.s("current")?.takeIf { id -> list.any { it.id == id } } ?: list.firstOrNull()?.id
            val top = list.mapNotNull { it.slot.removePrefix("a").toIntOrNull() }.maxOrNull() ?: 1
            return AccountList(current, list, maxOf(o["next"].int() ?: 2, top + 1, 2))
        }

        /**
         * The list of a device that had one account before accounts existed: that account, in the
         * empty slot (its saved session, store and push key keep their names: nothing moves, the
         * person stays signed in, the outbox keeps what it held).
         */
        fun migrated(saved: SavedSession): AccountList {
            val id = id(saved.community.domain, saved.pubkey)
            val rec = AccountRecord(
                id, "", saved.community.domain.lowercase(), saved.pubkey, community = saved.community.name,
                demo = saved.demo, guest = saved.guest != null,
            )
            return AccountList(id, listOf(rec), 2)
        }
    }
}

/** One store per account. [open] gives the same store for the same slot; [delete] forgets it for good. */
interface StoreProvider {
    fun open(slot: String): Store
    fun delete(slot: String)
}

/** Stores in memory, one per slot (tests, the demo). */
class MemoryStores(first: Store = MemoryStore()) : StoreProvider {
    private val map = HashMap<String, Store>().apply { put("", first) }
    @Synchronized override fun open(slot: String): Store = map.getOrPut(slot) { MemoryStore() }
    @Synchronized override fun delete(slot: String) { map.remove(slot)?.wipe() }
}
