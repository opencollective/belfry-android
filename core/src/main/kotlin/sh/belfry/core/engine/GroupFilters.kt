package sh.belfry.core.engine

import kotlinx.serialization.json.JsonObject
import sh.belfry.core.net.Filters

/**
 * The live subscription on the groups relay (SPEC 19.39).
 *
 * - The groups' own metadata (39000 name / about / access, 39001 admins, 39002 members, 39003
 *   roles) is always asked for without `since`: it is replaceable, one of each per group, and
 *   usually older than anything else. Asking for it only "since the newest event here minus an
 *   hour" left out every channel whose metadata is older: a guest who became a member had only
 *   the channels they were in as a guest, and a channel whose messages arrived without its 39000
 *   showed its id ("#issues-5fce") instead of its name.
 * - Everything else catches up from the newest event this device has, less an hour, except when
 *   [full]: this device never had the relay's whole view as a member (a guest until now: the relay
 *   showed them only their own channels), so the history is asked from the start, once.
 * - The charter's agreements and their changes (2100 / 2101 in group `charter`) are always asked
 *   for whole, apart: every agreement ever added and not deleted must be listed (Xavier,
 *   2026-11-18), and on a busy belfry an old one fell outside the newest [LIMIT] events.
 */
object GroupFilters {
    val META_KINDS = listOf(39000, 39001, 39002, 39003)
    const val META_LIMIT = 1000
    const val LIMIT = 2000

    fun of(kinds: List<Int>, newest: Long, full: Boolean): List<JsonObject> = listOf(
        Filters.of(kinds = META_KINDS, limit = META_LIMIT),
        Filters.of(kinds = kinds, since = if (full) 0 else maxOf(0, newest - 3600), limit = LIMIT),
        Filters.of(kinds = CHARTER_KINDS, limit = CHARTER_LIMIT, tags = mapOf("h" to listOf(sh.belfry.core.model.Charter.ID))),
    )

    val CHARTER_KINDS = listOf(2100, 2101)
    const val CHARTER_LIMIT = 5000
}
