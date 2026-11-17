package sh.belfry.core.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import sh.belfry.core.crypto.Random
import sh.belfry.core.nostr.arr
import sh.belfry.core.nostr.obj
import sh.belfry.core.nostr.parseJsonOrNull
import sh.belfry.core.nostr.str
import sh.belfry.core.outbox.Target

// Channel categories (SPEC 19.34; vectors/categories.json). The same event as the channel order:
// kind 30078, d = belfry:channel-order on the community relay, by a belfry admin (the latest by an
// admin wins). Its content gains `categories`:
//   {"order": [channel ids, flattened], "categories": [{"id": "life", "name": "…", "channels": [ids]}, …]}
// Built-in ids: `life` (the default) and `building` (default members `proposals`, `website`). The
// town (general, Members, the Charter) is not a category. Without `categories` the list is exactly
// as before (life, then building).

/** One category of the channel list: its id, its title and its channels' ids in order. */
data class ChannelCategory(val id: String, val name: String, val channels: List<String>) {
    val isBuiltIn: Boolean get() = id in DEFAULT_NAMES

    companion object {
        const val LIFE = "life"
        const val BUILDING = "building"
        val DEFAULT_NAMES: Map<String, String> = mapOf(LIFE to "Community life", BUILDING to "Building together")
        /** The channels `building` holds while no category names them. */
        val BUILDING_DEFAULT = listOf("proposals", "website")
        fun defaultName(id: String): String? = DEFAULT_NAMES[id]
    }
}

/** The admins' event as read: the flattened order and the categories (null = the event has none). */
data class ChannelLayout(val order: List<String> = emptyList(), val categories: List<ChannelCategory>? = null) {
    companion object {
        /** Reads the event's content; anything malformed is left out (never an error). */
        fun parse(content: String?): ChannelLayout {
            if (content.isNullOrEmpty()) return ChannelLayout()
            val obj = parseJsonOrNull(content).obj() ?: return ChannelLayout()
            val order = (obj["order"].arr() ?: JsonArray(emptyList())).mapNotNull { it.str() }.take(1000)
            val list = obj["categories"].arr() ?: return ChannelLayout(order, null)
            val cats = ArrayList<ChannelCategory>()
            val ids = HashSet<String>()
            for (item in list.take(100)) {
                val c = item.obj() ?: continue
                val rawId = c["id"].str() ?: continue
                val id = rawId.trim().take(64)
                if (id.isEmpty() || id == "town" || id in ids) continue
                ids.add(id)
                val name = (c["name"].str() ?: "").trim().take(60)
                val chans = (c["channels"].arr() ?: JsonArray(emptyList())).mapNotNull { it.str() }.take(1000)
                cats.add(ChannelCategory(id, name, chans))
            }
            return ChannelLayout(order, cats)
        }
    }
}

/** A row of the flat list a drag works on: a category's header or a channel. */
sealed interface CategoryRow {
    data class Header(val id: String) : CategoryRow
    data class Channel(val id: String) : CategoryRow

    companion object {
        /** "h:<id>" / "c:<id>" (the vectors' notation). */
        fun of(code: String): CategoryRow? = when {
            code.startsWith("h:") -> Header(code.substring(2))
            code.startsWith("c:") -> Channel(code.substring(2))
            else -> null
        }
    }
}

