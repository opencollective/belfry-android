package sh.belfry.core.enc

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import sh.belfry.core.Vectors
import sh.belfry.core.crypto.Hex
import sh.belfry.core.crypto.sha256
import sh.belfry.core.crypto.sha256Hex
import sh.belfry.core.dm.DmFiles
import sh.belfry.core.nostr.Event
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * vectors/encrypted.json (docs/encrypted.md format 3, §16–§26), copied byte for byte from the web
 * chat's branch enc-v3 (commit d7acb17; written by its tools/vectors/gen-encrypted.mjs). Every
 * section and every case is run, asserting what the web's tools/vectors/check.mjs asserts; on top
 * of that, what the generator made with fixed keys, nonces, dates and aux (lists, key rumor,
 * envelopes, messages, packs, asks, answers, the file) is made again here and compared byte for
 * byte. The generator's test keys are sha256("belfry-vector:<name>"), nonces
 * sha256("nonce:<label>:<i>"), aux 32 zero bytes, one-time keys sk("once:<label>").
 */
class EncryptedVectorsTest {
    private val v = Vectors.load("encrypted.json")

    private val known = setOf(
        "about", "keys", "secrets", "belfry", "lists", "changes", "listed_before", "signs_at", "listed_at", "alias",
        "lists_hash", "people_key", "group_key", "people_messages", "group_messages", "packs", "group_file", "declare",
        "people_of", "clear_after_key", "limits", "signs_at_gap", "signs_at_relisted", "signs_at_behind",
        "signs_at_slow_clock", "group_key_slow_clock", "invalid_above", "listed_at_gap", "pick_key", "asks",
    )

