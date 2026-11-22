package sh.belfry.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The face of an outside e-mail correspondent (SPEC 19.59): hash, initials, colour, cache times. */
class MailFaceTest {
    @Test fun hashTrimsAndLowercases() {
        val h = "86b9260a115ceac89a9738af23696ce3a5d70449de8d95ef28bd754bb80196b9"
        assertEquals(h, MailFace.hash("ana@example.org"))
        assertEquals(h, MailFace.hash("  Ana@Example.ORG \n"))
        assertEquals(64, MailFace.hash("x@y.z").length)
        assertEquals("1d40f060eba7f787dca5eafe4246bdcf8ee8cff4e9b2e643dab2e5fa03c84b71", MailFace.hash("permits@cityhall.example"))
    }

    @Test fun url() {
        assertEquals("https://belfry.demo.belfry.sh/api/v1/avatar/abc", MailFace.url("https://belfry.demo.belfry.sh/api/v1", "abc"))
        assertEquals("https://belfry.demo.belfry.sh/api/v1/avatar/abc", MailFace.url("https://belfry.demo.belfry.sh/api/v1/", "abc"))
    }

    @Test fun initials() {
        assertEquals("AP", MailFace.initials("Ana Pereira", "ana@example.org"))
        assertEquals("CO", MailFace.initials("City Office", "permits@cityhall.example"))
        assertEquals("AS", MailFace.initials("Ana Maria Silva", "a@x.org"))
        assertEquals("L", MailFace.initials("Lou", "lou@example.com"))
        assertEquals("L", MailFace.initials("Lou (neighbour)", "lou@example.com"))
        assertEquals("P", MailFace.initials(null, "permits@cityhall.example"))
        assertEquals("P", MailFace.initials("", "permits@cityhall.example"))
        // the name shown is the address itself when the mail had no name
        assertEquals("P", MailFace.initials("permits@cityhall.example", "permits@cityhall.example"))
        assertEquals("É", MailFace.initials("élodie", "e@x.org"))
        assertEquals("?", MailFace.initials(null, "@"))
    }

    @Test fun colourIsStable() {
        assertEquals(3, MailFace.colorIndex(MailFace.hash("ana@example.org"), 7))
        assertEquals(1, MailFace.colorIndex(MailFace.hash("permits@cityhall.example"), 7))
        assertEquals(MailFace.colorIndex(MailFace.hash("ANA@example.org "), 7), MailFace.colorIndex(MailFace.hash("ana@example.org"), 7))
    }

    @Test fun cacheTimes() {
        val t = 1_800_000_000L
        assertTrue(MailFace.fresh(true, t, t + 29L * 86400))
        assertFalse(MailFace.fresh(true, t, t + 30L * 86400))
        assertTrue(MailFace.fresh(false, t, t + 6L * 86400))
        assertFalse(MailFace.fresh(false, t, t + 7L * 86400))
        assertFalse(MailFace.fresh(true, t, t - 10)) // a clock set back: ask again
    }

    @Test fun cacheRules() {
        assertEquals(MailFace.Keep(MailFace.Kind.PICTURE, 30L * 86400), MailFace.keep(200))
        assertEquals(MailFace.Keep(MailFace.Kind.MISS, 7L * 86400), MailFace.keep(404))
        assertEquals(MailFace.Keep(MailFace.Kind.MISS, 600), MailFace.keep(404, cacheControl = "public, max-age=600"))
        assertEquals(MailFace.Keep(MailFace.Kind.MISS, 7L * 86400), MailFace.keep(404, cacheControl = "max-age=99999999"))
        // 429 is "later", never "no picture"; it is not cached
        assertEquals(MailFace.Kind.LATER, MailFace.keep(429).kind)
        assertEquals(MailFace.LATER_SECONDS, MailFace.keep(429).seconds)
        assertEquals(30L, MailFace.keep(429, retryAfter = "30").seconds)
        assertEquals(MailFace.Kind.LATER, MailFace.keep(500).kind)
        assertEquals(MailFace.Kind.LATER, MailFace.keep(200, image = false).kind)
        assertEquals(MailFace.Kind.LATER, MailFace.keep(302).kind)
    }
}
