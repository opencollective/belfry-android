package sh.belfry.core.model

import sh.belfry.core.nostr.Event
import sh.belfry.core.nostr.Tag
import sh.belfry.core.outbox.Target

// The town's places (SPEC 19.31) and the charter (SPEC 19.32, vectors/charter.json).
//
// Names: the default channels read on brand (display only, ids unchanged) while their own name is
// still the default. Charter: a short list of agreements every person ticks for themselves. It is
// the NIP-29 group `charter` on the groups relay; each agreement is a to-do (kind 2100) of that
// group's task list carrying ["each","1"]; a person's agreement is their own 2101 `done` (counted
// per author); a steward's 2101 `edit` changes the text, explanation or order (latest wins) and,
// when the text changes, asks everyone to agree to that rule again. Only the belfry's admins'
// agreements, edits and removals count.

/**
 * Every display name of the town in ONE place (the web is about to change them: a one-line change
 * here). Display only: never written back.
 */
object PlaceNames {
    /** The on-brand name of a default place, by group id. */
    val defaults: Map<String, String> = mapOf("general" to "Townsquare")

    /** The name to show instead of NAME, or null to keep it: only while the group's own name is still its default (empty, its id, or "#" + id, any case). */
    fun display(id: String, name: String): String? {
        val n = defaults[id] ?: return null
        val raw = name.trim().lowercase()
        return if (raw.isEmpty() || raw == id || raw == "#$id") n else null
    }

    /** What to write for a name shown on brand: the id itself (never "Square" on the wire). */
    fun wire(id: String, name: String): String = if (defaults[id] == name) id else name

    /** Members and their requests (the drawer row and its screen). */
    const val MEMBERS = "Members"
    const val TODAY = "Today"
    const val TOWN = "The town"
    const val CHARTER = "Charter"
    const val ARCHIVED = "Archived"

    /** The belfry roles as people read them: Steward (the built-in admin), Member, Guest. */
    const val STEWARD = "Steward"
    const val MEMBER = "Member"
    const val GUEST = "Guest"
    /** The per-event / per-group check-in role (wire value `steward`, SPEC 19.29). */
    const val DOOR = "Door"

    /** The five places of the bottom bar (SPEC 19.31). */
    const val HOME = "Home"
    const val CALENDAR = "Calendar"
    const val CREATE = "Create"
    const val GROUPS = "Groups"
    const val TOWN_PLACE = "Town"
}

data class Agreement(
    val id: String,
    val title: String,
    val about: String,
    /** Int.MAX_VALUE = none (last). */
    val order: Int,
    val author: String,
    val addedAt: Long,
    /** When its text (title or explanation) last changed: its 2100, or a steward's edit. */
    val textAt: Long,
    /** People whose latest tick is `done` and not older than [textAt] → when. */
    val agreed: Map<String, Long> = emptyMap(),
    /** People who had agreed before the text changed (they are asked again). */
    val stale: Set<String> = emptySet(),
    val pending: Boolean = false,
) {
    fun hasAgreed(pubkey: String): Boolean = pubkey in agreed
    /** Asked again of PUBKEY because the words changed after their tick. */
    fun changedFor(pubkey: String): Boolean = pubkey in stale
}

object Charter {
    const val ID = "charter"
    const val EACH = "each"
    val LIST_ID: String get() = World.listIdFor(ID)

