package sh.belfry.core.model

import sh.belfry.core.nostr.Event
import sh.belfry.core.nostr.parseJsonOrNull
import sh.belfry.core.nostr.withId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A channel's log (web ChannelInfo.tsx `logItem`, c6579a0): one line per event, the web's words. */
class ChannelLogTest {
    private val relay = "f".repeat(64)
    private val admin = "a".repeat(64)
    private val member = "b".repeat(64)
    private val now = 1_800_000_000L
    private val g = "garden"

    private fun ev(pk: String, at: Long, kind: Int, tags: List<List<String>>, content: String = ""): Event =
        Event("", pk, at, kind, tags, content).withId()

    private fun world(events: List<Event>) =
        World(events, emptyList(), WorldContext(me = member, domain = "example.org", communityName = "Example", zone = ZoneOffset.UTC))

    private fun line(e: Event, titles: Map<String, String> = emptyMap()): LogItem? = ChannelLog.item(e, titles) { "<" + it.take(2) + ">" }
    private fun h(vararg more: List<String>): List<List<String>> = listOf(listOf("h", g)) + more

    @Test
    fun everyKindHasItsWords() {
        fun words(kind: Int, tags: List<List<String>>, content: String = "") = line(ev(member, now, kind, tags, content))!!.let { it.icon + " " + it.text }
        assertEquals("💬 posted a message", words(9, h(), "Hello   there"))
        assertEquals("⬡ created a pod", words(9, h(listOf("pod", "pod-x")), "⬡ New pod"))
        assertEquals("📄 made a proposal", words(9, h(listOf("proposal", "prop-x"))))
        assertEquals("🖼 posted a photo", words(9, h(listOf("imeta", "url https://x/a.jpg", "m image/jpeg"))))
        assertEquals("🖼 posted 2 photos", words(9, h(listOf("imeta", "url https://x/a.jpg", "m image/jpeg"), listOf("imeta", "url https://x/b.jpg", "m image/png"))))
        assertEquals("🎙 posted a recording", words(9, h(listOf("imeta", "url https://x/a.m4a", "m audio/mp4"))))
        assertEquals("📎 posted a file", words(9, h(listOf("imeta", "url https://x/a.pdf", "m application/pdf"))))
        assertEquals("✉️ an e-mail came in", words(9, h(listOf("mail-from", "x@example.org"), listOf("subject", "Hi"))))
        assertEquals("↩️ replied in a thread", words(1111, h(listOf("E", "root"))))
        assertEquals("✏️ edited a message", words(1010, h(listOf("e", "m1"))))
        assertEquals("🎉 reacted", words(7, h(listOf("e", "m1")), "🎉"))
        assertEquals("👍 reacted", words(7, h(listOf("e", "m1")), "+"))
        assertEquals("👍 reacted", words(7, h(listOf("e", "m1")), ":a-long-custom-emoji:"))
        assertEquals("🗑 took back a reaction", words(5, h(listOf("e", "r1"), listOf("k", "7"))))
        assertEquals("🗑 deleted something", words(5, h(listOf("e", "m1"))))
        assertEquals("🗑 deleted something", words(9005, h(listOf("e", "m1"))))
        assertEquals("☑️ made a todo", words(2100, h(), "Book the room"))
        assertEquals("☑️ took a todo", words(2101, h(listOf("e", "t1"), listOf("action", "claim"))))
        assertEquals("✅ marked a todo done", words(2101, h(listOf("e", "t1"), listOf("action", "done"))))
        assertEquals("☑️ reopened a todo", words(2101, h(listOf("e", "t1"), listOf("action", "undone"))))
        assertEquals("💬 commented on a todo", words(2103, h(listOf("e", "t1")), "On it"))
        assertEquals("👋 added someone", words(9000, h(listOf("p", admin, "member"))))
        assertEquals("👋 made someone an admin", words(9000, h(listOf("p", admin, "admin"))))
        assertEquals("🚪 removed someone", words(9001, h(listOf("p", admin))))
        assertEquals("🙋 asked to join", words(9021, h(), "Because"))
        assertEquals("🚪 left", words(9022, h()))
        assertEquals("⚙️ changed the channel", words(9002, h(listOf("name", "garden"))))
        assertEquals("✨ created the channel", words(9007, h()))
        assertEquals("📌 pinned a message", words(1985, h(listOf("L", "belfry/pin"), listOf("l", "pinned", "belfry/pin"), listOf("e", "m1"))))
        assertEquals("✅ checked someone in", words(1985, h(listOf("L", "belfry/attendance"), listOf("l", "attended", "belfry/attendance"), listOf("p", admin))))
        assertEquals("🏷 marked someone not there", words(1985, h(listOf("L", "belfry/attendance"), listOf("l", "absent", "belfry/attendance"), listOf("p", admin))))
        assertEquals("🏷 labelled something organiser", words(1985, h(listOf("L", "x"), listOf("l", "organiser", "x"))))
        assertEquals("# changed the pinned topics", words(1985, h(listOf("L", "#t"), listOf("l", "garden", "#t"))))
        assertEquals("⚙️ changed a setting", words(30078, h(listOf("d", "belfry:inbox"))))
        assertEquals("📅 set the event", words(31923, h(listOf("d", "2026-abc123"), listOf("title", "Garden day"))))
        assertEquals("👥 set the meeting", words(31923, h(listOf("d", "2026-abc123"), listOf("t", "meeting"))))
        assertEquals("📅 answered going", words(31925, h(listOf("status", "accepted"))))
        assertEquals("📅 answered can't go", words(31925, h(listOf("status", "declined"))))
        assertEquals("📅 answered maybe", words(31925, h(listOf("status", "tentative"))))
        assertEquals("• kind 4242", words(4242, h()))
    }

