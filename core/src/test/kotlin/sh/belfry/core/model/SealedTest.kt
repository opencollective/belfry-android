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
        assertEquals("Lina started over on a new device and can't read your earlier messages. Only do this if you're sure it's Lina: ask in person or by phone if you're not.", SealedText.shareBody("Lina"))
        assertEquals("A new phone · signed in today 9:38", SealedText.newDeviceLine(LockedDevice("lina", DeviceKind.PHONE, at("2026-11-20T09:38:00Z")), now, zone))
        assertEquals("A new device · signed in today 9:38", SealedText.newDeviceLine(LockedDevice("lina", null, at("2026-11-20T09:38:00Z")), now, zone))
    }

    /** canvas Messages v5, note n-e2e-locked-named: the waiting device names who can unlock and where things stand. */
    @Test fun waitingWords() {
        val mac = "Chat · Safari on Mac"
        assertEquals("Open Chat · Safari on Mac. This device is added by itself as soon as that one is open.", SealedText.openText(listOf(mac)))
        assertEquals("Open Chat · Safari on Mac or Belfry on Pixel 8. This device is added by itself as soon as one is open.", SealedText.openText(listOf(mac, "Belfry on Pixel 8")))
        assertEquals("Open A, B or C. This device is added by itself as soon as one is open.", SealedText.openText(listOf("A", "B", "C")))
        assertEquals("Open Belfry on another device of yours. This device is added by itself as soon as that one is open.", SealedText.openText(emptyList()))
        assertEquals(SealedText.openText(emptyList()), SealedText.openText(listOf(SealedText.UNKNOWN_DEVICE)))
        assertEquals("Open Chat · Safari on Mac or another device of yours. This device is added by itself as soon as one is open.", SealedText.openText(listOf(mac, SealedText.UNKNOWN_DEVICE)))
        assertEquals("Open Chat · Safari on Mac. Nothing to approve here.", SealedText.openText(listOf(mac), short = true))
        // only the names are set apart (bold on the screens)
        assertEquals(listOf(mac), SealedText.openParts(listOf(mac, SealedText.UNKNOWN_DEVICE)).filter { it.second }.map { it.first })
        assertEquals("Open Chat · Safari on Mac. This device is added by itself as soon as that one is open. Nothing to approve here.", SealedText.unlockParts(listOf(mac)).joinToString("") { it.first })

        val asked = at("2026-11-20T21:40:00Z")
        val later = asked + 1800
        assertEquals("Asked 9:40 PM · waiting for one of your devices", SealedText.askedLine(asked, later, zone, h24 = false))
        assertEquals("Asked 21:40 · waiting for one of your devices", SealedText.askedLine(asked, later, zone, h24 = true))
        assertEquals("Asked 20 Nov 21:40 · waiting for one of your devices", SealedText.askedLine(asked, asked + 86400, zone, h24 = true))
        assertEquals("Asked 22:40 · waiting for one of your devices", SealedText.askedLine(asked, later, ZoneOffset.ofHours(1), h24 = true))
        assertEquals(SealedText.NOT_ASKED, SealedText.askedLine(0, later, zone, h24 = true))
        assertTrue(SealedText.NOT_ASKED.startsWith("Not asked yet"))
        assertEquals("Earlier messages arrive when one of your other devices is next open: Chat · Safari on Mac, Belfry on Pixel 8", SealedText.earlierArrive(listOf("Chat · Safari on Mac", "Belfry on Pixel 8")))
        assertEquals("Earlier messages arrive when one of your other devices is next open.", SealedText.earlierArrive(emptyList()))
        assertEquals("Not added yet · asked 9:40 PM", SealedText.addedLine(MyDevice("a", "Pixel 8", DeviceKind.PHONE, asked, thisDevice = true, allowed = false), later, zone, h24 = false))
        assertEquals("Not added yet", SealedText.addedLine(MyDevice("a", "Pixel 8", DeviceKind.PHONE, 0, thisDevice = true, allowed = false), later, zone))
        assertEquals("You can write here once this device is added.", SealedText.WRITE_LATER)
        assertEquals("No other device?", SealedText.UNLOCK_TITLE)
    }

    /** Devices are named by their session (application · device), never by a key; an unknown one gets plain words. */
    @Test fun unlockerNames() {
        assertEquals("Chat · Safari on Mac", Unlockers.sessionName("Chat", "Safari on Mac"))
        assertEquals("Belfry for Android", Unlockers.sessionName("Belfry for Android", ""))
        assertEquals("Safari on Mac", Unlockers.sessionName("", "Safari on Mac"))
        assertEquals("Belfry on Pixel 8", Unlockers.sessionName("Belfry", "Belfry on Pixel 8"))
        assertEquals(null, Unlockers.sessionName(null, " "))
        val names = mapOf("d1" to "Chat · Safari on Mac", "d2" to "Belfry on Pixel 8")
        assertEquals(listOf("Chat · Safari on Mac", "Belfry on Pixel 8"), Unlockers.names(listOf("d1", "d2"), names, live = true))
        // read just now: a head device with no session can't open anything, the named one is enough
        assertEquals(listOf("Chat · Safari on Mac"), Unlockers.names(listOf("d1", "d3"), names, live = true))
        // from what was kept (offline): the unknown one is still said, in plain words, last
        assertEquals(listOf("Chat · Safari on Mac", SealedText.UNKNOWN_DEVICE), Unlockers.names(listOf("d3", "d1"), names, live = false))
        assertEquals(listOf(SealedText.UNKNOWN_DEVICE), Unlockers.names(listOf("d3"), names, live = true))
        assertEquals(emptyList<String>(), Unlockers.names(emptyList(), names, live = true))
        // the same session name twice is said once
        assertEquals(listOf("Chat · Safari on Mac"), Unlockers.names(listOf("d1", "d4"), names + ("d4" to "Chat · Safari on Mac"), live = true))
    }

    @Test fun ask() {
        assertEquals("Give your new device “Safari on Mac” access to your conversations?", SealedText.askTitle("Safari on Mac"))
        assertEquals("2 min ago", SealedText.ago(now - 120, now))
    }
}
