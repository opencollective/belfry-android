package sh.belfry.core.enc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import sh.belfry.core.net.Community
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SPEC 19.82: the format a device writes, and the write-decision table, row by row. */
class EncWritesTest {
    private val V1 = EncWrite.V1
    private val V3 = EncWrite.V3()
    private val CLEAR = EncWrite.Clear
    private fun hold(r: HoldReason) = EncWrite.Hold(r)
    private fun f(format: Int?, demo: Boolean = false, guest: Boolean = false) = EncWrites.Facts(demo, guest, format)

    // ---- the format -----------------------------------------------------------------------------------

    @Test fun theFormatComesFromBelfryJson() {
        fun doc(s: String) = Community.parse("x.org", Json.parseToJsonElement(s).jsonObject).encrypted
        assertEquals(EncFormatState.DOC_3, doc("""{"encrypted":{"format":3}}"""))
        assertEquals(EncFormatState.DOC_1, doc("""{"encrypted":{"format":1}}"""))
        assertEquals(EncFormatState.DOC_OTHER, doc("""{"encrypted":{"format":2}}"""))
        assertEquals(EncFormatState.DOC_OTHER, doc("""{"encrypted":{"format":"3"}}"""))
        assertEquals(EncFormatState.DOC_OTHER, doc("""{"encrypted":{}}"""))
        assertEquals(EncFormatState.DOC_NONE, doc("""{"name":"x"}"""))
        assertEquals(EncFormatState.DOC_NONE, doc("""{"encrypted":null}"""))
        assertNull(Community.parse("x.org", null as kotlinx.serialization.json.JsonObject?).encrypted, "no document is no read")
        val c = Community.parse("x.org", Json.parseToJsonElement("""{"encrypted":{"format":3}}""").jsonObject)
        assertEquals(c, Community.fromJsonString(c.toJsonString()), "kept with the copy (a 304 needs it)")
    }

    @Test fun aOneCountsOnlyWhileFresh() {
        val s = EncFormatState().after(EncFormatState.DOC_1, 1000)
        assertEquals(1, s.writeFormat(1000))
        assertEquals(1, s.writeFormat(1299))
        assertNull(s.writeFormat(1300), "read 5 minutes ago: read again")
        assertTrue(s.wantsRead(1300))
        assertNull(s.writeFormat(600), "a read dated far ahead of the clock is no fresh read")
    }

    @Test fun threeIsForGood() {
        val s = EncFormatState().after(EncFormatState.DOC_3, 1000)
        assertEquals(3, s.writeFormat(1_000_000))
        assertEquals(3, s.after(EncFormatState.DOC_1, 2000).writeFormat(2000), "§17.13: one way")
        assertEquals(3, s.after(EncFormatState.DOC_NONE, 2000).writeFormat(2000))
        assertEquals(3, s.after(EncFormatState.DOC_OTHER, 2000).writeFormat(2000))
        assertFalse(s.wantsRead(9_999_999))
    }

    @Test fun noObjectEverSeenIsOne() {
        // the web's P3 (515400d): a belfry that never showed an `encrypted` object writes 1 at once
        assertEquals(1, EncFormatState().writeFormat(5))
        val s = EncFormatState().after(EncFormatState.DOC_NONE, 1000)
        assertEquals(1, s.writeFormat(1_000_000))
        // once an object was seen, its absence decides nothing, and a stale 1 holds
        val seen = EncFormatState().after(EncFormatState.DOC_1, 1000).after(EncFormatState.DOC_NONE, 2000)
        assertEquals(1000, seen.readAt)
        assertNull(seen.writeFormat(2000))
        val other = EncFormatState().after(EncFormatState.DOC_OTHER, 1000)
        assertTrue(other.seen)
        assertNull(other.writeFormat(1000), "an object with a format this app doesn't know: unknown")
    }

    // ---- people conversations (the web's deliver + encryptFor's guest rule) ----------------------------

