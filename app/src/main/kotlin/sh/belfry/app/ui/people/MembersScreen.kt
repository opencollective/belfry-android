package sh.belfry.app.ui.people

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.ui.chat.face
import sh.belfry.app.ui.kit.Avatar
import sh.belfry.app.ui.kit.BackButton
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.BelfryTextField
import sh.belfry.app.ui.kit.Chip
import sh.belfry.app.ui.kit.EmptyState
import sh.belfry.app.ui.kit.TopBar
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.nav.Routes
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.model.People
import sh.belfry.core.model.World
import sh.belfry.core.model.fold

private class Person(val pubkey: String, val name: String, val country: String?, val city: String?, val tags: List<String>, val chip: String?, val admin: Boolean)

private fun people(world: World): List<Person> {
    val skip = setOfNotNull(world.ctx.groupsKey, world.ctx.bridgeKey)
    return world.profiles.entries
        .filter { it.key !in skip && !it.value.name.isNullOrEmpty() }
        .map { (pk, p) ->
            val x = world.extras[pk]
            Person(pk, p.name ?: "", x?.place?.country, x?.place?.name, x?.tags ?: emptyList(), world.guestJoinsOf(pk).firstOrNull()?.chip, world.isBelfryAdmin(pk))
        }
        .sortedBy { fold(it.name) }
}

/**
 * Everyone in the community, by where they say they are: countries, then cities, then the people
 * who share no place. Search by name, place or tag; filter by a tag. (The web shows a map of a
 * channel's members; this list is the first version here.)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MembersScreen() {
    val c = Belfry.colors
    val world = LocalWorld.current
    val nav = LocalNav.current
    val session = LocalSession.current
    // who is a guest, an agent or a member: asked of the belfry again when the list opens
    LaunchedEffect(Unit) { session.recheckPeople(world.guestJoinKeys) }
    var q by rememberSaveable { mutableStateOf("") }
    var tag by rememberSaveable { mutableStateOf<String?>(null) }
    val all = people(world)
    val words = fold(q).split(' ').filter { it.isNotEmpty() }
    val shown = all.filter { p ->
        (tag == null || tag in p.tags) && words.all { w ->
            fold(p.name).contains(w) || p.tags.any { it.contains(w) } || fold(p.city ?: "").contains(w) || fold(p.country?.let { People.countryName(it) } ?: "").contains(w)
        }
    }
    val placed = shown.filter { it.country != null }.groupBy { People.countryName(it.country!!) }.toSortedMap()
    val unplaced = shown.filter { it.country == null }
    val tags = world.allTags.take(8)

    Column(Modifier.fillMaxSize().background(c.bg).imePadding()) {
        TopBar("Members", subtitle = "${all.size} ${if (all.size == 1) "person" else "people"}", navigation = { BackButton { nav.back() } })
        LazyColumn(Modifier.weight(1f).fillMaxWidth().navigationBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "search") {
                Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
                    BelfryTextField(q, { q = it }, placeholder = "Search by name, place or tag", trailing = {
                        androidx.compose.material3.Icon(BelfryIcons.Search, contentDescription = null, tint = c.faint, modifier = Modifier.width(18.dp))
                    })
                    if (tags.isNotEmpty()) {
                        FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            tags.forEach { t -> Chip("#${t.tag}", selected = tag == t.tag, onClick = { tag = if (tag == t.tag) null else t.tag }) }
                        }
                    }
                }
            }
            if (shown.isEmpty()) {
                item(key = "none") { EmptyState("Nobody matches", text = "Try another name, place or tag.") }
            }
            placed.forEach { (country, list) ->
                item(key = "c:$country") { GroupTitle(country, list.size) }
                val sorted = list.sortedWith(compareBy<Person>({ fold(it.city ?: "￿") }, { fold(it.name) }))
                sorted.forEach { p -> item(key = p.pubkey) { PersonRow(world, p) { nav.go(Routes.profile(p.pubkey)) } } }
            }
            if (unplaced.isNotEmpty()) {
                item(key = "c:none") { GroupTitle(if (placed.isEmpty()) "Everyone" else "No place shared", unplaced.size) }
                unplaced.forEach { p -> item(key = p.pubkey) { PersonRow(world, p) { nav.go(Routes.profile(p.pubkey)) } } }
            }
        }
    }
}

@Composable
private fun GroupTitle(text: String, n: Int) {
    val c = Belfry.colors
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = c.muted, modifier = Modifier.weight(1f))
        Text("$n", fontSize = 13.sp, color = c.faint)
    }
}

@Composable
private fun PersonRow(world: World, p: Person, onClick: () -> Unit) {
    val c = Belfry.colors
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(world.face(p.pubkey), size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    p.name + if (p.pubkey == world.me) " (you)" else "", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = c.text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                if (p.admin) { Spacer(Modifier.width(6.dp)); Chip("admin") }
                if (p.chip != null) { Spacer(Modifier.width(6.dp)); Chip(p.chip) }
            }
            val sub = listOfNotNull(p.city?.takeIf { it.isNotEmpty() }, p.tags.take(4).joinToString(" ") { "#$it" }.takeIf { it.isNotEmpty() }).joinToString(" · ")
            if (sub.isNotEmpty()) Text(sub, fontSize = 13.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
