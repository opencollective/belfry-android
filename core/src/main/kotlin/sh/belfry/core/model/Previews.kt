package sh.belfry.core.model

// Activity previews (canvas one-shape/action-previews; docs/one-shape.md 3 "Activity lines"): an
// action about an item (a to-do made / taken / done, an RSVP, an event or meeting set up, a
// proposal opened, a vote) shows in a channel's feed as ONE line that unfolds into a small card of
// the item. Nothing new on the wire: the lines are read from the events the channel already has.
// Inside the item's own conversation the lines stay plain (ThreadScreen's StepLine and the like).

enum class PreviewKind { TODO, EVENT, MEETING, PROPOSAL }

/**
 * One activity line. [key] is unique in the feed (a card message's id for "set up an event" /
 * "opened a proposal", so links to that message still land on it); [ref] is the item: a to-do id,
 * an event's coordinate, a proposal's group id. [verb] reads after the first name ("made a to-do").
 */
data class ActivityPreview(
    val key: String,
    val kind: PreviewKind,
    val who: String,
    val verb: String,
    val ref: String,
    val title: String,
    val at: Long,
)

/** A channel's previews, compared by value ([World.feedKey]: the feed redraws only when one changes). */
data class PreviewList(val list: List<ActivityPreview>)

object Previews {
    /** To-do steps that make a line ("working", "idle", "unassign" stay out of the channel). */
    val TODO_VERBS = mapOf(
        "created" to "made a to-do", "claim" to "took", "assign" to "assigned", "done" to "finished", "undone" to "reopened",
    )

    fun rsvpVerb(r: Rsvp): String = when (r) {
        Rsvp.ACCEPTED -> "is going"
        Rsvp.TENTATIVE -> "might go"
        Rsvp.DECLINED -> "can't go"
    }

    /** Same person, same item, within two minutes: one line (the first), like the thread's step lines. */
    const val MERGE_S = 120L

    /** A card message that becomes a preview (its event / proposal is known): which kind and item. */
    fun cardItem(world: World, m: Message): Pair<PreviewKind, String>? = when (val s = m.special) {
        is Special.EventCard -> world.calEvent(s.coord)?.let { (if (it.meeting) PreviewKind.MEETING else PreviewKind.EVENT) to it.coord }
        is Special.ProposalCard -> world.proposalFor(s.groupId)?.let { PreviewKind.PROPOSAL to it.groupId }
        else -> null
    }
}

/** The previews of channel [groupId], oldest first (cached per snapshot). */
fun World.previews(groupId: String): PreviewList =
    previewCache.getOrPut(groupId) { PreviewList(readPreviews(groupId)) }

