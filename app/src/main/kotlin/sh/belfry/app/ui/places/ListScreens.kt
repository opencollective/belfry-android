package sh.belfry.app.ui.places

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import sh.belfry.app.ui.chat.glyph
import sh.belfry.app.ui.kit.BackButton
import sh.belfry.app.ui.kit.EmptyState
import sh.belfry.app.ui.kit.MoreIcons
import sh.belfry.app.ui.kit.TopBar
import sh.belfry.app.ui.kit.icon
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.nav.Routes
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.model.myArchived
import sh.belfry.core.model.myTodos

/** My to-dos (Home's tile): on me, then open ones nobody took, across the channels and groups I'm in. */
@Composable
fun MyTodosScreen() {
    val world = LocalWorld.current
    val nav = LocalNav.current
    val (onMe, open) = world.myTodos()
    Column(Modifier.fillMaxSize().background(Belfry.colors.bg).testTag("my-todos")) {
        TopBar("To-dos", navigation = { BackButton { nav.back() } })
        if (onMe.isEmpty() && open.isEmpty()) {
            EmptyState("Nothing to do", text = "To-dos on you and the ones nobody took show here.", icon = MoreIcons.Todo)
            return@Column
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            for ((title, part) in listOf("On you" to onMe, "Nobody took them yet" to open)) {
                if (part.isEmpty()) continue
                item(key = "<$title>") { PlaceSection(title) }
                item(key = "<$title-list>") {
                    PlaceCard {
                        part.forEachIndexed { i, r ->
                            if (i > 0) RowDivider()
                            val g = world.group(r.group)
                            sh.belfry.app.ui.info.TodoListRow(r.group, r.task, sub = g?.name)
                        }
                    }
                }
            }
            item(key = "<end>") { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** The archived channels and groups I can open (SPEC 19.30); a tap opens one read only. */
@Composable
fun ArchivedScreen() {
    val world = LocalWorld.current
    val nav = LocalNav.current
    val list = world.myArchived()
    Column(Modifier.fillMaxSize().background(Belfry.colors.bg).testTag("archived")) {
        TopBar("Archived", navigation = { BackButton { nav.back() } })
        if (list.isEmpty()) {
            EmptyState("Nothing archived", icon = Icons.Outlined.Archive)
            return@Column
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            item(key = "<list>") {
                Spacer(Modifier.height(6.dp))
                PlaceCard {
                    list.forEachIndexed { i, g ->
                        if (i > 0) RowDivider()
                        NavRow(g.glyph(world).icon(), g.name, "Archived · read only", { nav.go(Routes.chat(g.id)) })
                    }
                }
            }
        }
    }
}
