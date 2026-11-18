package sh.belfry.core.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import sh.belfry.core.engine.GroupFilters
import sh.belfry.core.engine.GuestInfo
import sh.belfry.core.engine.SavedSession
import sh.belfry.core.net.Community
import sh.belfry.core.net.MemberLink
import sh.belfry.core.nostr.Event
import sh.belfry.core.nostr.parseJsonOrNull
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Member links (SPEC 19.39; collective-belfry roles.py, guests.py) and the channel list of a guest
 * who became a member: what the belfry's answers lead to, and who sees which channels.
 */
class MemberLinkTest {
    private val alice = "a".repeat(64)
    private val manuel = "b".repeat(64)
    private val gk = "9".repeat(64)
    private var n = 0
    private val t0 = 1_793_000_000L

    private fun ev(pk: String, at: Long, kind: Int, vararg tags: List<String>, content: String = ""): Event {
        n++
        return Event(n.toString(16).padStart(64, '0'), pk, at, kind, tags.toList(), content)
    }
    private fun t(vararg v: String) = v.toList()
    private fun world(events: List<Event>, me: String = manuel, kinds: Map<String, String> = emptyMap(), prev: World? = null) =
        World(events, emptyList(), WorldContext(me = me, domain = "belfry.example", communityName = "Belfry", groupsKey = gk, zone = ZoneId.of("UTC"), kinds = kinds), prev)
    private fun json(s: String) = parseJsonOrNull(s) as JsonObject

    // ---- what the answers say ----

    @Test fun theInvitesInfoNamesAMemberLink() {
        val m = MemberLink.of(json("""{"channel":"general","as":"member","member_role":"r1","member_role_label":"  Host  ","membership":"year"}"""))
        assertEquals(MemberLink("r1", "Host", "year"), m)
        // no label, an unknown length: Member, permanent
        assertEquals(MemberLink("member", "Member", "permanent"), MemberLink.of(json("""{"as":"member","membership":"forever"}""")))
        // a guest link, or an older belfry that does not say
        assertNull(MemberLink.of(json("""{"as":"guest","member_role":"r1"}""")))
        assertNull(MemberLink.of(json("""{"channel":"general"}""")))
        assertNull(MemberLink.of(null))
        // the label is cut, never taken whole
        assertEquals(40, MemberLink.of(json("""{"as":"member","member_role_label":"${"x".repeat(90)}"}"""))!!.label.length)
    }

    @Test fun verifyWithMemberTrueMeansAMemberNeverAnApplication() {
        assertEquals(GuestMode.AfterVerify.MEMBER, GuestMode.afterVerify(answerMember = true, joined = true))
        assertEquals(GuestMode.AfterVerify.MEMBER, GuestMode.afterVerify(answerMember = true, joined = false))
        assertEquals(GuestMode.AfterVerify.JOINED, GuestMode.afterVerify(answerMember = false, joined = true))
        assertEquals(GuestMode.AfterVerify.SAVED, GuestMode.afterVerify(answerMember = false, joined = false))
    }

    @Test fun theApplicationFormIsNeverForAMember() {
        assertTrue(GuestMode.mayApply(amGuest = true, reading = false, registerKind = null))
        assertTrue(GuestMode.mayApply(amGuest = false, reading = true, registerKind = "guest"))
        assertFalse(GuestMode.mayApply(amGuest = true, reading = false, registerKind = "member"))
        assertFalse(GuestMode.mayApply(amGuest = true, reading = true, registerKind = "agent"))
        assertFalse(GuestMode.mayApply(amGuest = false, reading = false, registerKind = null))
    }

    @Test fun theInvitationsWords() {
        assertEquals("You’re invited to join Belfry as a member", GuestMode.memberInviteTitle("Belfry", "Member"))
        assertEquals("You’re invited to join Belfry as Host", GuestMode.memberInviteTitle("Belfry", "Host"))
        assertEquals("You’re invited to join this belfry as a member", GuestMode.memberInviteTitle(" ", "member"))
        assertTrue(GuestMode.memberWhoLine("Belfry", "year").contains("a member of Belfry for a year."))
        assertTrue(GuestMode.memberWhoLine("Belfry", "permanent").contains("a member of Belfry."))
    }

    @Test fun theDeviceKeepsTheMemberLinkWithItsGuest() {
        val g = GuestInfo("general", saved = false, reading = true, member = MemberLink("r1", "Host", "month"), invite = "t".repeat(24))
        assertTrue(g.memberInvite)
        val s = SavedSession(Community.defaults("belfry.example"), "bunker://x", "1".repeat(64), manuel, guest = g)
        val back = SavedSession.fromJsonString(s.toJsonString())!!.guest!!
        assertEquals(g, back)
        assertNull(back.coHost) // a member link is never used again as a co-host link
        assertFalse(g.copy(reading = false).memberInvite)
        assertFalse(GuestInfo("general", false, reading = true).memberInvite)
    }

