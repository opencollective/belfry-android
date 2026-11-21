package sh.belfry.core.model

import sh.belfry.core.net.DirMember

/**
 * Members with their own agents folded under them (canvas v93, Xavier 2026-11-20: "Agents that
 * belong to a user shouldn't show up directly in the list of members"). An agent whose member is
 * in the list has no row; an agent that belongs to nobody here keeps its own. Counts never count
 * agents. Search finds a member by their name or by one of their agents' names.
 */
object MembersFold {
    /** A row: the member, their agents (pubkey, name), and the agent whose name matched the search (else null). */
    data class Line(val member: DirMember, val agents: List<Pair<String, String>>, val matched: String? = null)

    fun lines(
        list: List<DirMember>,
        owners: Map<String, String>,
        nameOf: (String) -> String,
        query: String,
        isAgent: (String) -> Boolean,
    ): List<Line> {
        val keys = list.map { it.pubkey }.toSet()
        val ownerOf = HashMap<String, String>()
        owners.forEach { (agent, owner) -> ownerOf[agent] = owner }
        list.forEach { m -> m.agents.forEach { (a, _) -> ownerOf[a] = m.pubkey } }
        val words = fold(query).split(' ').filter { it.isNotEmpty() }
        val out = ArrayList<Line>()
        for (m in list) {
            val owner = ownerOf[m.pubkey]
            if (owner != null && owner != m.pubkey && owner in keys) continue
            val agents = (m.agents.map { it.first } + ownerOf.filterValues { it == m.pubkey }.keys)
                .distinct().filter { it != m.pubkey }
                .map { a -> a to nameOf(a).ifEmpty { m.agents.firstOrNull { it.first == a }?.second.orEmpty() }.ifEmpty { "Agent" } }
                .sortedBy { fold(it.second) }
            if (words.isEmpty() || words.all { fold(nameOf(m.pubkey).ifEmpty { m.name }).contains(it) }) {
                out.add(Line(m, agents)); continue
            }
            val hit = agents.firstOrNull { (_, n) -> words.all { fold(n).contains(it) } } ?: continue
            out.add(Line(m, agents, hit.second))
        }
        return out.sortedBy { fold(nameOf(it.member.pubkey).ifEmpty { it.member.name }) }
    }

    /** People only: an agent row (one that belongs to nobody here) isn't counted. */
    fun count(lines: List<Line>, isAgent: (String) -> Boolean): Int = lines.count { !isAgent(it.member.pubkey) }

    fun agentsLine(n: Int): String = if (n == 1) "+ 1 agent" else "+ $n agents"
}
