package sh.belfry.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import sh.belfry.app.ui.cards.CreateEventScreen
import sh.belfry.app.ui.cards.EventCard
import sh.belfry.app.ui.cards.ReplyQueue
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.app.ui.theme.BelfryTheme

/**
 * The lead's test for the CardsShotsTest hang (2026-11-19): CardsShotsTest's own setup (a fresh
 * JVM, ReplyQueue.reset() first), a manual clock advanced frame by frame, and composition counts.
 * Counts growing with frames on a still screen = a real per-frame loop (the deepest growing
 * counter names it); flat counts while Compose still says busy = something outside composition.
 * It always fails, with the counts in its message, until the cause is known.
 */
class CreateEventLoopTest : DemoShots() {
    private val counts = LinkedHashMap<String, Int>()
    private fun bump(k: String) { counts[k] = (counts[k] ?: 0) + 1 }

    @Before fun clean() = ReplyQueue.reset()

    @Test fun countCompositionsPerFrame() {
        compose.mainClock.autoAdvance = false
        val coord = world.calendar.first { !it.isNew }.coord
        val proposal = world.proposals.first().groupId
        compose.setContent {
            SideEffect { bump("root") }
            BelfryTheme(dark = false) {
                Box(Modifier.fillMaxSize().background(Belfry.colors.bg)) {
                    InDemo {
                        SideEffect { bump("inDemo") }
                        Box { SideEffect { bump("screen") }; CreateEventScreen("general") }
                        Box { SideEffect { bump("eventCard") }; EventCard(coord, "") }
                        Box { SideEffect { bump("podCard") }; sh.belfry.app.ui.cards.PodCard(sh.belfry.core.engine.Demo.POD_OPEN, "") }
                        Box { SideEffect { bump("proposalCard") }; sh.belfry.app.ui.cards.ProposalCard(proposal, "") }
                    }
                }
            }
        }
        val log = StringBuilder()
        for (i in 1..120) {
            compose.mainClock.advanceTimeByFrame()
            if (i % 20 == 0) log.append("frame $i: $counts\n")
        }
        val busy = try {
            compose.mainClock.autoAdvance = true
            compose.waitForIdle(); "idle"
        } catch (e: Throwable) { "still busy: ${e.javaClass.simpleName}" }
        log.append("after 120 frames + autoAdvance: $busy, counts $counts")
        fail("composition counts (diagnosis, not a check):\n$log")
    }
}
