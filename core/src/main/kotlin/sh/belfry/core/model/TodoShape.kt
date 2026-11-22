package sh.belfry.core.model

import sh.belfry.core.nostr.Event
import sh.belfry.core.outbox.Target
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// The to-do in one shape (collective-belfry docs/one-shape.md 3; canvas one-shape/todo-*,
// 2026-11-22; the same rules as belfry-ios TodoShape.swift). Due date: ["due", "<unix seconds>"] on
// the kind 2100; the author's newest kind 1010 edit replaces the tags (its due wins; an edit
// without one clears it). Shown first in the pull-down, never in the header. The conversation
// weaves in quiet lines: made, took it, gave it, done, reopened, set the due date.

/** One quiet line in a to-do's conversation ([words] after the name), or a comment ([comment]). */
data class TodoLine(val id: String, val who: String, val at: Long, val words: String?, val comment: String? = null, val what: String = "")

object TodoShape {
    private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
    private val DAY_YEAR = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH)

    /** "Sat 28 Nov, 9 AM", "Sat 28 Nov, 9:30 AM"; the year when it is not this year's. */
    fun dueLabel(due: Long, zone: ZoneId, now: Long): String {
        val t = Instant.ofEpochSecond(due).atZone(zone)
        val year = Instant.ofEpochSecond(now).atZone(zone).year
        val day = (if (t.year == year) DAY else DAY_YEAR).format(t)
        val h = if (t.hour % 12 == 0) 12 else t.hour % 12
        val clock = if (t.minute == 0) "$h" else "$h:" + t.minute.toString().padStart(2, '0')
        return "$day, $clock " + (if (t.hour < 12) "AM" else "PM")
    }

    /** "Sat 28 Nov" (the year when not this year's): the day a to-do was done. */
    fun dayLabel(at: Long, zone: ZoneId, now: Long): String {
        val t = Instant.ofEpochSecond(at).atZone(zone)
        val year = Instant.ofEpochSecond(now).atZone(zone).year
        return (if (t.year == year) DAY else DAY_YEAR).format(t)
    }

    /** The author's newest kind 1010 per to-do id (AUTHORS: id → author); deleted edits don't count. */
    fun newestEdits(edits: List<Event>, authors: Map<String, String>, deleted: Set<String>): Map<String, Event> {
        val out = HashMap<String, Event>()
        for (e in edits) {
            val id = e.t("e") ?: continue
            if (authors[id] != e.pubkey || e.id in deleted) continue
            val had = out[id]
            if (had == null || had.createdAt < e.createdAt || (had.createdAt == e.createdAt && had.id < e.id)) out[id] = e
        }
        return out
    }

    /** The words after the name for a due date set ([due]) or cleared (null). */
    fun dueWords(due: Long?, zone: ZoneId, now: Long): String =
        if (due == null) "cleared the due date" else "set the due date to " + dueLabel(due, zone, now)
}

/**
 * The author sets or clears the due date: a kind 1010 naming the 2100, its text and every other
 * tag kept, plus `["due", unix seconds]` (null = no due date). Same tags as belfry-ios.
 */
fun Actions.setTaskDue(task: Event, title: String, due: Long?): Draft {
    val kept = task.tags.filter { it.isEmpty() || it[0] != "due" }
    return Draft(
        Target.GROUPS, World.EDIT_KIND, title,
        kept + listOf(listOf("e", task.id), listOf("k", "2100")) + (if (due != null) listOf(listOf("due", due.toString())) else emptyList()),
    )
}

/** The to-do's own kind 2100. */
fun World.taskEvent(groupId: String, taskId: String): Event? = taskEventsOf(groupId).firstOrNull { it.kind == 2100 && it.id == taskId }

/** What the to-do says beyond its title: the text of the message it was made from, when that says more. */
fun World.todoDescription(groupId: String, t: Task): String? =
    OneShape.todoDescription(t.description, t.title, t.source?.let { message(groupId, it) }?.text)

/** Who wrote the message a to-do was made from, if it is here. */
fun World.todoSourceAuthor(groupId: String, t: Task): String? = t.source?.let { message(groupId, it) }?.pubkey

/**
 * The to-do's conversation, oldest first: its quiet lines (words after the person's name) and its
 * comments. Heartbeats (working / idle) make no line.
 */
fun World.todoLines(groupId: String, taskId: String, now: Long): List<TodoLine> {
    val t = tasks(groupId).firstOrNull { it.id == taskId } ?: return emptyList()
    val zone = ctx.zone
    fun name(pk: String) = displayName(pk)
    val out = ArrayList<TodoLine>()
    val from = todoSourceAuthor(groupId, t)
    out.add(TodoLine(t.id, t.author, t.at, if (from != null) "made this to-do from ${if (from == t.author) "their own" else name(from) + "'s"} message" else "made this to-do", what = "created"))
    for (e in taskEventsOf(groupId)) {
        if (e.t("e") != taskId) continue
        if (e.kind == 2103) { out.add(TodoLine(e.id, e.pubkey, e.createdAt, null, e.content.sliceUnits(2000), "comment")); continue }
        if (e.kind != 2101) continue
        val a = e.t("action") ?: continue
        val p = e.t("p")
        val words = when (a) {
            "claim" -> "took it"
            "unclaim" -> "let go of it"
            "assign" -> p?.takeIf { sh.belfry.core.crypto.Hex.isKey(it) }?.let { if (it == me) "gave it to you" else "gave it to ${name(it)}" }
            "unassign" -> p?.let { if (it == me) "took you off it" else "took ${name(it)} off it" }
            "done" -> "marked it done"
            "undone" -> "reopened it"
            else -> null
        } ?: continue
        out.add(TodoLine(e.id, e.pubkey, e.createdAt, words, what = a))
    }
    // each of the author's edits that changed the due date
    var last = taskEvent(groupId, taskId)?.let { OneShape.due(it.tags) }
    for (e in editEvents().filter { it.t("e") == taskId && it.pubkey == t.author && it.id !in deleted }.sortedWith(World.BY_TIME)) {
        val due = OneShape.due(e.tags)
        if (due != last) out.add(TodoLine(e.id, e.pubkey, e.createdAt, TodoShape.dueWords(due, zone, now), what = "due"))
        last = due
    }
    return out.sortedWith(compareBy<TodoLine> { it.at }.thenBy { it.id })
}

/** Open and on me or on nobody: the to-do opens mid (the lead's ruling 5); else tiny. */
fun World.todoNeedsMe(t: Task): Boolean = !t.done && (t.assignee == null || t.assignee == me)

/** Photos and files linked in a to-do's comments (its header's 🖼 and 📁 counts): links ending in an image extension, or another file extension. */
fun Task.attachmentCounts(): Pair<Int, Int> {
    var photos = 0; var files = 0
    val link = Regex("https?://\\S+")
    for (cm in comments) for (m in link.findAll(cm.text)) {
        val path = m.value.substringBefore('?').substringBefore('#').trimEnd('.', ',', ')', ']')
        val ext = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()
        when {
            Pictures.isPicture(null, path) -> photos++ // the one rule for pictures (canvas v180 n-email-pictures)
            ext in setOf("pdf", "doc", "docx", "odt", "xls", "xlsx", "ods", "csv", "txt", "zip", "mp3", "m4a", "mp4", "mov", "ppt", "pptx", "odp") -> files++
        }
    }
    return photos to files
}
