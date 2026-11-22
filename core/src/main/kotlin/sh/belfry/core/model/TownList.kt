package sh.belfry.core.model

import sh.belfry.core.outbox.Target

// Navigation v2 (docs/one-shape.md §1, SPEC 19.53): the Town list on this device, from the rules of
// OneShape (pins for everyone, mine, then the channels I was involved in during the last 30 days).

/** The channels that can be in the Town list: the town's channels (not groups, not the charter). */
fun World.townKnown(): Set<String> = townChannels().map { it.id }.toSet()

/** The channels pinned for everyone: `pinned` in the admins' newest `belfry:channel-order`. */
fun World.everyonePins(): List<String> = OneShape.parsePins(channelOrderContent, townKnown(), key = "pinned")

/** The Town list: pins (everyone's, then [mine]), then the recent channels. Queued events count (a message I just wrote brings its channel in). */
fun World.townList(mine: List<String>, now: Long): OneShape.TownList {
    val events = ofKind(9) + ofKind(1111) + ofKind(7)
    return OneShape.townList(me, now, townKnown(), everyonePins(), mine, events)
}

/** A channel of the town that is not in my list: it opens as a preview (nav2/channel-preview). */
fun World.isPreview(id: String, list: OneShape.TownList): Boolean = id in townKnown() && id !in list.list

/** The admins' channel-order event with `pinned` set to [pinned], every other key kept. */
fun Actions.everyonePins(previousContent: String?, pinned: List<String>): Draft =
    Draft(Target.MAIN, 30078, OneShape.withEveryonePins(previousContent, pinned), listOf(listOf("d", World.ORDER_D)))
