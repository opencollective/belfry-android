package sh.belfry.core.sealed

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import sh.belfry.core.Vectors
import sh.belfry.core.dm.GiftWrap
import sh.belfry.core.nostr.Event
import sh.belfry.core.nostr.LocalSigner
import sh.belfry.core.nostr.isValid
import sh.belfry.core.nostr.s
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * vectors/sealed.json (collective-belfry belfry-sh 8290e37, sha256 572c8f01…8a95e4), shared with
 * the web, iOS and the server: chains, wraps, the ceiling and packing, hand-over, link, ready, history;
 * and `confirm` (§5.2 step 2b, kind 8563), added here on 2026-11-22 by tools/sealed_confirm_vectors.py.
 */
class SealedVectorsTest {
    private val v = Vectors.load("sealed.json")
    private val keys = v["keys"] as JsonObject
    private fun pub(name: String) = (keys[name] as JsonObject).s("pubkey")!!
    private fun secret(name: String) = (keys[name] as JsonObject).s("secret")!!

    private fun ev(o: JsonObject): Event = Event.fromJson(o)!!
    private fun evs(a: Any?): List<Event> = (a as? JsonArray)?.map { ev(it as JsonObject) } ?: emptyList()
    private fun cases(name: String) = (v[name] as JsonArray).map { it as JsonObject }
    private fun strs(a: Any?) = (a as JsonArray).map { it.jsonPrimitive.content }
    private fun str(o: JsonObject, k: String): String? = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    private fun chainOf(anchor: JsonObject?, rosters: List<Event>, pinned: String? = null): Chain? {
        val a = anchor?.let { ev(it) } ?: return null
        return Chain.of(a.pubkey, listOf(a), rosters, pinned)
    }

    private fun chainCase(name: String): JsonObject = cases("chains").first { it.s("name") == name }
    private fun namedChain(name: String): Chain? = chainCase(name).let { chainOf(it["anchor"] as? JsonObject, evs(it["rosters"])) }

    @Test fun chains() {
        val all = cases("chains")
        assertTrue(all.size >= 39)
        for (c in all) {
            val name = c.s("name")!!
            val e = c["expected"] as JsonObject
            val chain = chainOf(c["anchor"] as? JsonObject, evs(c["rosters"]), str(c, "pinned_head"))
            assertEquals(strs(e["valid_ids"]), chain?.rosters?.map { it.id } ?: emptyList<String>(), name)
            assertEquals(str(e, "head"), chain?.head?.id, name)
            assertEquals(strs(e["devices"]), chain?.devices ?: emptyList<String>(), name)
            assertEquals(str(e, "lb"), chain?.head?.lb, name)
            assertEquals(e["ready"]!!.jsonPrimitive.boolean, chain?.ready ?: false, name)
        }
    }

    /**
     * vectors `join` (sealed.md §5.7, belfry-sh 88a27b9; tools/sealed_join_vectors.py), FIRST PART: what holds
     * before the engine reads joins. Every event of the section is well formed and signed; the hand-over wraps
     * open with the phone's key and give the pairs that check out; and every `chains` case whose expected chain
     * holds no join (a join refused, not anchored, or only in the tail) already gives that chain. The cases that
     * need a join followed (chains, follow, requests, older_keys, confirm, endorsement) are read by the reading
     * side's tests, which replace the filter below.
     */
    @Test fun joinVectorsAsFarAsBuilt() = runBlocking<Unit> {
        val j = v["join"] as JsonObject
        fun list(k: String) = (j[k] as JsonArray).map { it as JsonObject }
        var events = 0
        fun walk(x: kotlinx.serialization.json.JsonElement?) {
            when (x) {
                is JsonObject -> if (x.containsKey("sig") && x.containsKey("kind")) { assertTrue(ev(x).isValid(), "a bad event in join: ${x.s("id")}"); events++ } else x.values.forEach { walk(it) }
                is JsonArray -> x.forEach { walk(it) }
                else -> {}
            }
        }
        walk(j)
        assertTrue(events > 150, "events checked: $events")
        // chains: the cases that no join rule is needed for
        var ran = 0
        for (c in list("chains")) {
            val name = c.s("name")!!
            val e = c["expected"] as JsonObject
            val rosters = evs(c["rosters"]).mapNotNull { Roster.parse(it) }.associateBy { it.id }
            val needsJoins = strs(e["valid_ids"]).any { id -> rosters[id]?.let { r -> r.prev != null && rosters[r.prev]?.devices?.contains(r.signer) == false } == true }
            if (needsJoins) continue
            val chain = chainOf(c["anchor"] as? JsonObject, evs(c["rosters"]))
            assertEquals(strs(e["valid_ids"]), chain?.rosters?.map { it.id } ?: emptyList<String>(), name)
            assertEquals(strs(e["devices"]), chain?.devices ?: emptyList<String>(), name)
            assertEquals(str(e, "lb"), chain?.head?.lb, name)
            ran++
        }
        assertTrue(ran >= 14, "chains cases run: $ran")
        // hand-overs: opened by the phone, the giver and the pairs that check out
        for (c in list("handover")) {
            val name = c.s("name")!!
            val e = c["expected"] as JsonObject
            val h = Sealing.openKeys(ev(c["wrap"] as JsonObject), LocalSigner(c.s("device_secret")!!))!!
            assertEquals(e.s("giver"), h.giver, name)
            assertTrue(GiftWrap.isSilent(ev(c["wrap"] as JsonObject)), name)
            if (e["accepted"]!!.jsonPrimitive.boolean) {
                assertEquals((e["keys"] as JsonArray).associate { k -> (k as JsonObject).s("lb")!! to k.s("secret")!! }, h.keys, name)
            }
        }
        assertEquals(3, list("handover").size)
    }

