package sh.belfry.core.engine

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import sh.belfry.core.crypto.Hex
import sh.belfry.core.net.Relay
import sh.belfry.core.net.SessionInfo
import sh.belfry.core.nostr.Event
import sh.belfry.core.nostr.EventTemplate
import sh.belfry.core.nostr.parseJsonOrNull
import sh.belfry.core.sealed.Query
import sh.belfry.core.sealed.SealedEngine
import sh.belfry.core.sealed.SealedPorts
import sh.belfry.core.sealed.SessionRow

/**
 * Where an account's letterbox secrets are kept (sealed.md): the Keystore-encrypted vault, per
 * account slot, never the SQLite store, never logged. The record names its account and is checked
 * on load; it is removed at sign-out. A write after [close] (a task asleep before a rotation) is
 * dropped.
 */
class SealedKeyStore(private val vault: Vault, private val name: String, private val account: String) {
    @Volatile private var closed = false

    /** Never saved or loaded before the account key is known (an empty owner lost a whole chain on iOS). */
    private val known: Boolean get() = account.isNotEmpty() && Hex.isKey(account)

    fun load(): Map<String, String> {
        if (!known) return emptyMap()
        val o = parseJsonOrNull(vault.get(name) ?: return emptyMap()) as? JsonObject ?: return emptyMap()
        if ((o["account"] as? JsonPrimitive)?.content != account) return emptyMap() // another account's keys: never used
        val keys = o["keys"] as? JsonObject ?: return emptyMap()
        return keys.mapNotNull { (lb, s) -> (s as? JsonPrimitive)?.content?.takeIf { Hex.isKey(lb) && Hex.isKey(it) }?.let { lb to it } }.toMap()
    }

    /** True only when written and read back the same (a commit; the review's 15). */
    fun save(keys: Map<String, String>): Boolean {
        if (closed || !known) return false
        val v = buildJsonObject {
            put("account", JsonPrimitive(account))
            put("keys", buildJsonObject { keys.forEach { (k, v) -> put(k, JsonPrimitive(v)) } })
        }.toString()
        return vault.setChecked(name, v)
    }

    /** Small records of the account's sealed side (not secrets), kept beside the keys. */
    fun loadNote(note: String): String? {
        if (!known) return null
        val o = parseJsonOrNull(vault.get("$name:notes") ?: return null) as? JsonObject ?: return null
        return (o[note] as? JsonPrimitive)?.content
    }

    fun saveNote(note: String, value: String) {
        if (closed || !known) return
        val o = (parseJsonOrNull(vault.get("$name:notes") ?: "") as? JsonObject) ?: JsonObject(emptyMap())
        vault.set("$name:notes", JsonObject(o + (note to JsonPrimitive(value))).toString())
    }

    /** Sign-out: forget them, and refuse any later write. */
    fun wipe() {
        closed = true
        vault.remove(name)
        vault.remove("$name:notes")
    }

    companion object {
        const val V_KEYS = "sealed-keys"
    }
}

/** The relay query of a [Query], as a NIP-01 filter (never `#lb`: the relay ignores it). */
internal fun Query.toFilter(): JsonObject = buildJsonObject {
    put("kinds", JsonArray(kinds.map { JsonPrimitive(it) }))
    authors?.let { put("authors", JsonArray(it.map { a -> JsonPrimitive(a) })) }
    p?.let { put("#p", JsonArray(it.map { a -> JsonPrimitive(a) })) }
    d?.let { put("#d", JsonArray(it.map { a -> JsonPrimitive(a) })) }
}

/** A session-list row as the engine reads it (last seen = last_activity, else created_at). */
internal fun SessionInfo.toRow(): SessionRow = SessionRow(
    clientPubkey = clientPubkey, chat = chat,
    lastSeen = RemoteTime.parse(lastActivity) ?: RemoteTime.parse(createdAt),
    device = device, applicationName = applicationName, id = bunkerPubkey,
    createdAt = RemoteTime.parse(createdAt),
)

