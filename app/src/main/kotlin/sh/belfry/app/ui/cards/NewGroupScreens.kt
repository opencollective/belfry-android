package sh.belfry.app.ui.cards

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.ui.chat.face
import sh.belfry.app.ui.kit.ActionGroup
import sh.belfry.app.ui.kit.ActionRow
import sh.belfry.app.ui.kit.Avatar
import sh.belfry.app.ui.kit.BackButton
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.BelfryTextField
import sh.belfry.app.ui.kit.BusyButton
import sh.belfry.app.ui.kit.ButtonKind
import sh.belfry.app.ui.kit.Hairline
import sh.belfry.app.ui.kit.Keep
import sh.belfry.app.ui.kit.TopBar
import sh.belfry.app.ui.kit.rememberBusy
import sh.belfry.app.ui.kit.rememberFormDraft
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.model.GroupJoin
import sh.belfry.core.model.pickableMembers

/** The line icon of each "Who can join" choice. */
internal fun GroupJoin.icon(): ImageVector = when (this) {
    GroupJoin.OPEN -> BelfryIcons.Globe
    GroupJoin.CLOSED -> Icons.Outlined.PersonAdd
    GroupJoin.PRIVATE -> BelfryIcons.Lock
}

/**
 * "Who can join": Open · Closed · Private as one grouped list with a check on the chosen row
 * (never colour alone: e-ink). The New group form and the group's Settings (SPEC 19.59).
 */
@Composable
fun GroupJoinChoices(selected: GroupJoin, onSelect: (GroupJoin) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = Belfry.colors
    ActionGroup(modifier.testTag("group-join")) {
        GroupJoin.entries.forEachIndexed { i, j ->
            if (i > 0) Hairline()
            ActionRow(
                j.label, onClick = { onSelect(j) }, icon = j.icon(), sub = j.sub, enabled = enabled,
                modifier = Modifier.testTag("join-${j.key}").semantics { this.selected = selected == j },
                trailing = { if (selected == j) Icon(BelfryIcons.Check, "Selected", tint = c.text, modifier = Modifier.size(22.dp)) },
            )
        }
    }
}

/**
 * New group (canvas groups/new + groups/new-pick, n-groups-new; SPEC 19.59). Step 1: name, what it
 * is working on, who can join. Step 2: the belfry's members as a grid of faces to add; "Add N
 * people" or Skip makes the group (9007 + 9002, then one 9000 per person, then — not for a Private
 * one — the announcement in PARENT). From the Groups screen PARENT is the Townsquare.
 */
@Composable
fun CreatePodScreen(parent: String) {
    val session = LocalSession.current
    val world = LocalWorld.current
    val nav = LocalNav.current
    var name by rememberSaveable { mutableStateOf("") }
    var about by rememberSaveable { mutableStateOf("") }
    var join by rememberSaveable { mutableStateOf(GroupJoin.OPEN) }
    var step by rememberSaveable { mutableIntStateOf(1) }
    var tried by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val picked = remember { mutableStateListOf<String>() }
    val where = world.group(parent)
    // kept on this device until the group is made
    val draft = rememberFormDraft("group:$parent") { kept ->
        name = kept["name"] ?: name
        about = kept["about"] ?: about
        join = GroupJoin.entries.firstOrNull { it.key == kept["join"] } ?: join
    }
    draft.Keep("name" to name, "about" to about, "join" to join.key)

    if (step == 2) {
        BackHandler { step = 1 }
        PickPeople(
            groupName = name.trim(), picked = picked, error = error,
            onBack = { step = 1 },
            create = { add ->
                error = null
                val id = session.createPod(name.trim(), about.trim(), parent, join.access(), add)
                draft.clear()
                nav.openChannel(id)
            },
            onError = { error = shown(it) },
        )
        return
    }

    CardScreen("New group", subtitle = "1 of 2") {
        PageColumn(Modifier.testTag("new-group")) {
            BelfryTextField(name, { name = it }, Modifier.fillMaxWidth().testTag("create-name"), label = "Name", placeholder = "e.g. Soup Sundays", error = if (tried && name.isBlank()) "Give the group a name" else null, keyboardOptions = words)
            BelfryTextField(about, { about = it }, Modifier.fillMaxWidth().testTag("create-about"), label = "What is it working on?", singleLine = false, minLines = 2, maxLines = 6, keyboardOptions = words)
            Column {
                Muted("Who can join")
                Spacer(Modifier.height(6.dp))
                GroupJoinChoices(join, { join = it })
            }
            BusyButton(
                "Next",
                onClick = { tried = true; if (name.isNotBlank()) step = 2 },
                modifier = Modifier.fillMaxWidth().testTag("create-next"),
            )
            Muted(
                when {
                    join == GroupJoin.PRIVATE -> "Only the people you add see it. It is not announced anywhere."
                    parent == "general" || where == null -> "Announced in the Townsquare. Its question and how requests are accepted are in its Settings."
                    else -> "A group inside ${where.name}, announced there."
                },
            )
        }
    }
}