    @Test fun wraps() {
        val all = cases("wraps")
        assertTrue(all.size >= 11)
        for (c in all) {
            val name = c.s("name")!!
            val e = c["expected"] as JsonObject
            val wrap = ev(c["wrap"] as JsonObject)
            val lb = pub(c.s("letterbox")!!)
            val chainKeys = KeyChain().also { it.add(lb, c.s("letterbox_secret")!!) }
            val o = Sealing.open(wrap, chainKeys)
            val opens = e["opens"]!!.jsonPrimitive.boolean
            assertEquals(opens, o is Opening.Opened, name)
            if (o !is Opening.Opened) continue
            assertEquals(e.s("seal_pubkey"), o.sealPubkey, name)
            val r = e["rumor"] as JsonObject
            assertEquals(r.s("id"), o.rumor.id, name)
            assertEquals(r.s("pubkey"), o.rumor.pubkey, name)
            assertEquals(r["created_at"]!!.jsonPrimitive.long, o.rumor.createdAt, name)
            assertEquals(r["kind"]!!.jsonPrimitive.int, o.rumor.kind, name)
            assertEquals(ev(r).tags, o.rumor.tags, name)
            assertEquals(r.s("content"), o.rumor.content, name)
            val author = c.s("author_chain")?.let { namedChain(it) }
                ?: (c["author_chain_events"] as? JsonObject)?.let { chainOf(it["anchor"] as? JsonObject, evs(it["rosters"])) }
            val verdict = Sealing.verdict(o, author, firstSeen = 0, now = 31 * 86400L, belfry = c.s("belfry"))
            assertEquals(e["accepted"]!!.jsonPrimitive.boolean, verdict == Sealing.Verdict.ACCEPT, name)
        }
    }