    // ---- reading the file ----
    private fun o(e: JsonElement?) = e as JsonObject
    private fun a(e: JsonElement?) = e as JsonArray
    private fun s(e: JsonElement?) = (e as JsonPrimitive).content
    private fun sOrNull(e: JsonElement?) = (e as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    private fun l(e: JsonElement?) = (e as JsonPrimitive).long
    private fun b(e: JsonElement?) = (e as JsonPrimitive).boolean
    private fun ev(e: JsonElement?): Event = assertNotNull(EncJson.event(e), "a signed event in the file")
    private fun evs(e: JsonElement?): List<Event> = a(e).map { ev(it) }
    private fun rumor(e: JsonElement?): Event = assertNotNull(EncJson.rumor(e), "a rumor in the file")
    private fun section(name: String) = o(v[name])
    private fun cases(e: JsonElement?) = a(e).map { o(it) }
    private fun norm(e: JsonElement?): Any? = when (e) {
        null, JsonNull -> null
        is JsonObject -> e.mapValues { norm(it.value) }
        is JsonArray -> e.map { norm(it) }
        is JsonPrimitive -> if (e.isString) e.content else e.booleanOrNull ?: e.longOrNull ?: e.content
    }
    private fun word(x: Verdict) = x.name.lowercase()

    // ---- the generator's fixed inputs ----
    private fun sk(name: String) = sha256("belfry-vector:$name".toByteArray(Charsets.UTF_8))
    private fun nonce(label: String): (Int) -> ByteArray = { i -> sha256("nonce:$label:$i".toByteArray(Charsets.UTF_8)) }
    private val aux = ByteArray(32)
    private fun fixed(label: String, at: Long, sealAt: Long = at - 100) = Fixed(at, sealAt, sk("once:$label"), nonce(label), aux)

    private val keys = section("keys")
    private fun key(name: String) = s(keys[name])
    private val belfry = s(v["belfry"])
    private val nameOf: Map<String, String> = keys.entries.associate { s(it.value) to it.key }
    private fun skOf(pub: String) = sk(nameOf.getValue(pub))
    private fun secret(name: String) = Hex.decode(s(section("secrets")[name]))
    private val sam get() = key("sam")
    private val lina get() = key("lina")
    private fun listEvent(name: String) = ev(o(o(section("lists")["valid"])[name])["event"])

    private fun chain(events: JsonElement?, account: String) = EncLists.chainOf(evs(events), belfry, account)
    /** §20: a chain, SERVICE (exactly the belfry key), or Unknown. */
    private fun chains(events: JsonElement?): (String) -> Devices = { acc -> Devices.of(acc, belfry, null, chain(events, acc)) }
    private fun view(c: Chain?): Any? = c?.let {
        mapOf("lists" to it.lists.map { l -> l.id }, "current" to it.current.id, "devices" to it.current.devices, "readers" to it.current.readers,
            "gaps" to it.gaps, "fork" to it.fork, "invalidAbove" to it.invalidAbove)
    }
    private fun tri(x: Boolean?): Any? = x

    @Test fun everySectionIsKnown() {
        assertEquals(known, v.keys, "a section was added to or removed from encrypted.json: teach this test")
    }

    @Test fun theFileIsTheWebsCopy() {
        assertEquals("16efbbc822d1e3e87e64fc6db6f6ee81cc6d1734364a411ab55e92472ebd42fe", sha256Hex(Vectors.file("encrypted.json").readBytes()))
        assertTrue(s(v["about"]).isNotEmpty())
    }

    @Test fun keysAndSecrets() {
        for ((name, pub) in keys) assertEquals(s(pub), Enc.pub(sk(name)), name)
        for ((name, secretHex) in section("secrets")) assertEquals(s(secretHex), Hex.encode(sk(name)), name)
        assertEquals(key("belfry"), belfry)
    }

    @Test fun lists() {
        val valid = o(section("lists")["valid"])
        assertEquals(18, valid.size)
        for ((name, c0) in valid) {
            val c = o(c0)
            val e = ev(c["event"])
            val got = EncLists.parseList(e, belfry)
            assertEquals(b(c["valid"]), got != null, name)
            if (got != null) {
                assertEquals(c["devices"]!!.let { (it as JsonPrimitive).int }, got.devices.size, name)
                assertEquals(c["readers"]!!.let { (it as JsonPrimitive).int }, got.readers.size, name)
            }
            // byte for byte: the same signature with the generator's key and aux
            assertEquals(e, Enc.signWith(skOf(e.pubkey), e.kind, e.createdAt, e.tags, e.content, aux), "$name signed again")
        }
        val chains = cases(section("lists")["chains"])
        assertEquals(7, chains.size)
        for (c in chains) assertEquals(norm(c["expect"]), view(chain(c["events"], s(c["account"]))), s(c["name"]))
    }

    @Test fun changes() {
        val c = section("changes")
        val got = EncLists.changes(chain(c["lists"], sam)!!).map {
            mapOf("list" to it.list, "n" to it.n.toLong(), "added" to it.added, "removed" to it.removed, "readersAdded" to it.readersAdded)
        }
        assertEquals(norm(c["expect"]), got)
        assertEquals(4, got.size)
    }

    private fun triSection(name: String, count: Int, f: (Chain, String, Long) -> Boolean?) {
        val sec = section(name)
        val c = chain(sec["lists"], sam)!!
        val all = cases(sec["cases"])
        assertEquals(count, all.size, name)
        for (x in all) assertEquals(norm(x["expect"]), tri(f(c, s(x["device"]), l(x["at"]))), "$name: ${s(x["device"]).take(6)} @${l(x["at"])}")
    }

    @Test fun listedBefore() {
        val sec = section("listed_before")
        val c = chain(sec["lists"], sam)!!
        val all = cases(sec["cases"])
        assertEquals(7, all.size)
        for (x in all) assertEquals(b(x["expect"]), EncLists.listedBefore(c, s(x["older"]), s(x["device"])), "${s(x["older"]).take(6)} before ${s(x["device"]).take(6)}")
    }

    @Test fun signsAt() = triSection("signs_at", 6, EncLists::signsAt)
    @Test fun signsAtGap() = triSection("signs_at_gap", 3, EncLists::signsAt)
    @Test fun signsAtRelisted() = triSection("signs_at_relisted", 6, EncLists::signsAt)
    @Test fun signsAtSlowClock() = triSection("signs_at_slow_clock", 4, EncLists::signsAt)
    @Test fun signsAtBehind() = triSection("signs_at_behind", 3, EncLists::signsAt)
    @Test fun listedAt() = triSection("listed_at", 7, EncLists::listedAt)
    @Test fun listedAtGap() = triSection("listed_at_gap", 3, EncLists::listedAt)

    @Test fun groupKeySlowClock() {
        val all = cases(v["group_key_slow_clock"])
        assertEquals(2, all.size)
        for (x in all) {
            val k = GroupKey("1".repeat(64), "a".repeat(64), "pod-garden-7f3a", s(x["maker"]), s(x["account"]), l(x["at"]), "b".repeat(64))
            val got = EncKeys.acceptGroupKey(k, Devices.of(chain(x["lists"], s(x["account"]))), l(x["now"]))
            assertEquals(s(x["expect"]), word(got), "@${l(x["at"])}")
        }
    }

    @Test fun invalidAbove() {
        val sec = section("invalid_above")
        val c = chain(sec["events"], sam)!!
        assertEquals(b(sec["invalid_above"]), c.invalidAbove)
        assertEquals(norm(sec["expect"]), view(c))
        for (e in evs(sec["events"])) assertEquals(e, Enc.signWith(skOf(e.pubkey), e.kind, e.createdAt, e.tags, e.content, aux))
    }

    @Test fun aliasAndListsHash() {
        val all = cases(v["alias"])
        assertEquals(2, all.size)
        for (x in all) assertEquals(s(x["expect"]), EncKeys.alias(s(x["K"]), s(x["account"])))
        val lh = section("lists_hash")
        assertEquals(s(lh["expect"]), EncKeys.listsHash(cases(lh["lists"]).map { s(it["account"]) to s(it["id"]) }))
    }

    private val convKey get() = EncKeys.newKey(sk("conv-key-1"))
    private val groupKeyPair get() = EncKeys.newKey(sk("group-key-1"))
    private fun keyRumorHere() = EncKeys.keyRumor(key("d2"), convKey.secret, listOf(KeyPerson(sam, listEvent("L3").id), KeyPerson(lina, listEvent("LC1").id)), 3100)

    @Test fun peopleKey() {
        val pk = section("people_key")
        // byte for byte: the key rumor and both envelopes
        val kr = keyRumorHere()
        assertEquals(rumor(pk["key_rumor"]), kr)
        assertEquals(s(o(pk["key_rumor"])["id"]), kr.id)
        val c3 = EncLists.chainOf(listOf(listEvent("L1"), listEvent("L2"), listEvent("L3")), belfry, sam)!!
        val envs = EncKeys.peopleEnvelopes(sk("d2"), kr, listOf(EnvelopeFor(sam, c3.current.receivers), EnvelopeFor(lina, listOf(key("c1")))), fixed("env1", 3100))
        assertEquals(evs(pk["envelopes"]), envs)

        val opens = cases(pk["open"])
        assertEquals(5, opens.size)
        for (x in opens) {
            val got: Any? = try {
                EncKeys.openPeopleEnvelope(secret(s(x["device"])), ev(a(pk["envelopes"])[l(x["envelope"]).toInt()]))
                    ?.let { mapOf("rumor_id" to it.rumor.id, "K" to Enc.one(it.rumor, "K")) }
            } catch (e: EncException) { mapOf("error" to true) }
            assertEquals(norm(x["expect"]), got, "envelope opened by ${s(x["device"])}")
        }
        val accepts = cases(pk["accept"])
        assertEquals(8, accepts.size)
        for (x in accepts) {
            val got = EncKeys.acceptKeyRumor(rumor(x["rumor"]), s(x["receiver"]), chains(x["lists"]), l(x["now"]))
            assertEquals(s(x["expect"]), word(got), s(x["name"]))
        }
    }

    @Test fun groupKey() {
        val gk = section("group_key")
        val group = s(gk["group"])
        val envs = evs(gk["envelopes"])
        assertEquals(l(gk["expect_events"]).toInt(), envs.size)
        assertEquals(b(gk["expect_each_under"]), envs.all { Enc.utf8(it.content) <= l(gk["max_content_bytes"]) })
        assertTrue(b(gk["expect_each_under"]))

        // byte for byte: 302 entries split over the events, then signed by Sam's account
        val gp = groupKeyPair
        val devices = listOf(key("d2"), key("c1")) + (0 until 300).map { Enc.pub(sk("g$it")) }
        val lists = EncKeys.listsHash(listOf(sam to listEvent("L3").id, lina to listEvent("LC1").id))
        val made = EncKeys.groupEnvelopes(sk("d2"), gp.secret, group, devices, lists, fixed("genv", 3200))
            .map { Enc.signWith(sk("sam"), it.kind, it.createdAt, it.tags, it.content, aux) }
        assertEquals(envs, made)

        val finds = cases(gk["find"])
        assertEquals(5, finds.size)
        for (x in finds) {
            val name = s(x["device"])
            // the generated devices (g150, g299) have no secret in the file: check.mjs skips them; here their secret is the generator's sk(name)
            val dev = if (section("secrets").containsKey(name)) secret(name) else sk(name)
            var got: Any? = null
            for ((i, e) in envs.withIndex()) {
                val g = try { EncKeys.openGroupEnvelope(dev, e, group) } catch (ex: EncException) { got = mapOf("error" to true); break }
                if (g != null) { got = mapOf("envelope" to i.toLong(), "K" to g.pub, "maker" to g.maker); break }
            }
            assertEquals(norm(x["expect"]), got, "group envelope for $name")
        }
        val wrong = o(gk["wrong_group"])
        val w = try { EncKeys.openGroupEnvelope(secret(s(wrong["device"])), envs[0], s(wrong["group"])); "opened" } catch (e: EncException) { "error" }
        assertEquals(s(wrong["expect"]), w)

        val g = assertNotNull(EncKeys.openGroupEnvelope(secret("c1"), envs[0], group))
        assertEquals(s(gk["expect_set"]), g.set)
        assertEquals(b(gk["expect_set_all_events"]), EncKeys.deviceSet(EncKeys.entryKeys(envs)) == g.set)
        assertEquals(b(gk["expect_set_first_event_only"]), EncKeys.deviceSet(EncKeys.entryKeys(listOf(envs[0]))) == g.set)
        val accepts = cases(gk["accept"])
        assertEquals(5, accepts.size)
        for (x in accepts) {
            val got = EncKeys.acceptGroupKey(g.copy(maker = s(x["maker"]), at = l(x["at"])), Devices.of(chain(x["lists"], s(x["account"]))), l(x["now"]))
            assertEquals(s(x["expect"]), word(got), "maker ${s(x["maker"]).take(6)} @${l(x["at"])}")
        }
    }

    /** The generator's labels for people_messages.accept, in order (tools/vectors/gen-encrypted.mjs). */
    private val acceptLabels = listOf("a1", "a2", "a3", "a4", "a8", "a9", "a10", "a5", "a6", "a11", "a11", "a12", "a7")

    @Test fun peopleMessages() {
        val pm = section("people_messages")
        val k = s(pm["k"])
        val K = s(pm["K"])
        assertEquals(convKey.pub, K)
        val r1 = rumor(pm["rumor"])
        assertEquals(s(o(pm["rumor"])["id"]), r1.id)
        val msgs = evs(pm["messages"])
        // byte for byte: both copies
        assertEquals(msgs, EncMessages.peopleMessage(sk("d2"), r1, K, listOf(sam, lina), sam, fixed("m1", 3300)))
        assertEquals(norm(pm["expect_tags"]), msgs.map { it.tags })
        val opens = cases(pm["open"])
        assertEquals(msgs.size, opens.size)
        msgs.forEachIndexed { i, m ->
            val got: Any = try { EncMessages.openPeopleMessage(k, m).let { mapOf("seal_signer" to it.seal.pubkey, "rumor_id" to it.rumor.id) } } catch (e: EncException) { mapOf("error" to true) }
            assertEquals(norm(opens[i]["expect"]), got, "copy $i")
        }
        for (other in listOf("11".repeat(32), EncKeys.newKey(sk("nope")).secret)) {
            val got: Any = try { EncMessages.openPeopleMessage(other, msgs[1]); "opened" } catch (e: EncException) { mapOf("error" to true) }
            assertEquals(norm(pm["open_wrong_key"]), got)
        }

        val accepts = cases(pm["accept"])
        assertEquals(acceptLabels.size, accepts.size)
        accepts.forEachIndexed { i, x ->
            val m = ev(x["message"])
            val opened = EncMessages.openPeopleMessage(k, m)
            val people = sOrNull(x["key_people"]) ?: s(pm["key_people"])
            val lists = if (x["lists_without_lina"]?.let { b(it) } == true) section("signs_at")["lists"] else o(a(section("people_key")["accept"])[0])["lists"]
            val got = EncMessages.acceptPeopleMessage(m, opened.seal, opened.rumor, people, chains(lists)(opened.rumor.pubkey))
            assertEquals(s(x["expect"]), word(got), s(x["name"]))
            // byte for byte: the same copy made again
            val p = Enc.one(m, "p")!!
            val me = if (m.tags.contains(Enc.SILENT)) p else ""
            val again = EncMessages.peopleMessage(skOf(opened.seal.pubkey), opened.rumor, K, listOf(p), me,
                Fixed(m.createdAt, opened.seal.createdAt, sk("once:${acceptLabels[i]}"), nonce(acceptLabels[i]), aux))
            assertEquals(m, again.single(), "${s(x["name"])} made again")
        }
    }

    private val groupLabels = listOf("gm", "gm2", "gm3")

    @Test fun groupMessages() {
        val gm = section("group_messages")
        val k = s(gm["k"])
        val K = s(gm["K"])
        val group = s(gm["group"])
        assertEquals(groupKeyPair.pub, K)
        assertEquals(groupKeyPair.secret, k)
        val all = cases(gm["cases"])
        assertEquals(3, all.size)
        all.forEachIndexed { i, x ->
            val m = ev(x["message"])
            val opened = EncMessages.openGroupMessage(k, m)
            val got = EncMessages.acceptGroupMessage(m, opened.seal, opened.rumor, group, Devices.of(chain(section("signs_at")["lists"], sam)))
            assertEquals(s(x["expect"]), word(got), s(x["name"]))
            x["expect_tags"]?.let { assertEquals(norm(it), m.tags) }
            if (i < 2) assertEquals(s(gm["rumor_id"]), opened.rumor.id)
            val t = EncMessages.groupMessage(skOf(opened.seal.pubkey), opened.rumor, K, group, fixed(groupLabels[i], m.createdAt), Enc.values(m, "p"))
            assertEquals(m, Enc.signWith(skOf(m.pubkey), t.kind, t.createdAt, t.tags, t.content, aux), "${s(x["name"])} made again")
        }
    }

    private fun packKeys(): List<PackKey> {
        val gp = groupKeyPair
        val v1 = EncKeys.newKey(sk("letterbox-1"))
        return listOf(PackKey.People(rumor(section("people_key")["key_rumor"])), PackKey.Group(gp.secret, gp.pub, "pod-garden-7f3a"), PackKey.V1(v1.secret, v1.pub))
    }

    @Test fun packs() {
        val sec = section("packs")
        val c = chain(sec["lists"], sam)
        val d4 = secret("d4")
        fun view(p: Event): Any? = EncPacks.openPack(d4, sam, p, c)?.let { o ->
            mapOf("from" to o.from, "keys" to o.keys.map {
                when (it) {
                    is PackKey.People -> mapOf("kind" to "people", "rumor_id" to it.rumor.id)
                    is PackKey.Group -> mapOf("kind" to "group", "K" to it.pub, "group" to it.group)
                    is PackKey.V1 -> mapOf("kind" to "v1", "lb" to it.lb)
                }
            })
        }
        val all = cases(sec["cases"])
        assertEquals(5, all.size)
        // the generator's packs p1, p2 (a bad key added), p3, p5, p6: label, sharer, keys
        val bad = PackKey.Group(groupKeyPair.secret, key("other"), "pod-garden-7f3a")
        val made = listOf(Triple("p1", "d2", packKeys()), Triple("p2", "d2", packKeys() + bad), Triple("p3", "c1", packKeys()),
            Triple("p5", "d3", packKeys()), Triple("p6", "agent", packKeys()))
        all.forEachIndexed { i, x ->
            val p = ev(x["pack"])
            assertEquals(norm(x["expect"]), view(p), s(x["name"]))
            val (label, sharer, keys) = made[i]
            assertEquals(p, EncPacks.packs(sk(sharer), sam, key("d4"), keys, fixed(label, p.createdAt))[0], "${s(x["name"])} made again")
        }
        // the split (not checked by check.mjs): 400 version-1 secrets, packs of at most 40 000 bytes
        val split = o(sec["split"])
        val many = (0 until l(split["keys"]).toInt()).map { EncKeys.newKey(sk("lb$it")).let { k -> PackKey.V1(k.secret, k.pub) } }
        val p4 = EncPacks.packs(sk("d2"), sam, key("d4"), many, fixed("p4", 4100))
        assertEquals(l(split["expect_packs"]).toInt(), p4.size)
        assertEquals(l(split["expect_total_keys"]).toInt(), p4.sumOf { EncPacks.openPack(d4, sam, it, c)?.keys?.size ?: 0 })
    }

    @Test fun groupFile() {
        val gf = section("group_file")
        val m = ev(gf["message"])
        val opened = EncMessages.openGroupMessage(s(gf["k"]), m)
        assertEquals(s(gf["rumor_id"]), opened.rumor.id)
        val im = opened.rumor.tags.first { it[0] == "imeta" }
        val f = im.drop(1).associate { it.substringBefore(' ') to it.substringAfter(' ') }
        val tags = listOf(listOf("encryption-algorithm", f.getValue("encryption-algorithm")), listOf("decryption-key", f.getValue("decryption-key")),
            listOf("decryption-nonce", f.getValue("decryption-nonce")), listOf("x", f.getValue("x")), listOf("ox", f.getValue("ox")), listOf("file-type", f.getValue("m")))
        val cipher = Hex.decode(s(gf["ciphertext_hex"]))
        val plain = DmFiles.open(tags, cipher)
        val expect = o(gf["expect"])
        assertEquals(b(expect["opens"]), plain != null)
        assertEquals(s(gf["plain_hex"]), Hex.encode(plain!!))
        assertEquals(s(expect["mime"]), f.getValue("m").lowercase())
        val tampered = cipher.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertEquals(b(expect["tampered_opens"]), DmFiles.open(tags, tampered) != null)
        // byte for byte: the ciphertext and the message
        assertEquals(s(gf["ciphertext_hex"]), Hex.encode(DmFiles.encrypt(plain, sha256Hex("file-key"), Hex.encode(sha256("file-nonce".toByteArray()).copyOf(12)))))
        val t = EncMessages.groupMessage(sk("d2"), opened.rumor, s(gf["K"]), s(gf["group"]), fixed("gfile", 3450))
        assertEquals(m, Enc.signWith(sk("sam"), t.kind, t.createdAt, t.tags, t.content, aux))
    }

    @Test fun pickKey() {
        val sec = section("pick_key")
        val held = cases(sec["held"]).map {
            HeldKey(s(it["K"]), s(it["k"]), s(it["conv"]), b(it["mine"]), s(it["lists"]), l(it["at"]),
                group = it["group"]?.let { g -> b(g) } ?: false, service = it["service"]?.let { g -> b(g) } ?: false)
        }
        assertEquals(7, held.size)
        val all = cases(sec["cases"])
        assertEquals(5, all.size)
        for (x in all) {
            val got = EncKeys.pickKey(held, sOrNull(x["conv"]) ?: s(sec["conv"]), sOrNull(x["lists"]) ?: s(sec["lists"]), l(x["now"]))?.pub
            assertEquals(sOrNull(x["expect"]), got, s(x["name"]))
        }
    }

    @Test fun asksAndAnswers() {
        val sec = section("asks")
        val c = chain(sec["lists"], sam)
        val all = cases(sec["cases"])
        assertEquals(8, all.size)
        for (x in all) {
            val e = ev(x["event"])
            val got: Any? = if (e.kind == Enc.ASK) {
                EncPacks.readAsk(e, sam, c)?.let { mapOf("device" to it.device, "id" to it.id, "at" to it.at) }
            } else {
                EncPacks.readAnswer(e, sam, c, sOrNull(x["about"]))?.let {
                    buildMap<String, Any> { put("by", it.by); it.ask?.let { q -> put("ask", q) }; it.device?.let { d -> put("device", d) }; put("answer", it.answer.wire); put("at", it.at) }
                }
            }
            assertEquals(norm(x["expect"]), got, s(x["name"]))
            // byte for byte
            val account = Enc.one(e, "p")!!
            val again = if (e.kind == Enc.ASK) EncPacks.ask(skOf(e.pubkey), account, e.createdAt, aux) else {
                val to = Enc.one(e, "e")?.let { AnswerTo.Ask(it) } ?: AnswerTo.Device(Enc.one(e, "device")!!)
                EncPacks.answer(skOf(e.pubkey), account, to, AnswerWord.entries.first { it.wire == Enc.one(e, "answer") }, e.createdAt, aux)
            }
            assertEquals(e, again, "${s(x["name"])} made again")
        }
    }

    @Test fun declareOnce() {
        val book = mutableMapOf<String, String>()
        val all = cases(v["declare"])
        assertEquals(3, all.size)
        for (x in all) assertEquals(b(x["expect"]), EncRules.declare(book, s(x["K"]), s(x["conv"])), "${s(x["K"]).take(6)} for ${s(x["conv"]).take(12)}")
    }

    @Test fun peopleOf() {
        val all = cases(v["people_of"])
        assertEquals(5, all.size)
        for (x in all) assertEquals(s(x["expect"]), EncKeys.peopleOf(rumor(x["rumor"])), s(x["name"]))
    }

    @Test fun clearAfterKey() {
        val all = cases(v["clear_after_key"])
        assertEquals(4, all.size)
        for (x in all) assertEquals(b(x["expect"]), EncRules.clearAfterKey(x["first_key_at"]?.let { (it as? JsonPrimitive)?.longOrNull }, l(x["at"])))
    }

    @Test fun limits() {
        val sec = section("limits")
        val people = cases(sec["people"])
        assertEquals(4, people.size)
        for (x in people) assertEquals(s(x["expect"]), EncRules.peopleLimit(l(x["devices"]).toInt(), b(x["ever_encrypted"])).name.lowercase())
        val group = cases(sec["group"])
        assertEquals(2, group.size)
        for (x in group) assertEquals(s(x["expect"]), EncRules.groupLimit(l(x["devices"]).toInt()).name.lowercase())
    }

    @Test fun theThreeAnswers() {
        // §20: the belfry key is SERVICE even with a chain; a member without a list is Unknown (wait), never SERVICE
        val lists = section("signs_at")["lists"]
        assertEquals(Devices.Service, chains(lists)(belfry))
        assertEquals(Devices.Unknown, chains(lists)(lina))
        assertTrue(chains(lists)(sam) is Devices.Listed)
        assertEquals(Devices.Service, Devices.of("f".repeat(64), belfry, "f".repeat(64), null))
        assertEquals(Devices.Unknown, Devices.of("f".repeat(64), belfry, "", null))
        assertNull(EncLists.chainOf(evs(lists), belfry, lina))
    }
}
