package sh.belfry.app.ui.info

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.ui.kit.*
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.nav.Routes
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.engine.ActionException

private val DAYS = listOf(1 to "1 day", 7 to "1 week", 30 to "1 month")

/**
 * A new drop box link: anyone with the link or its QR code uploads photos and files to the
 * channel without an account (or members only). Once made, the page shows the link to hand out.
 * [made] is for tests (the page as it is after "Create link").
 */
@Composable
fun DropScreen(id: String, made: String? = null) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val session = LocalSession.current
    val nav = LocalNav.current
    val g = world.group(id)
    var days by remember { mutableIntStateOf(7) }
    var login by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf(made) }
    var error by remember { mutableStateOf<String?>(null) }
    val busy = rememberBusy()

    InfoPage(if (url == null) "New drop link" else "Drop link", subtitle = channelLabel(g, id)) {
        val link = url
        if (link == null) {
            Text(
                buildAnnotatedString {
                    append("People with the link upload photos and files to ")
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(g?.name ?: id) }
                    append(". They can add their name afterwards, or sign in to post as themselves.")
                },
                fontSize = 15.sp, lineHeight = 21.sp,
            )
            Spacer(Modifier.height(16.dp))
            Text("Works for", fontSize = 13.sp, color = c.muted)
            Spacer(Modifier.height(6.dp))
            Segmented(DAYS.map { it.second }, DAYS.indexOfFirst { it.first == days }, { days = DAYS[it].first }, enabled = !busy.busy)
            Spacer(Modifier.height(14.dp))
            Text("Who can upload", fontSize = 13.sp, color = c.muted)
            Spacer(Modifier.height(6.dp))
            ActionGroup {
                ActionRow(
                    "Anyone", { login = false }, icon = BelfryIcons.Globe, sub = "No account needed", enabled = !busy.busy,
                    trailing = { if (!login) Icon(BelfryIcons.Check, "Selected", tint = c.text, modifier = Modifier.size(20.dp)) },
                )
                ActionRow(
                    "Members", { login = true }, icon = BelfryIcons.Lock, sub = "Must sign in to upload", enabled = !busy.busy,
                    modifier = Modifier.testTag("drop-members"),
                    trailing = { if (login) Icon(BelfryIcons.Check, "Selected", tint = c.text, modifier = Modifier.size(20.dp)) },
                )
            }
            Spacer(Modifier.height(18.dp))
            BusyButton(
                "Create link", { error = null; url = session.createDropLink(id, days, login) },
                modifier = Modifier.fillMaxWidth().testTag("drop-create"), busy = busy,
                onError = { e -> error = (e as? ActionException)?.message ?: "The link could not be made." },
            )
            error?.let { ErrorLine(it, Modifier.padding(top = 10.dp)) }
        } else {
            LinkBox(link, "Drop box")
            Hint(
                "Works for " + (DAYS.firstOrNull { it.first == days }?.second ?: "$days days") + " · " +
                    (if (login) "signed-in members only" else "anyone with the link") + ". End it any time from the Files tab.",
                Modifier.padding(top = 12.dp),
            )
            Spacer(Modifier.height(14.dp))
            ActionGroup {
                ActionRow("See the channel's files", { nav.go(Routes.files(id)) }, icon = BelfryIcons.Image, chevron = true)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
