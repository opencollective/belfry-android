package sh.belfry.core.model

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import sh.belfry.core.Vectors
import sh.belfry.core.markdown.Inline
import sh.belfry.core.markdown.Markdown
import sh.belfry.core.net.Community
import sh.belfry.core.nostr.Event
import sh.belfry.core.outbox.ErrorClass
import sh.belfry.core.outbox.Outbox
import sh.belfry.core.outbox.OutboxItem
import sh.belfry.core.outbox.OutboxStatus
import sh.belfry.core.outbox.SendOutcome
import sh.belfry.core.outbox.Target
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun JsonElement?.o(): JsonObject = this as? JsonObject ?: JsonObject(emptyMap())
private fun JsonElement?.list(): List<JsonElement> = (this as? JsonArray)?.toList() ?: emptyList()
private fun JsonElement?.s(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content
private fun JsonElement?.long(): Long? = (this as? JsonPrimitive)?.longOrNull
private fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull
private fun JsonElement?.tags(): List<List<String>> = this.list().map { t -> t.list().map { (it as JsonPrimitive).content } }

/** Round 5 (2026-11-17): links in text, buttons on messages, settings through the outbox, the pull-down rows. */
class R5Test {

    // ---- links --------------------------------------------------------------------------------

    private fun urls(text: String): List<String> {
        val out = ArrayList<String>()
        fun walk(nodes: List<Inline>) {
            for (n in nodes) when (n) {
                is Inline.Link -> out.add(n.href)
                is Inline.Bold -> walk(n.children)
                is Inline.Italic -> walk(n.children)
                is Inline.Strike -> walk(n.children)
                else -> {}
            }
        }
        walk(Markdown.inline(text, Markdown.rich))
        return out
    }

    private val npubUrl = "https://preview.opensourcevillage.org/p/npub10j0emvtx36vh5h0dmd2e2zau7uh7vssx9mqnk2mn68agvknvecsqraxdux@website"

    @Test
    fun linksAreFoundWithoutTheSentencesPunctuation() {
        val cases = mapOf(
            npubUrl to listOf(npubUrl),
            "See https://belfry.opensourcevillage.org/website." to listOf("https://belfry.opensourcevillage.org/website"),
            "**Website updated**: the new version is at $npubUrl" to listOf(npubUrl),
            "(see https://a.org/x)" to listOf("https://a.org/x"),
            "https://a.org/y, then https://b.org! or https://c.org? (https://d.org)" to listOf("https://a.org/y", "https://b.org", "https://c.org", "https://d.org"),
            "Agenda: https://a.org/page#top and https://a.org/s?q=1&x=2." to listOf("https://a.org/page#top", "https://a.org/s?q=1&x=2"),
            "a bare host example.org is not a link" to emptyList(),
            "my_file is at https://a.org/b_c_d" to listOf("https://a.org/b_c_d"),
            "**see https://a.org/x**" to listOf("https://a.org/x"),
        )
        for ((text, want) in cases) {
            assertEquals(want, urls(text), "markdown: $text")
            if (!text.contains("**")) assertEquals(want, Links.find(text).map { it.url }, "plain: $text")
        }
        // the plain finder keeps positions so the rest of the text stays as written
        val f = Links.find("See https://belfry.opensourcevillage.org/website.").single()
        assertEquals(4, f.start)
        assertEquals("See https://belfry.opensourcevillage.org/website.".length - 1, f.end)
    }

    @Test
    fun markdownStillWorksAroundLinks() {
        assertEquals(listOf("https://example.org/path?q=1", "https://example.org/x"), urls("Bare https://example.org/path?q=1. And (https://example.org/x), end"))
        val nodes = Markdown.merged(Markdown.inline("_https://a.org_ and *b*", Markdown.rich))
        assertTrue(nodes.first() is Inline.Italic, nodes.toString())
        assertTrue(nodes.last() is Inline.Italic, nodes.toString())
    }

    @Test
    fun thisBelfrysChatLinksOpenInTheApp() {
        val d = "opensourcevillage.org"
        assertEquals(Links.InApp.Channel("general", null), Links.inApp("https://chat.$d/#/c/general", d))
        val e = "e".repeat(64)
        assertEquals(Links.InApp.Channel("website", e), Links.inApp("https://chat.$d/#/c/website/m/$e", d))
        assertEquals(Links.InApp.Channel("garden", null), Links.inApp("https://$d/chat/#/c/garden", d))
        assertEquals(Links.InApp.EventMessage("repair-cafe", e), Links.inApp("https://chat.$d/#/events/repair-cafe/m/$e", d))
        assertTrue(Links.inApp("https://chat.$d/events/seed-swap", d) is Links.InApp.Event)
        assertTrue(Links.inApp("https://chat.$d/join/abcdefghijklmnopqrstuvwxyz", d) is Links.InApp.Join)
        assertNull(Links.inApp("https://chat.other.org/#/c/general", d))
        assertNull(Links.inApp(npubUrl, d))
        assertNull(Links.inApp("https://belfry.$d/website", d))
    }

    // ---- place names ----------------------------------------------------------------------------

    @Test
    fun websiteKeepsItsOwnName() {
        assertNull(PlaceNames.display("website", "website"))
        assertNull(PlaceNames.display("website", ""))
        assertEquals("Townsquare", PlaceNames.display("general", "general"))
        val relay = "d".repeat(64)
        fun meta(id: String, name: String) = Event("m-$id", relay, 100, 39000, listOf(listOf("d", id), listOf("name", name)), "")
        val w = World(listOf(meta("general", "general"), meta("website", "website")), emptyList(), WorldContext(me = "a".repeat(64), placeNames = true))
        assertEquals("Townsquare", w.group("general")?.name)
        assertEquals("website", w.group("website")?.name)
        assertEquals("website", w.group("website")?.rawName)
    }

    // ---- buttons (vectors/message-actions.json) -------------------------------------------------

    private val v = Vectors.load("message-actions.json")

    @Test
    fun buttonsAreReadFromTagsAsTheVectorsSay() {
        for (case in v["buttons"].list()) {
            val c = case.o()
            val want = c["buttons"].list().map { b -> val o = b.o(); MessageButton(o["id"].s()!!, o["label"].s()!!, o["style"].s()!!, o["action"].s()!!) }
            assertEquals(want, Buttons.parse(c["tags"].tags()), c["name"].s())
        }
    }

    @Test
    fun aPressIsTheVectorsEvent() {
        val p = v["press"].o()
        val m = p["message"].o()
        val d = Buttons.press(m["id"].s()!!, m["pubkey"].s()!!, m["group"].s()!!, p["button"].s()!!)
        val e = p["event"].o()
        assertEquals(e["kind"].long()!!.toInt(), d.kind)
        assertEquals(e["content"].s(), d.content)
        assertEquals(e["tags"].tags(), d.tags)
        assertEquals(Target.GROUPS, d.target)
    }

    @Test
    fun theStateIsTheVectorsState() {
        for (case in v["state"].list()) {
            val c = case.o()
            val name = c["name"].s()
            val m = c["message"].o()
            val buttons = m["buttons"].list().map { b -> val o = b.o(); MessageButton(o["id"].s()!!, "x", "", o["action"].s() ?: "") }
            val msgId = m["id"].s()!!
            val author = m["pubkey"].s()!!
            val labels = ArrayList<Event>()
            (c["press"] as? JsonObject)?.let { p ->
                labels.add(Event(p["id"].s()!!, p["pubkey"].s()!!, p["created_at"].long()!!, 1985,
                    Buttons.press(msgId, author, "g", p["button"].s()!!).tags, ""))
            }
            c["answers"].list().forEachIndexed { i, a0 ->
                val a = a0.o()
                val tags = arrayListOf(listOf("L", Buttons.RESULT_NS), listOf("l", a["l"].s()!!, Buttons.RESULT_NS), listOf("e", msgId), listOf("press", a["press"].s()!!), listOf("p", c["me"].s()!!), listOf("h", "g"))
                a["code"].s()?.let { tags.add(listOf("code", it)) }
                if (a["closed"].bool() == true) tags.add(listOf("closed", "1"))
                labels.add(Event("answer-$i", a["pubkey"].s()!!, a["created_at"].long()!!, 1985, tags, a["content"].s() ?: ""))
            }
            val st = Buttons.state(msgId, author, buttons, c["me"].s()!!, labels, belfryKey = "B", groupsKey = "G")
            val want = c["expect"].o()
            assertEquals(want["closed"].bool(), st.closed, name)
            if (st.closed) assertEquals(want["closed_text"].s(), st.closedText, name)
            val mine = want["mine"] as? JsonObject
            if (mine == null) assertNull(st.mine, name) else {
                assertEquals(mine["status"].s(), st.mine?.status, name)
                assertEquals(mine["text"].s(), st.mine?.text, name)
                mine["code"].s()?.let { assertEquals(it, st.mine?.code, name) }
            }
        }
    }

    @Test
    fun fixedTextsAreTheVectorsTexts() {
        val f = v["fixed_text"].o()
        for (code in listOf("not_allowed", "unknown_action", "no_target", "gone", "expired", "failed")) assertEquals(f[code].s(), Buttons.errorText(code), code)
        assertEquals(f[""].s(), Buttons.errorText(""))
        assertEquals(f[""].s(), Buttons.errorText("weird"))
        assertEquals(f["timeout"].s(), Buttons.TIMEOUT_TEXT)
        assertEquals(f["unknown_belfry_action"].s(), Buttons.UPDATE_APP)
        assertEquals(f["confirm:belfry:website-publish"].s()!!.replace("<domain>", "x.org"), Buttons.confirmText(Buttons.WEBSITE_PUBLISH, "x.org"))
        assertEquals(f["confirm:belfry:request-close"].s(), Buttons.confirmText(Buttons.REQUEST_CLOSE, "x.org"))
        assertEquals("Close", Buttons.confirmLabel(Buttons.REQUEST_CLOSE))
        assertEquals("Close this request?" to "It stays readable, and people can still comment.", Buttons.confirmParts(Buttons.confirmText(Buttons.REQUEST_CLOSE, "x.org")!!))
        assertEquals("Close?" to null, Buttons.confirmParts("Close?"))
        assertTrue(Buttons.REQUEST_CLOSE in Buttons.KNOWN_ACTIONS)
        assertNull(Buttons.confirmText("belfry:something-new", "x.org"))
        assertTrue(MessageButton("x", "X", "", "belfry:something-new").unknown)
        assertFalse(MessageButton("x", "X", "", Buttons.WEBSITE_PUBLISH).unknown)
    }

    @Test
    fun theWorldShowsTheStateAndTimesOut() {
        val me = "a".repeat(64)
        val kofi = "b".repeat(64)
        val gk = "9".repeat(64)
        val msg = Event("f".repeat(64), kofi, 100, 9, listOf(listOf("h", "website"), listOf("button", "publish", "Approve and publish", "primary", Buttons.WEBSITE_PUBLISH)), "Version 4")
        val press = Event("p1", me, 200, 1985, Buttons.press(msg.id, kofi, "website", "publish").tags, "")
        val ctx = WorldContext(me = me, groupsKey = gk)
        val w1 = World(listOf(msg, press), emptyList(), ctx)
        val m = w1.messages("website").single()
        assertEquals(1, w1.buttons(m).size)
        val s1 = w1.buttonState(m)
        assertTrue(s1.waiting(210))
        assertFalse(s1.noAnswer(210))
        assertTrue(s1.noAnswer(200 + Buttons.TIMEOUT_S))
        assertEquals("publish", s1.pressedButton)
        val closed = Event("r1", gk, 205, 1985, listOf(listOf("L", Buttons.RESULT_NS), listOf("l", "ok", Buttons.RESULT_NS), listOf("e", msg.id), listOf("press", "p1"), listOf("p", me), listOf("h", "website"), listOf("closed", "1")), "Published by Ana.")
        val s2 = World(listOf(msg, press, closed), emptyList(), ctx).buttonState(m)
        assertTrue(s2.closed)
        assertEquals("Published by Ana.", s2.closedText)
        assertFalse(s2.waiting(206))
        // the belfry key names itself in belfry.json: its answers count too; an edit never changes the buttons
        assertTrue(World(listOf(msg, press, closed.copy(pubkey = "8".repeat(64))), emptyList(), WorldContext(me = me, belfryKey = "8".repeat(64))).buttonState(m).closed)
        assertFalse(World(listOf(msg, press, closed.copy(pubkey = kofi)), emptyList(), ctx).buttonState(m).closed)
    }

    @Test
    fun belfryJsonNamesTheGroupsKey() {
        val gk = "7".repeat(64)
        val c = Community.parse("x.org", """{"name":"X","nostr":{"pubkey":"${"8".repeat(64)}"},"groups_key":{"pubkey":"$gk","npub":"npub1x"}}""")
        assertEquals(gk, c.groupsKey)
        assertEquals(gk, Community.fromJsonString(c.toJsonString())?.groupsKey)
        assertNull(Community.parse("x.org", """{"groups_key":{"pubkey":"nope"}}""").groupsKey)
    }

    // ---- settings through the outbox (SPEC 19.37) ------------------------------------------------

    @Test
    fun apiCallsAreClassifiedLikeRelayAnswers() {
        assertTrue(Outbox.httpOutcome(200, null) is SendOutcome.Ok)
        for (status in listOf(400, 403, 404, 409, 422)) {
            val o = Outbox.httpOutcome(status, "not_allowed") as SendOutcome.Error
            assertEquals(ErrorClass.FAILED, Outbox.classify(o.message), "$status")
        }
        for (status in listOf(401, 408, 429, 500, 502, 503)) {
            val o = Outbox.httpOutcome(status, null) as SendOutcome.Error
            assertEquals(ErrorClass.RETRY, Outbox.classify(o.message), "$status")
        }
    }

    private fun meta(at: Long = 100) = Event("m1", "d".repeat(64), at, 39000, listOf(listOf("d", "garden"), listOf("name", "Garden"), listOf("about", "Old words")), "")

    private fun apiItem(status: OutboxStatus = OutboxStatus.QUEUED) = OutboxItem(
        "local-1", Target.API, 9002, "", listOf(listOf("h", "garden"), listOf("name", "Kitchen garden"), listOf("about", "New words"), listOf("open")), 200,
        status = status, path = "/group-meta", body = """{"channel":"garden","name":"Kitchen garden","about":"New words"}""",
    )

    @Test
    fun aQueuedChangeShowsAtOnceAndARefusalCanBeDiscarded() {
        val me = "a".repeat(64)
        val ctx = WorldContext(me = me)
        val queued = World(listOf(meta()), listOf(apiItem()), ctx)
        assertEquals("Kitchen garden", queued.group("garden")?.name)
        assertEquals("New words", queued.group("garden")?.about)
        assertTrue(queued.failedSettings("garden").isEmpty())
        // refused for good: still shown, and the header's mark says so
        val failed = World(listOf(meta()), listOf(apiItem(OutboxStatus.FAILED)), ctx)
        assertEquals("Kitchen garden", failed.group("garden")?.name)
        assertEquals(listOf("local-1"), failed.failedSettings("garden").map { it.localId })
        assertTrue(failed.failedSettings("kitchen").isEmpty())
        // discarded: what the relay has
        val back = World(listOf(meta()), emptyList(), ctx)
        assertEquals("Garden", back.group("garden")?.name)
        assertEquals("Old words", back.group("garden")?.about)
        // the inbox switch and the web page setting count too; a seen marker does not
        val inbox = OutboxItem("local-2", Target.GROUPS, 30078, "{\"on\":true}", Actions.setInbox("garden", true).tags, 201, status = OutboxStatus.FAILED)
        val seen = OutboxItem("local-3", Target.GROUPS, 30078, "", listOf(listOf("d", "seen:garden"), listOf("h", "garden")), 202, status = OutboxStatus.FAILED)
        assertEquals(listOf("local-2"), World(listOf(meta()), listOf(inbox, seen), ctx).failedSettings("garden").map { it.localId })
    }

    @Test
    fun anApiItemSurvivesTheStoreAndRunsInOrder() = runBlocking {
        val item = apiItem()
        val again = OutboxItem.fromJson(item.toJson())
        assertEquals(item, again)
        assertEquals(Target.API, again.target)
        // a refusal fails that item only; a retryable answer stalls the API queue, not the relays
        val later = item.copy(localId = "local-9", createdAt = 300)
        val msg = OutboxItem("local-5", Target.GROUPS, 9, "hi", listOf(listOf("h", "garden")), 250)
        val r1 = Outbox.run(listOf(item, msg, later)) { if (it.target == Target.API) Outbox.httpOutcome(403, "not_allowed") else SendOutcome.Ok() }
        assertEquals(listOf("local-1", "local-9"), r1.failed.map { it.localId })
        assertEquals(listOf("local-5"), r1.sent.map { it.item.localId })
        val r2 = Outbox.run(listOf(item, msg, later)) { if (it.target == Target.API) Outbox.httpOutcome(503, null) else SendOutcome.Ok() }
        assertEquals(listOf("local-1", "local-5"), r2.attempted)
        assertEquals(setOf(Target.API), r2.stalled)
        assertEquals(OutboxStatus.QUEUED, r2.items.first { it.localId == "local-1" }.status)
    }

    // ---- the pull-down's rows -----------------------------------------------------------------

    @Test
    fun emptyRowsLeaveThePullDown() {
        val none = ChannelCounts(active = 0, photos = 0, groups = 0, pins = 0, events = 0, proposals = 0, todos = 0, todosDone = 0, files = 0, topics = 0, members = 3, isPublic = true)
        for (row in listOf("pinned", "events", "decide", "todos", "photos", "files", "groups", "topics")) assertFalse(none.showsRow(row), row)
        for (row in listOf("members", "notifications", "log", "settings")) assertTrue(none.showsRow(row), row)
        val some = none.copy(pins = 1, todos = 2, todosDone = 2, photos = 4)
        assertTrue(some.showsRow("pinned"))
        assertTrue(some.showsRow("todos"), "to-dos that are all done still show")
        assertTrue(some.showsRow("photos"))
        assertFalse(some.showsRow("files"))
    }
}

/** "This group has been deleted" for a group just made (iPhone bug, 2026-11-17): the rules of the web's groupstate.ts. */
class PresenceTest {
    private val me = "a".repeat(64)
    private val ana = "b".repeat(64)
    private val relay = "d".repeat(64)
    private fun ev(pk: String, kind: Int, at: Long, tags: List<List<String>>, c: String = "") =
        Event(sh.belfry.core.crypto.sha256Hex("$pk$kind$at$tags$c"), pk, at, kind, tags, c)
    private fun meta(id: String, at: Long = 100) = ev(relay, 39000, at, listOf(listOf("d", id), listOf("name", id)))
    private fun w(events: List<Event>, outbox: List<OutboxItem> = emptyList(), absent: Map<String, Long> = emptyMap()) =
        World(events, outbox, WorldContext(me = me, groupsAbsent = absent))
    private val general = meta("general")

    @Test
    fun aJustCreatedProposalIsNeverDeletedByAbsence() {
        val prop = "prop-new-roof-1a2b"
        val made = listOf(
            general,
            ev(me, 9007, 1000, listOf(listOf("h", prop))),
            ev(me, 9002, 1001, listOf(listOf("h", prop), listOf("name", "New roof"))),
            ev(me, 9, 1002, listOf(listOf("h", "general"), listOf("proposal", prop)), "New proposal: New roof"),
        )
        // even with a stale "absent" mark, and before its 39000 came back
        val world = w(made, absent = mapOf(prop to 0L))
        assertEquals(GroupState.OPEN, world.groupState(prop))
        assertFalse(prop in world.presenceCandidates())
        assertFalse(world.presenceAskable(prop))
        // an answer without it changes nothing for a proposal
        assertEquals(emptyMap<String, Long>(), world.presenceAfter(listOf(prop), listOf(general), emptyMap()))
        // only a 9008 newer than every 9007 deletes it
        assertEquals(GroupState.DELETED, w(made + ev(ana, 9008, 1100, listOf(listOf("h", prop)))).groupState(prop))
        assertEquals(GroupState.OPEN, w(made + ev(ana, 9008, 900, listOf(listOf("h", prop)))).groupState(prop))
    }

    @Test
    fun aJustCreatedGroupIsLiveWhileQueuedSentAndAnswered() {
        val pod = "pod-canal-41ec"
        val queued = Actions.createPod(pod, "Canal", "Along the water", "general", PodAccess()).mapIndexed { i, d ->
            OutboxItem("local-$i", d.target, d.kind, d.content, d.tags, 1000L + i)
        }
        // in the outbox: live, never asked, a stored mark ignored
        val q = w(listOf(general), queued, absent = mapOf(pod to 0L))
        assertEquals(GroupState.OPEN, q.groupState(pod))
        assertFalse(pod in q.presenceCandidates())
        assertFalse(q.presenceAskable(pod))
        // sent, its 39000 not back yet: still live, still not asked
        val sent = queued.map { ev(me, it.kind, it.createdAt, it.tags, it.content) }
        val s = w(listOf(general) + sent, absent = mapOf(pod to 0L))
        assertEquals(GroupState.OPEN, s.groupState(pod))
        assertFalse(s.presenceAskable(pod))
        // a card for a group this device knows nothing of: askable, but not in its first two minutes when made here
        val card = ev(ana, 9, 2000, listOf(listOf("h", "general"), listOf("pod", "pod-other-0000")), "New group: Other")
        val c = w(listOf(general, card))
        assertTrue(c.presenceAskable("pod-other-0000"))
        assertFalse(c.presenceAskable("pod-other-0000", nowS = 2050, createdHere = mapOf("pod-other-0000" to 2000L)))
        assertTrue(c.presenceAskable("pod-other-0000", nowS = 2200, createdHere = mapOf("pod-other-0000" to 2000L)))
        // the relay's answer with only its 9007 (no 39000 yet) means it is there
        assertEquals(emptyMap<String, Long>(), c.presenceAfter(listOf("pod-other-0000"), listOf(general, ev(ana, 9007, 1990, listOf(listOf("h", "pod-other-0000"))))))
        // EOSE with nothing: deleted; its events arriving later clear that
        val gone = c.presenceAfter(listOf("pod-other-0000"), listOf(general))!!
        assertEquals(GroupState.DELETED, w(listOf(general, card), absent = gone).groupState("pod-other-0000"))
        assertEquals(GroupState.OPEN, w(listOf(general, card, meta("pod-other-0000", 50)), absent = gone).groupState("pod-other-0000"))
        // events never by absence
        assertFalse(c.presenceAskable("ev-20261117-abcd"))
        assertFalse(c.presenceAskable("ws-e1a2b3c4"))
    }
}