    /** §5.2 step 2b (kind 8563): the accept rule on each case, and the one real wrap. */
    @Test fun confirm() {
        val c = v["confirm"] as JsonObject
        val ch = c["chain"] as JsonObject
        val chain = chainOf(ch["anchor"] as JsonObject, evs(ch["rosters"]))!!
        assertEquals(4, chain.rosters.size)
        val all = (c["cases"] as JsonArray).map { it as JsonObject }
        assertTrue(all.size >= 15)
        for (k in all) {
            val name = k.s("name")!!
            val e = k["expected"] as JsonObject
            val r = k["rumor"] as JsonObject
            val rumor = rumorOf(r)
            assertEquals(r.s("id"), rumor.id, name)
            val got = Sealing.acceptConfirm(rumor, k.s("seal_pubkey")!!, chain)
            assertEquals(e["accepted"]!!.jsonPrimitive.boolean, got is Sealing.Confirm.Accept, "$name: $got")
            if (got is Sealing.Confirm.Accept) { assertEquals(e.s("device"), got.device, name); assertEquals(e.s("roster"), got.roster, name) }
            // never shared, never a message: an 8564 holding it gives nothing
            assertTrue(rumor.kind !in Sealing.SHARED_KINDS, name)
        }
        // what this implementation sends is the accepted case's rumor, to the letter
        val first = all.first()["rumor"] as JsonObject
        val tags = ev(JsonObject(first + mapOf("content" to JsonPrimitive("")))).tags
        val mine = Sealing.confirmRumor(first.s("pubkey")!!, c.s("account")!!, tags.first { it[0] == "device" }[1], tags.first { it[0] == "e" }[1], first["created_at"]!!.jsonPrimitive.long)
        assertEquals(first.s("id"), mine.id)
        assertEquals(first.toString(), mine.copy(sig = "").toJsonString(), "serialised as the vectors are")
        // the wrap: opened with my head letterbox, judged by the same rule
        val w = c["wrap"] as JsonObject
        val we = w["expected"] as JsonObject
        val o = Sealing.open(ev(w["wrap"] as JsonObject), KeyChain().also { it.add(pub(w.s("letterbox")!!), w.s("letterbox_secret")!!) })
        assertTrue(o is Opening.Opened)
        o as Opening.Opened
        assertEquals(we.s("seal_pubkey"), o.sealPubkey)
        assertEquals((we["rumor"] as JsonObject).s("id"), o.rumor.id)
        assertEquals(Sealing.CONFIRM, o.rumor.kind)
        assertEquals(we["accepted"]!!.jsonPrimitive.boolean, Sealing.acceptConfirm(o.rumor, o.sealPubkey, chain) is Sealing.Confirm.Accept)
        // and an 8563 inside a shared history is not taken
        val share = Sealing.shareRumors(pub("alice"), pub("alice"), listOf(o.rumor), 0)
        assertTrue(Sealing.acceptShared(share.first(), pub("alice_phone"), chain, pub("alice")).isEmpty())
    }

    private fun rumorOf(o: JsonObject): Event {
        val content = when (val c = o["content"]) {
            is JsonObject -> c.s("repeat")!!.repeat(c["times"]!!.jsonPrimitive.int)
            else -> c!!.jsonPrimitive.content
        }
        return GiftWrap.rumor(o["kind"]!!.jsonPrimitive.int, o.s("pubkey")!!, ev(JsonObject(o + mapOf("content" to JsonPrimitive("")))).tags, content, o["created_at"]!!.jsonPrimitive.long)
    }

    @Test fun ceiling() {
        val ce = v["ceiling"] as JsonObject
        assertEquals(Sealing.MAX_RUMOR_BYTES, ce["max_rumor_json_bytes"]!!.jsonPrimitive.int)
        for (c in (ce["cases"] as JsonArray).map { it as JsonObject }) {
            val name = c.s("name")!!
            val r = rumorOf(c["rumor"] as JsonObject)
            assertEquals(c.s("rumor_id"), r.id, name)
            assertEquals(c["rumor_json_bytes"]!!.jsonPrimitive.int, Sealing.rumorBytes(r), name)
            assertEquals(c["may_seal"]!!.jsonPrimitive.boolean, Sealing.fits(r), name)
        }
    }

    @Test fun historyPacking() {
        val t0 = (v["fixed"] as JsonObject)["T0"]!!.jsonPrimitive.long
        val alice = pub("alice")
        val bob = pub("bob")
        val rumors = ArrayList<Event>()
        for (i in 0 until 300) {
            val from = if (i % 2 == 1) alice else bob
            val to = if (from == alice) bob else alice
            rumors.add(GiftWrap.rumor(14, from, listOf(listOf("p", to)), "Message $i: \"quoted\" \\ and é", t0 + i))
            if (i == 149) rumors.add(GiftWrap.rumor(14, bob, listOf(listOf("p", alice)), "x".repeat(39_700), t0 + i))
        }
        val shares = Sealing.shareRumors(bob, alice, rumors, t0 + 800)
        val e = (ce()["history_packing"] as JsonObject)["expected"] as JsonObject
        assertEquals((e["rumors_per_8564"] as JsonArray).map { it.jsonPrimitive.int }, shares.map { Sealing.sharedRumors(it).size })
        assertEquals((e["json_bytes_per_8564"] as JsonArray).map { it.jsonPrimitive.int }, shares.map { Sealing.rumorBytes(it) })
        assertEquals(strs(e["ids_of_8564"]), shares.map { it.id })
        assertEquals(301 - 1, shares.sumOf { Sealing.sharedRumors(it).size }, "the large one is left out")
    }

    private fun ce() = v["ceiling"] as JsonObject

