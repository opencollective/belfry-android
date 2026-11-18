package sh.belfry.core.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import sh.belfry.core.outbox.OutboxItem
import sh.belfry.core.outbox.Target

// Round 6 (2026-11-18; iOS SPEC 19.38 / 19.39, here SPEC 19.41 / 19.42): who reads a channel
// (public periods, closed channels), a channel's Notifications sheet, a proposal at the top of its
// chat (its own options, turnout, votes, versions). Ported from the iPhone app (8cc6e78), which
// matched the web (chat 8d31c30); vectors/visibility.json is shared.

/** One stretch of time a channel was public, in seconds; [to] null = still public. */
data class PublicPeriod(val from: Long, val to: Long?) {
    fun json(): JsonArray = JsonArray(listOf(JsonPrimitive(from), to?.let { JsonPrimitive(it) } ?: JsonNull))

    companion object {
        private fun num(x: JsonElement?): Long? {
            val p = x as? JsonPrimitive ?: return null
            if (p is JsonNull || p.isString || p.booleanOrNull != null) return null
            val d = p.doubleOrNull ?: return null
            return if (d.isFinite()) Math.floor(d).toLong() else null
        }

        /** web cleanPeriods: `[number, number | null]` pairs (floored), sorted by start (stable), the last 100. */
        fun clean(raw: JsonArray): List<PublicPeriod> {
            val list = raw.mapNotNull { x ->
                val a = x as? JsonArray ?: return@mapNotNull null
                if (a.size < 2) return@mapNotNull null
                val f = num(a[0]) ?: return@mapNotNull null
                if (a[1] is JsonNull) return@mapNotNull PublicPeriod(f, null)
                val t = num(a[1]) ?: return@mapNotNull null
                PublicPeriod(f, t)
            }
            return list.sortedBy { it.from }.takeLast(100)
        }
    }
}

/** Who reads a channel: the three choices of its Settings. */
enum class ChannelAccess(val key: String) { MEMBERS("members"), PUBLIC("public"), CLOSED("closed") }

/** When channel G was public: its web settings' `periods`; a public setting from before periods counts since ever. */
fun World.publicPeriods(g: Group?): List<PublicPeriod> {
    val w = webPage(g) ?: return emptyList()
    val raw = webContent(g)["periods"] as? JsonArray
    if (raw != null) return PublicPeriod.clean(raw)
    return if (w.on && w.visibility == "public") listOf(PublicPeriod(0, null)) else emptyList()
}

/** Who reads channel G: public (its page on and public) wins, then closed (the group's flag), else members only. */
fun World.channelAccess(g: Group?): ChannelAccess = when {
    isPublicChannel(g) -> ChannelAccess.PUBLIC
    g?.closed == true -> ChannelAccess.CLOSED
    else -> ChannelAccess.MEMBERS
}

private const val GENERAL_ID = "general"

/** The bar that asks to join (web JoinBar): a group I'm not in; a closed channel I'm not in and don't run (never #general). */
fun World.needsJoinBar(g: Group): Boolean {
    if (me in g.members || me in g.admins) return false
    if (g.isPod) return true
    return g.type == ChannelType.CHANNEL && g.closed && g.id != GENERAL_ID && !canAdmin(g)
}

/** Read only: a closed group or closed channel I'm not in (its stewards and the belfry's admins still write). */
fun World.readOnlyClosed(g: Group): Boolean {
    if (!g.closed || me in g.members || me in g.admins) return false
    if (g.isPod) return true
    return g.type == ChannelType.CHANNEL && g.id != GENERAL_ID && !canAdmin(g)
}

/** May it be closed? Channels and groups, never #general. */
fun mayClose(g: Group?): Boolean = g != null && (g.type == ChannelType.CHANNEL || g.isPod) && g.id != GENERAL_ID

/** A channel's web settings with this whole CONTENT (30078 `d` = belfry:web:<id>). */
fun Actions.webPage(groupId: String, content: JsonObject): Draft =
    Draft(Target.GROUPS, 30078, content.toString(), listOf(listOf("d", World.WEB_D + groupId), listOf("h", groupId), listOf("t", "belfry-web")))

/**
 * Public or members only (web setVisibility). Never retroactive: going public opens a period from
 * NOW (and turns the page on), going members only closes the open one. Every other key is kept.
 * Null when it is that already.
 */
