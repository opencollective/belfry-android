package sh.belfry.app.ui.kit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.core.engine.Session

/**
 * A form's fields kept on this device while it is being written (web `useFormDraft`, 2026-11-11):
 * leaving the form and opening it again brings them back; [clear] once it is sent.
 *
 *     val draft = rememberFormDraft(FormDrafts.CHANNEL) { kept -> name = kept["name"] ?: name }
 *     draft.Keep("name" to name, "about" to about)
 *     … onClick = { session.createChannel(…); draft.clear() }
 *
 * A null key keeps nothing (editing something that exists).
 */
@Stable
class FormDraft internal constructor(private val session: Session, private val key: String?) {
    /** What was kept has been read (and handed to `restore`). */
    var ready by mutableStateOf(key == null)
        internal set
    private var sent = false
    private var last: Map<String, String>? = null
    /** Fields just came back from the device: they are what is kept already. */
    internal var restored = false

    /** The form was sent: forget its fields (what is typed while leaving is not kept again). */
    fun clear() {
        sent = true
        if (key != null) session.saveFormDraft(key, null)
    }

    internal fun keep(fields: Map<String, String>) {
        if (key == null || sent) return
        // the fields as the form opened (or as they came back) are not written: only what changes after
        if (last == null || restored) { last = fields; restored = false; return }
        if (!ready || fields == last) return
        last = fields
        session.saveFormDraft(key, fields)
    }
}

@Composable
fun rememberFormDraft(key: String?, restore: (Map<String, String>) -> Unit): FormDraft {
    val session = LocalSession.current
    val draft = remember(session, key) { FormDraft(session, key) }
    LaunchedEffect(draft) {
        if (key != null && !draft.ready) {
            val kept = try { session.formDraft(key) } catch (e: Exception) { null }
            if (!kept.isNullOrEmpty()) { restore(kept); draft.restored = true }
            draft.ready = true
        }
    }
    return draft
}

/** Keeps these fields as they change. Call it on every composition of the form. */
@Composable
fun FormDraft.Keep(vararg fields: Pair<String, String>) {
    val now = fields.toMap()
    LaunchedEffect(now, ready) { keep(now) }
}
