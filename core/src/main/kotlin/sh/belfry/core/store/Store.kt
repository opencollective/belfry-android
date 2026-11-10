package sh.belfry.core.store

import sh.belfry.core.nostr.Event
import sh.belfry.core.outbox.OutboxItem

/**
 * Where everything the app knows is kept (SPEC 4-5): the event log, the outbox with its
 * attachments, and small key/value settings. The app implements it on SQLite; tests and the demo
 * use [MemoryStore]. All calls are blocking and must be safe from any thread.
 */
interface Store {
    /** Stores events not known yet (by id) and returns those that were new. */
    fun addEvents(events: List<Event>): List<Event>
    fun allEvents(): List<Event>
    fun deleteEvents(ids: List<String>)
    /** The newest created_at among events of these kinds, 0 when there is none. */
    fun latestCreatedAt(kinds: List<Int>): Long

    fun putOutbox(item: OutboxItem)
    fun dropOutbox(localId: String)
    /** Ordered by created_at. */
    fun outbox(): List<OutboxItem>

    fun putBlob(key: String, bytes: ByteArray)
    fun blob(key: String): ByteArray?
    fun dropBlob(key: String)

    fun get(key: String): String?
    fun set(key: String, value: String)
    fun remove(key: String)

    /** Sign-out: forget everything. */
    fun wipe()
}

class MemoryStore : Store {
    private val events = LinkedHashMap<String, Event>()
    private val outbox = LinkedHashMap<String, OutboxItem>()
    private val blobs = HashMap<String, ByteArray>()
    private val kv = HashMap<String, String>()

    @Synchronized
    override fun addEvents(events: List<Event>): List<Event> {
        val fresh = ArrayList<Event>()
        for (e in events) {
            if (e.id.isEmpty() || this.events.containsKey(e.id)) continue
            this.events[e.id] = e
            fresh.add(e)
        }
        return fresh
    }

    @Synchronized override fun allEvents(): List<Event> = events.values.toList()
    @Synchronized override fun deleteEvents(ids: List<String>) { ids.forEach { events.remove(it) } }
    @Synchronized override fun latestCreatedAt(kinds: List<Int>): Long =
        events.values.filter { it.kind in kinds }.maxOfOrNull { it.createdAt } ?: 0

    @Synchronized override fun putOutbox(item: OutboxItem) { outbox[item.localId] = item }
    @Synchronized override fun dropOutbox(localId: String) { outbox.remove(localId) }
    @Synchronized override fun outbox(): List<OutboxItem> = outbox.values.sortedBy { it.createdAt }

    @Synchronized override fun putBlob(key: String, bytes: ByteArray) { blobs[key] = bytes }
    @Synchronized override fun blob(key: String): ByteArray? = blobs[key]
    @Synchronized override fun dropBlob(key: String) { blobs.remove(key) }

    @Synchronized override fun get(key: String): String? = kv[key]
    @Synchronized override fun set(key: String, value: String) { kv[key] = value }
    @Synchronized override fun remove(key: String) { kv.remove(key) }

    @Synchronized
    override fun wipe() {
        events.clear(); outbox.clear(); blobs.clear(); kv.clear()
    }
}
