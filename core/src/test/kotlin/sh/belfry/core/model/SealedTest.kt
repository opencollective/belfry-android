package sh.belfry.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

/** The words of the encryption screens (canvas "Messages · encryption", v80 / v81). */
class SealedTest {
    private val zone = ZoneOffset.UTC
    private val now = Instant.parse("2026-11-20T10:30:00Z").epochSecond
    private fun at(s: String) = Instant.parse(s).epochSecond

    @Test fun counts() {
        assertEquals("1 device", SealedText.devices(1))
        assertEquals("3 devices", SealedText.devices(3))
        assertEquals("5 people · 11 devices", SealedText.peopleLine(5, 11))
    }

    @Test fun changeLines() {
        assertEquals("Removed a device today", SealedText.changeLine(DeviceChange(false, now - 3600), now, zone))
        assertEquals("Added a device yesterday", SealedText.changeLine(DeviceChange(true, at("2026-11-19T20:00:00Z")), now, zone))
        assertEquals("Removed a device on 2 Nov", SealedText.changeLine(DeviceChange(false, at("2026-11-02T12:00:00Z")), now, zone))
        assertTrue(SealedText.recent(DeviceChange(true, now - 29 * 86400L), now))
        assertFalse(SealedText.recent(DeviceChange(true, now - 31 * 86400L), now))
        assertFalse(SealedText.recent(null, now))
    }

    @Test fun myDevices() {
        val phone = MyDevice("a", "Pixel 8", DeviceKind.PHONE, at("2026-08-12T09:00:00Z"), thisDevice = true)
        val mac = MyDevice("b", "Safari on Mac", DeviceKind.COMPUTER, at("2026-10-02T09:00:00Z"), from = "Pixel 8", active = now - 3 * 86400L)
        assertEquals("Added 12 Aug", SealedText.addedLine(phone, now, zone))
        assertEquals("Added 2 Oct · from Pixel 8", SealedText.addedLine(mac, now, zone))
        assertEquals("Added 12 Aug · active now", SealedText.activeLine(phone, now, zone))
        assertEquals("Added 2 Oct · from Pixel 8 · active 3 days ago", SealedText.activeLine(mac, now, zone))
        assertEquals("Added 12 Aug 2025", SealedText.addedLine(phone.copy(added = at("2025-08-12T09:00:00Z")), now, zone))
    }

    @Test fun notices() {
        val n = DeviceNotice("1", now, "lina", DeviceNotice.Kind.ADDED)
        assertEquals("Lina added a device", SealedText.notice(n, "Lina", mine = false))
        assertEquals("Lina removed a device", SealedText.notice(n.copy(kind = DeviceNotice.Kind.REMOVED), "Lina", mine = false))
        assertEquals("You added a device · Safari on Mac", SealedText.notice(n.copy(device = "Safari on Mac"), "You", mine = true))
        assertEquals("Lina signed in on a new device that can't read earlier messages yet", SealedText.notice(n.copy(kind = DeviceNotice.Kind.NEW_LOCKED), "Lina", mine = false))
        // another person's line never names the device, even if one came along
        assertEquals("Lina added a device", SealedText.notice(n.copy(device = "Pixel 8"), "Lina", mine = false))
    }

    @Test fun shareSheet() {
        assertEquals("Share this conversation's history with Lina's new device?", SealedText.shareTitle("Lina", group = false))
        assertEquals("Share this group's history with Lina's new device?", SealedText.shareTitle("Lina", group = true))
        assertEquals("A new phone · signed in today 9:38", SealedText.newDeviceLine(LockedDevice("lina", DeviceKind.PHONE, at("2026-11-20T09:38:00Z")), now, zone))
    }

    @Test fun ask() {
        assertEquals("Give your new device “Safari on Mac” access to your conversations?", SealedText.askTitle("Safari on Mac"))
        assertEquals("2 min ago", SealedText.ago(now - 120, now))
    }
}
