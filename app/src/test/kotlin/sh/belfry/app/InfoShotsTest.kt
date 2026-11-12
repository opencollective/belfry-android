package sh.belfry.app

import kotlinx.coroutines.runBlocking
import org.junit.Test
import sh.belfry.app.ui.info.ChannelInfoScreen
import sh.belfry.app.ui.info.DropScreen
import sh.belfry.app.ui.info.DropShare
import sh.belfry.app.ui.info.FilesScreen
import sh.belfry.app.ui.info.FollowScreen
import sh.belfry.app.ui.info.InfoTab
import sh.belfry.app.ui.info.InviteScreen
import sh.belfry.app.ui.info.SearchScreen
import sh.belfry.app.ui.info.TopicsScreen
import sh.belfry.app.ui.kit.SheetContent
import sh.belfry.core.engine.Demo
import sh.belfry.core.model.Actions
import sh.belfry.core.net.Follows

/** Channel information and tools in demo mode: info tabs, settings, invite, search, topics, follow, files, drop links. */
class InfoShotsTest : DemoShots() {
    private val general = Demo.GENERAL

    private fun until(what: String, check: () -> Boolean) {
        repeat(200) {
            settle()
            if (check()) return
            Thread.sleep(25)
        }
        error(what)
    }

    @Test fun about() = demoShot("info-about") { ChannelInfoScreen(general) }

    @Test fun aboutPod() = demoShot("info-about-pod") { ChannelInfoScreen(Demo.POD_OPEN) }

    @Test fun todos() {
        started()
        runBlocking {
            session.send(Actions.addTask(general, "Order compost for the spring beds"))
            session.send(Actions.addTask(general, "Fix the gate latch"))
            session.send(Actions.addTask(general, "Print the welcome leaflet"))
        }
        until("the to-dos never left the demo outbox") { world.tasks(general).count { !it.pending } == 3 }
        val tasks = world.tasks(general)
        runBlocking {
            session.send(Actions.taskAction(general, tasks[0].id, "claim"))
            session.send(Actions.taskAction(general, tasks[2].id, "done"))
        }
        until("the to-do changes never showed") { world.tasks(general).any { it.done } && world.tasks(general).any { it.assignee != null } }
        demoShot("info-todos") { ChannelInfoScreen(general, initialTab = InfoTab.TODOS) }
    }

    @Test fun members() = demoShot("info-members") { ChannelInfoScreen(general, initialTab = InfoTab.MEMBERS) }

    /** A closed pod seen by its admin: someone asks to join. */
    @Test fun membersOfAClosedPod() = demoShot("info-members-pod") { ChannelInfoScreen(Demo.POD_CLOSED, initialTab = InfoTab.MEMBERS) }

    @Test fun files() = demoShot("info-files") { ChannelInfoScreen(general, initialTab = InfoTab.FILES) }

    /** Everything that happened in the channel, a day per section. */
    @Test fun log() = demoShot("info-log") { ChannelInfoScreen(Demo.GARDEN, initialTab = InfoTab.LOG) }

    @Test fun settings() = demoScreenShot("info-settings") { ChannelInfoScreen(Demo.POD_CLOSED, settingsOpen = true) }

    @Test fun invite() = demoShot("invite") { InviteScreen(Demo.GARDEN) }

    @Test fun inviteGuest() = demoShot("invite-guest") { InviteScreen(Demo.GARDEN, startAsGuest = true) }

    @Test fun searchEmpty() = demoShot("search-empty") { SearchScreen(general) }

    @Test fun searchInChannel() = demoShot("search-channel") { SearchScreen(general, initialQuery = "pantry budget") }

    @Test fun searchEverywhere() = demoShot("search-all") { SearchScreen(null, initialQuery = "ines") }

    @Test fun searchNothing() = demoShot("search-nothing") { SearchScreen(null, initialQuery = "zeppelin") }

    @Test fun topics() = demoShot("topics") { TopicsScreen(general) }

    @Test fun follow() = demoShot("follow") {
        FollowScreen(general, preloaded = Follows("alex@belfry.example", mapOf(general to "asap", Demo.GARDEN to "monthly")))
    }

    @Test fun filesPage() = demoShot("files") { FilesScreen(general) }

    @Test fun dropNew() = demoShot("drop-new") { DropScreen(general) }

    @Test fun dropMade() = demoShot("drop-made") { DropScreen(general, made = "https://belfry.example/drop/Zm9yLXRoZS1zY3JlZW5zaG90") }

    @Test fun dropShare() {
        val link = world.groups.flatMap { world.dropLinks(it.id, NOW) }.firstOrNull() ?: error("the demo has no drop link")
        demoShot("drop-share") { SheetContent("Drop link") { DropShare(link, onEnd = {}) } }
    }
}
