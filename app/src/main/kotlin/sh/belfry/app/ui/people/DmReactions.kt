package sh.belfry.app.ui.people

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.ui.chat.EmojiButton
import sh.belfry.app.ui.chat.myTopEmojis
import sh.belfry.app.ui.kit.ActionGroup
import sh.belfry.app.ui.kit.ActionRow
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.BelfrySheet
import sh.belfry.app.ui.kit.copyText
import sh.belfry.app.ui.kit.rememberBusy
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.dm.Dm
import sh.belfry.core.dm.DmChip
import sh.belfry.core.model.Actions

// Reactions under a direct message (canvas home/conversation, Xavier's to-dos 2026-11-19): the
// same chips as in channels, on the bubble's side. Tap = add or take back mine (mine is marked by
// a border and bold count, not by colour alone: e-ink); long-press a bubble = the menu with the
// reactions row. Not on e-mail threads. Wire: DirectMessages.react (the web's dm.ts).

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DmReactionChips(d: Dm, chips: List<DmChip>, onAdd: () -> Unit, modifier: Modifier = Modifier) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val session = LocalSession.current
    FlowRow(
        modifier.padding(top = 3.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, if (d.mine) Alignment.End else Alignment.Start),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (r in chips) {
            val busy = rememberBusy()
            val mine = r.mine != null
            val shape = RoundedCornerShape(99.dp)
            val names = r.who.keys.joinToString(", ") { if (it == world.me) "you" else world.displayName(it) }
            Row(
                Modifier
                    .clip(shape)
                    .background(if (mine) c.surface2 else c.surface)
                    .border(if (mine) 1.5.dp else 1.dp, if (mine) c.text else c.line, shape)
                    .clickable(enabled = !busy.busy, role = Role.Button, onClickLabel = if (mine) "Take back ${r.emoji}" else "React with ${r.emoji}") {
                        busy.run { session.toggleDmReaction(d, r.emoji) }
                    }
                    .semantics { contentDescription = "${r.emoji} ${r.who.size}, from $names" }
                    .padding(horizontal = 8.dp, vertical = 3.dp)
                    .testTag("dm-chip"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(r.emoji, fontSize = 14.sp)
                Spacer(Modifier.width(5.dp))
                Text(r.who.size.toString(), fontSize = 13.sp, fontWeight = if (mine) FontWeight.SemiBold else FontWeight.Normal, color = c.text)
            }
        }
        val shape = RoundedCornerShape(99.dp)
        Box(
            Modifier.clip(shape).border(1.dp, c.line, shape).clickable(role = Role.Button, onClickLabel = "Add a reaction", onClick = onAdd).padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Icon(BelfryIcons.SmilePlus, contentDescription = "Add a reaction", tint = c.muted, modifier = Modifier.size(17.dp))
        }
    }
}

/** A long-press on a bubble: the reactions row (not for e-mail), then Copy text. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DmMessageSheet(d: Dm, chips: List<DmChip>, emojiOnly: Boolean, onClose: () -> Unit, onReply: ((Dm) -> Unit)? = null) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val session = LocalSession.current
    val context = LocalContext.current
    val reacting = rememberBusy()
    val mine = chips.filter { it.mine != null }.map { it.emoji }.toSet()
    val top = remember(world) { myTopEmojis(world, 5) }
    var more by remember { mutableStateOf(emojiOnly) }
    var copied by remember { mutableStateOf(false) }
    BelfrySheet(onDismiss = onClose) {
        if (!d.isMail) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                for (e in top) EmojiButton(e, e in mine, reacting) { session.toggleDmReaction(d, e); onClose() }
                Box(
                    Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)).background(if (more) c.surface2 else c.surface)
                        .clickable(role = Role.Button, onClickLabel = "More emojis") { more = !more },
                    contentAlignment = Alignment.Center,
                ) { Icon(BelfryIcons.SmilePlus, contentDescription = "More emojis", tint = c.muted, modifier = Modifier.size(22.dp)) }
            }
            if (more) {
                Spacer(Modifier.height(10.dp))
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (e in Actions.MORE_EMOJIS) EmojiButton(e, e in mine, reacting, small = true) { session.toggleDmReaction(d, e); onClose() }
                }
            }
            Spacer(Modifier.height(14.dp))
        }
        // the same hold menu as in a channel (docs/one-shape.md 3, canvas one-shape/dm-actions): Reply,
        // then Copy text. No Make a to-do / Pin in DMs in this build (sealed rules first) and no
        // Copy link: a direct message has no link (no link scheme for DMs yet)
        if (!emojiOnly && onReply != null) {
            ActionGroup {
                ActionRow("Reply", onClick = { onClose(); onReply(d) }, icon = BelfryIcons.Reply, modifier = Modifier.testTag("dm-menu-reply"))
            }
            if (d.text.isNotEmpty()) Spacer(Modifier.height(12.dp))
        }
        if (!emojiOnly && d.text.isNotEmpty()) {
            ActionGroup {
                ActionRow(if (copied) "Copied" else "Copy text", onClick = { copyText(context, d.text); copied = true }, icon = BelfryIcons.Copy)
            }
        }
    }
}
