package sh.belfry.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import sh.belfry.core.crypto.Bech32
import sh.belfry.core.crypto.Hex
import sh.belfry.core.crypto.Keys
import sh.belfry.core.crypto.Nip04
import sh.belfry.core.crypto.Nip44
import sh.belfry.core.crypto.sha256Hex
import sh.belfry.core.dm.GiftWrap
import sh.belfry.core.net.Pkce
import sh.belfry.core.nostr.Event
import sh.belfry.core.nostr.EventTemplate
import sh.belfry.core.nostr.LocalSigner
import sh.belfry.core.nostr.Nip98
import sh.belfry.core.nostr.arr
import sh.belfry.core.nostr.computedId
import sh.belfry.core.nostr.int
import sh.belfry.core.nostr.isValid
import sh.belfry.core.nostr.obj
import sh.belfry.core.nostr.parseJsonOrNull
import sh.belfry.core.nostr.s
import sh.belfry.core.nostr.bool
import sh.belfry.core.crypto.B64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

private fun JsonObject.list(key: String): List<JsonObject> = (this[key] as JsonArray).map { it as JsonObject }
private fun JsonObject.o(key: String): JsonObject = this[key] as JsonObject
private fun JsonObject.str(key: String): String = this.s(key) ?: error("no $key")

private fun throws(what: String, block: () -> Unit) {
    try {
        block()
    } catch (e: Exception) {
        return
    }
    fail("expected a failure: $what")
}

class Nip44Test {
    private val v2: JsonObject = Vectors.load("nip44.json").o("v2")

    @Test
    fun conversationKeys() {
        val cases = v2.o("valid").list("get_conversation_key")
        assertTrue(cases.isNotEmpty())
        for (c in cases) {
            assertEquals(c.str("conversation_key"), Hex.encode(Nip44.conversationKey(c.str("sec1"), c.str("pub2"))), c.s("note") ?: "")
        }
    }

    @Test
    fun invalidConversationKeys() {
        val cases = v2.o("invalid").list("get_conversation_key")
        assertTrue(cases.isNotEmpty())
        for (c in cases) throws(c.s("note") ?: "") { Nip44.conversationKey(c.str("sec1"), c.str("pub2")) }
    }

    @Test
    fun messageKeys() {
        val mk = v2.o("valid").o("get_message_keys")
        val ck = Hex.decode(mk.str("conversation_key"))
        for (k in mk.list("keys")) {
            val keys = Nip44.messageKeys(ck, Hex.decode(k.str("nonce")))
            assertEquals(k.str("chacha_key"), Hex.encode(keys.chachaKey))
            assertEquals(k.str("chacha_nonce"), Hex.encode(keys.chachaNonce))
            assertEquals(k.str("hmac_key"), Hex.encode(keys.hmacKey))
        }
    }

    @Test
    fun paddedLength() {
        val cases = v2.o("valid")["calc_padded_len"].arr()!!
        assertTrue(cases.isNotEmpty())
        for (c in cases) {
            val pair = c.arr()!!
            assertEquals(pair[1].int()!!, Nip44.calcPaddedLen(pair[0].int()!!), "len ${pair[0]}")
        }
    }

    @Test
    fun encryptDecrypt() {
        val cases = v2.o("valid").list("encrypt_decrypt")
        assertTrue(cases.isNotEmpty())
        for (c in cases) {
            val ck = Nip44.conversationKey(c.str("sec1"), Keys.publicKeyHex(c.str("sec2")))
            assertEquals(c.str("conversation_key"), Hex.encode(ck))
            val payload = Nip44.encrypt(c.str("plaintext"), ck, Hex.decode(c.str("nonce")))
            assertEquals(c.str("payload"), payload)
            assertEquals(c.str("plaintext"), Nip44.decrypt(payload, ck))
            // and from the other side
            assertEquals(c.str("plaintext"), Nip44.decryptFrom(c.str("sec2"), Keys.publicKeyHex(c.str("sec1")), payload))
        }
    }

    @Test
    fun longMessages() {
        val cases = v2.o("valid").list("encrypt_decrypt_long_msg")
        assertTrue(cases.isNotEmpty())
        for (c in cases) {
            val plaintext = c.str("pattern").repeat(c["repeat"].int()!!)
            assertEquals(c.str("plaintext_sha256"), sha256Hex(plaintext))
            val ck = Hex.decode(c.str("conversation_key"))
            val payload = Nip44.encrypt(plaintext, ck, Hex.decode(c.str("nonce")))
            assertEquals(c.str("payload_sha256"), sha256Hex(payload))
            assertEquals(plaintext, Nip44.decrypt(payload, ck))
        }
    }

    @Test
    fun invalidDecrypt() {
        val cases = v2.o("invalid").list("decrypt")
        assertTrue(cases.isNotEmpty())
        for (c in cases) {
            val ck = Hex.decode(c.str("conversation_key"))
            throws(c.s("note") ?: "") { Nip44.decrypt(c.str("payload"), ck) }
        }
    }

    @Test
    fun invalidLengths() {
        val ck = ByteArray(32) { 1 }
        val lengths = v2.o("invalid")["encrypt_msg_lengths"].arr()!!
        assertTrue(lengths.isNotEmpty())
        for (n in lengths) {
            val len = n.int()!!
            if (len > 200_000) continue // 10 MB of "a": the 65536 and 100000 cases prove the bound
            throws("length $len") { Nip44.encrypt("a".repeat(len), ck) }
        }
    }

    @Test
    fun nip04RoundTrip() {
        val a = LocalSigner.random()
        val b = LocalSigner.random()
        val payload = Nip04.encrypt(a.secretHex, b.pubkey, "old style é")
        assertEquals("old style é", Nip04.decrypt(b.secretHex, a.pubkey, payload))
    }
}

