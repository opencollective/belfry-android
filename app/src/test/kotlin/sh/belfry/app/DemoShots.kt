package sh.belfry.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import sh.belfry.app.ui.kit.LocalNetworkImages
import sh.belfry.app.ui.nav.RecordingNav
import sh.belfry.app.ui.nav.SessionScope
import sh.belfry.core.engine.Demo
import sh.belfry.core.engine.MemoryVault
import sh.belfry.core.engine.Onboarding
import sh.belfry.core.engine.Session
import sh.belfry.core.model.Message
import sh.belfry.core.model.World
import sh.belfry.core.store.MemoryStore
import java.time.Instant
import java.time.ZoneOffset

/**
 * Screens in demo mode, for screenshot and interaction tests. Each test gets its own seeded
 * demo [session] (fixed clock, UTC, in memory, no network) and a [nav] that records where
 * screens wanted to go. Wrap the screen in [InDemo] (or use [demoShot]): it provides what `SessionScope` provides in
 * the app, with network pictures off.
 *
 *     class InfoShotsTest : DemoShots() {
 *         @Test fun info() = demoShot("channel-info") { ChannelInfoScreen(Demo.GENERAL) }
 *     }
 */
abstract class DemoShots : Shots() {
    val nav = RecordingNav()

    /** A settled demo session. Not started: nothing changes behind a screenshot. Call [started] for tests that send. */
    val session: Session by lazy {
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val onboarding = Onboarding(MemoryVault(), MemoryStore(), OkHttpClient(), scope, clock = { NOW }, zone = ZoneOffset.UTC)
            onboarding.demo().also { it.settle() }
        }
    }

    val world: World get() = session.world.value

    /** Starts the session's own loops (the outbox publishes on the device). */
    fun started(): Session = session.also { it.start() }

    /** Publishes what is queued and waits for the world to show it. */
    fun settle() = runBlocking {
        session.flush()
        session.settle()
    }

    /** Posts a message as me in a channel and returns it once it is out (editable, deletable). */
    fun myMessage(text: String, channel: String = Demo.GENERAL): Message {
        runBlocking { session.postMessage(channel, text) }
        settle()
        return world.messages(channel).last { it.pubkey == session.me && it.text == text }
    }

    @Composable
    fun InDemo(content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalNetworkImages provides false) {
            SessionScope(session, nav, content)
        }
    }

    /** `<name>.png` and `<name>-dark.png` of a signed-in screen. */
    fun demoShot(name: String, content: @Composable () -> Unit) = shot(name) { InDemo(content) }

    /** The same, capturing every window: for screens that open a sheet or a dialog. */
    fun demoScreenShot(name: String, content: @Composable () -> Unit) = screenShot(name) { InDemo(content) }

    companion object {
        /** Tuesday 10 November 2026, 10:30 UTC. */
        val NOW: Long = Instant.parse("2026-11-10T10:30:00Z").epochSecond
    }
}
