package sh.belfry.core.model

import sh.belfry.core.nostr.Event

/** Where a line of the log leads ("Go there"). */
sealed interface LogGo {
    /** The message, shown in the channel. */
    data class Message(val id: String) : LogGo
    data class Thread(val root: String) : LogGo
    data object Todos : LogGo
    data object Members : LogGo
}

/** One line of a channel's log: an icon for the kind of thing, what was done, and more about it. */
class LogItem(val ev: Event, val icon: String, val text: String, val detail: String? = null, val go: LogGo? = null)

/**
 * A channel's log (web ChannelInfo.tsx `logItem`, c6579a0): one line per Nostr event carrying the
 * channel's `h`, newest first: who did what. Heartbeats ("working" / "idle" on a to-do) and "seen"
 * markers are left out. The words are the web's.
 */
object ChannelLog {
    private val LABEL_WORDS = mapOf(
        "pinned" to "pinned a message", "unpinned" to "unpinned a message", "attended" to "checked someone in",
        "absent" to "marked someone not there", "saved" to "saved their account", "member" to "became a member",
        "cancelled" to "cancelled the event", "scheduled" to "reopened the event", "assigned" to "assigned a mail",
        "done" to "closed a mail", "open" to "reopened a mail",
    )
    private val TASK_WORDS = mapOf(
        "claim" to "took a todo", "unclaim" to "let go of a todo", "done" to "marked a todo done",
        "undone" to "reopened a todo", "delete" to "deleted a todo",
    )
    private val SPACES = Regex("\\s+")

    /** On one line, at most N characters ("…" at the cut). */
    fun short(text: String, n: Int = 80): String {
        val x = text.replace(SPACES, " ").jsTrim()
        return if (x.length > n) x.substring(0, n - 1) + "…" else x
    }

    /** The line for one event; null for what the log leaves out. TITLES: to-do id → title; NAME: a person's name. */
    fun item(e: Event, titles: Map<String, String>, name: (String) -> String): LogItem? {
        fun t(k: String) = e.t(k)
        val toMessage = t("e")?.let { LogGo.Message(it) }
        return when (e.kind) {
            9 -> {
                val here = LogGo.Message(e.id)
                val media = e.tags.filter { it.isNotEmpty() && it[0] == "imeta" }.map { x -> x.firstOrNull { it.startsWith("m ") }?.substring(2) ?: "" }
                when {
                    t("pod") != null -> LogItem(e, "⬡", "created a pod", short(e.content, 200), here)
                    t("proposal") != null -> LogItem(e, "📄", "made a proposal", short(e.content, 200), here)
                    media.any { it.startsWith("audio/") } -> LogItem(e, "🎙", "posted a recording", short(e.content, 200), here)
                    media.any { it.startsWith("image/") } -> LogItem(e, "🖼", if (media.size > 1) "posted ${media.size} photos" else "posted a photo", short(e.content, 200), here)
                    media.isNotEmpty() -> LogItem(e, "📎", "posted a file", short(e.content, 200), here)
                    t("mail-from") != null -> LogItem(e, "✉️", "an e-mail came in", short(t("subject")?.ifEmpty { null } ?: e.content, 200), here)
                    else -> LogItem(e, "💬", "posted a message", short(e.content, 200), here)
                }
            }
            1111 -> LogItem(e, "↩️", "replied in a thread", short(e.content, 200), LogGo.Thread(t("E") ?: e.id))
            1010 -> LogItem(e, "✏️", "edited a message", short(e.content, 200), toMessage)
            7 -> LogItem(e, if (e.content.isNotEmpty() && e.content.length <= 4 && e.content != "+") e.content else "👍", "reacted", go = toMessage)
            5, 9005 -> LogItem(e, "🗑", if (t("k") == "7") "took back a reaction" else "deleted something")
            2100 -> LogItem(e, "☑️", "made a todo", short(t("title")?.ifEmpty { null } ?: e.content, 200), LogGo.Todos)
            2101 -> {
                val w = TASK_WORDS[t("action") ?: ""] ?: return null
                LogItem(e, if (t("action") == "done") "✅" else "☑️", w, titles[t("e") ?: ""], LogGo.Todos)
            }
            2103 -> LogItem(e, "💬", "commented on a todo", short(e.content, 200), LogGo.Todos)
            9000 -> LogItem(
                e, "👋", if (e.tags.any { it.size >= 3 && it[0] == "p" && it[2] == "admin" }) "made someone an admin" else "added someone",
                t("p")?.let(name), LogGo.Members,
            )
            9001 -> LogItem(e, "🚪", "removed someone", t("p")?.let(name), LogGo.Members)
            9021 -> LogItem(e, "🙋", "asked to join", short(e.content, 200))
            9022 -> LogItem(e, "🚪", "left")
            9002 -> LogItem(e, "⚙️", "changed the channel", listOfNotNull(t("name"), t("about")).filter { it.isNotEmpty() }.joinToString(" · ") { short(it, 120) })
            9007 -> LogItem(e, "✨", "created the channel")
            1985 -> {
                val l = e.tags.firstOrNull { it.isNotEmpty() && it[0] == "l" }?.getOrNull(1) ?: ""
                if (t("L") == "#t") LogItem(e, "#", "changed the pinned topics")
                else LogItem(
                    e, if (l == "pinned" || l == "unpinned") "📌" else if (l == "attended") "✅" else "🏷",
                    LABEL_WORDS[l] ?: "labelled something $l", t("p")?.let(name), toMessage,
                )
            }
            30078 -> {
                val d = t("d") ?: ""
                if (d.startsWith("seen:")) null else LogItem(e, "⚙️", "changed a setting", d)
            }
            31922, 31923 -> {
                val meeting = e.tags.any { it.size >= 2 && it[0] == "t" && it[1] == "meeting" }
                LogItem(e, if (meeting) "👥" else "📅", if (meeting) "set the meeting" else "set the event", t("title"))
            }
            31925 -> LogItem(e, "📅", "answered " + when (t("status")) { "accepted" -> "going"; "declined" -> "can't go"; else -> "maybe" })
            else -> LogItem(e, "•", "kind ${e.kind}")
        }
    }
}

