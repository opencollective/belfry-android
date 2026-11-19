package sh.belfry.core.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import sh.belfry.core.Vectors
import sh.belfry.core.net.ApplyStatus
import sh.belfry.core.net.JoinsAs
import sh.belfry.core.net.MembershipSettings
import sh.belfry.core.net.PublicAgreement
import sh.belfry.core.net.Role
import sh.belfry.core.net.RolesInfo
import sh.belfry.core.nostr.Event
import sh.belfry.core.nostr.parseJsonOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

private fun JsonElement?.o(): JsonObject = this as? JsonObject ?: JsonObject(emptyMap())
private fun JsonElement?.list(): List<JsonObject> = (this as? JsonArray)?.map { it.o() } ?: emptyList()
private fun JsonElement?.s(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content
private fun JsonElement?.strs(): List<String> = (this as? JsonArray)?.map { (it as JsonPrimitive).content } ?: emptyList()
private fun JsonElement?.long(): Long? = (this as? JsonPrimitive)?.longOrNull
private fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.intOrNull
private fun JsonElement?.events(): List<Event> = (this as? JsonArray)?.map { Event.fromJson(it) ?: fail("bad event in vector") } ?: emptyList()
private fun cats(v: JsonElement?): List<ChannelCategory> = v.list().map { ChannelCategory(it["id"].s() ?: "", it["name"].s() ?: "", it["channels"].strs()) }

private val ME = "a".repeat(64)
private val ANA = "b".repeat(64)
private val OTHER = "c".repeat(64)
private val RELAY = "d".repeat(64)
private val GROUPS_KEY = "9".repeat(64)
private var n = 0
private fun ev(pk: String, kind: Int, at: Long, tags: List<List<String>>, c: String = ""): Event {
    n += 1
    return Event(String.format("%064x", 70000 + n), pk, at, kind, tags, c)
}
private fun meta(id: String, name: String, at: Long = 100) = ev(RELAY, 39000, at, listOf(listOf("d", id), listOf("name", name)))
private fun w(events: List<Event>, absent: Map<String, Long> = emptyMap(), admins: Set<String> = emptySet(), me: String = ME, placeNames: Boolean = false, groupsKey: String? = null) =
    World(events, emptyList(), WorldContext(me = me, communityAdmins = admins, groupsAbsent = absent, placeNames = placeNames, groupsKey = groupsKey))

/** SPEC 19.29–19.34 (vectors charter / mailthreads / categories, and the iPhone app's core tests ported). */
class ParityTest {
    // ---- 19.32 the charter ----

    @Test
    fun charterVector() {
        val v = Vectors.load("charter.json")
        assertEquals(v["list_id"].s(), Charter.LIST_ID)
        val evs = v["events"].events()
        val admins = v["admins"].strs().toSet()
        val got = Charter.parse(evs, admins)
        val want = v["expected"].list()
        assertEquals(want.map { it["id"].s() }, got.map { it.id })
        for ((g, x) in got.zip(want)) {
            assertEquals(x["title"].s(), g.title)
            assertEquals(x["about"].s(), g.about)
            assertEquals(x["order"].int(), g.order, g.title)
            assertEquals(x["textAt"].long(), g.textAt, g.title)
            assertEquals(x["agreed"].o().mapValues { (it.value as JsonPrimitive).longOrNull ?: 0L }, g.agreed, g.title)
            assertEquals(x["stale"].strs().toSet(), g.stale, g.title)
        }
        val me = v["me"].s()!!
        val world = World(evs, emptyList(), WorldContext(me = me, communityAdmins = admins))
        assertEquals(v["me_to_agree"].strs(), world.charterToAgree().map { it.id })
        assertEquals(v["me_agreed_at"].long(), world.charterAgreedAt())
    }

    @Test
    fun charterActionsAndNames() {
        val add = Charter.add("  Be kind ", "", 3)
        assertEquals(2100, add.kind)
        assertEquals("Be kind", add.content)
        assertTrue(listOf("each", "1") in add.tags)
        assertTrue(listOf("h", "charter") in add.tags)
        assertTrue(listOf("order", "3") in add.tags)
        assertFalse(add.tags.any { it.firstOrNull() == "about" })
        assertTrue(listOf("action", "done") in Charter.agree("x", true).tags)
        assertTrue(listOf("action", "undone") in Charter.agree("x", false).tags)
        val edit = Charter.edit("x", "Be kind", "Always", 1)
        assertTrue(listOf("action", "edit") in edit.tags)
        assertTrue(listOf("about", "Always") in edit.tags)
        assertEquals(listOf(9007, 9002), Charter.create().map { it.kind })

        assertEquals("Townsquare", PlaceNames.display("general", "general"))
        assertEquals("Townsquare", PlaceNames.display("general", ""))
        assertEquals("Townsquare", PlaceNames.display("general", "#General"))
        assertNull(PlaceNames.display("events", "#Events")) // events / proposals / website keep their own names (web, 2026-11-17)
        assertNull(PlaceNames.display("general", "Town square"))
        assertNull(PlaceNames.display("garden-3f2a", "garden-3f2a"))
        assertEquals("general", PlaceNames.wire("general", "Townsquare"))
        assertEquals("general", w(listOf(meta("general", "general"))).group("general")?.name)
        assertEquals("Townsquare", w(listOf(meta("general", "general")), placeNames = true).group("general")?.name)
        assertEquals("general", w(listOf(meta("general", "general")), placeNames = true).group("general")?.rawName)
        // the charter group is never a town channel
        val t = w(listOf(meta("general", "general"), meta("charter", "Charter")))
        assertFalse(t.townChannels().any { it.id == "charter" })
        assertNotNull(t.charterGroup)
    }

    // ---- 19.33 e-mail threads ----

    @Test
    fun mailThreadsVector() {
        val v = Vectors.load("mailthreads.json")
        val group = v["group"].s()!!
        val world = World(v["events"].events(), emptyList(), WorldContext(me = v["me"].s()!!, groupsKey = v["groups_key"].s()))
        val got = world.mailThreads(group).list
        val want = v["threads"].list()
        assertEquals(want.map { it["id"].s() }, got.map { it.id })
        for ((g, x) in got.zip(want)) {
            assertEquals(x["ids"].strs(), g.ids)
            assertEquals(x["address"].s(), g.address)
            assertEquals(x["name"].s(), g.name)
            assertEquals(x["subject"].s(), g.subject)
            assertEquals(x["status"].s(), when (g.state.status) { CardStatus.OPEN -> "open"; CardStatus.WAITING -> "waiting"; CardStatus.CLOSED -> "closed" })
            assertEquals(x["answered"].int(), g.state.answered)
            assertEquals(x["assignee"].s(), g.state.assignee)
            assertEquals(x["replies"].int(), g.replies)
            assertEquals(x["who"].strs(), g.who)
            assertEquals(x["last"].long(), g.last)
            assertEquals(x["refs"].strs(), g.refs)
            assertEquals(x["lastIn"].s(), g.lastIn?.m?.id)
        }
        assertEquals(v["feed"].strs(), world.feed(group).map { it.key })
        // without the groups key nothing is an e-mail from outside
        assertTrue(World(v["events"].events(), emptyList(), WorldContext(me = v["me"].s()!!)).mailThreads(group).list.isEmpty())
    }

    @Test
    fun mailLocal() {
        val admin = ME
        val evs = listOf(
            meta("hello-79f0", "Hello"), ev(admin, 9007, 1, listOf(listOf("h", "hello-79f0"))),
            meta("bugs-c3e5", "Bugs"), ev(OTHER, 9007, 1, listOf(listOf("h", "bugs-c3e5"))),
            meta("team-aaaa", "Team"), ev(admin, 9007, 1, listOf(listOf("h", "team-aaaa"))),
            meta("team-bbbb", "Team"), ev(admin, 9007, 1, listOf(listOf("h", "team-bbbb"))),
            meta("general", "general"),
        )
        val d = World(evs, emptyList(), WorldContext(me = admin, domain = "x.org", communityAdmins = setOf(admin), placeNames = true))
        assertEquals("hello", d.mailLocal("hello-79f0"))
        assertEquals("hello@x.org", d.mailAddress("hello-79f0"))
        assertEquals("bugs-c3e5", d.mailLocal("bugs-c3e5")) // not made by an admin
        assertEquals("team-aaaa", d.mailLocal("team-aaaa")) // two answer to "team"
        assertEquals("general", d.mailLocal("general"))
        assertEquals("cafe-ete", World.slugify("Café Été!"))
    }

    // ---- 19.34 categories ----

    @Test
    fun categoriesRead() {
        val v = Vectors.load("categories.json")
        for (r in v["read"].list()) {
            val name = r["name"].s() ?: ""
            val got = Categories.arrange(ChannelLayout.parse(r["content"].s()), r["channels"].strs())
            val want = cats(r["expected"])
            assertEquals(want.map { it.id }, got.map { it.id }, name)
            assertEquals(want.map { it.name }, got.map { it.name }, name)
            assertEquals(want.map { it.channels }, got.map { it.channels }, name)
            assertEquals(r["shown_member"].strs(), Categories.shown(got, admin = false).map { it.id }, name)
            assertEquals(r["shown_admin"].strs(), Categories.shown(got, admin = true).map { it.id }, name)
        }
    }

    @Test
    fun categoriesWrite() {
        val v = Vectors.load("categories.json")
        for (x in v["write"].list()) {
            val name = x["name"].s() ?: ""
            val got = Categories.encode(cats(x["categories"]), ChannelLayout.parse(x["previous"].s()), x["known"].strs().toSet())
            val want = x["expected"].o()
            assertEquals(want["order"].strs(), got["order"].strs(), name)
            val gc = got["categories"].list()
            val wc = want["categories"].list()
            assertEquals(wc.size, gc.size, name)
            for ((g, e) in gc.zip(wc)) {
                assertEquals(e["id"].s(), g["id"].s(), name)
                assertEquals(e["name"].s(), g["name"].s(), name)
                assertEquals(e["channels"].strs(), g["channels"].strs(), name)
            }
            val back = ChannelLayout.parse(got.toString())
            assertEquals<List<String?>?>(wc.map { it["id"].s() }, back.categories?.map { it.id }, name)
        }
    }

    @Test
    fun categoriesFlatAndHelpers() {
        val v = Vectors.load("categories.json")
        for (f in v["flat"].list()) {
            val got = Categories.rebuild(cats(f["categories"]), f["flat"].strs().mapNotNull { CategoryRow.of(it) })
            val want = cats(f["expected"])
            assertEquals(want.map { it.id }, got.map { it.id })
            assertEquals(want.map { it.channels }, got.map { it.channels }, f["name"].s() ?: "")
        }
        val base = listOf(ChannelCategory("life", "Community life", listOf("a", "b")), ChannelCategory("making-1234", "Making", listOf("c")), ChannelCategory("building", "Building together", emptyList()))
        assertEquals(listOf(listOf("b"), listOf("c", "a"), emptyList()), Categories.put("a", "making-1234", base).map { it.channels })
        assertEquals(listOf(listOf("a", "b"), listOf("c"), listOf("new")), Categories.put("new", "building", base).map { it.channels })
        assertEquals(listOf("life", "building"), Categories.remove("making-1234", base).map { it.id })
        assertEquals(listOf("a", "b", "c"), Categories.remove("making-1234", base).first().channels)
        assertEquals(base.map { it.id }, Categories.remove("building", base).map { it.id })
        val id = Categories.newId("Making & mending!")
        assertTrue(id.startsWith("making-mending-"), id)
        assertEquals("making-mending-".length + 4, id.length)
        assertTrue(Categories.newId("???").startsWith("category-"))
    }

    @Test
    fun categoriesOnlyFromAdmins() {
        val evs = listOf("general", "garden", "kitchen", "proposals").map { meta(it, it) } + listOf(
            ev(ANA, 30078, 200, listOf(listOf("d", "belfry:channel-order")), """{"order":["general","kitchen","garden"],"categories":[{"id":"food-aaaa","name":"Food","channels":["kitchen"]}]}"""),
            ev(OTHER, 30078, 300, listOf(listOf("d", "belfry:channel-order")), """{"order":[],"categories":[{"id":"x-bbbb","name":"X","channels":["garden"]}]}"""),
        )
        val d = w(evs, admins = setOf(ANA), me = OTHER)
        val c = d.channelCategories()
        assertEquals(listOf("life", "food-aaaa", "building"), c.map { it.id })
        assertEquals(listOf(listOf("garden"), listOf("kitchen"), listOf("proposals")), c.map { it.channels })
        assertEquals(listOf("general", "kitchen", "garden"), d.channelOrder())
    }

    // ---- the attention line (iOS 2026-11-17) ----

    @Test
    fun attentionLine() {
        val base = listOf(meta("garden", "garden"), ev(OTHER, 9, 200, listOf(listOf("h", "garden")), "hello"))
        assertNull(w(base).attentionLine("garden", 1000), "normal times: no line")
        assertEquals(LineIcon.TOPIC, w(base).attentionLine("garden", 1000, topic = "seeds")?.icon)
        val task = ev(OTHER, 2100, 300, Actions.addTask("garden", "Water the beds").tags, "Water the beds")
        val free = w(base + task)
        assertEquals(LineIcon.TODO, free.attentionLine("garden", 1000)?.icon)
        assertEquals(LineAction.Todos, free.attentionLine("garden", 1000)?.action)
        assertEquals("1 to-do", free.attentionLine("garden", 1000)?.bold)
        assertEquals("1 unclaimed", free.attentionLine("garden", 1000)?.text)
        // canvas channels/header-todos: the parts, a part at 0 left out, the total = the sum
        val task2 = ev(OTHER, 2100, 310, Actions.addTask("garden", "Turn the compost").tags, "Turn the compost")
        val task3 = ev(OTHER, 2100, 320, Actions.addTask("garden", "Fix the gate").tags, "Fix the gate")
        val mixed = w(base + task + task2 + task3 + ev(OTHER, 2101, 400, Actions.taskAction("garden", task2.id, "claim").tags) +
            ev(OTHER, 2101, 500, Actions.taskAction("garden", task3.id, "done").tags))
        val line = mixed.attentionLine("garden", 1000)!!
        assertEquals("3 to-dos", line.bold)
        assertEquals("1 unclaimed, 1 in progress, 1 closed today", line.text)
        assertTrue(line.twoLines)
        // done yesterday (the viewer's midnight, UTC here) is not "closed today"
        val old = w(base + task3 + ev(OTHER, 2101, 500, Actions.taskAction("garden", task3.id, "done").tags))
        assertNull(old.attentionLine("garden", 90_000), "all parts at 0: no line")
        // a claimer whose latest step is idle (agents send it on 2101) is not in progress
        val idle = w(base + task2 + ev(OTHER, 2101, 400, Actions.taskAction("garden", task2.id, "claim").tags) +
            ev(OTHER, 2101, 450, Actions.taskAction("garden", task2.id, "idle").tags))
        assertEquals("1 unclaimed", idle.attentionLine("garden", 1000)?.text, "claimed and idle: back to up next, unclaimed while that part is off")
        val back = w(base + task2 + ev(OTHER, 2101, 400, Actions.taskAction("garden", task2.id, "claim").tags) +
            ev(OTHER, 2101, 450, Actions.taskAction("garden", task2.id, "idle").tags) + ev(OTHER, 2101, 460, Actions.taskAction("garden", task2.id, "working").tags))
        assertEquals("1 in progress", back.attentionLine("garden", 1000)?.text)
        // assigned, not started: unclaimed until "up next" is confirmed (canvas v35)
        val assigned = w(base + task2 + ev(OTHER, 2101, 400, Actions.assignTask("garden", task2.id, ME, OTHER).tags))
        assertEquals("1 unclaimed", assigned.attentionLine("garden", 1000)?.text)
    }

    // ---- 19.30 deleted and archived ----

    private val pod = "pod-canal-41ec"
    private fun card(text: String, at: Long = 200) = ev(ANA, 9, at, listOf(listOf("h", "general"), listOf("pod", pod)), text)

    @Test
    fun deletedBy9008() {
        val d = w(listOf(meta("general", "general"), meta(pod, "Canal"), ev(ANA, 9007, 90, listOf(listOf("h", pod))), card("⬡ New pod: Canal · Along the water"),
            ev(ANA, 9, 300, listOf(listOf("h", pod), listOf("p", ME)), "hello"), ev(ANA, 9008, 400, listOf(listOf("h", pod)))))
        assertEquals(GroupState.DELETED, d.groupState(pod))
        assertNull(d.group(pod))
        assertFalse(d.allGroups.any { it.id == pod })
        assertTrue(d.pods("general").isEmpty())
        assertEquals(1, d.messages("general").size)
        assertTrue(pod in d.lockedGroups)
        assertEquals(GroupState.OPEN, d.groupState("general"))
    }

    @Test
    fun absentIsUnknownUntilTheRelayAnswered() {
        val events = listOf(meta("general", "general"), card("⬡ New pod: Canal"))
        val before = w(events)
        assertEquals(GroupState.UNKNOWN, before.groupState(pod))
        assertEquals(listOf(pod), before.presenceCandidates())
        assertNull(before.presenceAfter(listOf(pod), emptyList()))
        val absent = before.presenceAfter(listOf(pod), listOf(meta("general", "general")))
        assertEquals(mapOf(pod to 0L), absent)
        val after = w(events, absent = absent!!)
        assertEquals(GroupState.DELETED, after.groupState(pod))
        assertEquals(GroupState.UNKNOWN, after.groupState("pod-other-0000"))
        // a group known from its own metadata is live: never asked about, a stored mark is ignored (web groupstate.ts)
        val ev2 = listOf(meta("general", "general"), meta("garden", "garden", 100), ev(ANA, 9007, 90, listOf(listOf("h", "garden"))))
        assertFalse("garden" in w(ev2).presenceCandidates())
        assertEquals(emptyMap<String, Long>(), w(ev2).presenceAfter(listOf("garden"), listOf(meta("general", "general")))!!)
        assertEquals(GroupState.OPEN, w(ev2, absent = mapOf("garden" to 100L)).groupState("garden"))
        assertEquals(GroupState.OPEN, w(ev2, absent = mapOf("general" to 999L)).groupState("general"))
        // the card-only group: its metadata arriving later clears the mark
        assertEquals(GroupState.OPEN, w(events + meta(pod, "Canal", 50), absent = absent).groupState(pod))
    }

    @Test
    fun archivedByTheLatest9002() {
        val base = listOf(
            meta("general", "general"), meta(pod, "Canal"), ev(ME, 9007, 90, listOf(listOf("h", pod))),
            ev(ME, 9002, 95, listOf(listOf("h", pod), listOf("name", "Canal"), listOf("about", "Along the water"), listOf("closed"), listOf("prompt", "Why?"), listOf("vouches", "2"))),
            card("⬡ New pod: Canal"), ev(ANA, 9, 300, listOf(listOf("h", pod)), "boats"),
            ev(ME, 9002, 400, listOf(listOf("h", pod), listOf("name", "Canal"), listOf("about", "Along the water"), listOf("closed"), listOf("prompt", "Why?"), listOf("vouches", "2"), listOf("archived", "1"))),
        )
        val d = w(base)
        assertEquals(GroupState.ARCHIVED, d.groupState(pod))
        assertFalse(d.groups.any { it.id == pod })
        assertEquals("Canal", d.group(pod)?.name)
        assertEquals(2, d.group(pod)?.vouches)
        assertEquals(listOf(pod), d.myArchived().map { it.id })
        assertEquals(listOf(pod), d.search("boats").map { it.groupId })
        assertTrue(d.mayArchive(d.group(pod)))
        assertFalse(d.mayArchive(d.group("general")))
        val g = d.group(pod)!!
        assertEquals(listOf("archived", "0"), Actions.archive(g, false).tags.last())
        assertEquals(listOf("archived", "1"), Actions.groupMeta(g, name = "Quay").tags.last())
        assertTrue(listOf("vouches", "2") in Actions.groupMeta(g, name = "Quay").tags)
        val open = w(base + ev(ME, 9002, 600, listOf(listOf("h", pod), listOf("name", "Canal"), listOf("about", ""), listOf("archived", "0"))))
        assertEquals(GroupState.OPEN, open.groupState(pod))
        val gen = w(base + ev(ME, 9002, 800, listOf(listOf("h", "general"), listOf("name", "general"), listOf("archived", "1"))))
        assertEquals(GroupState.OPEN, gen.groupState("general"))
        // nothing is queued for a locked group, except unarchiving
        val locked = setOf(pod)
        assertTrue(GroupLock.refuses(Draft(sh.belfry.core.outbox.Target.GROUPS, 9, "hi", listOf(listOf("h", pod))), locked))
        assertFalse(GroupLock.refuses(Draft(sh.belfry.core.outbox.Target.GROUPS, 9002, "", listOf(listOf("h", pod), listOf("archived", "0"))), locked))
        assertFalse(GroupLock.refuses(Draft(sh.belfry.core.outbox.Target.GROUPS, 9, "hi", listOf(listOf("h", "general"))), locked))
        assertEquals("Canal", GroupLock.cardName("⬡ New pod: Canal · Along the water"))
        assertEquals("Seed swap", GroupLock.cardName("⬡ New group: Seed swap"))
        assertNull(GroupLock.cardName("Come to the canal"))
        assertNull(GroupLock.cardName("⬡ New pod: "))
        val q = GroupPresence.questions((0 until 150).map { "pod-$it" } + "general")
        assertEquals(2, q.size)
        assertEquals("general", q[0][0])
        assertEquals(101, q[0].size)
    }

    // ---- 19.29 roles in a group, check-ins ----

    @Test
    fun groupRolesAndCheckIns() {
        val ev0 = "ev-20261120-abcd"
        val evs = listOf(
            meta(ev0, "Repair café"), ev(ANA, 9007, 90, listOf(listOf("h", ev0))),
            ev(GROUPS_KEY, 9000, 100, listOf(listOf("h", ev0), listOf("p", OTHER, "admin"))),
            ev(GROUPS_KEY, 1985, 110, listOf(listOf("h", ev0), listOf("L", GroupRoles.ROLE_NS), listOf("l", "steward", GroupRoles.ROLE_NS), listOf("p", ME))),
            ev(ANA, 1985, 120, listOf(listOf("h", ev0), listOf("L", GroupRoles.ROLE_NS), listOf("l", "none", GroupRoles.ROLE_NS), listOf("p", ME))), // not the groups key: ignored
            ev(GROUPS_KEY, 1985, 130, listOf(listOf("h", ev0), listOf("L", GroupRoles.TICKET_NS), listOf("l", "checked-in", GroupRoles.TICKET_NS), listOf("p", OTHER))),
            ev(GROUPS_KEY, 1985, 140, listOf(listOf("h", ev0), listOf("L", GroupRoles.TICKET_NS), listOf("l", "not-checked-in", GroupRoles.TICKET_NS), listOf("p", OTHER))),
        )
        val d = w(evs, groupsKey = GROUPS_KEY)
        assertEquals(setOf(ME), d.stewards(ev0))
        assertEquals(GroupRole.STEWARD, d.groupRole(ev0, ME))
        assertEquals(GroupRole.ADMIN, d.groupRole(ev0, OTHER))
        assertEquals(GroupRole.ADMIN, d.groupRole(ev0, ANA))
        assertTrue(d.checkIns(ev0).isEmpty())
        assertEquals(false, d.ticketLabels(ev0)[OTHER]?.first)
        assertEquals(setOf(ANA), d.group(ev0)?.relayAdmins)
        // a later 9000 with role member ends an admin by role
        val d2 = w(evs + ev(GROUPS_KEY, 9000, 200, listOf(listOf("h", ev0), listOf("p", OTHER), listOf("role", "member"))), groupsKey = GROUPS_KEY)
        assertEquals(GroupRole.MEMBER, d2.groupRole(ev0, OTHER))
        // the latest 39001 replaces the one before
        val d3 = w(evs + ev(RELAY, 39001, 300, listOf(listOf("d", ev0), listOf("p", RELAY))) + ev(RELAY, 39001, 400, listOf(listOf("d", ev0), listOf("p", ME))), groupsKey = GROUPS_KEY)
        assertEquals(setOf(ME, ANA), d3.group(ev0)?.relayAdmins)

        assertEquals("abcdefghijklmnop", TicketScan.code("https://chat.x.org/events/repair/t/abcdefghijklmnop"))
        assertEquals("abcdefghijklmnop", TicketScan.code("https://chat.x.org/#/events/repair/t/abcdefghijklmnop"))
        assertNull(TicketScan.code("https://chat.x.org/events/repair/t/ABC"))
        assertNull(TicketScan.code("hello"))
        assertEquals(Triple("x.org", "repair-cafe", "abcdefghijklmnop"), TicketScan.eventLink("https://chat.x.org/events/repair-cafe/t/abcdefghijklmnop"))
        assertEquals(Triple("x.org", "repair-cafe", null), TicketScan.eventLink("https://chat.x.org/events/repair-cafe"))
        assertNull(TicketScan.eventLink("https://x.org/events/repair-cafe"))
    }

    // ---- roles, membership, links, a guest's request ----

    @Test
    fun rolesMembershipAndLinks() {
        val r = RolesInfo.parse(parseJsonOrNull("""{"roles":[{"id":"admin","label":"Admin"},{"id":"gardener","label":"Gardener","description":"Garden"},{"id":"member","label":"Member"}],"default":"admin","members":{"${ANA}":"gardener","bad":"x"}}""") as JsonObject)
        assertEquals(listOf("member", "gardener", "admin"), r.roles.map { it.id })
        assertEquals("Steward", r.role("admin")?.label)
        assertEquals("member", r.defaultRole)
        assertEquals("Gardener", r.label(ANA))
        assertEquals(1, r.members.size)
        assertEquals(RolesInfo.parse(r.toJson()), r)
        assertEquals(listOf("member", "admin"), RolesInfo.parse(JsonObject(emptyMap())).roles.map { it.id })

        val m = MembershipSettings.parse(parseJsonOrNull("""{"invite":"members","accept":"vouches","vouches":9}""") as JsonObject)
        assertEquals("members", m.invite)
        assertEquals(5, m.vouches)

        val g = JoinsAs.Guest(7).body("garden", 50, 7)
        assertEquals("7", (g["guest_days"] as JsonPrimitive).content)
        assertEquals("50", (g["people"] as JsonPrimitive).content)
        val a = JoinsAs.Agent.body("general", 50, 3)
        assertEquals("1", (a["people"] as JsonPrimitive).content)
        assertEquals("1", (a["link_days"] as JsonPrimitive).content)
        assertEquals("true", (a["agent"] as JsonPrimitive).content)
        val mm = JoinsAs.Member(Role.ADMIN, "forever").body("general", 50, 30)
        assertEquals("member", (mm["as"] as JsonPrimitive).content)
        assertEquals("permanent", (mm["membership"] as JsonPrimitive).content)
        assertEquals("1", (mm["people"] as JsonPrimitive).content)
        assertEquals("Guest for a week" to "#garden only, for a week", JoinsAs.Guest(7).line("#garden", "X", r))

        val s = ApplyStatus.parse(parseJsonOrNull("""{"application":{"id":3,"state":"new","vouches":1,"needed":2,"vouched_by":[{"pubkey":"x","name":"Dev"}]},"until":1700000000,"accept":"vouches"}""") as JsonObject)
        assertEquals("1 of 2 vouches", s.line)
        assertEquals("Dev", s.application?.vouchedBy?.first()?.second)
        assertEquals(1700000000L, s.until)
        assertEquals(s, ApplyStatus.parse(s.toJson()))
        assertEquals("Not this time", ApplyStatus.parse(parseJsonOrNull("""{"application":{"state":"declined"}}""") as JsonObject).line)
        val list = PublicAgreement.parseList(parseJsonOrNull("""{"agreements":[{"id":"${"1".repeat(64)}","title":"Be kind","about":"","order":null,"added":5,"text_at":7},{"id":"bad","title":"x"}]}""") as JsonObject)
        assertEquals(1, list.size)
        assertNull(list[0].order)
        assertEquals(7L, list[0].agreement.textAt)
    }

    // ---- vouches ----

    @Test
    fun groupVouches() {
        val g = "pod-seeds-1234"
        val apply = ev(OTHER, 9021, 300, listOf(listOf("h", g)), "please")
        val evs = listOf(
            meta(g, "Seeds"), ev(ANA, 9007, 90, listOf(listOf("h", g))),
            ev(ANA, 9002, 91, listOf(listOf("h", g), listOf("name", "Seeds"), listOf("about", ""), listOf("closed"), listOf("prompt", "Why?"), listOf("vouches", "2"))),
            ev(ANA, 9000, 100, listOf(listOf("h", g), listOf("p", ME))),
            apply,
        )
        val d = w(evs)
        val grp = d.group(g)!!
        assertEquals(2, grp.vouches)
        assertEquals(1, d.groupRequestsFor(grp).size)
        assertEquals(1, d.groupRequestCount())
        val v = Actions.vouch(g, apply.id, OTHER)
        val d2 = w(evs + ev(ME, v.kind, 400, v.tags))
        assertEquals(listOf(ME), d2.vouchesFor(d2.group(g)!!, d2.group(g)!!.applications.first()))
        assertEquals(0, d2.groupRequestCount())
    }
}
