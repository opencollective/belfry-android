package sh.belfry.core.model

import sh.belfry.core.markdown.Js
import sh.belfry.core.nostr.Event
import sh.belfry.core.nostr.Tag
import sh.belfry.core.outbox.Target

/**
 * Buttons on messages (SPEC 19.36; collective-belfry docs/actions.md, vectors/message-actions.json).
 * The message carries `["button", id, label, style?, action?]` tags; a press is a kind 1985
 * `L belfry/action` signed by whoever tapped; the handler answers with a kind 1985
 * `L belfry/action-result`. Only answers by the button's handler count: the message's author
 * for a button without action, the belfry key or its groups key for a `belfry:` action.
 */
data class MessageButton(val id: String, val label: String, val style: String, val action: String) {
    val primary: Boolean get() = style == "primary"
    val danger: Boolean get() = style == "danger"
    /** Handled by the belfry (`belfry:<name>`), not by the message's author. */
    val belfry: Boolean get() = action.startsWith(Buttons.BELFRY_PREFIX)
    /** A `belfry:` action this app does not know: shown disabled with [Buttons.UPDATE_APP]. */
    val unknown: Boolean get() = belfry && action !in Buttons.KNOWN_ACTIONS
}

/** An answer to a press, as the person who pressed reads it. */
data class ButtonAnswer(val status: String, val code: String?, val text: String, val pressId: String, val at: Long) {
    val ok: Boolean get() = status == "ok"
}

/**
 * What a message's buttons show for me. [closed]: finished for everyone, [closedText] replaces
 * the buttons. [myPress]: my latest press; [mine]: the trusted answer to it, if any.
 */
data class ButtonState(
    val closed: Boolean = false,
    val closedText: String? = null,
    val myPress: Event? = null,
    val mine: ButtonAnswer? = null,
) {
    /** My latest press has no answer yet and is younger than [Buttons.TIMEOUT_S]: the buttons stay locked. */
    fun waiting(now: Long): Boolean = !closed && myPress != null && mine == null && now - myPress.createdAt < Buttons.TIMEOUT_S

    /** My latest press had no answer within [Buttons.TIMEOUT_S] ("No answer yet."). */
    fun noAnswer(now: Long): Boolean = !closed && myPress != null && mine == null && now - myPress.createdAt >= Buttons.TIMEOUT_S

    /** Which button my latest press named (to show its spinner). */
    val pressedButton: String? get() = myPress?.let { Buttons.pressedId(it) }

    companion object {
        val NONE = ButtonState()
    }
}

object Buttons {
    const val NS = "belfry/action"
    const val RESULT_NS = "belfry/action-result"
    const val BELFRY_PREFIX = "belfry:"
    const val WEBSITE_PUBLISH = "belfry:website-publish"
    /** An admin closes a plain request from its card in the requests channel (collective-belfry docs/actions.md §4, requests.md §4b). */
    const val REQUEST_CLOSE = "belfry:request-close"
    const val MAX = 4
    const val TIMEOUT_S = 20L

    /** The `belfry:` actions this app knows (and asks to confirm with its own text). */
    val KNOWN_ACTIONS: Set<String> = setOf(WEBSITE_PUBLISH, REQUEST_CLOSE)

    private val ID = Regex("^[a-z0-9-]{1,32}$")

    /** Fixed sentences (vectors/message-actions.json `fixed_text`). */
    val FIXED: Map<String, String> = mapOf(
        "not_allowed" to "You're not allowed to do this.",
        "unknown_action" to "This belfry doesn't know this button yet.",
        "no_target" to "This message doesn't say what to act on.",
        "gone" to "This is no longer there.",
        "expired" to "That took too long. Try again.",
        "failed" to "That didn't work. Try again.",
    )
    const val GENERAL_ERROR = "That didn't work."
    const val TIMEOUT_TEXT = "No answer yet."
    const val UPDATE_APP = "Update the app to use this."
    const val NOT_SENT = "Couldn't send. Check your connection and try again."

    /** The sentence for an error answer with no text of its own. */
    fun errorText(code: String?): String = FIXED[code ?: ""] ?: GENERAL_ERROR

    /** The app's own question before a `belfry:` action, null for one it doesn't know. */
    fun confirmText(action: String, domain: String): String? = when (action) {
        WEBSITE_PUBLISH -> "Publish this version as the website? Everyone will see it at https://$domain."
        REQUEST_CLOSE -> "Close this request? It stays readable, and people can still comment."
        else -> null
    }

    /** A confirmation shown as a dialog: its question (up to the first "? ") as the title, the rest under it. */
    fun confirmParts(text: String): Pair<String, String?> {
        val i = text.indexOf("? ")
        return if (i < 0) text to null else text.substring(0, i + 1) to text.substring(i + 2).trim().ifEmpty { null }
    }