    @Test fun people() {
        val n = "n:" + "a".repeat(64)
        val m = "m:someone@example.org|subject"
        // demo
        assertEquals(V1, EncWrites.people(n, f(3, demo = true), ever = true, guestIn = true))
        // format 1
        assertEquals(V1, EncWrites.people(n, f(1), ever = false, guestIn = false))
        assertEquals(V1, EncWrites.people(n, f(1), ever = false, guestIn = true))
        assertEquals(V1, EncWrites.people(m, f(1), ever = false, guestIn = false))
        // format 3
        assertEquals(V3, EncWrites.people(n, f(3), ever = false, guestIn = false))
        assertEquals(V3, EncWrites.people(n, f(3), ever = true, guestIn = false))
        assertEquals(CLEAR, EncWrites.people(n, f(3), ever = false, guestIn = true), "§25: a guest, never encrypted: no lock")
        assertEquals(hold(HoldReason.GUEST), EncWrites.people(n, f(3), ever = true, guestIn = true), "§25: once encrypted, never clear")
        assertEquals(hold(HoldReason.MAIL), EncWrites.people(m, f(3), ever = false, guestIn = false))
        // unknown (or a stale 1)
        assertEquals(hold(HoldReason.FORMAT), EncWrites.people(n, f(null), ever = false, guestIn = false))
        assertEquals(hold(HoldReason.FORMAT), EncWrites.people(m, f(null), ever = false, guestIn = false))
        // ever encrypted here: encrypted whatever the format says (§17.2, §18.6)
        assertEquals(V3, EncWrites.people(n, f(1), ever = true, guestIn = false))
        assertEquals(V3, EncWrites.people(n, f(null), ever = true, guestIn = false))
        assertEquals(hold(HoldReason.GUEST), EncWrites.people(n, f(1), ever = true, guestIn = true))
        // a guest's session (§25: no runtime): clear on format 3, as today on 1, held once encrypted
        assertEquals(CLEAR, EncWrites.people(n, f(3, guest = true), ever = false, guestIn = false))
        assertEquals(V1, EncWrites.people(n, f(1, guest = true), ever = false, guestIn = false))
        assertEquals(hold(HoldReason.GUEST), EncWrites.people(n, f(3, guest = true), ever = true, guestIn = false))
        // anything else: as today
        assertEquals(V1, EncWrites.people("x:1", f(null), ever = false, guestIn = false))
    }

    // ---- groups (the web's groupWriteMode + the guest guard) --------------------------------------------

    @Test fun groups() {
        fun g(format: Int?, ever: Boolean = false, priv: Boolean = false, known: Boolean = true, clear: Boolean = false, guest: Boolean = false, demo: Boolean = false) =
            EncWrites.group(f(format, demo, guest), ever, priv, known, clear)
        assertEquals(V1, g(3, ever = true, priv = true, demo = true))
        // a key of the group seen: encrypted on any format (§26)
        assertEquals(V3, g(1, ever = true))
        assertEquals(V3, g(null, ever = true, known = false))
        assertEquals(V3, g(3, ever = true))
        // format 3
        assertEquals(V3, g(3, priv = true))
        assertEquals(CLEAR, g(3, priv = false))
        assertEquals(hold(HoldReason.GROUP_UNKNOWN), g(3, known = false))
        // unknown format (an object was seen, or a stale 1)
        assertEquals(hold(HoldReason.FORMAT), g(null, priv = true))
        assertEquals(hold(HoldReason.FORMAT), g(null, known = false))
        assertEquals(V1, g(null, priv = false), "a known group that isn't private is the same on both formats")
        // format 1: as today
        assertEquals(V1, g(1, priv = true))
        assertEquals(V1, g(1, known = false))
        // §16.9's clear list
        assertEquals(CLEAR, g(3, priv = true, clear = true))
        assertEquals(CLEAR, g(1, ever = true, clear = true))
        assertEquals(V1, g(1, priv = true, clear = true))
        // a guest's session: no part in a private group (encrypted or waiting to be)
        assertEquals(hold(HoldReason.GUEST), g(3, priv = true, guest = true))
        assertEquals(hold(HoldReason.GUEST), g(1, ever = true, guest = true))
        assertEquals(hold(HoldReason.GUEST), g(null, priv = true, guest = true))
        assertEquals(CLEAR, g(3, priv = false, guest = true))
        assertEquals(V1, g(1, priv = true, guest = true), "format 1: a private group is plain, as today")
        assertEquals(CLEAR, g(3, priv = true, clear = true, guest = true), "leaving (9022) stays possible")
    }

    @Test fun heldWords() {
        val name = { _: String -> "Lina" }
        assertEquals("Lina isn’t signed in on any device · It will be sent when they are", EncText.held(EncWrite.Hold(HoldReason.NO_DEVICE, "x"), name))
        assertEquals(EncText.NOT_SET_UP, EncText.held(EncWrite.Hold(HoldReason.NOT_LISTED), name))
        assertEquals(EncText.GROUP_GUEST, EncText.held(EncWrite.Hold(HoldReason.GUEST), name, group = true))
        for (r in HoldReason.entries) assertFalse(EncText.held(EncWrite.Hold(r, "x"), name).isEmpty())
    }
}
