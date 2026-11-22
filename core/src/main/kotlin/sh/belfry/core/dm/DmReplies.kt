package sh.belfry.core.dm

/**
 * Replies in a direct message (docs/one-shape.md 3, vectors/one-shape.json `dmReply`): a kind 14
 * with `["e", root, "", "root"]` shows under its root, in the root's thread; one whose root is not
 * in the conversation shows in line, as older apps show every reply.
 */
object DmReplies {
    /** A conversation split: [inline] = what it shows (oldest first), [replies] = each root's replies (oldest first). */
    data class Split(val inline: List<Dm>, val replies: Map<String, List<Dm>>)

    /** The root a reply goes under: its own root when that one is a known reply too (one level of threads). */
    private fun top(d: Dm, byId: Map<String, Dm>): String? {
        var root = d.root ?: return null
        val seen = HashSet<String>().apply { add(d.rid) }
        while (true) {
            val r = byId[root] ?: return null
            if (!seen.add(root)) return null // a loop: shown in line
            val up = r.root?.takeIf { byId.containsKey(it) } ?: return root
            root = up
        }
    }

    /** LIST = one conversation's messages, oldest first. */
    fun split(list: List<Dm>): Split {
        if (list.none { it.root != null }) return Split(list, emptyMap())
        val byId = list.associateBy { it.rid }
        val inline = ArrayList<Dm>()
        val replies = LinkedHashMap<String, MutableList<Dm>>()
        for (d in list) {
            val root = top(d, byId)
            if (root == null) inline.add(d) else replies.getOrPut(root) { ArrayList() }.add(d)
        }
        return Split(inline, replies)
    }
}
