package sh.belfry.core.model

// Groups: open, closed, private (SPEC 19.59; collective-belfry docs/one-shape.md "Groups: open,
// closed, private (2026-11-22)"; canvas groups/new, groups/new-pick, groups/settings-closed).
//
// | Choice  | In the form           | On the 9007 / 9002   |
// | Open    | Any member can join   | `open`, `public`     |
// | Closed  | Members apply to join | `closed`, `public`   |
// | Private | Only invited people   | `closed`, `private`  |
//
// The app filters Private groups itself (never relies on the relay): one I'm not in is in no list,
// card, count or search; its 9021s are never shown or answered; it has no Apply / Join.

/** Who can join a group: the three choices of the New group form and of the group's Settings. */
enum class GroupJoin(val key: String, val label: String, val sub: String) {
    OPEN("open", "Open", "Any member can join"),
    CLOSED("closed", "Closed", "Members apply to join"),
    PRIVATE("private", "Private", "Only invited people");

    val closed: Boolean get() = this != OPEN
    val isPrivate: Boolean get() = this == PRIVATE

    /** The access a new group is made with: a new Closed group starts with no question (its Settings add one). */
    fun access(): PodAccess = PodAccess(closed = closed, isPrivate = isPrivate)

    companion object {
        fun of(g: Group): GroupJoin = when { g.isPrivate -> PRIVATE; g.closed -> CLOSED; else -> OPEN }

        /** Is a group with these members hidden from ME? A Private one I'm not in (member, admin or a guest let in). */
        fun hides(isPrivate: Boolean, me: String, members: Set<String>, admins: Set<String>, reading: Set<String> = emptySet()): Boolean =
            isPrivate && me !in members && me !in admins && me !in reading

        /** The 9007 / 9002 tags for who can join (NIP-29 `open|closed` + `public|private`; no belfry tag, never `hidden`). */
        fun tags(closed: Boolean, isPrivate: Boolean): List<Tag> = when {
            isPrivate -> listOf(listOf("closed"), listOf("private"))
            closed -> listOf(listOf("closed"), listOf("public"))
            else -> listOf(listOf("open"), listOf("public"))
        }
    }
}

/** Can people ask to join G (an Apply / Ask to join)? Closed and not Private. */
fun Group.takesRequests(): Boolean = closed && !isPrivate

/** The belfry's members I can add to a new group: the directory (no guests, no agents, no one removed), me left out, by name. */
fun World.pickableMembers(): List<String> =
    memberKeys().filter { it != me }.sortedWith(compareBy({ displayName(it).lowercase() }, { it }))

/** Members whose name has every word of QUERY (case and accents folded). */
fun World.pickableMembers(query: String): List<String> {
    val words = java.text.Normalizer.normalize(query.lowercase(), java.text.Normalizer.Form.NFKD).replace(Regex("\\p{M}+"), "")
        .split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return pickableMembers()
    return pickableMembers().filter { pk ->
        val hay = java.text.Normalizer.normalize(displayName(pk).lowercase(), java.text.Normalizer.Form.NFKD).replace(Regex("\\p{M}+"), "")
        words.all { hay.contains(it) }
    }
}

/** Add PUBKEYS to group ID: one 9000 each, `["p", <pubkey>, "member"]`, by an admin of the group (its creator). */
fun Actions.addMembers(id: String, pubkeys: List<String>): List<Draft> = pubkeys.distinct().map { admit(id, it) }