private fun World.readPreviews(groupId: String): List<ActivityPreview> {
    val out = ArrayList<ActivityPreview>()
    // to-dos: made, taken, assigned, finished, reopened
    for (t in tasks(groupId)) {
        var lastWho = ""; var lastAt = Long.MIN_VALUE
        for ((i, s) in t.history.sortedBy { it.at }.withIndex()) {
            val verb = Previews.TODO_VERBS[s.what] ?: continue
            if (s.who == lastWho && s.at - lastAt <= Previews.MERGE_S) continue
            lastWho = s.who; lastAt = s.at
            out.add(ActivityPreview("pv:${t.id}:$i", PreviewKind.TODO, s.who, verb, t.id, t.title, s.at))
        }
    }
    // events and proposals announced here by a card: the card itself, then the RSVPs and votes
    for (m in messages(groupId)) {
        val (kind, ref) = Previews.cardItem(this, m) ?: continue
        val title = when (kind) {
            PreviewKind.PROPOSAL -> proposalFor(ref)?.title
            else -> calEvent(ref)?.title
        } ?: continue
        val verb = when (kind) {
            PreviewKind.MEETING -> "set up a meeting"
            PreviewKind.PROPOSAL -> "opened a proposal"
            else -> "set up an event"
        }
        out.add(ActivityPreview(m.id, kind, m.pubkey, verb, ref, title, m.at))
    }
    for (e in calendar) {
        if (groupId !in e.channels && !(e.meeting && e.madeIn == groupId)) continue
        val kind = if (e.meeting) PreviewKind.MEETING else PreviewKind.EVENT
        for ((pk, r) in e.rsvps) {
            val ev = e.rsvpEvents[pk] ?: continue
            out.add(ActivityPreview("pv:rsvp:${ev.id}", kind, pk, Previews.rsvpVerb(r), e.coord, e.title, ev.createdAt))
        }
    }
    for (p in proposalsIn(groupId)) {
        // each voter's latest answer, at its time (what they voted is not said in the line)
        val latest = HashMap<String, sh.belfry.core.nostr.Event>()
        for (e in groupEventsSorted(p.groupId)) if (e.kind == 1018) latest[e.pubkey] = e
        for ((pk, ev) in latest) out.add(ActivityPreview("pv:vote:${ev.id}", PreviewKind.PROPOSAL, pk, "voted", p.groupId, p.title, ev.createdAt))
    }
    return out.sortedWith(compareBy<ActivityPreview> { it.at }.thenBy { it.key })
}

/** Does the item of [p] wait for me: a to-do on me, an event I haven't answered, a vote I haven't cast. */
fun World.previewNeedsMe(groupId: String, p: ActivityPreview, now: Long): Boolean = when (p.kind) {
    PreviewKind.TODO -> tasks(groupId).firstOrNull { it.id == p.ref }?.let { it.assignee == me && !it.done } == true
    PreviewKind.EVENT, PreviewKind.MEETING -> calEvent(p.ref)?.let { e ->
        e.status != EventStatus.CANCELLED && !isPast(e, now) && e.rsvps[me] == null && me !in e.hosts &&
            (!e.meeting || me in e.invited)
    } == true
    PreviewKind.PROPOSAL -> proposalFor(p.ref)?.let { needsMyVote(it) } == true
}

/**
 * Which previews start open: of the items that need me, only the newest line about each (a
 * busy event's twenty RSVPs don't all unfold).
 */
fun World.previewsOpenByDefault(groupId: String, list: List<ActivityPreview>, now: Long): Set<String> {
    val newest = HashMap<String, ActivityPreview>()
    for (p in list) newest[p.kind.name + ":" + p.ref] = p
    return newest.values.filter { previewNeedsMe(groupId, it, now) }.map { it.key }.toSet()
}

/** A to-do's due date (docs/one-shape.md 3: `["due", unix]` on its kind 2100), else null. */
fun World.taskDue(groupId: String, taskId: String): Long? =
    groupEventsSorted(groupId).firstOrNull { it.id == taskId && it.kind == 2100 }?.let { OneShape.due(it.tags) }

/**
 * The channel's feed with its activity previews woven in (no topic: previews show only in the
 * whole feed). A card message whose event or proposal is known becomes its preview.
 */
fun World.feedRowsWithPreviews(groupId: String, topic: String? = null, openThreads: Set<String> = emptySet()): List<FeedRow> {
    if (topic != null) return feedRows(groupId, topic, openThreads)
    val pv = previews(groupId).list
    if (pv.isEmpty()) return feedRows(groupId, topic, openThreads)
    val cards = pv.mapTo(HashSet()) { it.key }
    val items = feed(groupId, topic).filter { !(it is FeedItem.Msg && it.m.id in cards) } + pv.map { FeedItem.Preview(it) }
    val sorted = items.withIndex().sortedWith(
        compareBy<IndexedValue<FeedItem>> { it.value.at }.thenBy { if (it.value is FeedItem.Msg) 0 else 1 }.thenBy { it.index },
    ).map { it.value }
    return rowsOf(groupId, sorted, openThreads)
}