object Categories {
    /** The categories with their visible channels. CHANNELS = the town's listed channels in their natural order (general is never in one). */
    fun arrange(layout: ChannelLayout, channels: List<String>): List<ChannelCategory> {
        val visible = channels.filter { it != "general" }
        val vis = visible.toSet()
        val ordered = inOrder(visible, layout.order)
        val life = ChannelCategory.LIFE
        val building = ChannelCategory.BUILDING
        val names = ChannelCategory.DEFAULT_NAMES
        val cats = layout.categories ?: return listOf(
            ChannelCategory(life, names.getValue(life), ordered.filter { it !in ChannelCategory.BUILDING_DEFAULT }),
            ChannelCategory(building, names.getValue(building), ordered.filter { it in ChannelCategory.BUILDING_DEFAULT }),
        )
        val seen = HashSet<String>()
        val out = ArrayList<ChannelCategory>()
        for (c in cats) {
            val chans = ArrayList<String>()
            for (x in c.channels) if (x in vis && seen.add(x)) chans.add(x)
            out.add(ChannelCategory(c.id, c.name.ifEmpty { ChannelCategory.defaultName(c.id) ?: c.id }, chans))
        }
        if (out.none { it.id == building }) {
            val chans = ordered.filter { it in ChannelCategory.BUILDING_DEFAULT && it !in seen }
            seen.addAll(chans)
            out.add(ChannelCategory(building, names.getValue(building), chans))
        }
        if (out.none { it.id == life }) out.add(0, ChannelCategory(life, names.getValue(life), emptyList()))
        val i = out.indexOfFirst { it.id == life }
        if (i >= 0) out[i] = out[i].copy(channels = out[i].channels + ordered.filter { it !in seen })
        return out
    }

    /** The categories a list shows: `building` only with channels; others always for the belfry's admins (empty, with their ＋), with channels for everyone else. */
    fun shown(cats: List<ChannelCategory>, admin: Boolean): List<ChannelCategory> =
        cats.filter { it.channels.isNotEmpty() || (admin && it.id != ChannelCategory.BUILDING) }

    /**
     * The content of the admins' event for CATS (the whole arrangement as edited). The flattened
     * `order` (general first) keeps older clients working; built-in names equal to their default
     * are left out; an empty `building` is left out; ids from PREVIOUS that the writer doesn't see
     * (KNOWN = the channels it sees) stay at the end of their category.
     */
    fun encode(cats: List<ChannelCategory>, previous: ChannelLayout?, known: Set<String>): JsonObject {
        val prev = HashMap<String, List<String>>()
        for (c in previous?.categories ?: emptyList()) if (c.id !in prev) prev[c.id] = c.channels
        val written = HashSet<String>()
        val lists = cats.map { c -> c.channels.filter { written.add(it) } }
        val order = arrayListOf("general")
        val res = ArrayList<JsonObject>()
        for ((c, base) in cats.zip(lists)) {
            val chans = ArrayList(base)
            for (x in prev[c.id] ?: emptyList()) if (x !in known && x != "general" && written.add(x)) chans.add(x)
            if (c.id == ChannelCategory.BUILDING && chans.isEmpty()) continue
            val name = c.name.trim()
            res.add(buildJsonObject {
                put("id", JsonPrimitive(c.id))
                if (name.isNotEmpty() && !(c.isBuiltIn && name == ChannelCategory.defaultName(c.id))) put("name", JsonPrimitive(name))
                put("channels", JsonArray(chans.map { JsonPrimitive(it) }))
            })
            order.addAll(chans)
        }
        return buildJsonObject {
            put("order", JsonArray(order.map { JsonPrimitive(it) }))
            put("categories", JsonArray(res))
        }
    }

    /** CATS after a drag in the flat list FLAT: a channel belongs to the header above it (above the first header: the first one); categories not in FLAT keep theirs. */
    fun rebuild(cats: List<ChannelCategory>, flat: List<CategoryRow>): List<ChannelCategory> {
        val heads = flat.filterIsInstance<CategoryRow.Header>().map { it.id }
        val lists = LinkedHashMap<String, MutableList<String>>()
        for (h in heads) lists[h] = ArrayList()
        var cur = heads.firstOrNull() ?: ChannelCategory.LIFE
        for (r in flat) when (r) {
            is CategoryRow.Header -> cur = r.id
            is CategoryRow.Channel -> lists.getOrPut(cur) { ArrayList() }.add(r.id)
        }
        return cats.map { c -> lists[c.id]?.let { c.copy(channels = it.toList()) } ?: c }
    }

