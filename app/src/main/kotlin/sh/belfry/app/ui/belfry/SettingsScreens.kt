package sh.belfry.app.ui.belfry

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.HowToReg
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.ViewList
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.ui.chat.glyph
import sh.belfry.app.ui.kit.ActionGroup
import sh.belfry.app.ui.kit.ActionRow
import sh.belfry.app.ui.kit.BackButton
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.BelfrySheet
import sh.belfry.app.ui.kit.BelfryTextField
import sh.belfry.app.ui.kit.BusyButton
import sh.belfry.app.ui.kit.ButtonKind
import sh.belfry.app.ui.kit.ConfirmSheet
import sh.belfry.app.ui.kit.IconAction
import sh.belfry.app.ui.kit.SectionTitle
import sh.belfry.app.ui.kit.Segmented
import sh.belfry.app.ui.kit.TopBar
import sh.belfry.app.ui.kit.icon
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.nav.Routes
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.engine.ActionException
import sh.belfry.core.model.Categories
import sh.belfry.core.model.ChannelCategory
import sh.belfry.core.model.channelCategories
import sh.belfry.core.model.hasContent
import sh.belfry.core.model.mayArchive
import sh.belfry.core.net.Role
import kotlinx.coroutines.launch

@Composable
private fun SettingsPage(title: String, content: @Composable ColumnScope.() -> Unit) {
    val nav = LocalNav.current
    Column(Modifier.fillMaxSize().background(Belfry.colors.bg)) {
        TopBar(title, navigation = { BackButton { nav.back() } })
        Column(Modifier.weight(1f).fillMaxWidth().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp), content = content)
    }
}

/** Belfry settings (stewards; the drawer's gear): Branding, Roles, Membership, Channels. Each row pushes its screen. */
@Composable
fun BelfrySettingsScreen() {
    val nav = LocalNav.current
    val session = LocalSession.current
    SettingsPage("Belfry settings") {
        Text(session.community.name, fontSize = 15.sp, color = Belfry.colors.muted)
        Spacer(Modifier.height(12.dp))
        ActionGroup(Modifier.testTag("belfry-settings")) {
            ActionRow("Branding", onClick = { nav.go(Routes.BRANDING) }, icon = Icons.Outlined.Palette, sub = "Logo, cover, colours, description", chevron = true)
            ActionRow("Roles", onClick = { nav.go(Routes.SETTINGS_ROLES) }, icon = Icons.Outlined.Badge, sub = "Steward · Member · your own", chevron = true)
            ActionRow("Membership", onClick = { nav.go(Routes.SETTINGS_MEMBERSHIP) }, icon = Icons.Outlined.HowToReg, sub = "Who invites members, who accepts them", chevron = true)
            ActionRow("Channels", onClick = { nav.go(Routes.SETTINGS_CHANNELS) }, icon = Icons.Outlined.ViewList, sub = "Categories, order, archive or delete", chevron = true, modifier = Modifier.testTag("settings-channels"))
        }
        // home/belfry-settings: the console's own pages, in the browser (stewards sign in there)
        val context = androidx.compose.ui.platform.LocalContext.current
        val console = session.community.apiBase.substringBefore("/api/") + "/console"
        Text("In the console", fontSize = 13.sp, color = Belfry.colors.muted, modifier = Modifier.padding(top = 20.dp, bottom = 6.dp))
        ActionGroup(Modifier.testTag("belfry-console")) {
            for ((label, path, icon) in listOf(
                Triple("Mail", "/plugins/email", BelfryIcons.Mail),
                Triple("Website", "/website", BelfryIcons.Globe),
                Triple("Plugins and sales", "/plugins", Icons.Outlined.Extension),
                Triple("Add people", "/members/add", Icons.Outlined.PersonAdd),
            )) {
                ActionRow(label, onClick = { sh.belfry.app.ui.signin.openInBrowser(context, console + path) }, icon = icon,
                    trailing = { androidx.compose.material3.Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = "Opens in the browser", tint = Belfry.colors.faint, modifier = Modifier.size(18.dp)) })
            }
        }
        Text("These open ${console.removePrefix("https://").substringBefore('/')} in the browser.",
            fontSize = 13.sp, color = Belfry.colors.muted, modifier = Modifier.padding(top = 6.dp))
    }
}