fun Actions.setVisibility(g: Group, public: Boolean, world: World, now: Long): Draft? {
    if (world.isPublicChannel(g) == public) return null
    val c = LinkedHashMap(world.webContent(g))
    val w = world.webPage(g)
    val periods = world.publicPeriods(g).toMutableList()
    val open = periods.lastOrNull()?.to == null && periods.isNotEmpty()
    if (public && !open) periods.add(PublicPeriod(now, null))
    if (!public && open) periods[periods.size - 1] = periods.last().copy(to = now)
    val slug = w?.slug?.takeIf { it.isNotEmpty() } ?: World.slugify(g.rawName.ifEmpty { g.name })
    c["on"] = JsonPrimitive(if (public) true else (w?.on ?: false))
    c["visibility"] = JsonPrimitive(if (public) "public" else "members")
    c["periods"] = JsonArray(periods.map { it.json() })
    c["slug"] = JsonPrimitive(slug)
    return webPage(g.id, JsonObject(c))
}

/** Is this refused outbox item a change of who can read a channel (its web settings)? */
fun OutboxItem.isVisibilitySetting(): Boolean =
    kind == 30078 && tags.any { it.size >= 2 && it[0] == "d" && it[1].startsWith(World.WEB_D) }

/** The channel's Notifications sheet: three choices over the four stored levels (docs/push.md). */
enum class NotifyChoice(val level: String, val title: String, private val base: String) {
    EVERYTHING("all", "Everything", "Every new message"),
    FOR_ME("default", "When it’s for me", "Mentions, replies to me, announcements"),
    NOTHING("off", "Nothing", "I’ll look when I open the channel");

    /** The explaining line; "(the default here)" after the one a stored `default` resolves to. */
    fun line(stored: String?): String = if (stored == "default" && this == FOR_ME) "$base (the default here)" else base

    companion object {
        /** The choice a stored level shows as (`default` and `mentions` → When it's for me). */
        fun of(level: String?): NotifyChoice = when (level) { "all" -> EVERYTHING; "off" -> NOTHING; else -> FOR_ME }
    }
}

// ---- a proposal at the top of its chat ------------------------------------------------------------

/** The choices this poll really has (its `option` tags, in order); the three defaults when it names none. */
val Poll.options: List<Pair<String, String>>
    get() {
        val out = ArrayList<Pair<String, String>>()
        for (t in ev.tags) if (t.size >= 2 && t[0] == "option" && t[1].isNotEmpty() && out.none { it.first == t[1] }) {
            out.add(t[1] to (t.getOrNull(2)?.takeIf { it.isNotEmpty() } ?: t[1]))
        }
        return out.ifEmpty { Proposal.VOTE_OPTIONS }
    }

/** The choices to vote on: the current poll's own (Yes / No / Abstain by default). */
val Proposal.options: List<Pair<String, String>> get() = poll?.options ?: Proposal.VOTE_OPTIONS

data class Turnout(val voted: Int, val of: Int)

/** "X of Y members have" voted (web voteCounts): X = votes on the current poll, Y = the chat's members and the voters. */
fun World.voteTurnout(p: Proposal): Turnout {
    val voters = p.poll?.votes?.keys.orEmpty()
    val members = group(p.groupId)?.members.orEmpty()
    return Turnout(voters.size, (members + voters).size)
}

/** Who voted what on the current poll: option id → voters (sorted keys). */
fun World.votesByOption(p: Proposal): Map<String, List<String>> =
    (p.poll?.votes ?: emptyMap()).entries.groupBy({ it.value }, { it.key }).mapValues { it.value.sorted() }

/** One version of a proposal (its Versions tab). */
data class ProposalVersion(val version: Int, val at: Long, val by: String, val note: String, val text: String?)

private val VERSION_NOTE = Regex("^📝 Version \\d+:?\\s*")

/**
 * The versions it had, newest first (web proposalVersions): version 1 (when its card was posted,
 * by its author), then each "📝 Version N" note in its chat (kind 9, `version` ≥ 2, `a` = the
 * proposal), with that version's text when this device still holds its 30023.
 */
fun World.proposalVersions(p: Proposal): List<ProposalVersion> {
    val texts = HashMap<Int, String>()
    for (e in ofKind(30023)) if ("30023:${e.pubkey}:${e.t("d") ?: ""}" == p.coord) {
        val v = e.t("version")?.toIntOrNull() ?: 0
        texts[if (v == 0) 1 else v] = e.content
    }
    val card = ofKind(9).filter { e -> e.hasTag("proposal", p.groupId) }.minByOrNull { it.createdAt }
    val out = arrayListOf(ProposalVersion(1, card?.createdAt ?: 0, p.author, "", texts[1]))
    for (m in messages(p.groupId)) {
        val v = m.ev.t("version")?.toIntOrNull() ?: continue
        if (v < 2 || m.ev.tags.none { it.size > 1 && it[0] == "a" && it[1] == p.coord } || out.any { it.version == v }) continue
        out.add(ProposalVersion(v, m.ev.createdAt, m.ev.pubkey, m.text.replace(VERSION_NOTE, ""), texts[v]))
    }
    return out.sortedByDescending { it.version }
}
