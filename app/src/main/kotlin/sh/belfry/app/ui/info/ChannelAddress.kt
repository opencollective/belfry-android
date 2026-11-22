package sh.belfry.app.ui.info

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import sh.belfry.app.ui.kit.ActionGroup
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.BelfryTextField
import sh.belfry.app.ui.kit.BusyButton
import sh.belfry.app.ui.kit.ButtonKind
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.engine.ActionException
import sh.belfry.core.engine.Session
import sh.belfry.core.model.Group
import sh.belfry.core.net.AliasState
import sh.belfry.core.net.MailAliasInfo

/**
 * The left part of a channel's address being edited (SPEC 19.60): what is typed and what the
 * belfry's live check says about it. [save] runs from the Settings' Save.
 */
@Stable
class AddressEdit internal constructor(val channel: String) {
    var info by mutableStateOf<MailAliasInfo?>(null)
    var text by mutableStateOf("")
    var state by mutableStateOf<AliasState?>(null)
    var checking by mutableStateOf(false)
    var checkFailed by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    val maySet: Boolean get() = info?.maySet == true
    val changed: Boolean get() = maySet && text.trim().lowercase() != (info?.alias ?: "")

    /** Save it when it changed and the check said available; a refusal throws with fixed words. */
    suspend fun save(session: Session) {
        if (!changed) return
        if (state != AliasState.AVAILABLE) throw ActionException(state?.key ?: "invalid", state?.let { "That address can't be used: " + it.words.lowercase().removePrefix("✓ ") } ?: "Wait for the check, then save.")
        info = session.mailAlias.set(channel, text.trim().lowercase())
        text = info?.alias ?: text
        state = null
    }
}

/** Asks the belfry for G's address (once a session) and keeps the edit in step with its answer. */
@Composable
fun rememberAddressEdit(g: Group): AddressEdit {
    val session = LocalSession.current
    val edit = remember(g.id) { AddressEdit(g.id) }
    val infos by session.mailAlias.infos.collectAsState()
    val known = infos[g.id]
    LaunchedEffect(known) {
        if (known != null && (edit.info == null || !edit.changed)) { edit.info = known; edit.text = known.alias }
    }
    LaunchedEffect(g.id) {
        try { session.mailAlias.info(g.id, force = true) } catch (e: CancellationException) { throw e } catch (e: Exception) { }
    }
    // the live check, while typing (debounced)
    LaunchedEffect(edit.text, edit.info) {
        edit.state = null
        edit.checkFailed = false
        if (!edit.changed || edit.text.isBlank()) return@LaunchedEffect
        delay(CHECK_DELAY_MS)
        edit.checking = true
        try {
            edit.state = session.mailAlias.check(g.id, edit.text.trim().lowercase()).state
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            edit.checkFailed = true
        } finally { edit.checking = false }
    }
    return edit
}

private const val CHECK_DELAY_MS = 400L

/**
 * Settings → E-mail (canvas channels/settings): the channel's address. The part before the @ is
 * editable by the belfry's admins (`may_set`), with the live check in words; read-only for the
 * channel's other admins. "Use the automatic address" clears a custom one.
 */
@Composable
fun AddressSection(g: Group, edit: AddressEdit, enabled: Boolean) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val session = LocalSession.current
    val domain = world.ctx.domain
    val address = edit.info?.address ?: world.shownMailAddress(g)
    Column(Modifier.fillMaxWidth().testTag("settings-address")) {
        if (edit.maySet) {
            BelfryTextField(
                edit.text, { edit.text = it.replace(" ", "") }, Modifier.fillMaxWidth().testTag("address-field"),
                enabled = enabled,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, keyboardType = KeyboardType.Email, autoCorrectEnabled = false),
                trailing = { Text("@$domain", fontSize = 15.sp, color = c.muted, modifier = Modifier.padding(end = 12.dp)) },
            )
            val line = when {
                edit.checking -> "Checking…"
                edit.checkFailed -> "Could not check it just now."
                edit.state != null -> edit.state!!.words
                else -> null
            }
            if (line != null) {
                Text(line, fontSize = 14.sp, fontWeight = if (edit.state == AliasState.AVAILABLE) FontWeight.Medium else FontWeight.Normal, color = if (edit.state == AliasState.AVAILABLE || edit.checking) c.text else c.danger, modifier = Modifier.padding(start = 4.dp, top = 6.dp).testTag("address-state"))
            }
        } else {
            ActionGroup {
                Row(Modifier.fillMaxWidth().background(c.surface).padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(BelfryIcons.Inbox, contentDescription = null, tint = c.muted, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(address, fontSize = 16.sp, color = c.text, modifier = Modifier.testTag("address-readonly"))
                }
            }
        }
        Hint(
            when {
                edit.info?.custom == true -> "Set by the belfry's admins." + if (edit.maySet) "" else " Only they can change it."
                edit.maySet -> "Set automatically from the channel's name. You can change the part before the @."
                else -> "Set automatically from the channel's name. The belfry's admins can change it."
            },
            Modifier.padding(top = 6.dp),
        )
        edit.error?.let { Text(it, fontSize = 13.sp, color = c.danger, modifier = Modifier.padding(start = 4.dp, top = 4.dp)) }
        if (edit.maySet && edit.info?.custom == true) {
            Spacer(Modifier.height(8.dp))
            BusyButton(
                "Use the automatic address",
                onClick = {
                    edit.error = null
                    edit.info = session.mailAlias.set(g.id, null)
                    edit.text = edit.info?.alias ?: ""
                },
                kind = ButtonKind.Ghost, small = true, enabled = enabled,
                onError = { edit.error = (it as? ActionException)?.message ?: "Not saved. Try again." },
                modifier = Modifier.testTag("address-automatic"),
            )
        }
    }
}