    /** The flat list of CATS: each header, then its channels. */
    fun flat(cats: List<ChannelCategory>): List<CategoryRow> =
        cats.flatMap { c -> listOf<CategoryRow>(CategoryRow.Header(c.id)) + c.channels.map { CategoryRow.Channel(it) } }

    /** CHANNEL moved to the end of category TO (out of wherever it was). */
    fun put(channel: String, to: String, cats: List<ChannelCategory>): List<ChannelCategory> {
        val out = cats.map { c -> c.copy(channels = c.channels.filter { it != channel }) }.toMutableList()
        val i = out.indexOfFirst { it.id == to }.takeIf { it >= 0 } ?: out.indexOfFirst { it.id == ChannelCategory.LIFE }
        if (i >= 0) out[i] = out[i].copy(channels = out[i].channels + channel)
        return out
    }

    /** Category ID removed: its channels go to the end of Community life (built-ins stay). */
    fun remove(id: String, cats: List<ChannelCategory>): List<ChannelCategory> {
        val i = cats.indexOfFirst { it.id == id }
        if (i < 0 || cats[i].isBuiltIn) return cats
        val out = cats.toMutableList()
        val moved = out.removeAt(i).channels
        val l = out.indexOfFirst { it.id == ChannelCategory.LIFE }
        if (l >= 0) out[l] = out[l].copy(channels = out[l].channels + moved)
        else out.add(0, ChannelCategory(ChannelCategory.LIFE, ChannelCategory.DEFAULT_NAMES.getValue(ChannelCategory.LIFE), moved))
        return out
    }

    /** A category renamed. */
    fun rename(id: String, name: String, cats: List<ChannelCategory>): List<ChannelCategory> =
        cats.map { if (it.id == id) it.copy(name = name.trim().take(60)) else it }

    /** A new category's id: a short slug of its name and 4 random characters. */
    fun newId(name: String, random: (Int) -> String = { Random.hex(it) }): String {
        var s = Actions.slug(name) { "" }.take(24).trimEnd('-')
        if (s.isEmpty() || s == "channel-") s = "category"
        if (s == "channel") s = "category"
        return s + "-" + random(2)
    }

    internal fun inOrder(ids: List<String>, order: List<String>): List<String> {
        val at = HashMap<String, Int>()
        for ((i, id) in order.withIndex()) if (id !in at) at[id] = i
        return ids.withIndex().sortedBy { at[it.value] ?: (1_000_000 + it.index) }.map { it.value }
    }
}

/** The admins' latest d=belfry:channel-order event, read (order and categories). */
val World.channelLayout: ChannelLayout get() = channelLayoutRead

/** The flattened order of the admins' event. */
fun World.channelOrder(): List<String> = channelLayout.order

/** LIST in the admins' order (stable: channels it doesn't name keep their place, after). */
fun World.inOrder(list: List<Group>, order: List<String> = channelOrder()): List<Group> {
    val at = HashMap<String, Int>()
    for ((i, id) in order.withIndex()) if (id !in at) at[id] = i
    return list.withIndex().sortedBy { at[it.value.id] ?: (1_000_000 + it.index) }.map { it.value }
}

/** The town's channels (listed, not groups, not the charter) in their natural order. */
fun World.townChannels(): List<Group> = groups.filter { it.type == ChannelType.CHANNEL && it.id != Charter.ID }

/** The drawer's categories with their channels (general is never in one). */
fun World.channelCategories(): List<ChannelCategory> = Categories.arrange(channelLayout, townChannels().map { it.id })

/** The admins' order with categories (SPEC 19.34); `order` stays the flattened list. */
fun Actions.channelLayout(cats: List<ChannelCategory>, previous: ChannelLayout?, known: Set<String>): Draft =
    Draft(Target.MAIN, 30078, Categories.encode(cats, previous, known).toString(), listOf(listOf("d", World.ORDER_D)))