/** Everything that happened in a channel, newest first (at most LIMIT lines). */
fun World.channelLog(groupId: String, limit: Int = 1000): List<LogItem> {
    val titles = tasks(groupId).associate { it.id to it.title }
    return groupEventsSorted(groupId).asReversed().asSequence()
        .mapNotNull { ChannelLog.item(it, titles) { pk -> displayName(pk) } }
        .take(limit).toList()
}

/** Who a log line is by: the relay signs joins and channel changes itself, so its key reads as the community. */
fun World.logWho(groupId: String, pubkey: String): String =
    if (pubkey != me && pubkey == metaAuthors[groupId] && ctx.communityName.isNotEmpty()) ctx.communityName else displayName(pubkey)

/** The latest 20 to-dos (web `latestTasks`): the open ones first, then the done ones; newest first in each. */
fun latestTasks(tasks: List<Task>): List<Task> {
    val recent = tasks.sortedByDescending { it.at }.take(20)
    return recent.filter { !it.done } + recent.filter { it.done }
}

/** An event as indented JSON, for the Log's "Inspect the Nostr event" (developer mode). */
fun Event.prettyJson(): String {
    fun q(s: String) = kotlinx.serialization.json.JsonPrimitive(s).toString()
    val lines = tags.joinToString(",\n") { tag -> "    [" + tag.joinToString(", ") { q(it) } + "]" }
    return buildString {
        append("{\n")
        append("  \"id\": ${q(id)},\n")
        append("  \"pubkey\": ${q(pubkey)},\n")
        append("  \"created_at\": $createdAt,\n")
        append("  \"kind\": $kind,\n")
        append("  \"tags\": [" + (if (tags.isEmpty()) "" else "\n$lines\n  ") + "],\n")
        append("  \"content\": ${q(content)}")
        if (sig.isNotEmpty()) append(",\n  \"sig\": ${q(sig)}")
        append("\n}")
    }
}
