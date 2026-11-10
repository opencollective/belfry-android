package sh.belfry.app.ui.people

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.ui.chat.face
import sh.belfry.app.ui.kit.Avatar
import sh.belfry.app.ui.kit.BelfryTextField
import sh.belfry.app.ui.kit.BusyButton
import sh.belfry.app.ui.kit.SectionTitle
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.nav.Routes
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.engine.ActionException
import sh.belfry.core.model.fold

/** Start a conversation: a member (by name or address), a key, or anyone's e-mail address. */
@Composable
fun NewConversationScreen() {
    val c = Belfry.colors
    val world = LocalWorld.current
    val session = LocalSession.current
    val nav = LocalNav.current
    var to by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val skip = setOfNotNull(world.ctx.groupsKey, world.ctx.bridgeKey, world.me)
    val people = world.profiles.entries.filter { it.key !in skip && (!it.value.name.isNullOrEmpty() || !it.value.nip05.isNullOrEmpty()) }
        .sortedBy { fold(it.value.name ?: it.value.nip05 ?: "") }
    val q = fold(to.trim())
    val matches = (if (q.isEmpty()) people else people.filter { fold(it.value.name ?: "").contains(q) || (it.value.nip05 ?: "").lowercase().contains(q) }).take(8)
    // The new screen takes this one's place: back from the conversation goes to the list.
    val open: (String) -> Unit = { conv -> nav.back(); nav.go(Routes.conversation(conv)) }
    val domain = session.community.domain.lowercase()
    val mail = q.contains('@') && !q.endsWith("@$domain")

    Page("New message") {
        BelfryTextField(
            to, { to = it; error = null }, label = "To", placeholder = "A member, or an e-mail address", error = error,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Email),
            modifier = Modifier.testTag("new-to"),
        )
        Spacer(Modifier.height(12.dp))
        BusyButton(
            if (mail) "Write an e-mail" else "Write",
            onClick = {
                val conv = try { session.convFor(to.trim()) } catch (e: ActionException) { null }
                if (conv != null) open(conv) else error = "Type a member’s name, or an e-mail address."
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = q.isNotEmpty(),
        )
        if (matches.isNotEmpty()) {
            SectionTitle("Members")
            matches.forEach { (pk, p) ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button) { open("n:$pk") }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(world.face(pk), size = 36.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name?.takeIf { it.isNotEmpty() } ?: p.nip05 ?: "", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val nip05 = p.nip05
                        if (!nip05.isNullOrEmpty()) Text(nip05, fontSize = 13.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
