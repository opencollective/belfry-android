package sh.belfry.app.ui.belfry

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import sh.belfry.app.ui.chat.face
import sh.belfry.app.ui.kit.Avatar
import sh.belfry.app.ui.kit.BackButton
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.BelfrySheet
import sh.belfry.app.ui.kit.BelfryTextField
import sh.belfry.app.ui.kit.BusyButton
import sh.belfry.app.ui.kit.ButtonKind
import sh.belfry.app.ui.kit.ConfirmSheet
import sh.belfry.app.ui.kit.EmptyState
import sh.belfry.app.ui.kit.IconAction
import sh.belfry.app.ui.kit.SectionTitle
import sh.belfry.app.ui.kit.TopBar
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.nav.Routes
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.engine.ActionException
import sh.belfry.core.model.Agreement
import sh.belfry.core.model.PlaceNames
import sh.belfry.core.model.charter
import sh.belfry.core.model.charterAgreedAt
import sh.belfry.core.model.charterPeople
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH)

/**
 * The charter (SPEC 19.32): agreements each person ticks for themselves. Mine with a tick; a rule I
 * haven't agreed to is marked NEW (or CHANGED when its words changed since my tick); "N of M
 * members". Stewards: New agreement, edit (changing the words asks everyone again), move, remove;
 * a count opens who agreed. People who can't read the group (guests) read the belfry's copy.
 */
@Composable
fun CharterScreen() {
    val c = Belfry.colors
    val world = LocalWorld.current
    val session = LocalSession.current
    val nav = LocalNav.current
    val public by session.members.publicCharter.collectAsState()
    val own = world.charter()
    LaunchedEffect(Unit) { if (own.isEmpty()) try { session.members.refreshPublicCharter() } catch (e: Exception) { } }
    val rules = own.ifEmpty { public }
    val readOnly = own.isEmpty() // the public copy: nobody's ticks
    val steward = world.isCommunityAdmin
    val people = world.charterPeople()
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Agreement?>(null) }
    var removing by remember { mutableStateOf<Agreement?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().background(c.bg).testTag("charter")) {
        TopBar(PlaceNames.CHARTER, navigation = { BackButton { nav.back() } }, actions = {
            if (steward) IconAction(BelfryIcons.Plus, "New agreement", { adding = true }, modifier = Modifier.testTag("charter-new"))
        })
        Column(Modifier.weight(1f).fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
            if (rules.isEmpty()) {
                EmptyState("No agreements yet", text = if (steward) "Write the first agreement: everyone here ticks it for themselves." else null, icon = Icons.Outlined.Description,
                    action = if (steward) ({ BusyButton("Write the first agreement", onClick = { adding = true }) }) else null)
                return@Column
            }
            if (!readOnly && !world.amGuest) {
                val at = world.charterAgreedAt()
                val left = rules.count { world.me !in it.agreed }
                Text(
                    if (at != null) "You agreed · " + DATE.format(Instant.ofEpochSecond(at).atZone(world.ctx.zone)) else "$left of ${rules.size} to read and tick",
                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.text, modifier = Modifier.testTag("charter-state"),
                )
                Spacer(Modifier.height(12.dp))
            }
            error?.let { Text(it, color = c.danger, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp)) }
            rules.forEachIndexed { i, a ->
                AgreementRow(
                    a, i, rules.size, readOnly, steward, people.size,
                    onTick = { on -> scope.launch { try { session.members.agree(a, on); error = null } catch (e: ActionException) { error = e.message } } },
                    onWho = { nav.go(Routes.charterWho(a.id)) },
                    onEdit = { editing = a }, onRemove = { removing = a },
                    onMove = { by -> scope.launch { try { session.members.moveAgreement(a, by) } catch (e: ActionException) { error = e.message } } },
                )
            }
            if (!readOnly) Text("A new or changed agreement never stops anyone from posting: it is a reminder.", fontSize = 13.sp, color = c.muted, modifier = Modifier.padding(top = 12.dp))
        }
    }
    if (adding || editing != null) AgreementSheet(editing, onDone = { adding = false; editing = null })
    removing?.let { a ->
        ConfirmSheet("Remove “${a.title}”?", "Remove", onConfirm = { try { session.members.removeAgreement(a) } catch (e: ActionException) { error = e.message }; removing = null },
            onDismiss = { removing = null }, message = "It leaves the charter for everyone.")
    }
}

