package sh.belfry.app.ui.people

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.belfry.app.ui.chat.Composer
import sh.belfry.app.ui.chat.rememberComposerState
import sh.belfry.app.ui.kit.BusyButton
import sh.belfry.app.ui.kit.ButtonKind
import sh.belfry.app.ui.kit.Hairline
import sh.belfry.app.ui.nav.LocalAccounts
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.model.ComposerDraft
import sh.belfry.core.model.GuestTopic

/**
 * A topic reached through a printed card, as a reading guest (SPEC 19.77; canvas cards/follow-channel,
 * contribute-compose, contribute-save; the web's GuestTopicComposer): the composer to share at once
 * (text and photos, nothing else), "Sending as a guest · Log in" under it; after the first post one
 * card, "Shared. Don't lose your account": Save opens the Join sheet (name and e-mail, then the
 * code), Not now puts it away for this topic on this device. Log in = this belfry's e-mail log-in
 * over the app (the guest session stays until it made an account's).
 */
@Composable
fun GuestTopicComposer(groupId: String, modifier: Modifier = Modifier) {
    val c = Belfry.colors
    val session = LocalSession.current
    val world = LocalWorld.current
    val accounts = LocalAccounts.current
    val composer = rememberComposerState(groupId)
    var later by remember(groupId) { mutableStateOf(false) }
    LaunchedEffect(groupId) {
        later = try { withContext(Dispatchers.IO) { session.store.get(GuestTopic.laterKey(groupId)) } == "1" } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { false }
    }
    // sent, or still waiting in the outbox: both show in the feed
    val shared = world.messages(groupId).any { it.pubkey == world.me }
    Column(modifier.fillMaxWidth().testTag("guest-topic")) {
        if (shared && !later) {
            val shape = RoundedCornerShape(16.dp)
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).clip(shape).background(c.surface).border(1.dp, c.line, shape)
                    .padding(horizontal = 14.dp, vertical = 12.dp).testTag("guest-topic-save"),
            ) {
                Text(GuestTopic.SAVE_TITLE, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                Spacer(Modifier.height(4.dp))
                Text(GuestTopic.SAVE_TEXT, fontSize = 14.sp, lineHeight = 19.sp, color = c.muted)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BusyButton(GuestTopic.SAVE, onClick = { session.askToJoin() }, modifier = Modifier.weight(1f).testTag("guest-topic-save-button"))
                    BusyButton(
                        GuestTopic.NOT_NOW,
                        onClick = {
                            withContext(Dispatchers.IO) { session.store.set(GuestTopic.laterKey(groupId), "1") }
                            later = true
                        },
                        modifier = Modifier.weight(1f).testTag("guest-topic-later"), kind = ButtonKind.Ghost,
                    )
                }
            }
        }
        Hairline()
        Composer(
            composer,
            placeholder = GuestTopic.PLACEHOLDER,
            onSend = { text, files, _, _ -> session.postGuestMessage(groupId, text, files) },
            draftKey = ComposerDraft.channel(groupId),
            photos = true,
            plain = true,
        )
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 8.dp).testTag("guest-topic-line"),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(GuestTopic.SENDING + " ·", fontSize = 13.sp, color = c.muted)
            Spacer(Modifier.width(2.dp))
            Text(
                GuestTopic.LOG_IN, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.text, textDecoration = TextDecoration.Underline,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { accounts.wayIn(null, false) }.padding(horizontal = 6.dp, vertical = 8.dp).testTag("guest-topic-login"),
            )
        }
    }
}
