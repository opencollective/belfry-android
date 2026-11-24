package sh.belfry.core.model

import sh.belfry.core.engine.KeyChange

/**
 * A private group's lines in format 3 (SPEC 19.88; Encrypted canvas encrypted/group-joined, -joined-others, -removed,
 * -removed-self, -removed-info; docs/encrypted.md §8, §17.10): who joined or was removed (the relay's 9000 / 9001,
 * the web's MemberLine), and who can read from a key on or no longer can (the difference between one key's entry
 * keys and the next, mapped to people through the lists). WHO: the person; BY: who added or removed them.
 */
data class GroupLine(val kind: Kind, val who: String, val by: String?, val at: Long) {
    enum class Kind { JOINED, REMOVED, CAN_READ, NO_LONGER }
}

object GroupLines {
    /**
     * H's lines: its 9000 / 9001 (not a reading guest's) and the key changes CHANGES. A key line for a person whose
     * membership line falls between the key before and this one is left out: that line says it already.
     */
    fun of(world: World, h: String, changes: List<KeyChange>): List<GroupLine> {
        val out = ArrayList<GroupLine>()
        for (e in world.groupEventsSorted(h)) {
            if (e.kind != 9000 && e.kind != 9001) continue
            if (e.tags.any { it.isNotEmpty() && it[0] == "reading" }) continue
            val who = e.tags.firstOrNull { it.size >= 2 && it[0] == "p" && sh.belfry.core.crypto.Hex.isKey(it[1]) }?.get(1) ?: continue
            out += GroupLine(if (e.kind == 9000) GroupLine.Kind.JOINED else GroupLine.Kind.REMOVED, who, world.realAuthor(e), e.createdAt)
        }
        val members = out.toList()
        for (c in changes) {
            for (a in c.added) if (members.none { it.kind == GroupLine.Kind.JOINED && it.who == a && it.at in c.since..c.at }) out += GroupLine(GroupLine.Kind.CAN_READ, a, null, c.at)
            for (a in c.removed) if (members.none { it.kind == GroupLine.Kind.REMOVED && it.who == a && it.at in c.since..c.at }) out += GroupLine(GroupLine.Kind.NO_LONGER, a, null, c.at)
        }
        return out.sortedBy { it.at }
    }
}

/** Every word of a private group in format 3 (SPEC 19.88; the boards and the web's Chat.tsx / WhoCanRead.tsx, enc-v3). */
object GroupEncText {
    const val NO_EXTRAS = "Not available in private groups yet."
    const val GUEST = "Guests can’t take part in private groups yet."
    const val BY_EMAIL = "By e-mail"
    const val TOO_BIG = "Not sent: this group has too many devices to encrypt"
    const val TELL_STEWARD = "Tell a steward"
    const val CANT_CHECK = "Can’t check who can read this"
    const val NOT_SIGNED_IN = "Not signed in on any device"
    const val GETS_LATER = "Gets new messages once signed in"
    const val LOCK = "Encrypted: see who can read this group"
    const val OK = "OK"
    const val MAIL_NOTE = "E-mail to this group reaches the belfry’s mail server unencrypted and is encrypted as soon as it arrives. Once here, the belfry can’t read it."

    fun lead(n: Int, name: String): String = "Only the devices of these $n people can read $name. Someone who joins later can’t read what was said before."
    fun cantCheckText(name: String): String = "This device couldn’t confirm which devices can read $name. It keeps trying."

    private fun first(name: String): String = name.split(' ').firstOrNull { it.isNotEmpty() } ?: name

    /** The line's bold part and its small line. NAME: a person's display name; GROUP: the group's. */
    fun title(l: GroupLine, me: String, name: (String) -> String, group: String): String {
        val by = l.by?.let { if (it == me) "You" else first(name(it)) } ?: "Someone"
        val mine = l.who == me
        return when (l.kind) {
            GroupLine.Kind.JOINED -> if (mine) "You joined $group" else "${name(l.who)} joined"
            GroupLine.Kind.REMOVED -> if (mine) "$by removed you from $group" else "$by removed ${name(l.who)}"
            GroupLine.Kind.CAN_READ -> if (mine) "You can read from here" else "${name(l.who)} can read from here"
            GroupLine.Kind.NO_LONGER -> "${name(l.who)} can no longer read"
        }
    }

    fun small(l: GroupLine, me: String): String = when (l.kind) {
        GroupLine.Kind.JOINED -> if (l.who == me) "You see messages from when you joined." else "They see messages from now on."
        GroupLine.Kind.REMOVED -> if (l.who == me) "You can’t read or send new messages here. What you could read before stays on your devices." else "New messages no longer reach their devices."
        GroupLine.Kind.CAN_READ -> if (l.who == me) "You see messages from now on." else "Their devices get the new key: they see messages from now on."
        GroupLine.Kind.NO_LONGER -> "New messages no longer reach their devices."
    }

    /** A line with the (i): someone else removed, or no longer reading. */
    fun hasInfo(l: GroupLine, me: String): Boolean = l.who != me && (l.kind == GroupLine.Kind.REMOVED || l.kind == GroupLine.Kind.NO_LONGER)

    fun infoTitle(l: GroupLine, name: (String) -> String): String =
        if (l.kind == GroupLine.Kind.REMOVED) "${name(l.who)} was removed" else "${name(l.who)} can no longer read"

    /** The (i) of encrypted/group-removed-info, in two paragraphs. */
    fun info(l: GroupLine, me: String, name: (String) -> String, group: String): List<String> {
        val who = first(name(l.who))
        val by = l.by?.let { if (it == me) "you" else first(name(it)) }
        val made = if (l.kind == GroupLine.Kind.REMOVED && by != null) "When $by removed $who, a new key was made for $group." else "A new key was made for $group without $who’s devices."
        return listOf(
            "$made Messages sent from now on are encrypted with it, and $who’s devices never get it, so they can’t read them.",
            "What $who could read before stays on $who’s devices.",
        )
    }
}
