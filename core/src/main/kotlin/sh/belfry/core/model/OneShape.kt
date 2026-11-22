package sh.belfry.core.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import sh.belfry.core.nostr.Event
import sh.belfry.core.nostr.obj
import sh.belfry.core.nostr.parseJsonOrNull

// One shape and Navigation v2 (collective-belfry docs/one-shape.md; canvas v171-172; the rules of
// vectors/one-shape.json, which belfry-ios copies). Pure: no clock, no I/O.

object OneShape {
    const val RECENT_DAYS = 30L
    const val PINS_D = "belfry:pins"
    const val MAIL_DRAFT = "mail-draft"
    const val MAX_RECIPIENTS = 20
    /** The main channel: first in every Town list. */
    const val MAIN = "general"

    /** Pins first (everyone's, then mine), then the recent channels. */
    data class TownList(val pinned: List<String>, val recent: List<String>) {
        val list: List<String> get() = pinned + recent
    }

    /**
     * The Town list (vectors/one-shape.json "recent"). Recent = a known, unpinned channel where in the
     * last 30 days I posted (9 / 1111), reacted (7) or someone else's 9 / 1111 tagged me with p;
     * ordered by the channel's newest 9 / 1111 / 7 by anyone, newest first, ties by id.
     */
    fun townList(me: String, now: Long, known: Set<String>, pinsAll: List<String>, pinsMine: List<String>, events: List<Event>): TownList {
        val pinned = LinkedHashSet<String>()
        // the main channel is always first, pinned or not, and can't be unpinned (docs/one-shape.md 1)
        if (MAIN in known) pinned.add(MAIN)
        for (id in pinsAll + pinsMine) if (id in known) pinned.add(id)
        val since = now - RECENT_DAYS * 86_400
        val involved = HashSet<String>()
        val newest = HashMap<String, Long>()
        for (e in events) {
            // 9007: creating a channel counts as posting in it (docs/one-shape.md 1)
            if (e.kind != 9 && e.kind != 1111 && e.kind != 7 && e.kind != 9007) continue
            val h = e.t("h") ?: continue
            if (h !in known) continue
            if (e.createdAt > (newest[h] ?: Long.MIN_VALUE)) newest[h] = e.createdAt
            if (e.createdAt <= since) continue
            val mine = e.pubkey == me
            if (mine || (e.kind != 7 && e.kind != 9007 && e.tags.any { it.size >= 2 && it[0] == "p" && it[1] == me })) involved.add(h)
        }
        val recent = involved.filter { it !in pinned }
            .sortedWith(compareByDescending<String> { newest[it] ?: 0 }.thenBy { it })
        return TownList(pinned.toList(), recent)
    }

