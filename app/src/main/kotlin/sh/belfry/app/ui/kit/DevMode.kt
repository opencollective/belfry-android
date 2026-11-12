package sh.belfry.app.ui.kit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.core.engine.Session

/**
 * Developer mode (web 2026-11-12, Me → Developer mode): a channel's Log lets its admins look at
 * the Nostr event behind each line. Kept on this device.
 */
object DevMode {
    private const val KEY = "dev-mode"

    suspend fun read(session: Session): Boolean = withContext(Dispatchers.IO) {
        try { session.store.get(KEY) == "1" } catch (e: Exception) { false }
    }

    suspend fun write(session: Session, on: Boolean) = withContext(Dispatchers.IO) {
        try { if (on) session.store.set(KEY, "1") else session.store.remove(KEY) } catch (e: Exception) { }
    }
}

/** Is developer mode on (read once when the screen opens)? [initial]: for tests. */
@Composable
fun rememberDevMode(initial: Boolean = false): MutableState<Boolean> {
    val session = LocalSession.current
    val on = remember(session) { mutableStateOf(initial) }
    LaunchedEffect(session) { if (!initial) on.value = DevMode.read(session) }
    return on
}
