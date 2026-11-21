package sh.belfry.app.ui.people

import android.content.Context
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import sh.belfry.app.ui.kit.ActionGroup
import sh.belfry.app.ui.kit.SectionTitle
import sh.belfry.app.ui.kit.SwitchRow
import sh.belfry.app.ui.nav.LocalSession

/** "Show others when I'm typing" (docs/typing.md §4): this device's, not the account's; on by default. */
object TypingSetting {
    private const val PREFS = "typing"
    private const val K_SEND = "send"
    const val LABEL = "Show others when I'm typing"
    const val FOOT = "When this is off, this phone doesn't tell anyone you're typing. You still see when others are."

    fun on(c: Context): Boolean = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(K_SEND, true)
    fun set(c: Context, on: Boolean) { c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(K_SEND, on).apply() }
}

/** canvas v110 messages/typing-setting: Me → Privacy. */
@Composable
fun PrivacyScreen() {
    val context = LocalContext.current
    val session = LocalSession.current
    var on by remember { mutableStateOf(TypingSetting.on(context)) }
    Page("Privacy") {
        SectionTitle("This phone")
        ActionGroup {
            SwitchRow(
                TypingSetting.LABEL, on,
                { v -> on = v; TypingSetting.set(context, v); session.sendTyping = v },
                modifier = Modifier.testTag("privacy-typing"),
            )
        }
        Hint(TypingSetting.FOOT, Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp))
    }
}
