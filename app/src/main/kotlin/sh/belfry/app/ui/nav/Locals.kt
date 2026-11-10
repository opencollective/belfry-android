package sh.belfry.app.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import sh.belfry.core.dm.DmSnapshot
import sh.belfry.core.engine.Session
import sh.belfry.core.engine.Status
import sh.belfry.core.model.World

/** How screens move around. A back arrow calls [back]; nothing else pops. */
interface Nav {
    /** Push a screen: `nav.go(Routes.profile(pubkey))`. */
    fun go(route: String)
    fun back()
    /** Show a channel in the home screen (pops everything above it); optionally scroll to a message. */
    fun openChannel(id: String, messageId: String? = null)
}

/** For previews and tests: goes nowhere, remembers what was asked. */
class RecordingNav : Nav {
    val went = ArrayList<String>()
    override fun go(route: String) { went.add(route) }
    override fun back() { went.add("<back>") }
    override fun openChannel(id: String, messageId: String?) { went.add("<channel:$id>") }
}

/** The signed-in session: actions (`session.postMessage(…)`), `session.me`, `session.community`. */
val LocalSession = staticCompositionLocalOf<Session> { error("No session: wrap the screen in SessionScope") }

/** The current snapshot of everything derived from the log. Recomposes readers when it changes. */
val LocalWorld = compositionLocalOf<World> { error("No world: wrap the screen in SessionScope") }

/** Direct messages and e-mail. */
val LocalDms = compositionLocalOf<DmSnapshot> { error("No session: wrap the screen in SessionScope") }

/** Connection, access and outbox counts. */
val LocalStatus = compositionLocalOf<Status> { error("No session: wrap the screen in SessionScope") }

val LocalNav = staticCompositionLocalOf<Nav> { RecordingNav() }

/** Signs out (wipes the device) and returns the belfry's logout address to optionally open; set by AppRoot. */
val LocalSignOut = staticCompositionLocalOf<suspend () -> String?> { { null } }

/** The world when there is a session, else null (kit components that work signed out too). */
val LocalWorldOrNull = compositionLocalOf<World?> { null }

/** Everything a signed-in screen reads, provided once around the navigation graph (and by tests). */
@Composable
fun SessionScope(session: Session, nav: Nav, content: @Composable () -> Unit) {
    val world by session.world.collectAsState()
    val dms by session.dms.collectAsState()
    val status by session.status.collectAsState()
    CompositionLocalProvider(
        LocalSession provides session,
        LocalWorld provides world,
        LocalWorldOrNull provides world,
        LocalDms provides dms,
        LocalStatus provides status,
        LocalNav provides nav,
        content = content,
    )
}
