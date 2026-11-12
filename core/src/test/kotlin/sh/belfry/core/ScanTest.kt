package sh.belfry.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import sh.belfry.core.net.InviteLink
import sh.belfry.core.net.InviteScan

class ScanTest {
    private val token = "AbCdEfGhIjKlMnOpQrStUvWx_-12"

    @Test fun anInvitation() {
        assertEquals(InviteLink("belfry.sh", token), InviteScan.read("https://chat.belfry.sh/join/$token"))
        assertEquals(InviteLink("enspiral.eu", token), InviteScan.read("  https://chat.enspiral.eu/#/join/$token/\n"))
    }

    @Test fun anythingElse() {
        assertNull(InviteScan.read(null))
        assertNull(InviteScan.read(""))
        assertNull(InviteScan.read("hello"))
        assertNull(InviteScan.read("https://example.org/join/$token"))
        assertNull(InviteScan.read("http://chat.belfry.sh/join/$token"))
        assertNull(InviteScan.read("https://chat.belfry.sh/join/short"))
        assertNull(InviteScan.read("https://chat.belfry.sh/join/$token?next=https://evil.example"))
        assertNull(InviteScan.read("https://chat.belfry.sh@evil.example/join/$token"))
        assertNull(InviteScan.read("https://chat.evil.example/x/../join/$token#" + "a".repeat(600)))
        assertNull(InviteScan.read("javascript:alert(1)"))
        assertNull(InviteScan.read("WIFI:S:home;T:WPA;P:secret;;"))
        assertNull(InviteScan.read("https://chat.belfry.sh/events/abc"))
    }
}