    @Test fun keysPacking() {
        val t0 = (v["fixed"] as JsonObject)["T0"]!!.jsonPrimitive.long
        val chain = KeyChain()
        for (i in 0 until 600) {
            val s = sh.belfry.core.crypto.sha256Hex("belfry sealed vectors/key/many-lb-$i")
            assertTrue(chain.add(sh.belfry.core.crypto.Keys.publicKeyHex(s), s))
        }
        val rumors = Sealing.keysRumors(pub("alice_phone"), pub("alice"), chain, t0 + 600)
        val e = (ce()["keys_packing"] as JsonObject)["expected"] as JsonObject
        assertEquals((e["keys_per_8561"] as JsonArray).map { it.jsonPrimitive.int }, rumors.map { r ->
            ((sh.belfry.core.nostr.parseJsonOrNull(r.content) as JsonObject)["keys"] as JsonArray).size
        })
        assertEquals((e["json_bytes_per_8561"] as JsonArray).map { it.jsonPrimitive.int }, rumors.map { Sealing.rumorBytes(it) })
        assertEquals(strs(e["ids_of_8561"]), rumors.map { it.id })
    }

    @Test fun handover() = runBlocking<Unit> {
        for (c in cases("keys_handover")) {
            val name = c.s("name")!!
            val e = c["expected"] as JsonObject
            val device = LocalSigner(c.s("device_secret")!!)
            val h = Sealing.openKeys(ev(c["wrap"] as JsonObject), device)!!
            assertEquals(e.s("giver"), h.giver, name)
            val chain = namedChain(c.s("chain")!!)!!
            val taken = if (Sealing.acceptHandover(h, c.s("account")!!, listOf(chain))) h.keys else emptyMap()
            val want = (e["keys"] as JsonArray).associate { k -> (k as JsonObject).s("lb")!! to k.s("secret")!! }
            assertEquals(want, taken, name)
        }
    }

    @Test fun link() {
        for (c in cases("link")) {
            val name = c.s("name")!!
            val e = c["expected"] as JsonObject
            val req = ev(c["request"] as JsonObject)
            val secret = c.s("link_secret")!!
            assertEquals(e.s("link_for_this_device"), Sealing.linkProof(secret, req.pubkey), name)
            assertEquals(e["valid"]!!.jsonPrimitive.boolean, Sealing.linkMatches(req, listOf(secret)), name)
        }
    }

    @Test fun ready() {
        val r = v["ready"] as JsonObject
        val now = r["now"]!!.jsonPrimitive.long
        fun t(s: String?) = s?.let { java.time.Instant.parse(it).epochSecond }
        for (c in (r["cases"] as JsonArray).map { it as JsonObject }) {
            val name = c.s("name")!!
            val sessions = (c["sessions"] as JsonArray).map { it as JsonObject }.map { s ->
                Triple(str(s, "client_pubkey"), s["chat"]!!.jsonPrimitive.boolean, t(str(s, "last_activity")) ?: t(str(s, "created_at")))
            }
            val ok = Sealing.readyFor(strs(c["roster_devices"]), Sealing.countedSessions(sessions, now))
            assertEquals((c["expected"] as JsonObject)["may_carry_ready"]!!.jsonPrimitive.boolean, ok, name)
        }
    }

    @Test fun history() {
        for (c in cases("history")) {
            val name = c.s("name")!!
            val e = c["expected"] as JsonObject
            val lb = pub(c.s("letterbox")!!)
            val o = Sealing.open(ev(c["wrap"] as JsonObject), KeyChain().also { it.add(lb, c.s("letterbox_secret")!!) })
            o as Opening.Opened
            assertEquals(e.s("sharer"), o.rumor.pubkey, name)
            assertEquals(e.s("seal_pubkey"), o.sealPubkey, name)
            val sc = c["sharer_chain_events"] as JsonObject
            val chain = chainOf(sc["anchor"] as? JsonObject, evs(sc["rosters"]))
            val added = Sealing.acceptShared(o.rumor, o.sealPubkey, chain, c.s("receiver")!!)
            assertEquals(strs(e["added_ids"]), added.map { it.id }, name)
        }
    }

    // ---- follow and trust (belfry-sh 6b6ca54, sha256 a1daadbd…07e35286) -------------------------

