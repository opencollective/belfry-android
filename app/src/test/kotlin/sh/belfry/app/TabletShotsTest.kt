package sh.belfry.app

import org.junit.Test
import org.robolectric.annotation.Config
import androidx.navigation.compose.rememberNavController
import sh.belfry.app.ui.nav.AppNav
import sh.belfry.app.ui.shell.ShellState
import sh.belfry.core.engine.Demo

/**
 * Tablets (Xavier, 2026-11-11: "make it work for tablets as well"): the same APK, the layout
 * follows the window. A 10" tablet in landscape keeps the channel list at the left and opens a
 * thread in a third column; a 7" tablet in landscape keeps the list; in portrait a tablet is a
 * big phone (the drawer).
 */
class TabletShotsTest : DemoShots() {
    private val general = Demo.GENERAL

    private fun busiest() = world.threadsOf(general).maxByOrNull { it.value.count }!!.key

    @Config(qualifiers = "w1280dp-h800dp-land-240dpi")
    @Test fun tenInchLandscapeWithThread() {
        val shell = ShellState(general).apply { thread = busiest() }
        demoShot("tablet-10-landscape-thread") { AppNav(rememberNavController(), shell) }
    }

    @Config(qualifiers = "w1280dp-h800dp-land-240dpi")
    @Test fun tenInchLandscape() {
        val shell = ShellState(general)
        demoShot("tablet-10-landscape") { AppNav(rememberNavController(), shell) }
    }

    @Config(qualifiers = "w960dp-h600dp-land-240dpi")
    @Test fun sevenInchLandscape() {
        val shell = ShellState(general)
        demoShot("tablet-7-landscape") { AppNav(rememberNavController(), shell) }
    }

    /** A phone on its side keeps the list too (Xavier, 2026-11-11). */
    @Config(qualifiers = "w891dp-h411dp-land-420dpi")
    @Test fun phoneLandscape() {
        val shell = ShellState(general)
        demoShot("phone-landscape") { AppNav(rememberNavController(), shell) }
    }

    @Config(qualifiers = "w800dp-h1280dp-port-240dpi")
    @Test fun tenInchPortrait() {
        val shell = ShellState(general)
        demoShot("tablet-10-portrait") { AppNav(rememberNavController(), shell) }
    }
}
