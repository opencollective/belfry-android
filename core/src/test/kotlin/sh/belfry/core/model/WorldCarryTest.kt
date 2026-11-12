package sh.belfry.core.model

import sh.belfry.core.nostr.Event
import sh.belfry.core.outbox.OutboxItem
import sh.belfry.core.outbox.Target
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A snapshot built on the one before (what it worked out for untouched channels is taken over)
 * must show exactly what a snapshot built from nothing shows. A few hundred random steps of
 * everything a log can hold, each compared view by view.
 */
class WorldCarryTest {
    private val now = 1_800_000_000L
    private val people = (1..6).map { "%064x".format(it) }
    private val relayKey = "f".repeat(64)
    private val channels = listOf("general", "garden-ab12", "kitchen")
    private val special = listOf("pod-tools-1a", "prop-fence-2b")
    private val ctx = WorldContext(me = people[0], domain = "example.org", communityName = "Example", zone = ZoneOffset.UTC, groupsKey = people[5])
    private var n = 0
    private var clock = now - 100_000
    private fun ev(pk: String, kind: Int, tags: List<List<String>>, content: String = "", at: Long = ++clock) =
        Event("%064x".format(900_000 + n++), pk, at, kind, tags, content, "")

    private fun base(): MutableList<Event> {
        val out = ArrayList<Event>()
        for ((i, p) in people.withIndex()) out.add(ev(p, 0, emptyList(), """{"name":"Person $i"}"""))
        for (h in channels + special) {
            out.add(ev(people[1], 9007, listOf(listOf("h", h))))
            out.add(ev(relayKey, 39000, listOf(listOf("d", h), listOf("name", h.substringBefore('-')))))
            out.add(ev(relayKey, 39002, listOf(listOf("d", h)) + people.take(4).map { listOf("p", it) }))
            out.add(ev(people[2], 9, listOf(listOf("h", h)), "first in $h"))
        }
        return out
    }