/** groups/new-pick: a grid of the belfry's members (no guests, no agents, no one removed), search on top. */
@Composable
private fun PickPeople(
    groupName: String,
    picked: MutableList<String>,
    error: String?,
    onBack: () -> Unit,
    create: suspend (List<String>) -> Unit,
    onError: (Throwable) -> Unit,
) {
    val c = Belfry.colors
    val world = LocalWorld.current
    var q by rememberSaveable { mutableStateOf("") }
    val list = remember(world, q) { world.pickableMembers(q) }
    val busy = rememberBusy()
    Column(Modifier.fillMaxSize().background(c.bg).testTag("new-group-pick")) {
        TopBar("Add people", subtitle = "2 of 2 · $groupName", navigation = { BackButton(onBack) })
        BelfryTextField(q, { q = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("pick-search"), placeholder = "Search members")
        if (list.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.TopCenter) {
                Text(if (q.isBlank()) "No other members yet. Skip, and add people later." else "No member by that name.", fontSize = 15.sp, color = c.muted, textAlign = TextAlign.Center)
            }
        } else {
            LazyVerticalGrid(
                GridCells.Adaptive(96.dp), Modifier.weight(1f).fillMaxWidth().testTag("pick-grid"),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(list, key = { it }) { pk ->
                    val on = pk in picked
                    val face = world.face(pk)
                    Column(
                        Modifier.clip(RoundedCornerShape(12.dp))
                            .toggleable(on, enabled = !busy.busy, role = Role.Checkbox) { if (it) picked.add(pk) else picked.remove(pk) }
                            .padding(vertical = 6.dp, horizontal = 4.dp).testTag("pick-$pk"),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box {
                            Avatar(face, size = 60.dp, modifier = if (on) Modifier.border(3.dp, c.text, CircleShape) else Modifier)
                            if (on) {
                                // a check, not a colour: e-ink shows it
                                Box(
                                    Modifier.align(Alignment.BottomEnd).offset(x = 2.dp, y = 2.dp).size(24.dp).clip(CircleShape).background(c.text).border(2.dp, c.bg, CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) { Icon(BelfryIcons.Check, contentDescription = null, tint = c.bg, modifier = Modifier.size(15.dp)) }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            face.name, fontSize = 14.sp, lineHeight = 18.sp, color = c.text, textAlign = TextAlign.Center,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        Hairline()
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
            error?.let { ErrorLine(it, Modifier.padding(bottom = 8.dp)) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // one lock for both: whichever is tapped, neither can be tapped again
                BusyButton("Skip", onClick = { create(emptyList()) }, kind = ButtonKind.Ghost, busy = busy, onError = onError, modifier = Modifier.weight(1f).testTag("pick-skip"))
                val n = picked.size
                BusyButton(
                    when (n) { 0 -> "Add people"; 1 -> "Add 1 person"; else -> "Add $n people" },
                    onClick = { create(picked.toList()) }, enabled = n > 0, busy = busy, onError = onError,
                    modifier = Modifier.weight(1.6f).testTag("pick-add"),
                )
            }
        }
    }
}
