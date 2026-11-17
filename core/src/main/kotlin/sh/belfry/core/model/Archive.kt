package sh.belfry.core.model

import sh.belfry.core.nostr.Event

// Deleted and archived groups (SPEC 19.30; the belfry's contract of 2026-11-16). "Group" here is
// anything with a NIP-29 group id that people open from the channel list or a card: channels and
// groups (ids `pod-…`). Events and proposals keep their own cancel / status mechanisms.
//
// Deleted: a kind 9008 for it is known, or the groups relay was asked for its metadata and
// answered that it has none (`WorldContext.groupsAbsent`), and nothing newer about it arrived
// since. Until the relay answered, a group nothing is known about is "unknown": never "deleted"
// on a guess.
//
// Archived: `["archived","1"]` on the group's latest 9002 that says anything about it;
// `["archived","0"]` unarchives. In no list, readable, nobody writes in it.

enum class GroupState { UNKNOWN, OPEN, ARCHIVED, DELETED }

fun World.groupState(id: String): GroupState {
    if (id in groupIndex.deleted) return GroupState.DELETED
    val g = group(id) ?: return GroupState.UNKNOWN
    return if (g.archived) GroupState.ARCHIVED else GroupState.OPEN
}

fun World.isDeletedGroup(id: String): Boolean = id in groupIndex.deleted
fun World.isArchived(id: String): Boolean = group(id)?.archived == true

/** Nothing is written in it: archived or deleted. */
fun World.isLocked(id: String): Boolean = isDeletedGroup(id) || isArchived(id)

/** The ids nothing is written in (the outbox's last check, [GroupLock]). */
val World.lockedGroups: Set<String> get() = groupIndex.deleted + groupIndex.all.filter { it.archived }.map { it.id }

/** Every archived group, by name. */
fun World.archivedGroups(): List<Group> = groupIndex.all.filter { it.archived }.sortedBy { it.name.lowercase() }

/**
 * The archived groups I can open (the "Archived" row at the bottom of the channel list): the ones
 * I am in or run, and for a member every archived channel.
 */
fun World.myArchived(): List<Group> {
    val guest = isGuest(me)
    return archivedGroups().filter { g -> me in g.members || canAdmin(g) || (g.type == ChannelType.CHANNEL && !guest) }
}

/**
 * May I archive / unarchive G: whoever may edit it (its admins, admins by role, the belfry's
 * admins); only channels and groups, never #general.
 */
fun World.mayArchive(g: Group?): Boolean {
    if (g == null || g.id == "general" || g.pending) return false
    if (g.type != ChannelType.CHANNEL && g.type != ChannelType.POD) return false
    return canAdmin(g)
}

/**
 * The ids to ask the groups relay about (does it still have them?): the channels and groups this
 * device knows, those already said absent, and the groups named by cards it knows nothing about.
 * Never one I am still creating.
 */
fun World.presenceCandidates(): List<String> {
    val out = ArrayList<String>()
    val seen = hashSetOf("general")
    for (id in ctx.groupsAbsent.keys.sorted()) if (presenceAskable(id) && seen.add(id)) out.add(id)
    for (e in all) {
        if (e.kind != 9) continue
        val p = World.podOf(e) ?: continue
        if (p in seen || isPending(e.id) || !presenceAskable(p)) continue
        seen.add(p); out.add(p)
    }
    return out
}

/**
 * May the groups relay be asked whether ID is still there (web groupstate.ts)? Only channels and
 * groups (never events `ev-` / `ws-` or proposals `prop-`), only when this device holds none of
 * its metadata (39000 / 9007 / 9002, mine queued or sent included: such a group is live), and
 * never while the outbox holds anything for it. NOW_S (with CREATED_HERE: when this device made
 * it) keeps a group made here in the last [Presence.FRESH_S] seconds out too.
 */
fun World.presenceAskable(id: String, nowS: Long = 0, createdHere: Map<String, Long> = emptyMap()): Boolean {
    if (id == "general" || !Presence.mayBeAbsent(id)) return false
    if (id in groupIndex.newestMeta) return false
    if (outboxItems.any { o -> o.tags.any { it.size >= 2 && (it[0] == "h" || it[0] == "pod") && it[1] == id } }) return false
    val made = createdHere[id]
    if (made != null && nowS - made < Presence.FRESH_S) return false
    return true
}

object Presence {
    /** A group made on this device is never asked about for this long (seconds). */
    const val FRESH_S = 120L

    /** Only channels and groups can be deleted by absence; events and proposals only by a 9008. */
    fun mayBeAbsent(id: String): Boolean {
        val t = World.typeOfId(id)
        return t == ChannelType.CHANNEL || t == ChannelType.POD
    }
}