    // ---- a guest who became a member: the register's word, and the channels ----

    private val channels = listOf(
        ev(alice, t0, 9007, t("h", "general")),
        ev(gk, t0, 39000, t("d", "general"), t("name", "general")),
        ev(alice, t0 + 1, 9007, t("h", "garden")),
        ev(gk, t0 + 1, 39000, t("d", "garden"), t("name", "garden")),
        ev(alice, t0 + 2, 9007, t("h", "issues-5fce")),
        ev(gk, t0 + 2, 39000, t("d", "issues-5fce"), t("name", "issues")),
        ev(gk, t0 + 3, 39002, t("d", "general"), t("p", alice), t("p", manuel)),
        ev(gk, t0 + 3, 39002, t("d", "garden"), t("p", alice)),
        ev(gk, t0 + 3, 39002, t("d", "issues-5fce"), t("p", alice)),
        // the member link: in as a (named) guest of general first
        ev(gk, t0 + 10, 9000, t("h", "general"), t("p", manuel, "member"), t("guest", "2026-12-12"), t("invited-by", alice), t("name", "Manuel")),
    )

    @Test fun aGuestSeesOnlyTheirChannelsAMemberSeesEveryOpenOne() {
        val asGuest = world(channels)
        assertTrue(asGuest.amGuest)
        assertEquals(listOf("general"), asGuest.guestChannels().map { it.id })

        // the register says "member" (whois after the code): not a guest, every channel is theirs,
        // although the relay's 39002 lists them in general only
        val asMember = world(channels, kinds = mapOf(manuel to "member"))
        assertFalse(asMember.amGuest)
        assertFalse(asMember.mayApply(reading = false))
        assertEquals(setOf("general", "garden", "issues-5fce"), asMember.townChannels().map { it.id }.toSet())
        // the name is the 39000's, never the id
        assertEquals("issues", asMember.group("issues-5fce")!!.name)

        // or the belfry's label in the channel says so (before whois answers)
        val labelled = world(channels + ev(gk, t0 + 20, 1985, t("h", "general"), t("L", "belfry/guest"), t("l", "member", "belfry/guest"), t("p", manuel)))
        assertFalse(labelled.amGuest)
    }

    @Test fun anArchivedChannelIsInNoList() {
        val w = world(channels + ev(alice, t0 + 30, 9002, t("h", "garden"), t("name", "garden"), t("archived", "1")), kinds = mapOf(manuel to "member"))
        assertEquals(setOf("general", "issues-5fce"), w.townChannels().map { it.id }.toSet())
    }

    @Test fun aChannelSeenWithoutItsMetadataShowsItsIdUntilThe39000Arrives() {
        // messages of issues-5fce came in, its 39000 did not (the old subscription): the id, as is
        val only = channels.filter { !(it.kind == 39000 && it.t("d") == "issues-5fce") && !(it.kind == 9007 && it.t("h") == "issues-5fce") } +
            ev(alice, t0 + 40, 9, t("h", "issues-5fce"), content = "a bug")
        val w1 = world(only, kinds = mapOf(manuel to "member"))
        assertEquals("issues-5fce", w1.group("issues-5fce")!!.name)
        // the metadata arrives: the next snapshot (built on the one before) has the name
        val w2 = world(only + ev(gk, t0 + 2, 39000, t("d", "issues-5fce"), t("name", "issues")), kinds = mapOf(manuel to "member"), prev = w1)
        assertEquals("issues", w2.group("issues-5fce")!!.name)
    }

    // ---- the groups relay subscription ----

    @Test fun groupsMetadataIsAlwaysAskedForWhole() {
        val kinds = listOf(39000, 9, 9000)
        val normal = GroupFilters.of(kinds, newest = t0, full = false)
        assertEquals(3, normal.size)
        // the charter, whole and apart: no agreement falls outside the newest events
        assertNull(normal[2]["since"])
        assertEquals(listOf(2100L, 2101L), normal[2]["kinds"]!!.jsonArray.map { it.jsonPrimitive.long })
        val meta = normal[0]
        assertNull(meta["since"])
        assertEquals(GroupFilters.META_KINDS.map { it.toLong() }, meta["kinds"]!!.jsonArray.map { it.jsonPrimitive.long })
        assertEquals(t0 - 3600, normal[1]["since"]!!.jsonPrimitive.long)
        // a member who had only a guest's view on this device: the whole history, once
        assertEquals(0L, GroupFilters.of(kinds, newest = t0, full = true)[1]["since"]!!.jsonPrimitive.long)
        assertEquals(0L, GroupFilters.of(kinds, newest = 100, full = false)[1]["since"]!!.jsonPrimitive.long)
    }
}