class EventsTest {
    private val v = Vectors.load("events.json")

    private fun event(o: JsonObject): Event = Event.fromJson(o) ?: error("not an event")

    @Test
    fun signedAndTampered() {
        val signed = event(v.o("signed").o("event"))
        assertEquals(signed.id, signed.computedId())
        assertTrue(signed.isValid())
        assertEquals(v.o("signed")["valid"].bool(), signed.isValid())
        assertFalse(event(v.o("tampered").o("event")).isValid())
        // JSON round trip keeps every field
        assertEquals(signed, Event.fromJsonString(signed.toJsonString()))
    }

    @Test
    fun ids() {
        val cases = v.list("id_only")
        assertTrue(cases.isNotEmpty())
        for (c in cases) {
            val t = event(c.o("template"))
            assertEquals(c.str("id"), Event.computeId(t.pubkey, t.createdAt, t.kind, t.tags, t.content))
        }
    }

    @Test
    fun people() {
        for ((name, p) in v.o("people")) {
            val o = p as JsonObject
            assertEquals(o.str("pubkey"), Keys.publicKeyHex(o.str("secret")), name)
            assertEquals(o.str("npub"), Bech32.npub(o.str("pubkey")), name)
        }
    }

    @Test
    fun bech32() {
        val b = v.list("bech32")
        assertEquals(b[0].str("npub"), Bech32.npub(b[0].str("hex")))
        assertEquals(b[0].str("hex"), Bech32.npubToHex(b[0].str("npub")))
        assertEquals(b[1].str("nsec"), Bech32.encode("nsec", Hex.decode(b[1].str("secret"))))
        assertNull(Bech32.npubToHex("npub1notavalidkey"))
    }

    @Test
    fun signAndVerifyRoundTrip() {
        val s = LocalSigner.random()
        val e = s.signNow(EventTemplate(1, "hi \"there\"\n/é", listOf(listOf("t", "x")), 1_700_000_000))
        assertTrue(e.isValid())
        assertEquals(e, Event.fromJsonString(e.toJsonString()))
        assertFalse(e.copy(content = "other").isValid())
        assertFalse(e.copy(sig = e.sig.reversed()).isValid())
    }

    @Test
    fun giftWraps() = runBlocking {
        val cases = v.list("gift_wraps")
        assertEquals(3, cases.size)
        for (c in cases) {
            val signer = LocalSigner(c.str("recipient_secret"))
            val wrap = event(c.o("wrap"))
            assertTrue(wrap.isValid(), c.str("name"))
            val opened = GiftWrap.unwrap(wrap, signer)
            val expect = c.o("expect")
            val wanted = expect["rumor"]
            if (wanted == null || wanted is JsonNull) {
                assertNull(opened, c.str("name"))
                continue
            }
            val rumor = event(wanted.obj()!!)
            assertNotNull(opened, c.str("name"))
            assertEquals(rumor.id, opened.rumor.id, c.str("name"))
            assertEquals(rumor.content, opened.rumor.content)
            assertEquals(rumor.kind, opened.rumor.kind)
            assertEquals(rumor.tags, opened.rumor.tags)
            assertEquals(expect.str("seal_pubkey"), opened.sealPubkey)
        }
    }

    @Test
    fun wrapThenUnwrap() = runBlocking {
        val alice = LocalSigner.random()
        val bob = LocalSigner.random()
        val carol = LocalSigner.random()
        val now = 1_800_000_000L
        val rumor = GiftWrap.rumor(14, alice.pubkey, listOf(listOf("p", bob.pubkey)), "hello", now)
        val wrap = GiftWrap.wrap(rumor, bob.pubkey, alice)
        assertEquals(1059, wrap.kind)
        assertTrue(wrap.isValid())
        assertEquals(bob.pubkey, wrap.tag("p"))
        assertTrue(wrap.pubkey != alice.pubkey)
        val opened = GiftWrap.unwrap(wrap, bob)
        assertEquals("hello", opened?.rumor?.content)
        assertEquals(rumor.id, opened?.rumor?.id)
        assertEquals(alice.pubkey, opened?.sealPubkey)
        // someone else cannot open it
        val other = try { GiftWrap.unwrap(wrap, carol) } catch (e: Exception) { null }
        assertNull(other)
    }

    @Test
    fun pkce() {
        // RFC 7636 appendix B
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        assertTrue(Pkce.challenge(Pkce.verifier()).length >= 43)
        assertTrue(Pkce.verifier().length >= 43)
    }

    @Test
    fun nip98Header() {
        val s = LocalSigner.random()
        val body = "{\"channel\":\"general\"}"
        val header = Nip98.headerNow(s, "https://belfry.example.org/api/v1/channel-invites", "post", body)
        assertTrue(header.startsWith("Nostr "))
        val ev = Event.fromJson(parseJsonOrNull(String(B64.decode(header.removePrefix("Nostr ")), Charsets.UTF_8))!!)!!
        assertTrue(ev.isValid())
        assertEquals(27235, ev.kind)
        assertEquals(s.pubkey, ev.pubkey)
        assertEquals(
            listOf(listOf("u", "https://belfry.example.org/api/v1/channel-invites"), listOf("method", "POST"), listOf("payload", sha256Hex(body))),
            ev.tags,
        )
        // no body: no payload tag
        assertEquals(2, Nip98.template("https://x.example/y", "GET").tags.size)
        val up = Nip98.blossomUpload("photo.jpg", "ab".repeat(32), 1000)
        assertEquals(24242, up.kind)
        assertEquals("Upload photo.jpg", up.content)
        assertEquals(listOf(listOf("t", "upload"), listOf("x", "ab".repeat(32)), listOf("expiration", "1600")), up.tags)
    }
}
