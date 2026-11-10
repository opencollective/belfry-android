package sh.belfry.app.ui.people

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import sh.belfry.app.ui.kit.ActionGroup
import sh.belfry.app.ui.kit.ActionRow
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.BelfryTextField
import sh.belfry.app.ui.kit.Chip
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.core.model.People

/** Your tags, with suggestions from everyone's. A tap on a chosen tag removes it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TagsField(tags: SnapshotStateList<String>) {
    val world = LocalWorld.current
    val known = remember { world.allTags }
    var q by remember { mutableStateOf("") }
    val clean = People.cleanTag(q)
    val suggestions = known.filter { it.tag !in tags && (clean.isEmpty() || it.tag.contains(clean)) }.take(6)
    val options = suggestions.map { it.tag } + (if (clean.isNotEmpty() && suggestions.none { it.tag == clean } && clean !in tags) listOf(clean) else emptyList())
    val add: (String) -> Unit = { t ->
        val v = People.cleanTag(t)
        if (v.isNotEmpty() && v !in tags && tags.size < 30) tags.add(v)
        q = ""
    }
    Column(Modifier.fillMaxWidth()) {
        BelfryTextField(
            q, { v -> if (v.endsWith(",") || v.endsWith(" ")) { if (clean.isNotEmpty()) add(clean) else q = "" } else q = v },
            label = "Tags · what you do, care about, can help with",
            placeholder = if (tags.isEmpty()) "e.g. facilitation" else "Add a tag",
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { val first = options.firstOrNull(); if (first != null) add(first) }),
        )
        if (tags.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                tags.toList().forEach { t -> Chip("#$t", selected = true, icon = BelfryIcons.Close, onClick = { tags.remove(t) }) }
            }
        }
        if ((q.isNotEmpty() || tags.isEmpty()) && options.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            ActionGroup {
                options.forEach { t ->
                    val n = known.firstOrNull { it.tag == t }?.count
                    ActionRow("#$t", onClick = { add(t) }, value = if (n != null) "$n ${if (n == 1) "member" else "members"}" else "new tag")
                }
            }
        }
    }
}