/**
 * What `groupsAbsent` becomes after the relay answered a question about ASKED with ANSWER (its
 * kind 39000 events). Null when the answer can't be trusted: it must name #general, which is
 * always asked along and always there.
 */
fun World.presenceAfter(asked: List<String>, answer: List<Event>, was: Map<String, Long>? = null): Map<String, Long>? {
    val meta = answer.filter { it.kind == 39000 }.mapNotNull { it.tag("d") }.toSet()
    if ("general" !in meta) return null
    // its 39000, or any 9007 / 9002 for it: there (a 9008 alone is not, and deletes it by itself)
    val there = meta + answer.filter { it.kind == 9007 || it.kind == 9002 }.mapNotNull { it.tag("h") }
    val out = HashMap(was ?: ctx.groupsAbsent)
    for (id in asked) {
        if (id == "general") continue
        if (id in there || !presenceAskable(id)) out.remove(id) else out[id] = 0L
    }
    return out
}

object GroupPresence {
    const val CHUNK = 100

    /** One question per chunk: #general (the proof the relay answered) and up to 100 ids. */
    fun questions(ids: List<String>): List<List<String>> =
        ids.filter { it != "general" }.chunked(CHUNK).map { listOf("general") + it }
}

/** The last check before anything is queued for a group nothing is written in. */
object GroupLock {
    fun refuses(d: Draft, locked: Set<String>): Boolean {
        if (locked.isEmpty()) return false
        val h = d.tags.firstOrNull { it.size > 1 && it[0] == "h" }?.get(1) ?: return false
        if (h !in locked) return false
        // unarchiving is the one thing an archived group takes
        if (d.kind == 9002 && d.tags.any { it == listOf("archived", "0") }) return false
        return true
    }

    /**
     * The name on a group's card when the group itself is gone: taken from the card's own text
     * ("⬡ New pod: Canal · about" / "⬡ New group: …"), else null ("A group").
     */
    fun cardName(text: String): String? {
        val m = Regex("^⬡ New (pod|group): ").find(text) ?: return null
        var rest = text.substring(m.range.last + 1)
        val nl = rest.indexOfFirst { it == '\n' || it == '\r' }
        if (nl >= 0) rest = rest.substring(0, nl)
        val dot = rest.indexOf(" · ")
        if (dot >= 0) rest = rest.substring(0, dot)
        return rest.jsTrim().sliceUnits(80).ifEmpty { null }
    }
}

/**
 * A 9002 that changes only what is given and keeps the rest of G's metadata (the relay replaces
 * the whole metadata on each 9002: one with only a name dropped closed / prompt / vouches /
 * picture). ARCHIVED: said when it changes, kept while it is on (SPEC 19.30).
 */
fun Actions.groupMeta(
    g: Group,
    name: String? = null,
    about: String? = null,
    picture: String? = null,
    closed: Boolean? = null,
    prompt: String? = null,
    vouches: Int? = null,
    archived: Boolean? = null,
): Draft {
    val raw = name ?: g.rawName.ifEmpty { g.name }
    val tags = arrayListOf(listOf("h", g.id), listOf("name", PlaceNames.wire(g.id, raw)), listOf("about", about ?: g.about))
    val pic = picture ?: g.picture ?: ""
    if (pic.isNotEmpty()) tags.add(listOf("picture", pic))
    val isClosed = closed ?: g.closed
    tags.add(listOf(if (isClosed) "closed" else "open"))
    val p = (prompt ?: g.prompt ?: "").jsTrim()
    if (isClosed) tags.add(listOf("prompt", p.ifEmpty { Actions.DEFAULT_PROMPT }))
    val v = vouches ?: g.vouches
    if (isClosed && v > 0) tags.add(listOf("vouches", v.coerceIn(1, 5).toString()))
    if (archived != null) tags.add(listOf("archived", if (archived) "1" else "0")) else if (g.archived) tags.add(listOf("archived", "1"))
    return Draft(sh.belfry.core.outbox.Target.GROUPS, 9002, tags = tags)
}

/** Archive / unarchive G: a 9002 keeping every other setting. */
fun Actions.archive(g: Group, on: Boolean): Draft = groupMeta(g, archived = on)

/** Kinds that count as content: a channel holding none of them can be deleted, else archived (web Categories.tsx). */
val CONTENT_KINDS: Set<Int> = setOf(9, 10, 11, 12, 20, 1063, 1068, 1111, 9802, 30023, 31922, 31923)

/** Does the channel hold any content (then it is archived, not deleted)? */
fun World.hasContent(groupId: String): Boolean = groupEventsSorted(groupId).any { it.kind in CONTENT_KINDS && it.id !in deleted }
