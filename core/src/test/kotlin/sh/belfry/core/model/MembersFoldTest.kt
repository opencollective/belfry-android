package sh.belfry.core.model

import sh.belfry.core.net.DirMember
import kotlin.test.Test
import kotlin.test.assertEquals

/** canvas v93 home/members, -search-agent: agents fold under their member. */
class MembersFoldTest {
    private val names = mapOf("ana" to "Ana Silva", "ben" to "Ben Okafor", "cb" to "Compost bot", "sb" to "Seed bot", "bb" to "Bike bot", "mh" to "Minutes helper", "lina" to "Lina Moreau")
    private val agents = setOf("cb", "sb", "bb", "mh")
    private val list = listOf(
        DirMember("ana", "Ana Silva", null, emptyList()),
        DirMember("cb", "Compost bot", null, emptyList()),
        DirMember("sb", "Seed bot", null, emptyList()),
        DirMember("ben", "Ben Okafor", "steward", listOf("bb" to "Bike bot")),
        DirMember("bb", "Bike bot", null, emptyList()),
        DirMember("mh", "Minutes helper", null, emptyList()),
        DirMember("lina", "Lina Moreau", null, emptyList()),
    )
    private val owners = mapOf("cb" to "ana", "sb" to "ana", "mh" to "nobody-here")

    private fun lines(q: String = "") = MembersFold.lines(list, owners, { names[it].orEmpty() }, q) { it in agents }

    @Test fun folded() {
        val l = lines()
        assertEquals(listOf("ana", "ben", "lina", "mh"), l.map { it.member.pubkey })
        assertEquals(listOf("Compost bot", "Seed bot"), l.first { it.member.pubkey == "ana" }.agents.map { it.second })
        assertEquals(listOf("Bike bot"), l.first { it.member.pubkey == "ben" }.agents.map { it.second })
        assertEquals(3, MembersFold.count(l) { it in agents }, "the guest agent nobody took has a row but isn't counted")
        assertEquals("+ 2 agents", MembersFold.agentsLine(2))
        assertEquals("+ 1 agent", MembersFold.agentsLine(1))
    }

    @Test fun searchByAgent() {
        val l = lines("comp")
        assertEquals(listOf("ana"), l.map { it.member.pubkey })
        assertEquals("Compost bot", l.single().matched)
        // by the member's own name: no match line
        assertEquals(null, lines("ana").single().matched)
    }
}