@Composable
private fun AgreementRow(
    a: Agreement, i: Int, n: Int, readOnly: Boolean, steward: Boolean, members: Int,
    onTick: (Boolean) -> Unit, onWho: () -> Unit, onEdit: () -> Unit, onRemove: () -> Unit, onMove: (Int) -> Unit,
) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val mine = world.me in a.agreed
    val marker = if (readOnly || mine || world.amGuest) null else if (a.changedFor(world.me)) "CHANGED" else "NEW"
    var menu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(shape).border(if (marker != null) 2.dp else 1.dp, if (marker != null) c.text else c.line, shape)
            .padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 8.dp).testTag("agreement"),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            if (!readOnly && !world.amGuest) {
                Checkbox(checked = mine, onCheckedChange = onTick, colors = CheckboxDefaults.colors(checkedColor = c.accent, checkmarkColor = c.onAccent), modifier = Modifier.testTag("agree-$i"))
            } else Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).padding(top = 10.dp)) {
                if (marker != null) Text(marker, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = c.text)
                sh.belfry.app.ui.kit.AgreementText(a.title, a.about, titleSize = 17, aboutSize = 15)
                if (!readOnly) {
                    val count = "${a.agreed.keys.count { it in world.charterPeople() || it == world.me }} of $members members"
                    Text(count, fontSize = 13.sp, color = c.muted, modifier = Modifier.padding(top = 4.dp).then(if (steward) Modifier.clickable(role = Role.Button, onClick = onWho) else Modifier))
                }
            }
            if (steward && !readOnly) {
                Box {
                    IconAction(Icons.Outlined.MoreVert, "More for this agreement", { menu = true })
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Edit") }, leadingIcon = { Icon(Icons.Outlined.Edit, null) }, onClick = { menu = false; onEdit() })
                        if (i > 0) DropdownMenuItem(text = { Text("Move up") }, leadingIcon = { Icon(Icons.Outlined.ArrowUpward, null) }, onClick = { menu = false; onMove(-1) })
                        if (i < n - 1) DropdownMenuItem(text = { Text("Move down") }, leadingIcon = { Icon(Icons.Outlined.ArrowDownward, null) }, onClick = { menu = false; onMove(1) })
                        DropdownMenuItem(text = { Text("Who agreed") }, leadingIcon = { Icon(BelfryIcons.Users, null) }, onClick = { menu = false; onWho() })
                        DropdownMenuItem(text = { Text("Remove") }, leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) }, onClick = { menu = false; onRemove() })
                    }
                }
            }
        }
    }
}

@Composable
private fun AgreementSheet(a: Agreement?, onDone: () -> Unit) {
    val session = LocalSession.current
    var title by remember { mutableStateOf(a?.title ?: "") }
    var about by remember { mutableStateOf(a?.about ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    val changed = a != null && (title.trim() != a.title || about.trim() != a.about)
    BelfrySheet(onDismiss = onDone, title = if (a == null) "New agreement" else "Edit agreement") {
        BelfryTextField(title, { title = it.take(300) }, Modifier.fillMaxWidth().testTag("agreement-title"), label = "The agreement", singleLine = false, minLines = 2)
        Spacer(Modifier.height(10.dp))
        BelfryTextField(about, { about = it.take(200) }, Modifier.fillMaxWidth(), label = "A line to explain it (optional)")
        if (changed) Text("Changing the words asks everyone to agree again.", fontSize = 14.sp, color = Belfry.colors.text, modifier = Modifier.padding(top = 8.dp))
        error?.let { Text(it, color = Belfry.colors.danger, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(12.dp))
        BusyButton(if (a == null) "Add the agreement" else "Save", onClick = {
            try {
                if (a == null) session.members.addAgreement(title, about) else session.members.editAgreement(a, title, about)
                onDone()
            } catch (e: ActionException) { error = e.message }
        }, enabled = title.isNotBlank(), modifier = Modifier.fillMaxWidth().testTag("agreement-save"))
        Spacer(Modifier.height(12.dp))
    }
}

/** Who agreed to one agreement, and who not yet (stewards). */
@Composable
fun CharterWhoScreen(id: String) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val nav = LocalNav.current
    val a = world.charter().firstOrNull { it.id == id }
    Column(Modifier.fillMaxSize().background(c.bg)) {
        TopBar("Who agreed", subtitle = a?.title, navigation = { BackButton { nav.back() } })
        if (a == null) { EmptyState("This agreement is gone"); return@Column }
        val people = world.charterPeople()
        val yes = people.filter { it in a.agreed }.sortedBy { world.displayName(it).lowercase() }
        val no = people.filter { it !in a.agreed }.sortedBy { world.displayName(it).lowercase() }
        Column(Modifier.weight(1f).fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
            for ((title, list) in listOf("Agreed · ${yes.size}" to yes, "Not yet · ${no.size}" to no)) {
                SectionTitle(title)
                for (pk in list) {
                    Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { nav.go(Routes.profile(pk)) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(world.face(pk), size = 36.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(world.displayName(pk), fontSize = 16.sp, color = c.text)
                            if (pk in a.stale) Text("Agreed before the words changed", fontSize = 13.sp, color = c.muted)
                        }
                    }
                }
            }
        }
    }
}

