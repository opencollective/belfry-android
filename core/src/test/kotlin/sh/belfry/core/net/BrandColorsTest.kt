package sh.belfry.core.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** belfry.json / GET /branding `branding.colors` (the lead, 2026-11-20; OSV's colours). */
class BrandColorsTest {
    private val doc = """{"relays":{},"branding":{"primary":"#2B402E","secondary":"#ad6e21",
        "colors":{"light":{"accent":"#2b402e","on_accent":"#ffffff"},"dark":{"accent":"#58835E","on_accent":"#09090b"}}}}"""

    @Test fun readsBothModes() {
        val c = Community.parse("opensourcevillage.org", doc)
        assertEquals("#2b402e", c.brandPrimary)
        assertEquals(BrandMode("#2b402e", "#ffffff"), c.brandColors!!.forMode(dark = false))
        assertEquals(BrandMode("#58835e", "#09090b"), c.brandColors!!.forMode(dark = true))
        // kept in the offline copy
        assertEquals(c.brandColors, Community.fromJsonString(c.toJsonString())!!.brandColors)
    }

    @Test fun lenient() {
        assertNull(Community.parse("x.example", """{"branding":{"primary":"#2b402e"}}""").brandColors, "no colors: as before")
        val odd = Community.parse("x.example", """{"branding":{"primary":"#2b402e","colors":{"light":{"accent":"green"},"dark":{"accent":"#58835e","on_accent":7}}}}""")
        assertNull(odd.brandColors!!.light)
        assertEquals(BrandMode("#58835e", null), odd.brandColors!!.dark)
        assertNull(Community.parse("x.example", """{"branding":{"primary":"#2b402e","colors":"dark"}}""").brandColors)
    }

    @Test fun savingKeepsOrDrops() {
        val c = Community.parse("opensourcevillage.org", doc)
        // POST /branding answers without colors: same primary keeps them, a new primary drops them
        assertEquals(c.brandColors, c.withBranding(c.branding.copy(logo = "https://x.example/l.png", colors = null)).brandColors)
        assertNull(c.withBranding(c.branding.copy(primary = "#112233", colors = null)).brandColors)
        // the POST body never carries colors
        assertNull(c.branding.toJson()["colors"])
    }
}
