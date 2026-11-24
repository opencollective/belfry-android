package sh.belfry.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import sh.belfry.app.ui.nav.LocalDms
import sh.belfry.app.ui.nav.LocalStatus
import sh.belfry.core.dm.Dm
import sh.belfry.core.dm.DmSnapshot
import sh.belfry.core.engine.Demo
import sh.belfry.core.engine.DemoEnc3View
import sh.belfry.core.engine.MyDevices
import sh.belfry.core.engine.Session
import sh.belfry.core.engine.Status
import sh.belfry.core.net.SessionInfo
import sh.belfry.core.read.ReadState

/**
 * Format 3 for the screens' tests (SPEC 19.84): the demo stays format 1, these install a made-up [DemoEnc3View] on the
 * demo session (my three devices with their names, counts for the others) and, where a state needs it, made-up messages.
 */
object Enc3Fixture {
    val HERE = "a1".repeat(32)
    val MAC = "b2".repeat(32)
    val TABLET = "c3".repeat(32)
    val AUG12 = java.time.Instant.parse("2026-08-12T09:00:00Z").epochSecond
    val OCT2 = java.time.Instant.parse("2026-10-02T09:00:00Z").epochSecond

    val sessions = listOf(
        SessionInfo("Belfry", "Belfry on Pixel 8", "", "bk-here", "2026-08-12", null, HERE, true),
        SessionInfo("Belfry", "Safari on Mac", "", "bk-mac", "2026-10-02", null, MAC, true),
        SessionInfo("Belfry", "Belfry on Boox Tab", "", "bk-tab", "2026-11-01", null, TABLET, true),
    )

    fun maya() = "n:" + Demo.maya.pubkey

    /** Installs format 3 on SESSION: ENCRYPTED conversations (first key's date), my devices (none = not set up), my AGENT. */
    fun install(session: Session, encrypted: Map<String, Long>, agent: String? = null, setUp: Boolean = true, format3: Boolean = true) {
        session.demoEnc3View = DemoEnc3View(
            encryptedConvs = encrypted,
            counts = mapOf(Demo.maya.pubkey to 2, Demo.tomas.pubkey to 3, Demo.lena.pubkey to 1),
            myDevices = if (!setUp) null else MyDevices(listOf(HERE, MAC, TABLET), listOfNotNull(agent), mapOf(HERE to AUG12, MAC to OCT2, TABLET to OCT2 + 30 * 86400L) + (agent?.let { mapOf(it to DemoShots.NOW - 3600) } ?: emptyMap()), HERE),
            mySessions = sessions,
            format3 = format3,
        )
    }

    /** Made-up messages in place of the demo's (states the demo can't make: opening, held, not-before…). */
    @Composable
    fun WithDms(session: Session, items: List<Dm>, online: Boolean = true, content: @Composable () -> Unit) {
        val dms = DmSnapshot(items, emptyMap(), ReadState.EMPTY, session.me, session.now())
        CompositionLocalProvider(LocalDms provides dms, LocalStatus provides Status(online = online, localOnly = true)) { content() }
    }
}
