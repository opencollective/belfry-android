package sh.belfry.core.model

import sh.belfry.core.crypto.Hex
import sh.belfry.core.nostr.Event

// Who really made it (SPEC 19.75; collective-belfry docs/cards.md §2.2 "Who created it"; web
// model.ts `authorOf`, chat 802be45). The relay only takes a printed card's group from the groups
// key, so the belfry writes the 9007, the 9002, the "⬡ New group" post in general and the first
// line in the group for the person who claimed the card, and names them in ["by", <pubkey>].
//
// The one rule: `by` counts ONLY on an event whose author is exactly the groups key. Anybody can
// put a `by` tag on their own event; that says nothing.
//
// Groups claimed before the server wrote `by`: the ["p", …] of the group's kind 9 tagged
// ["card", "1"] by the groups key (the card's first line) names the claimer. That fallback covers
// the events the belfry writes at a claim and nothing else in the group (here stricter than the
// web, which gives the claimer every event of the groups key in that group, an e-mail card too).

object CardAuthors {
    const val BY = "by"
    /** The `t` of the town square's post about a topic started from a card. */
    const val NEW_TOPIC = "new-topic"

    private fun fromGroupsKey(e: Event, groupsKey: String?): Boolean = !groupsKey.isNullOrEmpty() && e.pubkey == groupsKey

    /**
     * The person the groups key wrote E for: its first well-formed `by` (64 lower-case hex), as the
     * web reads it. Null on anyone else's event, whatever it carries, and when no `by` is a key.
     */
    fun by(e: Event, groupsKey: String?): String? {
        if (!fromGroupsKey(e, groupsKey)) return null
        return e.tags.firstOrNull { it.size >= 2 && it[0] == BY && Hex.isKey(it[1]) }?.get(1)
    }

    /** The groups key's first line in a group or topic started from a card: kind 9 tagged `["card","1"]`. */
    fun isCardLine(e: Event, groupsKey: String?): Boolean = e.kind == 9 && fromGroupsKey(e, groupsKey) && (e.hasTag("card", "1") || cardIdOf(e) != null)

    /** Who a card line says started the group: its first `p` that is a key. */
    fun cardLineWho(e: Event): String? = e.tags.firstOrNull { it.size >= 2 && it[0] == "p" && Hex.isKey(it[1]) }?.get(1)

    /** Group id → who claimed its card, from the card lines (the oldest per group). EVENTS in any order. */
    fun claimers(events: Iterable<Event>, groupsKey: String?): Map<String, String> {
        if (groupsKey.isNullOrEmpty()) return emptyMap()
        val at = HashMap<String, Event>()
        for (e in events) {
            if (!isCardLine(e, groupsKey)) continue
            val h = e.tag("h")?.takeIf { it.isNotEmpty() } ?: continue
            if (cardLineWho(e) == null) continue
            val had = at[h]
            if (had == null || World.BY_TIME.compare(e, had) < 0) at[h] = e
        }
        return at.mapValues { cardLineWho(it.value)!! }
    }

    /** The events the belfry writes at a claim (the fallback covers these only). */
    private fun atClaim(e: Event): Boolean =
        e.kind == 9007 || e.kind == 9002 || (e.kind == 9 && (e.tags.any { it.size >= 2 && it[0] == "pod" } || e.hasTag("card", "1") || cardIdOf(e) != null))

    /**
     * E's real author: the person behind an event the groups key signed for them (its `by`, else
     * for a claim's own events the claimer of its group, CLAIMERS = [claimers]); everybody else's
     * events are their own.
     */
    fun authorOf(e: Event, groupsKey: String?, claimers: Map<String, String>): String {
        if (!fromGroupsKey(e, groupsKey)) return e.pubkey
        by(e, groupsKey)?.let { return it }
        if (!atClaim(e)) return e.pubkey
        val group = e.tags.firstOrNull { it.size >= 2 && it[0] == "pod" }?.get(1) ?: e.tag("h")
        return group?.let { claimers[it] } ?: e.pubkey
    }

    /**
     * The topic a town-square post announces (the cards server: kind 9, `["t","new-topic"]`,
     * `["channel", <its id, the hashtag>]`), whoever signed it: the card only links a channel.
     */
    fun newTopicOf(e: Event): String? =
        if (e.kind == 9 && e.hasTag("t", NEW_TOPIC)) e.tag("channel")?.takeIf { it.isNotEmpty() } else null

    private val POST_HEAD = Regex("^#\\s*New topic:\\s*#\\S+\\s*·?\\s*")

    /**
     * What the post itself says of the topic while the channel is not known here: its first line
     * "# New topic: #tag · topic · description" without the head (web TopicCard `fromPost`).
     */
    fun aboutInPost(text: String): String = (text.split('\n').firstOrNull() ?: "").replace(POST_HEAD, "").trim()

    /** A card topic's about is "<topic> · <description>" (Belfry 7bfa781): the two parts ("" when missing). */
    fun topicParts(about: String): Pair<String, String> {
        val parts = about.split(" · ")
        return parts.first().trim() to parts.drop(1).joinToString(" · ").trim()
    }

    /** "1 following" / "8 following": a topic is followed, not joined. */
    fun following(n: Int): String = "$n following"

    /** "Steward: Lou · 1 person" / "Steward: Dev · 3 following" on the town square's cards (onb-town-group, onb-town-topic). */
    fun stewardLine(who: String, pod: Boolean, count: Int?): String =
        "Steward: $who" + (count?.let { " · " + if (pod) (if (it == 1) "1 person" else "$it people") else following(it) } ?: "")

    /**
     * The card's id when a card line carries one: `["card_id", <ID>]` (Belfry 9b4482b, beside `["card", "1"]`), or an
     * id in `["card", <id>]` (read in case a belfry ever writes it). Lower case read as upper case, as in a card address.
     */
    fun cardIdOf(e: Event): String? =
        (e.tags.firstOrNull { it.size >= 2 && it[0] == "card_id" }?.get(1) ?: e.tags.firstOrNull { it.size >= 2 && it[0] == "card" && it[1] != "1" }?.get(1))
            ?.uppercase()?.takeIf { Cards.isId(it) }

    /**
     * The first line of a group or topic started from a card, in our words (never the event's text):
     * "You created the group from card K7Q2MX" (onb-group-done), "from a printed card" without the id.
     */
    fun startedLine(name: String?, pod: Boolean, cardId: String? = null): String {
        val word = if (pod) "group" else "topic"
        val from = if (cardId != null) "card $cardId" else "a printed card"
        return if (name.isNullOrEmpty()) "This $word was created from $from" else "$name created the $word from $from"
    }

    /** The grey line over a town square card a printed card made: "created the group" / "created the topic" (+ its name, in bold). */
    fun createdWords(pod: Boolean): String = if (pod) "created the group" else "created the topic"
}