    private fun summary(c: Chain?): JsonObject = kotlinx.serialization.json.buildJsonObject {
        if (c == null) {
            put("valid_ids", JsonArray(emptyList())); put("head", JsonNull); put("devices", JsonArray(emptyList())); put("lb", JsonNull); put("ready", JsonPrimitive(false))
        } else {
            put("valid_ids", JsonArray(c.rosters.map { JsonPrimitive(it.id) }))
            put("head", JsonPrimitive(c.head.id))
            put("devices", JsonArray(c.devices.map { JsonPrimitive(it) }))
            put("lb", JsonPrimitive(c.head.lb))
            put("ready", JsonPrimitive(c.ready))
        }
    }

    /** The steps of a follow / trust case; each step's event, pin and chain checked; the pin through JSON each time. */
    private fun runSteps(case: JsonObject): Pair<Chain?, Pin?> {
        var pin = Pin.fromJson(case["pin"] as? JsonObject)
        var c: Chain? = null
        for ((i, step) in (case["steps"] as JsonArray).withIndex()) {
            step as JsonObject
            val r = Follow.follow((step["anchor"] as? JsonObject)?.let { ev(it) }, evs(step["rosters"]), pin)
            c = r.chain; pin = r.pin
            (step["allow_locally"] as? JsonArray)?.forEach { d -> pin = pin?.let { Follow.allowLocally(it, d.jsonPrimitive.content) } }
            val want = step["expected"] as JsonObject
            val where = "${case.s("name")}, step ${i + 1}"
            assertEquals(want.s("event"), r.event.wire, where)
            assertEquals(want["pin"], pin?.toJson() ?: JsonNull, where)
            assertEquals(want["chain"], summary(c), where)
            pin = Pin.fromJson(pin?.toJson())
        }
        return c to pin
    }

    @Test fun follow() {
        val cases = ((v["follow"] as JsonObject)["cases"] as JsonArray).map { it as JsonObject }
        assertTrue(cases.size >= 17)
        val events = HashSet<String>()
        for (case in cases) {
            runSteps(case)
            (case["steps"] as JsonArray).forEach { events.add(((it as JsonObject)["expected"] as JsonObject).s("event")!!) }
        }
        assertEquals(Follow.Change.entries.map { it.wire }.toSet(), events)
    }

    @Test fun trust() {
        val cases = ((v["trust"] as JsonObject)["cases"] as JsonArray).map { it as JsonObject }
        assertTrue(cases.size >= 13)
        var asks = 0
        for (case in cases) {
            val (c, pin) = runSteps(case)
            for (a in (case["asks"] as JsonArray).map { it as JsonObject }) {
                val given = (a["added_at"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.long
                for (addedAt in listOf(given, null)) {
                    if (addedAt == null && given == null) continue // as the reference: only checked when given
                    val got = Follow.answerWithoutPrompt(
                        c, pin, a.s("device")!!, strs(a["live_chat_devices"]), a["request_created_at"]!!.jsonPrimitive.long,
                        addedAt, (a["last_handover_at"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.long,
                    )
                    assertEquals(a["expected"]!!.jsonPrimitive.boolean, got, "${case.s("name")}: ${a.s("device_name")}")
                }
                asks++
            }
        }
        assertEquals(27, asks)
    }

    /** vectors `reports` (belfry-sh d076753, sha256 32f97764…986db09a): the 7679 of a sealed sender. */
    @Test fun reports() {
        val r = v["reports"] as JsonObject
        val belfry = r.s("belfry")!!
        val cases = (r["cases"] as JsonArray).map { it as JsonObject }
        assertTrue(cases.size >= 4)
        for (c in cases) {
            val name = c.s("name")!!
            val want = c["expected"] as JsonObject
            // the key chain as held (the reference's list), unchecked: the wrong-secret case pairs an lb with another secret
            val chain = (c["key_chain"] as JsonArray).associate { e -> e as JsonObject; e.s("lb")!! to e.s("secret")!! }
            val got = Sealing.openReport(ev(c["report"] as JsonObject), belfry) { chain[it] }
            when (want.s("state")) {
                "opened" -> assertEquals(want["body"], (got as Sealing.Report.Opened).body, name)
                "signer" -> assertEquals(Sealing.Report.Signer, got, name)
                "locked" -> assertEquals(Sealing.Report.Locked, got, name)
                "error" -> assertEquals(Sealing.Report.Invalid, got, name)
                else -> error(name)
            }
        }
        // not the belfry's: refused
        assertEquals(Sealing.Report.Invalid, Sealing.openReport(ev(cases[0]["report"] as JsonObject), "aa".repeat(32), KeyChain()))
    }
}
