package sh.belfry.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.serialization.json.JsonObject
import sh.belfry.core.nostr.Event
import sh.belfry.core.nostr.parseJsonOrNull
import sh.belfry.core.outbox.OutboxItem
import sh.belfry.core.store.Store

/**
 * The [Store] on plain SQLite: the event log, the outbox with its attachments, and settings.
 * Same shape as the web chat's IndexedDB. Pass `name = null` for an in-memory database (tests).
 * Calls block: use them off the main thread.
 */
class SqliteStore(context: Context, name: String? = "belfry.db") : Store {
    private val helper = object : SQLiteOpenHelper(context.applicationContext, name, null, 1) {
        // Write-ahead log: a batch of events from the relay is one cheap append, and reading (the
        // screens' small look-ups) is not held up by it.
        override fun onConfigure(db: SQLiteDatabase) {
            try { db.enableWriteAheadLogging() } catch (e: Exception) { /* an in-memory or read-only database: the default journal */ }
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE events (id TEXT PRIMARY KEY NOT NULL, kind INTEGER NOT NULL, created_at INTEGER NOT NULL, json TEXT NOT NULL)")
            db.execSQL("CREATE INDEX events_kind ON events (kind, created_at)")
            db.execSQL("CREATE TABLE outbox (local_id TEXT PRIMARY KEY NOT NULL, created_at INTEGER NOT NULL, json TEXT NOT NULL)")
            db.execSQL("CREATE TABLE blobs (key TEXT PRIMARY KEY NOT NULL, bytes BLOB NOT NULL)")
            db.execSQL("CREATE TABLE kv (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // version 1 is the only one
        }
    }

    private val db: SQLiteDatabase get() = helper.writableDatabase

    @Synchronized
    override fun addEvents(events: List<Event>): List<Event> {
        if (events.isEmpty()) return emptyList()
        val fresh = ArrayList<Event>()
        val d = db
        d.beginTransaction()
        try {
            val st = d.compileStatement("INSERT OR IGNORE INTO events (id, kind, created_at, json) VALUES (?, ?, ?, ?)")
            for (e in events) {
                if (e.id.isEmpty()) continue
                st.clearBindings()
                st.bindString(1, e.id)
                st.bindLong(2, e.kind.toLong())
                st.bindLong(3, e.createdAt)
                st.bindString(4, e.toJsonString())
                // -1 when the id was there already
                if (st.executeInsert() != -1L) fresh.add(e)
            }
            st.close()
            d.setTransactionSuccessful()
        } finally {
            d.endTransaction()
        }
        return fresh
    }

    @Synchronized
    override fun allEvents(): List<Event> {
        val out = ArrayList<Event>()
        db.rawQuery("SELECT json FROM events ORDER BY created_at, id", null).use { c ->
            while (c.moveToNext()) Event.fromJsonString(c.getString(0))?.let(out::add)
        }
        return out
    }

    @Synchronized
    override fun deleteEvents(ids: List<String>) {
        if (ids.isEmpty()) return
        val d = db
        d.beginTransaction()
        try {
            for (id in ids) d.delete("events", "id = ?", arrayOf(id))
            d.setTransactionSuccessful()
        } finally {
            d.endTransaction()
        }
    }

    @Synchronized
    override fun latestCreatedAt(kinds: List<Int>): Long {
        if (kinds.isEmpty()) return 0
        // kinds are integers from our own code: safe to inline
        val list = kinds.joinToString(",") { it.toString() }
        db.rawQuery("SELECT MAX(created_at) FROM events WHERE kind IN ($list)", null).use { c ->
            return if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0
        }
    }

    @Synchronized
    override fun putOutbox(item: OutboxItem) {
        val v = ContentValues().apply {
            put("local_id", item.localId)
            put("created_at", item.createdAt)
            put("json", item.toJson().toString())
        }
        db.insertWithOnConflict("outbox", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    override fun dropOutbox(localId: String) {
        db.delete("outbox", "local_id = ?", arrayOf(localId))
    }

    @Synchronized
    override fun outbox(): List<OutboxItem> {
        val out = ArrayList<OutboxItem>()
        db.rawQuery("SELECT json FROM outbox ORDER BY created_at, local_id", null).use { c ->
            while (c.moveToNext()) (parseJsonOrNull(c.getString(0)) as? JsonObject)?.let { out.add(OutboxItem.fromJson(it)) }
        }
        return out
    }

    @Synchronized
    override fun putBlob(key: String, bytes: ByteArray) {
        val v = ContentValues().apply {
            put("key", key)
            put("bytes", bytes)
        }
        db.insertWithOnConflict("blobs", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    override fun blob(key: String): ByteArray? {
        db.rawQuery("SELECT bytes FROM blobs WHERE key = ?", arrayOf(key)).use { c ->
            return if (c.moveToFirst()) c.getBlob(0) else null
        }
    }

    @Synchronized
    override fun dropBlob(key: String) {
        db.delete("blobs", "key = ?", arrayOf(key))
    }

    @Synchronized
    override fun get(key: String): String? {
        db.rawQuery("SELECT value FROM kv WHERE key = ?", arrayOf(key)).use { c ->
            return if (c.moveToFirst()) c.getString(0) else null
        }
    }

    @Synchronized
    override fun set(key: String, value: String) {
        val v = ContentValues().apply {
            put("key", key)
            put("value", value)
        }
        db.insertWithOnConflict("kv", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    override fun remove(key: String) {
        db.delete("kv", "key = ?", arrayOf(key))
    }

    @Synchronized
    override fun wipe() {
        val d = db
        d.beginTransaction()
        try {
            for (t in listOf("events", "outbox", "blobs", "kv")) d.delete(t, null, null)
            d.setTransactionSuccessful()
        } finally {
            d.endTransaction()
        }
    }

    fun close() = helper.close()
}

/** One SQLite database per account ([sh.belfry.core.engine.Slots.database]): `belfry.db` for the first, `belfry-a2.db`… */
class SqliteStores(context: Context) : sh.belfry.core.engine.StoreProvider {
    private val context = context.applicationContext
    private val open = HashMap<String, SqliteStore>()

    @Synchronized
    override fun open(slot: String): Store = open.getOrPut(slot) { SqliteStore(context, sh.belfry.core.engine.Slots.database(slot)) }

    /** Sign-out of that account: its database file goes. */
    @Synchronized
    override fun delete(slot: String) {
        if (!sh.belfry.core.engine.Slots.isSlot(slot)) return
        try { open.remove(slot)?.close() } catch (e: Exception) { }
        try { context.deleteDatabase(sh.belfry.core.engine.Slots.database(slot)) } catch (e: Exception) { }
    }
}