    /** `{"v":1,"c":[ids]}` (belfry:pins, decrypted) or channel-order's `pinned`: strings in [known], first of each. */
    fun parsePins(content: String?, known: Set<String>, key: String = "c"): List<String> {
        val o = parseJsonOrNull(content ?: return emptyList()).obj() ?: return emptyList()
        val arr = o[key] as? JsonArray ?: return emptyList()
        val out = LinkedHashSet<String>()
        for (x in arr) {
            val s = (x as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
            if (s in known) out.add(s)
        }
        return out.toList()
    }

    /** My pins after pinning or unpinning [channel]: added at the end, or removed. */
    fun togglePin(current: List<String>, channel: String): List<String> =
        if (channel in current) current.filter { it != channel } else current + channel

    fun pinsContent(list: List<String>): String =
        buildJsonObject { put("v", JsonPrimitive(1)); put("c", JsonArray(list.distinct().take(1000).map { JsonPrimitive(it) })) }.toString()

    // ---- the header's icon row ------------------------------------------------------------------

    val DROP_ORDER = listOf("owner", "files", "todos", "photos", "comments", "events", "pinned")

    /** Which items stay (their order kept): while over [budget], drop by [DROP_ORDER]; lead and people stay. */
    fun <T> fitRow(items: List<T>, key: (T) -> String, width: (T) -> Int, gap: Int, budget: Int): List<T> {
        val kept = items.toMutableList()
        fun total() = kept.sumOf(width) + gap * (kept.size - 1).coerceAtLeast(0)
        for (k in DROP_ORDER) {
            if (total() <= budget) break
            kept.removeAll { key(it) == k }
        }
        return kept
    }

    // ---- Reply all ------------------------------------------------------------------------------

    data class Recipients(val rcptTo: List<String>, val to: String?, val cc: List<String>)

    /** Is [address] at the belfry's own [domain] (or one of its hosts)? */
    fun isOwnDomain(address: String, domain: String): Boolean {
        val d = address.substringAfterLast('@', "").lowercase()
        val own = domain.lowercase().trim().trimEnd('.')
        return own.isNotEmpty() && (d == own || d.endsWith(".$own"))
    }

    /** Sender, then the card's `to` and `cc` tags; trimmed, lower case, not ours, first of each, at most 20. */
    fun replyAll(mailFrom: String?, tags: List<List<String>>, domain: String): Recipients {
        val raw = ArrayList<String>()
        mailFrom?.let(raw::add)
        for (t in tags) if (t.size >= 2 && t[0] == "to") raw.add(t[1])
        for (t in tags) if (t.size >= 2 && t[0] == "cc") raw.add(t[1])
        val out = LinkedHashSet<String>()
        for (a in raw) {
            val x = a.trim().lowercase()
            if (!x.contains('@') || isOwnDomain(x, domain)) continue
            out.add(x)
            if (out.size >= MAX_RECIPIENTS) break
        }
        val list = out.toList()
        return Recipients(list, list.firstOrNull(), list.drop(1))
    }

    // ---- an e-mail thread's state ---------------------------------------------------------------

    data class StatusLabel(val id: String, val at: Long, val l: String)

    /** open | answered | closed: the newest label (none = open); a received mail newer than the newest answered / closed label reopens it. */
    fun threadState(received: List<Long>, labels: List<StatusLabel>, sent: List<Long> = emptyList()): String {
        // a sent reply card counts as an `answered` label at its time (threads answered before 0.8.0 have none)
        val all = labels + sent.mapIndexed { i, at -> StatusLabel("sent:$i", at, "answered") }
        val sorted = all.filter { it.l == "open" || it.l == "answered" || it.l == "closed" }.sortedWith(compareBy<StatusLabel> { it.at }.thenBy { it.id })
        val base = sorted.lastOrNull()?.l ?: "open"
        val settled = sorted.lastOrNull { it.l == "answered" || it.l == "closed" }?.at ?: return base
        val newestIn = received.maxOrNull() ?: return base
        return if (newestIn > settled) "open" else base
    }

    // ---- internal drafts ------------------------------------------------------------------------

    fun isMailDraft(e: Event): Boolean = e.kind == 1111 && e.tags.any { it.size >= 2 && it[0] == MAIL_DRAFT && it[1] == "1" }

    /** The draft ids still shown: not named by a sent card's `draft`, not deleted by their author. */
    fun visibleDrafts(drafts: List<Pair<String, String>>, sentDraftIds: Set<String>, deletions: List<Pair<String, String>>): List<String> {
        val deleted = deletions.toSet()
        return drafts.filter { (id, author) -> id !in sentDraftIds && (author to id) !in deleted }.map { it.first }
    }

    // ---- due date, DM replies -------------------------------------------------------------------

    fun due(tags: List<List<String>>): Long? =
        tags.firstOrNull { it.size >= 2 && it[0] == "due" }?.get(1)?.toLongOrNull()?.takeIf { it > 0 }

    const val MAX_DESCRIPTION = 2000

    /** A to-do's own description: `["description", Markdown]`, trimmed, at most 2000 characters; blank = none. */
    fun description(tags: List<List<String>>): String? =
        tags.firstOrNull { it.size >= 2 && it[0] == "description" }?.get(1)?.trim()?.take(MAX_DESCRIPTION)?.takeIf { it.isNotEmpty() }

    /** What a to-do shows as its description: its own, else the source message's text when that says more than the title. */
    fun todoDescription(own: String?, title: String, sourceText: String?): String? {
        own?.trim()?.takeIf { it.isNotEmpty() }?.let { return it.take(MAX_DESCRIPTION) }
        val text = sourceText?.trim() ?: return null
        return if (text.isEmpty() || text == title.trim()) null else text
    }

    fun dmReplyTag(root: String): List<String> = listOf("e", root, "", "root")

    /** The root a DM reply names (["e", id, relay, "root"]), else null. */
    fun dmRoot(tags: List<List<String>>): String? =
        tags.firstOrNull { it.size >= 4 && it[0] == "e" && it[3] == "root" }?.get(1)?.takeIf { it.isNotEmpty() }

    /** channel-order content with `pinned` set, every other key kept. */
    fun withEveryonePins(content: String?, pinned: List<String>): String {
        val o = parseJsonOrNull(content ?: "{}").obj() ?: JsonObject(emptyMap())
        return JsonObject(o + ("pinned" to JsonArray(pinned.distinct().map { JsonPrimitive(it) }))).toString()
    }
}
