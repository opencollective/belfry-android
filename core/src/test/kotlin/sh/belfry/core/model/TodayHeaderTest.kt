package sh.belfry.core.model

import sh.belfry.core.engine.AccountList
import sh.belfry.core.engine.AccountRecord
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

/** SPEC 19.90: the words of Today's header and accounts menu; the role and logo kept per account. */
class TodayHeaderTest {
    private val key = "a".repeat(64)

    @Test fun words() {
        assertEquals("Wednesday, November 25", TodayHeader.date(Instant.parse("2026-11-25T10:00:00Z").epochSecond, ZoneOffset.UTC))
        assertEquals("Steward", TodayHeader.role(steward = true, guest = false))
        assertEquals("Guest", TodayHeader.role(steward = true, guest = true))
        assertEquals("Member", TodayHeader.role(steward = false, guest = false))
        assertEquals("sam@demo.belfry.sh", TodayHeader.address("sam@demo.belfry.sh", "demo.belfry.sh"))
        assertEquals("demo.belfry.sh", TodayHeader.address(null, "demo.belfry.sh"))
        assertEquals("sam@x.example · Steward", TodayHeader.line("sam@x.example", "Steward"))
        assertEquals("x.example", TodayHeader.line("x.example", ""))
    }

    @Test fun aRecordKeepsItsRoleAndLogo() {
        val (list, rec, _) = AccountList.EMPTY.signedIn("hillside.example", key)
        // an older record: no role, no logo; a guest reads Guest
        assertEquals("hillside.example", rec.menuLine)
        assertEquals("hillside.example · Guest", rec.copy(guest = true).menuLine)
        val described = list.described(rec.id, "Sam", "sam@hillside.example", "Hillside Makers", "", false, role = "Member", logo = "https://hillside.example/logo.png")
        val again = AccountList.fromJsonString(described.toJsonString())!![rec.id]!!
        assertEquals("Member", again.role)
        assertEquals("https://hillside.example/logo.png", again.logo)
        assertEquals("sam@hillside.example · Member", again.menuLine)
        // not told (null): kept; a logo that is not https: not taken
        val kept = described.described(rec.id, "Sam", "sam@hillside.example", "Hillside Makers", "", false, logo = "http://x.example/l.png")[rec.id]!!
        assertEquals("Member", kept.role)
        assertEquals("https://hillside.example/logo.png", kept.logo)
        val bad = AccountRecord.fromJson(kotlinx.serialization.json.buildJsonObject {
            put("domain", kotlinx.serialization.json.JsonPrimitive("x.example")); put("pubkey", kotlinx.serialization.json.JsonPrimitive(key))
            put("slot", kotlinx.serialization.json.JsonPrimitive("")); put("logo", kotlinx.serialization.json.JsonPrimitive("javascript:alert(1)"))
        })!!
        assertEquals("", bad.logo)
    }
}