    /** The confirm button's word for a `belfry:` action. */
    fun confirmLabel(action: String): String = when (action) {
        WEBSITE_PUBLISH -> "Publish"
        REQUEST_CLOSE -> "Close"
        else -> "OK"
    }

    /** The buttons of a message's tags, in order; tags that break a rule are left out, at most [MAX]. */
    fun parse(tags: List<Tag>): List<MessageButton> {
        val out = ArrayList<MessageButton>()
        val seen = HashSet<String>()
        for (t in tags) {
            if (out.size >= MAX) break
            if (t.size < 3 || t[0] != "button") continue
            val id = t[1]
            if (!ID.matches(id) || id in seen) continue
            val label = Js.trim(t[2])
            if (label.isEmpty() || label.codePointCount(0, label.length) > 40) continue
            val style = t.getOrNull(3).orEmpty().let { if (it == "primary" || it == "danger") it else "" }
            seen.add(id)
            out.add(MessageButton(id, label, style, t.getOrNull(4).orEmpty()))
        }
        return out
    }

    /** The press of BUTTON on the message (its id and author) in GROUP_ID: published at once, not queued. */
    fun press(messageId: String, author: String, groupId: String, button: String): Draft = Draft(
        Target.GROUPS, 1985, "",
        listOf(listOf("L", NS), listOf("l", button, NS), listOf("e", messageId), listOf("p", author), listOf("h", groupId)),
    )

    /** The button a press names, else null. */
    fun pressedId(e: Event): String? = e.tags.firstOrNull { it.size >= 3 && it[0] == "l" && it[2] == NS }?.get(1)

    /** Is E a press or an answer about a message (kind 1985, L belfry/action[-result])? */
    fun isLabel(e: Event): Boolean = e.kind == 1985 && e.tags.any { it.size >= 2 && it[0] == "L" && (it[1] == NS || it[1] == RESULT_NS) }

    /** Who may answer BUTTON of a message by AUTHOR. */
    fun handlers(button: MessageButton, author: String, belfryKey: String?, groupsKey: String?): Set<String> =
        if (button.action.isEmpty()) setOf(author)
        else if (button.belfry) setOfNotNull(belfryKey, groupsKey)
        else emptySet()

    /**
     * The state of a message's BUTTONS for ME from LABELS (kind 1985 presses and answers whose `e`
     * is the message). An answer counts only from the handler of the button its press named (a
     * press this device hasn't seen: the handler of any of the message's buttons).
     */
    fun state(
        messageId: String,
        author: String,
        buttons: List<MessageButton>,
        me: String,
        labels: List<Event>,
        belfryKey: String?,
        groupsKey: String?,
    ): ButtonState {
        if (buttons.isEmpty()) return ButtonState.NONE
        val byId = buttons.associateBy { it.id }
        val presses = HashMap<String, Event>()
        var myPress: Event? = null
        for (e in labels) {
            if (e.kind != 1985 || e.t("L") != NS || e.t("e") != messageId) continue
            val b = pressedId(e) ?: continue
            if (b !in byId) continue
            presses[e.id] = e
            if (e.pubkey == me && (myPress == null || later(e, myPress))) myPress = e
        }
        val any = buttons.flatMap { handlers(it, author, belfryKey, groupsKey) }.toSet()
        var closing: Event? = null
        var mine: Event? = null
        for (e in labels) {
            if (e.kind != 1985 || e.t("L") != RESULT_NS || e.t("e") != messageId) continue
            val status = e.tags.firstOrNull { it.size >= 3 && it[0] == "l" && it[2] == RESULT_NS }?.get(1) ?: continue
            if (status != "ok" && status != "error") continue
            val pressId = e.t("press") ?: continue
            val pressed = presses[pressId]?.let { pressedId(it) }?.let { byId[it] }
            val trusted = if (pressed != null) handlers(pressed, author, belfryKey, groupsKey) else any
            if (e.pubkey !in trusted) continue
            if (status == "ok" && e.t("closed") == "1" && (closing == null || later(e, closing))) closing = e
            if (myPress != null && pressId == myPress.id && (mine == null || later(e, mine))) mine = e
        }
        if (closing != null) return ButtonState(true, Js.trim(closing.content).ifEmpty { "Done." }, myPress, mine?.let(::answer))
        return ButtonState(false, null, myPress, mine?.let(::answer))
    }

    private fun answer(e: Event): ButtonAnswer {
        val status = e.tags.first { it.size >= 3 && it[0] == "l" && it[2] == RESULT_NS }[1]
        val code = e.t("code")
        val said = Js.trim(e.content)
        val text = if (status == "ok") said else said.ifEmpty { errorText(code) }
        return ButtonAnswer(status, code, text, e.t("press").orEmpty(), e.createdAt)
    }

    private fun later(a: Event, b: Event): Boolean = a.createdAt > b.createdAt || (a.createdAt == b.createdAt && a.id > b.id)
}
