package sh.belfry.app.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import sh.belfry.app.ui.theme.Belfry

/**
 * Every kit component on a page, for debug builds and the screenshot tests. Not a screen of the
 * product. Sections are separate composables so each fits one screenshot.
 */
object KitGallery {
    private val people = listOf(
        Face("a1b2c3d4", "Ada"), Face("0f0f0f11", "Bashir"), Face("77aa1203", "Chloé"), Face("5e5e5e01", "Dmitri"),
    )

    val sections: List<Pair<String, @Composable () -> Unit>> = listOf(
        "buttons" to { Buttons() },
        "lists" to { Lists() },
        "bits" to { BitsSection() },
        "icons" to { IconsSection() },
    )

    @Composable
    fun Page() {
        val c = Belfry.colors
        var busyInfo by remember { mutableStateOf("") }
        Column(Modifier.fillMaxSize().background(c.bg)) {
            TopBar("Kit gallery", subtitle = "Debug build · every shared component$busyInfo", navigation = { IconAction(BelfryIcons.Menu, "Menu", {}) }) {
                IconAction(BelfryIcons.Search, "Search", {})
                IconAction(BelfryIcons.Bell, "Follow", {}, badge = 3)
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding()) {
                sections.forEach { (_, content) -> content() }
            }
        }
    }

    @Composable
    fun Buttons() {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Buttons lock while their work runs", fontSize = 13.sp, color = Belfry.colors.muted)
            BusyButton("Continue", onClick = { delay(1500) }, modifier = Modifier.fillMaxWidth())
            BusyButton("Invite people", onClick = { delay(1500) }, modifier = Modifier.fillMaxWidth(), kind = ButtonKind.Ghost, icon = BelfryIcons.Users)
            BusyButton("Delete", onClick = { delay(1500) }, modifier = Modifier.fillMaxWidth(), kind = ButtonKind.Danger)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                BusyButton("Small", onClick = { delay(1500) }, small = true)
                BusyButton("Disabled", onClick = {}, small = true, enabled = false)
                BusyIconButton(BelfryIcons.Send, "Send", onClick = { delay(1500) })
                Spinner()
            }
            var name by remember { mutableStateOf("Ada Lovelace") }
            var mail by remember { mutableStateOf("") }
            BelfryTextField(name, { name = it }, label = "Your name")
            BelfryTextField(mail, { mail = it }, label = "E-mail", placeholder = "you@example.org", hint = "We send a 6-digit code.")
            BelfryTextField("not-an-address", {}, label = "E-mail", error = "That doesn't look like an e-mail address.")
            var about by remember { mutableStateOf("Mathematician.\nLikes **engines** 🙂 👍 ❤️ 🎉") }
            BelfryTextField(about, { about = it }, label = "About (Markdown)", singleLine = false, minLines = 3)
        }
    }

    @Composable
    fun Lists() {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            var inbox by remember { mutableStateOf(true) }
            SectionTitle("Message")
            ActionGroup {
                ActionRow("Reply in a thread", onClick = {}, icon = BelfryIcons.Reply)
                ActionRow("Open thread", onClick = {}, icon = BelfryIcons.Thread, value = "3 replies", chevron = true)
                ActionRow("Edit", onClick = {}, icon = BelfryIcons.Edit)
                BusyRow("Copy link", onClick = { delay(1500) }, icon = BelfryIcons.Link, sub = "Anyone in the channel can open it")
                ActionRow("Delete", onClick = {}, icon = BelfryIcons.Trash, danger = true)
            }
            SectionTitle("Settings")
            ActionGroup {
                SwitchRow("E-mails from anyone", inbox, { inbox = it }, icon = BelfryIcons.Inbox, sub = "A shared inbox: people outside can write to this channel")
                SwitchRow("Saving…", false, {}, icon = BelfryIcons.Bell, busy = true)
                ActionRow("Leave channel", onClick = {}, icon = BelfryIcons.Logout, enabled = false)
            }
            SectionTitle("Channels")
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(
                    ChannelGlyph.Channel to "general", ChannelGlyph.Event to "Harvest dinner",
                    ChannelGlyph.Pod to "Garden pod", ChannelGlyph.Proposal to "Shared tool library",
                ).forEach { (g, n) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ChannelIcon(g)
                        Spacer(Modifier.width(10.dp))
                        Text(n, fontSize = 16.sp, color = Belfry.colors.text)
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    fun BitsSection() {
        Column(Modifier.fillMaxWidth()) {
            var tab by remember { mutableIntStateOf(0) }
            var seg by remember { mutableIntStateOf(1) }
            Banner("Waiting for connection…", icon = BelfryIcons.Clock)
            Banner("Not sent: restricted", kind = BannerKind.Error, actionLabel = "Retry", onAction = {})
            Tabs(listOf("About", "Members", "Files", "Settings"), tab, { tab = it })
            DaySeparator("Monday 9 November")
            Column(Modifier.padding(horizontal = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    people.forEach { Avatar(it) }
                    Avatar(people[0], size = 56.dp)
                    AvatarStack(people)
                }
                Spacer(Modifier.height(12.dp))
                Segmented(listOf("1 day", "1 week", "1 month"), seg, { seg = it })
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("#garden", selected = true)
                    Chip("#tools")
                    Chip("pinned", icon = BelfryIcons.Pin)
                    Chip("👍 3")
                    Chip("🎉 1", selected = true)
                }
                Spacer(Modifier.height(12.dp))
                QrCode("https://chat.belfry.sh/join/demo-token", size = 140.dp)
            }
            EmptyState(
                "Garden is new", text = "No messages yet. Bring the people who belong here, then say hi.", icon = BelfryIcons.Hash,
                action = { BusyButton("Invite people", onClick = { delay(1500) }, small = true) },
            )
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    fun IconsSection() {
        val c = Belfry.colors
        FlowRow(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BelfryIcons.all.forEach { (name, icon) ->
                Column(Modifier.width(56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(icon, contentDescription = name, tint = c.text, modifier = Modifier.size(24.dp))
                    Text(name, fontSize = 10.sp, lineHeight = 12.sp, color = c.muted, maxLines = 1)
                }
            }
        }
    }
}