/** Roles (docs/roles.md): Member and Steward built in; stewards add their own (a label and a line), pick the default. */
@Composable
fun RolesSettingsScreen() {
    val session = LocalSession.current
    val roles by session.members.roles.collectAsState()
    var error by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Role?>(null) }
    var adding by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { try { session.members.refreshRoles() } catch (e: ActionException) { error = e.message } }
    SettingsPage("Roles") {
        ActionGroup {
            for (r in roles.roles) {
                ActionRow(
                    r.label, onClick = if (r.builtin && r.id == Role.ADMIN) null else ({ editing = r }), icon = Icons.Outlined.Badge,
                    sub = listOfNotNull(r.description.ifEmpty { null }, if (r.isDefault) "Default for new members" else null).joinToString(" · ").ifEmpty { null },
                    chevron = !(r.builtin && r.id == Role.ADMIN),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        BusyButton("New role", onClick = { adding = true }, kind = ButtonKind.Ghost, icon = BelfryIcons.Plus, modifier = Modifier.fillMaxWidth().testTag("role-new"))
        error?.let { Text(it, color = Belfry.colors.danger, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp)) }
        Text("Only Steward has powers: the others are names people carry.", fontSize = 13.sp, color = Belfry.colors.muted, modifier = Modifier.padding(top = 12.dp))
    }
    if (adding || editing != null) RoleSheet(editing, onDone = { adding = false; editing = null })
}

@Composable
private fun RoleSheet(role: Role?, onDone: () -> Unit) {
    val session = LocalSession.current
    var label by remember { mutableStateOf(role?.label ?: "") }
    var about by remember { mutableStateOf(role?.description ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    BelfrySheet(onDismiss = onDone, title = if (role == null) "New role" else role.label) {
        BelfryTextField(label, { label = it.take(40) }, Modifier.fillMaxWidth().testTag("role-label"), label = "Name", enabled = role?.builtin != true)
        Spacer(Modifier.height(10.dp))
        BelfryTextField(about, { about = it.take(200) }, Modifier.fillMaxWidth(), label = "What it means", singleLine = false, minLines = 2)
        error?.let { Text(it, color = Belfry.colors.danger, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(12.dp))
        BusyButton("Save", onClick = {
            try { session.members.saveRole(role?.id, label.trim(), about.trim(), null); onDone() } catch (e: ActionException) { error = e.message }
        }, enabled = label.isNotBlank(), modifier = Modifier.fillMaxWidth().testTag("role-save"))
        if (role != null && !role.isDefault && role.id != Role.ADMIN) {
            Spacer(Modifier.height(8.dp))
            BusyButton("Make it the default", onClick = {
                try { session.members.saveRole(role.id, role.label, role.description, true); onDone() } catch (e: ActionException) { error = e.message }
            }, kind = ButtonKind.Ghost, modifier = Modifier.fillMaxWidth())
        }
        if (role != null && !role.builtin) {
            Spacer(Modifier.height(8.dp))
            BusyButton("Delete this role", onClick = {
                try { session.members.deleteRole(role.id); onDone() } catch (e: ActionException) { error = e.message }
            }, kind = ButtonKind.Danger, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** Membership (docs/membership.md): who invites members (stewards / members), who accepts requests (stewards / vouches, 1–5). */
@Composable
fun MembershipSettingsScreen() {
    val session = LocalSession.current
    val m by session.members.membership.collectAsState()
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { try { session.members.refreshMembership() } catch (e: ActionException) { error = e.message } }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun save(invite: String? = null, accept: String? = null, vouches: Int? = null) {
        if (busy) return
        busy = true
        scope.launch {
            try { session.members.setMembership(invite, accept, vouches); error = null } catch (e: ActionException) { error = e.message } finally { busy = false }
        }
    }
    SettingsPage("Membership") {
        SectionTitle("Who invites new members")
        Segmented(listOf("Stewards", "Every member"), if (m.invite == "members") 1 else 0, { save(invite = if (it == 1) "members" else "admins") }, enabled = !busy)
        SectionTitle("Who accepts requests to join")
        Segmented(listOf("Stewards", "Vouches"), if (m.accept == "vouches") 1 else 0, { save(accept = if (it == 1) "vouches" else "admins") }, enabled = !busy)
        if (m.accept == "vouches") {
            SectionTitle("Vouches needed")
            Segmented((1..5).map { it.toString() }, m.vouches - 1, { save(vouches = it + 1) }, enabled = !busy)
            Text("Members vouch from Members; once enough did, the person is in.", fontSize = 13.sp, color = Belfry.colors.muted, modifier = Modifier.padding(top = 8.dp))
        }
        error?.let { Text(it, color = Belfry.colors.danger, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp)) }
    }
}

/**
 * Channels (SPEC 19.34; iOS ChannelsSettings): the categories with their channels; a category's
 * menu renames or deletes it (its channels go to Community life; built-ins stay); a channel's menu
 * moves it to another category and ends with "Archive the channel" (with content) or "Delete the
 * channel" (none), each behind a confirmation. Every change publishes the order event at once.
 */
@Composable
fun ChannelsSettingsScreen() {
    val world = LocalWorld.current
    val session = LocalSession.current
    val cats = world.channelCategories()
    val byId = world.groups.associateBy { it.id }
    var error by remember { mutableStateOf<String?>(null) }
    var naming by remember { mutableStateOf<ChannelCategory?>(null) }
    var newCat by remember { mutableStateOf(false) }
    var archiving by remember { mutableStateOf<sh.belfry.core.model.Group?>(null) }
    var deleting by remember { mutableStateOf<sh.belfry.core.model.Group?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun save(next: List<ChannelCategory>) {
        scope.launch { try { session.members.setChannelLayout(next); error = null } catch (e: ActionException) { error = e.message } }
    }
    SettingsPage("Channels") {
        error?.let { Text(it, color = Belfry.colors.danger, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp)) }
        for (cat in cats) {
            var catMenu by remember(cat.id) { mutableStateOf(false) }
            SectionTitle(cat.name, action = {
                Box {
                    IconAction(Icons.Outlined.MoreVert, "${cat.name}: more", { catMenu = true }, modifier = Modifier.testTag("cat-menu-${cat.id}"))
                    DropdownMenu(expanded = catMenu, onDismissRequest = { catMenu = false }) {
                        DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Outlined.Edit, null) }, onClick = { catMenu = false; naming = cat })
                        if (!cat.isBuiltIn) {
                            DropdownMenuItem(text = { Text("Delete the category") }, leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) }, onClick = { catMenu = false; save(Categories.remove(cat.id, cats)) })
                        }
                    }
                }
            })
            ActionGroup {
                if (cat.channels.isEmpty()) ActionRow("No channels yet", onClick = null, enabled = false)
                for (id in cat.channels) {
                    val g = byId[id] ?: continue
                    var menu by remember(id) { mutableStateOf(false) }
                    Box {
                        ActionRow(g.name, onClick = { menu = true }, icon = g.glyph(world).icon(), trailing = { Icon(Icons.Outlined.MoreVert, null, tint = Belfry.colors.muted) }, modifier = Modifier.testTag("ch-$id"))
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            for (other in cats.filter { it.id != cat.id }) {
                                DropdownMenuItem(text = { Text("Move to ${other.name}") }, leadingIcon = { Icon(Icons.Outlined.DriveFileMove, null) }, onClick = { menu = false; save(Categories.put(id, other.id, cats)) })
                            }
                            HorizontalDivider()
                            if (world.hasContent(id)) {
                                if (world.mayArchive(g)) DropdownMenuItem(text = { Text("Archive the channel") }, leadingIcon = { Icon(Icons.Outlined.Archive, null) }, onClick = { menu = false; archiving = g })
                            } else {
                                DropdownMenuItem(text = { Text("Delete the channel") }, leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) }, onClick = { menu = false; deleting = g }, modifier = Modifier.testTag("ch-delete-$id"))
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        BusyButton("New category", onClick = { newCat = true }, kind = ButtonKind.Ghost, icon = Icons.Outlined.Category, modifier = Modifier.fillMaxWidth().testTag("cat-new"))
        Text("The Townsquare, Members and the Charter are always at the top.", fontSize = 13.sp, color = Belfry.colors.muted, modifier = Modifier.padding(top = 12.dp))
    }
    if (newCat || naming != null) {
        val cat = naming
        var name by remember(cat?.id) { mutableStateOf(cat?.name ?: "") }
        BelfrySheet(onDismiss = { newCat = false; naming = null }, title = if (cat == null) "New category" else "Rename") {
            BelfryTextField(name, { name = it.take(60) }, Modifier.fillMaxWidth().testTag("cat-name"), label = "Name")
            Spacer(Modifier.height(12.dp))
            BusyButton("Save", onClick = {
                val next = if (cat == null) cats + ChannelCategory(Categories.newId(name), name.trim(), emptyList()) else Categories.rename(cat.id, name, cats)
                session.members.setChannelLayout(next)
                newCat = false; naming = null
            }, enabled = name.isNotBlank(), modifier = Modifier.fillMaxWidth().testTag("cat-save"))
            Spacer(Modifier.height(12.dp))
        }
    }
    archiving?.let { g -> sh.belfry.app.ui.chat.ArchiveConfirm(g, onDone = { archiving = null }, onDismiss = { archiving = null }) }
    deleting?.let { g ->
        ConfirmSheet(
            title = "Delete ${g.name}?", confirmLabel = "Delete the channel",
            onConfirm = { try { session.members.deleteGroup(g.id); error = null } catch (e: ActionException) { error = e.message }; deleting = null },
            onDismiss = { deleting = null }, message = "It has no messages. It leaves the belfry for everyone.",
        )
    }
}
