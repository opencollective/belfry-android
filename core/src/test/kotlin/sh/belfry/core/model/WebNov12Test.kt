package sh.belfry.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import sh.belfry.core.net.Branding
import sh.belfry.core.net.Community
import sh.belfry.core.nostr.Event
import sh.belfry.core.nostr.withId
import sh.belfry.core.outbox.Outbox
import sh.belfry.core.outbox.OutboxItem
import sh.belfry.core.outbox.OutboxStatus
import sh.belfry.core.outbox.Target
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the web chat added on 2026-11-11 / 12 (chat-source up to c6579a0), as the core reads it. */
class WebNov12Test {
    private val admin = "a".repeat(64)
    private val member = "b".repeat(64)
    private val agent = "c".repeat(64)
    private val now = 1_800_000_000L
    private val g = "garden"

    private fun ev(pk: String, at: Long, kind: Int, tags: List<List<String>>, content: String = ""): Event =
        Event("", pk, at, kind, tags, content).withId()
    private fun ev(pk: String, at: Long, d: Draft): Event = ev(pk, at, d.kind, d.tags, d.content)
    private fun world(events: List<Event>, me: String = member) =
        World(events, emptyList(), WorldContext(me = me, domain = "example.org", communityName = "Example", zone = ZoneOffset.UTC))
    private fun channel(id: String, name: String, at: Long = now - 9000): List<Event> = listOf(
        ev(admin, at, 9007, listOf(listOf("h", id))),
        ev(admin, at + 1, 39000, listOf(listOf("d", id), listOf("name", name))),
    )

    @Test
    fun aToDoBeingWorkedOnNow() {
        val events = channel(g, "garden").toMutableList()
        val task = ev(member, now - 4000, Actions.addTask(g, "Mend the door")).also(events::add)
        fun beat(who: String, ago: Long, action: String = "working") = events.add(ev(who, now - ago, Actions.taskAction(g, task.id, action)))
        fun t() = world(events).tasks(g).single()

        assertNull(t().beat)
        assertFalse(t().isWorking(now))
        // a heartbeat from someone who has not taken it does not count
        beat(agent, 100)
        assertNull(t().beat)
        events.add(ev(agent, now - 3000, Actions.taskAction(g, task.id, "claim")))
        // …and now it does: the agent's latest beat, the stretch began with the first of the run
        events.removeAt(events.size - 2)
        for (ago in listOf(840L, 600, 360, 120)) beat(agent, ago)
        assertEquals(now - 120, t().beat)
        assertEquals(now - 840, t().since)
        assertTrue(t().isWorking(now))
        assertTrue(world(events).todoWho(t(), now).endsWith(" is working on it · 14 min"))
        assertEquals("You are working on it · 14 min", world(events, me = agent).todoWho(t(), now))
        // without the time: as before
        assertTrue(world(events, me = agent).todoWho(t()).endsWith("You are on it"))
        // five minutes without a beat: not working any more
        assertFalse(t().isWorking(now + 181))
        // a pause of more than five minutes starts a new stretch
        events.removeAll { it.kind == 2101 && it.tags.any { t -> t == listOf("action", "working") } }
        for (ago in listOf(3000L, 2900, 400, 200)) beat(agent, ago)
        assertEquals(now - 400, t().since)
        // idle stops it; done too
        beat(agent, 100, "idle")
        assertNull(t().beat)
        beat(agent, 50)
        assertEquals(now - 50, t().since)
        beat(agent, 10, "done")
        assertFalse(t().isWorking(now))
        // someone else takes it over: the old assignee's beats say nothing
        events.add(ev(member, now - 5, Actions.taskAction(g, task.id, "undone")))
        events.add(ev(member, now - 4, Actions.taskAction(g, task.id, "claim")))
        assertNull(t().beat)

        assertEquals("1 min", Task.stretch(20))
        assertEquals("59 min", Task.stretch(59 * 60))
        assertEquals("1 h", Task.stretch(3600))
        assertEquals("1 h 05", Task.stretch(3900))
        assertEquals("2 h 20", Task.stretch(2 * 3600 + 20 * 60))
    }

