package sh.belfry.core.net

import kotlinx.serialization.json.JsonObject
import sh.belfry.core.nostr.parseJsonOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `POST /mail-alias` (SPEC 19.60; docs/mail.md "A channel's address"): the three answers and the errors. */
class MailAliasTest {
    private fun j(s: String) = parseJsonOrNull(s) as JsonObject

    @Test fun info() {
        val i = MailAlias.parseInfo(j("""{"channel":"garden-3f2a","alias":"garden","address":"garden@belfry.example","custom":false,"may_set":true}"""))!!
        assertEquals(MailAliasInfo("garden-3f2a", "garden", "garden@belfry.example", false, true), i)
        // round trip through the stored copy
        assertEquals(i, MailAlias.parseInfo(i.toJson()))
        // no address: not an answer
        assertNull(MailAlias.parseInfo(j("""{"channel":"x","alias":"x"}""")))
        // a missing alias comes from the address; missing flags are false
        assertEquals(MailAliasInfo("x", "soup", "soup@b.example", false, false), MailAlias.parseInfo(j("""{"channel":"x","address":"soup@b.example"}""")))
    }

    @Test fun check() {
        assertEquals(AliasCheck(AliasState.AVAILABLE, "soup"), MailAlias.parseCheck(j("""{"state":"available","alias":"soup"}""")))
        assertEquals(AliasState.TAKEN, MailAlias.parseCheck(j("""{"state":"taken","alias":"x"}"""))!!.state)
        assertEquals(AliasState.RESERVED, MailAlias.parseCheck(j("""{"state":"reserved","alias":"abuse"}"""))!!.state)
        assertEquals(AliasState.INVALID, MailAlias.parseCheck(j("""{"state":"invalid","alias":"a b"}"""))!!.state)
        assertNull(MailAlias.parseCheck(j("""{"state":"maybe"}""")))
        assertEquals("✓ Available", AliasState.AVAILABLE.words)
    }

    @Test fun setAnswersLikeInfo() {
        val i = MailAlias.parseInfo(j("""{"channel":"garden-3f2a","alias":"veg","address":"veg@belfry.example","custom":true,"may_set":true}"""))!!
        assertTrue(i.custom)
        assertEquals("veg@belfry.example", i.address)
    }

    @Test fun errorsHaveFixedWords() {
        assertEquals("That address is taken.", MailAlias.error(409, j("""{"error":"taken","message":"server words"}""")).message)
        assertEquals("reserved", MailAlias.error(409, j("""{"error":"reserved"}""")).code)
        assertEquals("archived", MailAlias.error(409, j("""{"error":"archived"}""")).code)
        assertEquals("not_allowed", MailAlias.error(403, null).code)
        assertEquals("invalid", MailAlias.error(422, null).code)
        assertEquals("signer", MailAlias.error(503, null).code)
        assertEquals(MailAlias.FALLBACK, MailAlias.error(500, j("""{"error":"boom"}""")).message)
        // an older belfry
        assertTrue(MailAlias.unsupported(404, null))
        assertTrue(MailAlias.unsupported(409, j("""{"error":"unsupported"}""")))
        assertFalse(MailAlias.unsupported(409, j("""{"error":"taken"}""")))
    }
}
