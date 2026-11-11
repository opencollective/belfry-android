package sh.belfry.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import sh.belfry.core.net.Community

class BrandingTest {
    @Test fun brandingFromBelfryJson() {
        val c = Community.parse("x.org", Json.parseToJsonElement("""{"branding":{"primary":"#2563EB","logo":"https://x.org/l.png"}}""").jsonObject)
        assertEquals("#2563eb", c.brandPrimary)
        assertEquals("https://x.org/l.png", c.brandLogo)
        val bad = Community.parse("x.org", Json.parseToJsonElement("""{"branding":{"primary":"blue","logo":"http://x.org/l.png"}}""").jsonObject)
        assertNull(bad.brandPrimary)
        assertNull(bad.brandLogo)
    }
}