    @Test
    fun whatIsLeftOutAndWhereALineLeads() {
        // heartbeats and "seen" markers are no news
        assertNull(line(ev(member, now, 2101, h(listOf("e", "t1"), listOf("action", "working")))))
        assertNull(line(ev(member, now, 2101, h(listOf("e", "t1"), listOf("action", "idle")))))
        assertNull(line(ev(member, now, 30078, h(listOf("d", "seen:garden")))))

        val msg = ev(member, now, 9, h(), "  Watering   rota:\nMon Joris ")
        val l = line(msg)!!
        assertEquals("Watering rota: Mon Joris", l.detail)
        assertEquals(LogGo.Message(msg.id), l.go)
        assertEquals(LogGo.Thread("root"), line(ev(member, now, 1111, h(listOf("E", "root"))))!!.go)
        assertEquals(LogGo.Message("m1"), line(ev(member, now, 7, h(listOf("e", "m1")), "🎉"))!!.go)
        assertNull(line(ev(member, now, 7, h(), "🎉"))!!.go)
        assertEquals(LogGo.Todos, line(ev(member, now, 2100, h(), "x"))!!.go)
        assertEquals(LogGo.Members, line(ev(relay, now, 9000, h(listOf("p", admin))))!!.go)
        // a to-do step says which to-do; someone added is named
        assertEquals("Book the room", line(ev(member, now, 2101, h(listOf("e", "t1"), listOf("action", "done"))), mapOf("t1" to "Book the room"))!!.detail)
        assertEquals("<aa>", line(ev(relay, now, 9000, h(listOf("p", admin))))!!.detail)
        assertEquals("garden · Beds and compost", line(ev(admin, now, 9002, h(listOf("name", "garden"), listOf("about", "Beds and compost"))))!!.detail)
        // cut on one line
        assertEquals(60, ChannelLog.short("x".repeat(100), 60).length)
        assertTrue(ChannelLog.short("x".repeat(100), 60).endsWith("…"))
        assertEquals("a b", ChannelLog.short(" a \n\t b "))
    }

    @Test
    fun theLogOfAChannelNewestFirst() {
        val made = ev(admin, now - 5000, 9007, h())
        val meta = ev(relay, now - 4999, 39000, listOf(listOf("d", g), listOf("name", "garden")))
        val joined = ev(relay, now - 4990, 9000, h(listOf("p", member)))
        val todo = ev(member, now - 4000, 2100, h(listOf("t", World.listIdFor(g))), "Book the room")
        val done = ev(admin, now - 3000, 2101, h(listOf("t", World.listIdFor(g)), listOf("e", todo.id), listOf("action", "done")))
        val beat = ev(admin, now - 3500, 2101, h(listOf("t", World.listIdFor(g)), listOf("e", todo.id), listOf("action", "working")))
        val seen = ev(member, now - 2000, 30078, h(listOf("d", "seen:$g")))
        val elsewhere = ev(member, now - 1000, 9, listOf(listOf("h", "kitchen")), "Soup")
        val msg = ev(member, now - 500, 9, h(), "Hello")
        val w = world(listOf(msg, made, meta, joined, todo, beat, done, seen, elsewhere))
        val log = w.channelLog(g)
        assertEquals(listOf(msg.id, done.id, todo.id, joined.id, made.id), log.map { it.ev.id })
        assertEquals("Book the room", log[1].detail)
        // the relay signs joins itself: its key reads as the community
        assertEquals("Example", w.logWho(g, relay))
        assertEquals(w.displayName(admin), w.logWho(g, admin))
        assertEquals(2, w.channelLog(g, limit = 2).size)
        assertTrue(w.channelLog("nowhere").isEmpty())
    }

    @Test
    fun theLatestTodosOpenOnesFirst() {
        fun task(id: String, at: Long, done: Boolean) = Task(id, id, member, null, done, false, emptyList(), at = at)
        val tasks = (1..25).map { task("t$it", now + it, done = it % 2 == 0) }
        val latest = latestTasks(tasks)
        assertEquals(20, latest.size)
        // the 20 newest (t6…t25): the open ones first, newest first in each
        assertEquals("t25", latest.first().id)
        assertEquals(listOf(false, true), latest.map { it.done }.distinct())
        assertEquals("t24", latest.first { it.done }.id)
        assertTrue(latest.none { it.id == "t5" })
    }

    @Test
    fun anEventAsIndentedJson() {
        val e = ev(member, now, 9, h(listOf("t", "garden")), "He said \"hi\"\n")
        val back = Event.fromJson(parseJsonOrNull(e.prettyJson())!!)
        assertNotNull(back)
        assertEquals(e.id, back.id)
        assertEquals(e.tags, back.tags)
        assertEquals(e.content, back.content)
        assertTrue(e.prettyJson().contains("\n  \"kind\": 9,\n"))
        assertTrue(ev(member, now, 9, emptyList()).prettyJson().contains("\"tags\": [],"))
    }
}
