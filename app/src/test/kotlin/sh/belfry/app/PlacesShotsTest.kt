package sh.belfry.app

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import sh.belfry.app.ui.nav.Routes
import sh.belfry.app.ui.places.ArchivedScreen
import sh.belfry.app.ui.places.CalendarPlace
import sh.belfry.app.ui.places.CreateSheet
import sh.belfry.app.ui.places.GroupsPlace
import sh.belfry.app.ui.places.MyTodosScreen
import sh.belfry.app.ui.shell.ChannelDrawer
import sh.belfry.app.ui.shell.DrawerActions
import sh.belfry.app.ui.shell.HomeScreen
import sh.belfry.app.ui.shell.ShellState
import sh.belfry.app.ui.theme.BelfryTheme
import sh.belfry.core.engine.Demo

/** The five places (SPEC 19.31): Home, Calendar, Groups, Create, Town and its drawer, in demo mode. */
class PlacesShotsTest : DemoShots() {
    private fun shell(place: ShellState.Place = ShellState.Place.HOME) = ShellState(Demo.GENERAL).apply { this.place = place }

    @Test fun home() = demoShot("places-home") { HomeScreen(shell()) }
    @Test fun homeDrawerAdmin() = demoShot("places-drawer-admin") { HomeScreen(shell(), drawerOpen = true) }
    @Test fun town() = demoShot("places-town") { HomeScreen(shell(ShellState.Place.TOWN)) }
    @Test fun townDrawer() = demoShot("places-town-drawer") { HomeScreen(shell(ShellState.Place.TOWN), drawerOpen = true) }
    @Test fun calendar() = demoShot("places-calendar") { HomeScreen(shell(ShellState.Place.CALENDAR)) }
    @Test fun calendarDiscover() {
        demoShot("places-calendar-discover") { CalendarPlace() }
        compose.onNodeWithTag("calendar-tab-2").performClick()
        compose.waitForIdle()
    }
    @Test fun groups() = demoShot("places-groups") { HomeScreen(shell(ShellState.Place.GROUPS)) }
    @Test fun groupsAll() {
        demoShot("places-groups-all") { GroupsPlace() }
        compose.onNodeWithTag("groups-all").performClick()
        compose.waitForIdle()
    }
    @Test fun create() = demoScreenShot("places-create") { CreateSheet(shell(ShellState.Place.TOWN), onDismiss = {}) }
    @Test fun myTodos() = demoShot("places-my-todos") { MyTodosScreen() }
    @Test fun archived() = demoShot("places-archived") { ArchivedScreen() }

    /** A member who is not a steward: no gear, a ＋ only on Community life, no drag hint. */
    @Test fun drawerMember() {
        runBlocking { session.setAdmin(session.me, false) }
        demoShot("places-drawer-member") { ChannelDrawer(shell(ShellState.Place.TOWN), DrawerActions({}, {}, {}, {})) }
    }

    /** A guest: Home · Calendar · Town; Home asks them to apply. */
    @Test fun guestHome() {
        session.demoGuestMode(Demo.GENERAL)
        demoShot("places-guest-home") { HomeScreen(shell()) }
    }

    @Test fun guestDrawer() {
        session.demoGuestMode(Demo.GENERAL)
        demoShot("places-guest-drawer") { ChannelDrawer(shell(), DrawerActions({}, {}, {}, {})) }
    }

    /** The bar moves between places; Create opens its menu; the drawer's rows push their screens. */
    @Test fun theBarAndTheDrawer() {
        val s = shell()
        compose.setContent { BelfryTheme { InDemo { HomeScreen(s) } } }
        compose.onNodeWithTag("place-calendar").performClick()
        compose.waitForIdle()
        assertEquals(ShellState.Place.CALENDAR, s.place)
        compose.onNodeWithTag("place-town").performClick()
        compose.waitForIdle()
        assertEquals(ShellState.Place.TOWN, s.place)
        compose.onNodeWithTag("place-create").performClick()
        compose.waitForIdle()
        assertEquals(true, s.creating)
    }

    @Test fun theDrawersRowsOpenScreens() {
        val s = shell(ShellState.Place.TOWN)
        val went = ArrayList<String>()
        compose.setContent { BelfryTheme { InDemo { ChannelDrawer(s, DrawerActions({ s.open(it) }, { s.place = ShellState.Place.HOME }, {}, { went.add(it) })) } } }
        compose.onNodeWithTag("drawer-members").performClick()
        compose.onNodeWithTag("drawer-today").performClick()
        compose.waitForIdle()
        assertEquals(listOf(Routes.MEMBERS), went)
        assertEquals(ShellState.Place.HOME, s.place)
    }
}