    @Test
    fun aChannelsShownMailAddressIsItsPlainSlug() {
        assertEquals("cafe-des-amis", World.slugify("  Café des Amis! "))
        assertEquals("a".repeat(40), World.slugify("a".repeat(60)))
        assertEquals("", World.slugify("…"))
        val events = (channel("bugs-c3e5", "Bugs") + channel("general", "general") + channel("garden-ab12", "Garden") + channel("garden-zz99", "garden")).toMutableList()
        val w = world(events)
        assertEquals("bugs", w.mailLocal(w.group("bugs-c3e5")!!))
        assertEquals("bugs@example.org", w.shownMailAddress(w.group("bugs-c3e5")!!))
        assertEquals("general", w.mailLocal(w.group("general")!!))
        // two channels answer to "garden": each keeps its full id
        assertEquals("garden-ab12", w.mailLocal(w.group("garden-ab12")!!))
        assertEquals("garden-zz99", w.mailLocal(w.group("garden-zz99")!!))
        // answers still go out as the full id
        assertEquals("bugs-c3e5@example.org", w.mailAddress("bugs-c3e5"))
    }

    @Test
    fun visibilityAndThePageOnTheWeb() {
        val events = (channel(g, "Garden") + channel("help-1a2b", "Help")).toMutableList()
        fun w() = world(events, me = admin)
        assertNull(w().webPage(w().group(g)))
        val d = Actions.setWebPage(g, true, "garden", "public")
        assertEquals(30078, d.kind)
        assertEquals("""{"on":true,"slug":"garden","visibility":"public"}""", d.content)
        assertEquals(listOf(listOf("d", "belfry:web:garden"), listOf("h", g), listOf("t", "belfry-web")), d.tags)
        events.add(ev(admin, now - 100, d))
        assertEquals(World.WebPage(true, "garden", "public", null), w().webPage(w().group(g)))
        assertTrue(w().isPublicChannel(w().group(g)))
        // members only, the page stays; a one-type channel keeps its type
        events.add(ev(admin, now - 50, Actions.setWebPage(g, true, "garden", "members", "proposal")))
        assertEquals(World.WebPage(true, "garden", "members", "proposal"), w().webPage(w().group(g)))
        assertFalse(w().isPublicChannel(w().group(g)))
        assertTrue(Actions.setWebPage(g, false, "", "anything").content.contains("\"visibility\":\"public\""))
        // someone who is not an admin of the channel: ignored
        events.add(ev(member, now - 10, Actions.setWebPage(g, false, "x", "public")))
        assertTrue(w().webPage(w().group(g))!!.on)

        val help = w().group("help-1a2b")!!
        assertEquals("Give the page an address: two letters or more.", w().webSlugProblem(help, "h"))
        assertEquals("example.org/events is taken by example.org itself: pick another address.", w().webSlugProblem(help, "events"))
        assertEquals("example.org/garden is the page of #Garden: pick another address.", w().webSlugProblem(help, "garden"))
        assertNull(w().webSlugProblem(help, "help"))
        assertNull(w().webSlugProblem(w().group(g)!!, "garden"))
    }

    @Test
    fun brandingFromBelfryJsonAndBack() {
        val doc = """{"branding":{"logo":"https://x.org/l.png","cover":"https://x.org/c.jpg","primary":"#2563EB","secondary":"#F59E0B"}}"""
        val c = Community.parse("x.org", Json.parseToJsonElement(doc).jsonObject)
        assertEquals(Branding("https://x.org/l.png", "https://x.org/c.jpg", "#2563eb", "#f59e0b"), c.branding)
        // kept in the saved session: the colours are there before the belfry answers
        val again = Community.fromJsonString(c.toJsonString())!!
        assertEquals(c.branding, again.branding)
        assertEquals("#f59e0b", again.brandSecondary)
        // nothing set: nothing kept
        assertFalse(Community.defaults("x.org").toJsonString().contains("branding"))
        val bad = Branding.clean(Json.parseToJsonElement("""{"logo":"javascript:alert(1)","cover":"https://x.org/a b","primary":"red","secondary":"#12345"}""").jsonObject)
        assertEquals(Branding(), bad)
        assertEquals(c.copy(brandLogo = null), c.withBranding(c.branding.copy(logo = "")))
    }

