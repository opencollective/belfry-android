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
import kotlinx.coroutines.CancellationException
import sh.belfry.app.ui.kit.*
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.nav.Routes
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.engine.ActionException
import sh.belfry.core.net.Follows

private val OPTIONS = listOf(
    Triple("asap", "As soon as possible", "A few minutes after each announcement"),
    Triple("monthly", "Monthly", "On the 1st, when there was something new"),
    Triple("stop", "Not following", "No e-mails"),
)

private fun frequencyLabel(f: String?): String = OPTIONS.firstOrNull { it.first == f }?.second ?: "Following"

/**
 * The bell: follow a channel's announcements by e-mail, as soon as possible or monthly. The
 * belfry keeps the follows and mails the member's own address. [preloaded] is for tests.
 */
@Composable
fun FollowScreen(id: String, preloaded: Follows? = null) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val session = LocalSession.current
    val nav = LocalNav.current
    val g = world.group(id)
    val label = channelLabel(g, id)
    var follows by remember { mutableStateOf(preloaded) }
    var failed by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf<String?>(null) }
    val busy = rememberBusy()

    suspend fun load() {
        failed = null
        try {
            follows = session.follows()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ActionException) {
            failed = e.message ?: "Your follows could not be loaded."
        } catch (e: Exception) {
            failed = "Your follows could not be loaded."
        }
    }
    LaunchedEffect(id) { if (follows == null) load() }

    val f = follows
    val current = f?.byChannel?.get(id) ?: "stop"

    InfoPage("Announcements", subtitle = label) {
        Text(
            buildAnnotatedString {
                append("Get the announcements of ")
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(label) }
                append(" by e-mail")
                if (!f?.email.isNullOrEmpty()) {
                    append(" at ")
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(f?.email ?: "") }
                }
                append(". Only its admins post announcements.")
            },
            fontSize = 15.sp, lineHeight = 21.sp,
        )
        Spacer(Modifier.height(14.dp))
        if (f == null && failed == null) {
            Row { Spinner(); Spacer(Modifier.width(10.dp)); Hint("Loading…") }
        }
        failed?.let {
            ErrorLine(it)
            Spacer(Modifier.height(10.dp))
            BusyButton("Retry", { load() }, small = true, kind = ButtonKind.Ghost, icon = BelfryIcons.Retry)
        }
        if (f != null) {
            ActionGroup {
                OPTIONS.forEach { (choice, title, hint) ->
                    ActionRow(
                        title,
                        onClick = {
                            if (choice != current) {
                                picking = choice
                                error = null
                                busy.run(onError = { e -> error = (e as? ActionException)?.message ?: "Not saved."; picking = null }) {
                                    val now = session.setFollow(id, choice)
                                    val map = HashMap(f.byChannel)
                                    if (now == null) map.remove(id) else map[id] = now
                                    follows = Follows(f.email, map)
                                    picking = null
                                }
                            }
                        },
                        icon = when (choice) { "asap" -> BelfryIcons.BellFilled; "monthly" -> BelfryIcons.Calendar; else -> BelfryIcons.Bell },
                        sub = hint, enabled = !busy.busy, modifier = Modifier.testTag("follow-$choice"),
                        trailing = {
                            if (picking == choice) Spinner(size = 18.dp)
                            else if (choice == current) Icon(BelfryIcons.Check, "Selected", tint = c.text, modifier = Modifier.size(20.dp))
                        },
                    )
                }
            }
            error?.let { ErrorLine(it, Modifier.padding(top = 10.dp)) }
            val others = f.byChannel.filterKeys { it != id }.toSortedMap()
            if (others.isNotEmpty()) {
                SectionTitle("You also follow")
                ActionGroup {
                    others.forEach { (channel, freq) ->
                        ActionRow(
                            channelLabel(world.group(channel), channel), { nav.go(Routes.follow(channel)) },
                            icon = BelfryIcons.BellFilled, value = frequencyLabel(freq), chevron = true,
                        )
                    }
                }
            }
        }
        Hint("${session.community.name} sends these mails; every one has links to change or stop.", Modifier.padding(top = 14.dp))
        Spacer(Modifier.height(24.dp))
    }
}