    private fun views(w: World): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        out["groups"] = w.groups
        out["deleted"] = w.deleted
        out["profiles"] = w.profiles
        out["extras"] = w.extras
        out["calendar"] = w.calendar
        out["proposals"] = w.proposals
        out["amGuest"] = w.amGuest
        for (g in w.groups) {
            val h = g.id
            out["messages:$h"] = w.messages(h)
            out["threads:$h"] = w.threadsOf(h)
            out["reactions:$h"] = w.reactions(h)
            out["joins:$h"] = w.guestJoins(h)
            out["feed:$h"] = w.feedRows(h)
            out["tasks:$h"] = w.tasks(h)
            out["web:$h"] = w.webPage(g)
            out["public:$h"] = w.isPublicChannel(g)
            out["pinned:$h"] = w.pinned(h)
            out["line:$h"] = w.channelLine(h, now)
            out["topics:$h"] = w.pinnedTopics(h)
            out["replies:$h"] = w.messages(h).map { w.threadReplies(h, it.id) }
        }
        return out
    }

    @Test
    fun builtOnTheSnapshotBeforeShowsTheSameAsBuiltFromNothing() {
        val rnd = java.util.Random(7)
        val log = base()
        var carried = World(ArrayList(log), emptyList(), ctx)
        views(carried)
        var keptLists = 0
        var keptEpochs = 0
        var newEpochs = 0
        val held = ArrayList<Event>() // made, but arriving later (their deletion or edit comes first)

        fun messagesIn(h: String) = log.filter { it.kind == 9 && it.tags.any { t -> t == listOf("h", h) } }
        fun step(): List<Event> {
            val h = (channels + special)[rnd.nextInt(5)]
            val who = people[rnd.nextInt(5)]
            val msgs = messagesIn(h)
            val target = msgs[rnd.nextInt(msgs.size)]
            return when (rnd.nextInt(26)) {
                in 0..7 -> listOf(ev(who, 9, listOf(listOf("h", h)), "message ${n} #seeds"))
                in 8..10 -> listOf(ev(who, 7, listOf(listOf("h", h), listOf("e", target.id)), if (rnd.nextBoolean()) "+" else "🎉"))
                in 11..12 -> listOf(ev(who, 1111, listOf(listOf("h", h), listOf("E", target.id), listOf("e", target.id)), "reply ${n}"))
                13 -> listOf(ev(who, 30078, listOf(listOf("d", "seen:$h"), listOf("h", h))))
                14 -> listOf(ev(who, 2100, listOf(listOf("h", h), listOf("t", World.listIdFor(h))), "to-do ${n}"))
                15 -> {
                    val task = log.lastOrNull { it.kind == 2100 && it.tags.any { t -> t == listOf("h", h) } }
                    if (task == null) emptyList() else listOf(ev(who, 2101, listOf(listOf("h", h), listOf("t", World.listIdFor(h)), listOf("e", task.id), listOf("action", listOf("claim", "working", "done", "undone")[rnd.nextInt(4)]))))
                }
                16 -> listOf(ev(who, 0, emptyList(), """{"name":"Renamed ${n}"}"""))
                17 -> listOf(ev(if (rnd.nextBoolean()) target.pubkey else who, 1010, listOf(listOf("h", h), listOf("e", target.id)), "edited ${n}"))
                18 -> listOf(ev(if (rnd.nextInt(3) > 0) target.pubkey else who, 5, listOf(listOf("e", target.id))))
                19 -> {
                    // a message whose deletion and edit arrive before it does
                    val late = ev(who, 9, listOf(listOf("h", h)), "arrives late ${n}")
                    held.add(late)
                    listOf(ev(who, if (rnd.nextBoolean()) 5 else 1010, listOf(listOf("h", h), listOf("e", late.id)), "early"))
                }
                20 -> if (held.isEmpty()) emptyList() else listOf(held.removeAt(0))
                21 -> listOf(ev(people[1], 1985, listOf(listOf("h", h), listOf("L", "belfry/pin"), listOf("l", if (rnd.nextBoolean()) "pinned" else "unpinned", "belfry/pin"), listOf("e", target.id))))
                22 -> listOf(ev(people[5], 9000, listOf(listOf("h", h), listOf("p", "%064x".format(50 + rnd.nextInt(3))), listOf("invited-by", who), listOf("name", "Guest ${n}"), listOf("unsaved", "1"))))
                23 -> listOf(ev(people[1], 30078, listOf(listOf("d", "belfry:web:$h"), listOf("h", h), listOf("t", "belfry-web")), """{"on":${rnd.nextBoolean()},"slug":"s${n}","visibility":"public"}"""))
                24 -> listOf(ev(who, 9, listOf(listOf("h", h), listOf("a", "31923:${people[1]}:2026-abcdef")), "see https://example.org/events/2026-abcdef"))
                else -> listOf(
                    ev(who, 9, listOf(listOf("h", h)), "two at once ${n}"),
                    ev(who, 7, listOf(listOf("h", channels[rnd.nextInt(3)]), listOf("e", messagesIn(channels[0])[0].id)), "+"),
                )
            }
        }

        repeat(400) { round ->
            val before = carried
            val beforeLists = channels.associateWith { before.messages(it) }
            val added = step()
            log.addAll(added)
            val box = if (round % 37 == 36) listOf(OutboxItem("local-$round", Target.GROUPS, 9, "waiting $round", listOf(listOf("h", "kitchen")), clock + 1)) else emptyList()
            carried = World(ArrayList(log), box, ctx, before)
            val fresh = World(ArrayList(log), box, ctx)
            val a = views(carried)
            val b = views(fresh)
            for ((k, v) in b) assertEquals(v, a[k], "round $round, after ${added.map { it.kind }}: $k")
            if (carried.epoch === before.epoch) keptEpochs++ else newEpochs++
            for (h in channels) if (carried.messages(h) === beforeLists[h]) keptLists++
            // a channel that got something is never taken over
            for (e in added) if (e.kind == 9) e.tags.firstOrNull { it[0] == "h" }?.get(1)?.takeIf { it in channels }?.let { h ->
                assertNotSame(beforeLists[h], carried.messages(h), "round $round: $h got a message")
            }
        }
        // the short cut is really taken, and really refused
        assertTrue(keptLists > 120, "lists taken over: $keptLists")
        assertTrue(keptEpochs > 60, "epochs kept: $keptEpochs")
        assertTrue(newEpochs > 50, "epochs renewed: $newEpochs")
    }

    @Test
    fun theFeedKeyChangesOnlyWithWhatTheRowsShow() {
        val log = base()
        fun next(prev: World?, box: List<OutboxItem> = emptyList()) = World(ArrayList(log), box, ctx, prev)
        var w = next(null)
        val key = w.feedKey("general")
        assertEquals(key, w.feedKey("general"))
        val list = w.messages("general")

        // a message, a reaction, a seen marker, a to-do in another channel: nothing to redraw here
        val there = log.first { it.kind == 9 && it.tags.contains(listOf("h", "kitchen")) }
        log.add(ev(people[3], 9, listOf(listOf("h", "kitchen")), "dinner?"))
        log.add(ev(people[2], 7, listOf(listOf("h", "kitchen"), listOf("e", there.id)), "+"))
        log.add(ev(people[2], 30078, listOf(listOf("d", "seen:kitchen"), listOf("h", "kitchen"))))
        log.add(ev(people[2], 2100, listOf(listOf("h", "kitchen"), listOf("t", World.listIdFor("kitchen"))), "Buy rice"))
        w = next(w)
        assertEquals(key, w.feedKey("general"))
        assertSame(list, w.messages("general"))
        assertNotEquals(key, w.feedKey("kitchen"))
        assertEquals(2, w.messages("kitchen").size)
        assertEquals(1, w.tasks("kitchen").size)

        // here: a reaction to one of its messages
        val here = log.first { it.kind == 9 && it.tags.contains(listOf("h", "general")) }
        log.add(ev(people[2], 7, listOf(listOf("h", "general"), listOf("e", here.id)), "🎉"))
        w = next(w)
        val key2 = w.feedKey("general")
        assertNotEquals(key, key2)

        // someone changes their name, a member is added, a message in a pod, an outbox change: all redraw
        for (e in listOf(
            ev(people[3], 0, emptyList(), """{"name":"Another name"}"""),
            ev(people[5], 9000, listOf(listOf("h", "kitchen"), listOf("p", people[4]))),
            ev(people[3], 9, listOf(listOf("h", "pod-tools-1a")), "in the pod"),
            ev(people[3], 5, listOf(listOf("e", there.id))),
        )) {
            val before = w.feedKey("general")
            log.add(e)
            w = next(w)
            assertNotEquals(before, w.feedKey("general"), "kind ${e.kind}")
        }
        val before = w.feedKey("general")
        w = next(w, listOf(OutboxItem("local-1", Target.GROUPS, 9, "on its way", listOf(listOf("h", "kitchen")), clock + 1)))
        assertNotEquals(before, w.feedKey("general"))
        assertTrue(w.isPending("local-1"))
        // another context (the register answered who is a guest)
        val other = World(ArrayList(log), emptyList(), ctx.copy(kinds = mapOf(people[4] to "guest")), w)
        assertNotEquals(w.feedKey("general"), other.feedKey("general"))
    }
}