    @Test
    fun draftsOfTheComposer() {
        val d = ComposerDraft("See @Maya in #Garden", listOf("Maya" to member), listOf("#Garden" to "nostr:naddr1xyz"), now)
        val back = ComposerDraft.parse(d.toJsonString(), now + 60)
        assertEquals(d, back)
        assertNull(ComposerDraft.parse(d.toJsonString(), now + 30 * 86400L), "thirty days")
        assertNull(ComposerDraft.parse(ComposerDraft("   ", at = now).toJsonString(), now))
        assertNull(ComposerDraft.parse(null, now))
        assertNull(ComposerDraft.parse("not json", now))
        assertNull(ComposerDraft.parse("[]", now))
        assertEquals(ComposerDraft.MAX, ComposerDraft.parse(ComposerDraft("x".repeat(30_000), at = now).toJsonString(), now)!!.text.length)
        // the web's own shape reads too
        assertNotNull(ComposerDraft.parse("""{"text":"hi","picked":[["Maya","$member"]],"chans":[],"at":$now}""", now))
        assertEquals("draft:c:general", ComposerDraft.storeKey(ComposerDraft.channel("general")))
        assertEquals("draft:t:abc", ComposerDraft.storeKey(ComposerDraft.thread("abc")))
    }

    @Test
    fun aRetriedSendSaysSoWhileConnected() {
        val item = OutboxItem(localId = "local-1", target = Target.GROUPS, kind = 9, content = "x", tags = emptyList(), createdAt = now, status = OutboxStatus.QUEUED, tries = 2)
        assertEquals("Waiting for connection…", Outbox.stateText(item))
        assertEquals("Sending again…", Outbox.stateText(item, connected = true))
        assertEquals("Sending…", Outbox.stateText(item.copy(tries = 0), connected = true))
        assertEquals("Not sent", Outbox.stateText(item.copy(status = OutboxStatus.FAILED), connected = true))
    }
}

/** The session side of the same changes: drafts on the device, the demo's branding. */
class WebNov12SessionTest {
    @Test
    fun draftsAreKeptPerConversationAndBrandingInTheDemo() = kotlinx.coroutines.runBlocking<Unit> {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        try {
            val store = sh.belfry.core.store.MemoryStore()
            val on = sh.belfry.core.engine.Onboarding(sh.belfry.core.engine.MemoryVault(), store, okhttp3.OkHttpClient(), scope, { 1_800_000_000L }, ZoneOffset.UTC)
            val s = on.demo()
            val key = ComposerDraft.channel("general")
            assertNull(s.draft(key))
            s.saveDraft(key, "Half a thought about @Maya", listOf("Maya" to "b".repeat(64), "Gone" to "c".repeat(64)))
            s.saveDraft(ComposerDraft.thread("abc"), "in a thread")
            suspend fun until(cond: suspend () -> Boolean) { var n = 0; while (!cond()) { kotlinx.coroutines.delay(20); if (++n > 250) error("timed out") } }
            until { s.draft(ComposerDraft.thread("abc")) != null }
            val d = s.draft(key)!!
            assertEquals("Half a thought about @Maya", d.text)
            // only the people still named in the text are kept
            assertEquals(listOf("Maya" to "b".repeat(64)), d.picked)
            assertEquals("in a thread", s.draft(ComposerDraft.thread("abc"))!!.text)
            // sending (an empty box) forgets it
            s.saveDraft(key, "  ")
            until { s.draft(key) == null }
            assertNotNull(s.draft(ComposerDraft.thread("abc")))

            val b = Branding("https://x.org/l.png", "", "#2563eb", "")
            assertEquals(b, s.saveBranding(b))
            assertEquals(b, s.branding)
            s.stop()
        } finally {
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }
}