    /** The charter's agreements from EVENTS (2100 / 2101 of the `charter` task list). */
    fun parse(events: List<Event>, admins: Set<String>, pending: (String) -> Boolean = { false }): List<Agreement> {
        val listId = LIST_ID
        val evs = events.filter { (it.kind == 2100 || it.kind == 2101) && it.t("t") == listId && it.t("h") == ID }.sortedWith(World.BY_TIME)
        val rules = LinkedHashMap<String, Agreement>()
        val ticks = HashMap<String, HashMap<String, Pair<Boolean, Long>>>() // rule → person → latest tick
        for (e in evs) {
            if (e.kind == 2100) {
                if (e.t(EACH) != "1" || e.pubkey !in admins || e.id in rules) continue
                val title = e.content.jsTrim().sliceUnits(300)
                if (title.isEmpty()) continue
                rules[e.id] = Agreement(
                    id = e.id, title = title, about = (e.t("about") ?: "").jsTrim().sliceUnits(200),
                    order = e.t("order")?.toIntOrNull() ?: Int.MAX_VALUE, author = e.pubkey, addedAt = e.createdAt,
                    textAt = e.createdAt, pending = pending(e.id),
                )
                continue
            }
            val rid = e.t("e") ?: continue
            val r = rules[rid] ?: continue
            when (e.t("action") ?: "") {
                "edit" -> {
                    if (e.pubkey !in admins) continue
                    val title = e.content.jsTrim().sliceUnits(300)
                    val about = (e.t("about") ?: "").jsTrim().sliceUnits(200)
                    var x = r
                    if (title.isNotEmpty() && (title != r.title || about != r.about)) x = x.copy(title = title, about = about, textAt = e.createdAt)
                    e.t("order")?.toIntOrNull()?.let { x = x.copy(order = it) }
                    rules[rid] = x
                }
                "delete" -> if (e.pubkey in admins) { rules.remove(rid); ticks.remove(rid) }
                "done" -> ticks.getOrPut(rid) { HashMap() }[e.pubkey] = true to e.createdAt
                "undone" -> ticks.getOrPut(rid) { HashMap() }[e.pubkey] = false to e.createdAt
            }
        }
        val out = rules.values.map { r ->
            val agreed = HashMap<String, Long>()
            val stale = HashSet<String>()
            for ((pk, t) in ticks[r.id] ?: emptyMap<String, Pair<Boolean, Long>>()) {
                if (!t.first) continue
                if (t.second >= r.textAt) agreed[pk] = t.second else stale.add(pk)
            }
            r.copy(agreed = agreed, stale = stale)
        }
        return out.sortedWith(compareBy<Agreement> { it.order }.thenBy { it.addedAt }.thenBy { it.id })
    }

    fun tags(): List<Tag> = listOf(listOf("t", LIST_ID), listOf("h", ID), listOf(EACH, "1"))

    /** The charter's group, made by the steward who writes the first agreement. */
    fun create(): List<Draft> = listOf(
        Draft(Target.GROUPS, 9007, tags = listOf(listOf("h", ID))),
        Draft(Target.GROUPS, 9002, tags = listOf(listOf("h", ID), listOf("name", "Charter"), listOf("about", "The agreements everyone here ticks."), listOf("open"))),
    )

    fun add(title: String, about: String, order: Int): Draft =
        Draft(Target.GROUPS, 2100, title.jsTrim().sliceUnits(300), tags() + aboutTag(about) + listOf(listOf("order", order.toString())))

    /** A steward's change: the full text, explanation and place (latest wins). */
    fun edit(id: String, title: String, about: String, order: Int): Draft =
        Draft(Target.GROUPS, 2101, title.jsTrim().sliceUnits(300), listOf(listOf("e", id), listOf("action", "edit")) + tags() + aboutTag(about) + listOf(listOf("order", order.toString())))

    fun remove(id: String): Draft = Draft(Target.GROUPS, 2101, tags = listOf(listOf("e", id), listOf("action", "delete")) + tags())

    /** My own tick (or taking it back). */
    fun agree(id: String, on: Boolean): Draft = Draft(Target.GROUPS, 2101, tags = listOf(listOf("e", id), listOf("action", if (on) "done" else "undone")) + tags())

    private fun aboutTag(about: String): List<Tag> {
        val a = about.jsTrim().sliceUnits(200)
        return if (a.isEmpty()) emptyList() else listOf(listOf("about", a))
    }
}

/** The charter's group, when a steward made it. */
val World.charterGroup: Group? get() = group(Charter.ID)

/** The agreements, in the stewards' order (then oldest first). */
fun World.charter(): List<Agreement> = charterList

/** The rules I haven't agreed to (or agreed to before their text changed). */
fun World.charterToAgree(): List<Agreement> = charter().filter { me !in it.agreed }

/** When I agreed to the last rule I ticked, while I have agreed to all of them; null otherwise. */
fun World.charterAgreedAt(): Long? {
    val list = charter()
    if (list.isEmpty() || list.any { me !in it.agreed }) return null
    return list.mapNotNull { it.agreed[me] }.maxOrNull()
}

/** People the charter counts ("N of M members"): people of `general` who are not guests or agents. */
fun World.charterPeople(): List<String> = (group("general")?.members ?: emptySet()).filter { !isGuest(it) && !isAgent(it) }

/** Did I ever tick an agreement (a 2101 of mine in the charter's list)? */
fun World.everTickedCharter(): Boolean = charterTicked

/** Shown when the charter group exists, or to stewards (SPEC 19.31). */
fun World.showsCharter(): Boolean = charterGroup != null || isCommunityAdmin || charter().isNotEmpty()