/**
 * Reading everything from 0 on the community relay, which caps `limit` at 300 (rnostr max_limit):
 * pages of 300 going back with `until`, stopping only on a page that brings nothing new; a short
 * page never means "done" (the lead, from iOS's third review). Complete only if every page was.
 */
internal object Paging {
    const val PAGE = 300

    fun all(ask: suspend (JsonObject) -> Pair<List<Event>, Boolean>?): suspend (JsonObject) -> SealedEngine.Answer = { base ->
        val seen = LinkedHashMap<String, Event>()
        var complete = true
        var until: Long? = null
        var answered = false
        var exhausted = true
        for (page in 0 until 200) {
            val f = JsonObject(base + mapOf("limit" to JsonPrimitive(PAGE)) + (until?.let { mapOf("until" to JsonPrimitive(it)) } ?: emptyMap()))
            val a = ask(f)
            if (a == null) { complete = false; break }
            answered = true
            complete = complete && a.second
            val fresh = a.first.filter { it.id !in seen }
            if (fresh.isEmpty()) { exhausted = false; break }
            fresh.forEach { seen[it.id] = it }
            until = a.first.minOf { it.createdAt } // inclusive: the same second may hold more
        }
        // 200 pages without an empty one: not known to be complete (the review's 16)
        SealedEngine.Answer(seen.values.toList(), complete && answered && !exhausted)
    }
}

internal object RemoteTime {
    fun parse(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        return try { java.time.OffsetDateTime.parse(s).toEpochSecond() } catch (e: Exception) {
            try { java.time.Instant.parse(s).epochSecond } catch (e2: Exception) { null }
        }
    }
}

/** The engine's ports on a real session: the community relay, the signer, /api/me/sessions, the vault. */
internal class SessionSealedPorts(
    private val relay: () -> Relay?,
    private val signAccount: suspend (EventTemplate) -> Event,
    private val listSessions: suspend () -> List<SessionInfo>,
    private val revokeSession: suspend (String) -> Unit,
    private val keys: SealedKeyStore,
    private val state: Pair<() -> String?, (String) -> Unit> = { null } to { _ -> },
    /** belfry.json `sealed.self_join`, fetched now (null: the belfry did not answer). */
    private val selfJoinNow: suspend () -> Boolean? = { false },
) : SealedPorts {
    override suspend fun selfJoin(): Boolean? = try { selfJoinNow() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
    override fun loadState(): String? = state.first()
    override fun saveState(json: String) = state.second(json)
    override suspend fun query(q: Query): SealedEngine.Answer {
        val r = relay() ?: return SealedEngine.Answer(emptyList(), false)
        return Paging.all { filter -> r.queryKnown(listOf(filter)) }(q.toFilter())
    }

    override suspend fun publish(e: Event): Boolean {
        val r = relay() ?: return false
        return try { r.publish(e); true } catch (x: Exception) { false }
    }

    override suspend fun signAsAccount(t: EventTemplate): Event = signAccount(t)

    override suspend fun sessions(): List<SessionRow>? = try { listSessions().map { it.toRow() } } catch (e: Exception) { null }

    /** Revokes the session whose client key is CLIENT_PUBKEY (the belfry names sessions by their bunker key). */
    override suspend fun revoke(clientPubkey: String) {
        // already gone: nothing to revoke (the device can still be removed by hand; the review's 12)
        val row = listSessions().firstOrNull { it.clientPubkey == clientPubkey } ?: return
        revokeSession(row.bunkerPubkey)
    }

    override fun loadNote(name: String): String? = keys.loadNote(name)
    override fun saveNote(name: String, value: String) = keys.saveNote(name, value)
    override fun loadKeys(): Map<String, String> = keys.load()
    override fun saveKeys(keys: Map<String, String>): Boolean = this.keys.save(keys)
}
