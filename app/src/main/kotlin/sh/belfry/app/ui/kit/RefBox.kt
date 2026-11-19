package sh.belfry.app.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import sh.belfry.app.ui.cards.CardBits
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.model.RefKind
import sh.belfry.core.model.RefSeen
import sh.belfry.core.model.RefTarget
import sh.belfry.core.model.refSeen

/**
 * The preview box of a reference at the very end of a message (SPEC 19.44, canvas
 * channels/link-preview-end): the channel, group, event or proposal with its name and a line,
 * a tap opens it. Loading: a grey bar, the kind's word after 5 s. I can't open it: a lock and no
 * name. Deleted: grey words. A bar on the left, not colour alone, sets it apart (e-ink).
 */
@Composable
fun RefBox(target: RefTarget, modifier: Modifier = Modifier) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val nav = LocalNav.current
    val now = LocalSession.current.now()
    // an event: its card as everywhere in the app (date, where, my answer; fetched from the belfry for a guest)
    if (target is RefTarget.Event && world.refSeen(target) !is RefSeen.Deleted) {
        sh.belfry.app.ui.cards.EventRefCard(sh.belfry.core.model.EventRef(target.coord, target.link), modifier.padding(top = 6.dp))
        return
    }
    val seen = world.refSeen(target)
    var waited by remember { mutableStateOf(false) }
    if (seen is RefSeen.Loading) LaunchedEffect(Unit) { delay(5_000); waited = true }
    val open = seen is RefSeen.GroupSeen || seen is RefSeen.EventSeen || seen is RefSeen.ProposalSeen
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier
            .padding(top = 6.dp)
            .widthIn(max = 320.dp)
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(shape)
            .background(c.surface)
            .then(if (open) Modifier.clickable(role = Role.Button, onClickLabel = "Open") { openRef(nav, seen) } else Modifier)
            .testTag("ref-box"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(c.line))
        Row(Modifier.padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            when (seen) {
                is RefSeen.EventSeen -> {
                    val z = CardBits.zoned(world, seen.event.start)
                    Column(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(c.bg), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text(z.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH).uppercase(), fontSize = 10.sp, lineHeight = 11.sp, fontWeight = FontWeight.SemiBold, color = c.danger)
                        Text(z.dayOfMonth.toString(), fontSize = 17.sp, lineHeight = 19.sp, fontWeight = FontWeight.Bold, color = c.text)
                    }
                }
                else -> Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(c.bg), contentAlignment = Alignment.Center) {
                    Icon(iconOf(seen), contentDescription = null, tint = c.muted, modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                when (seen) {
                    is RefSeen.GroupSeen -> {
                        val g = seen.group
                        Title(g.name)
                        Line((if (g.isPod) "Group" else "Channel") + " · ${g.members.size} member${if (g.members.size == 1) "" else "s"}")
                        if (g.about.isNotBlank()) About(g.about.trim().lineSequence().first())
                    }
                    is RefSeen.EventSeen -> {
                        val e = seen.event
                        Title(e.title)
                        Line(CardBits.dayAndWhen(world, e, now) + (e.location?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""))
                    }
                    is RefSeen.ProposalSeen -> {
                        val p = seen.proposal
                        Title(p.title)
                        val parent = world.group(p.groupId)?.parent?.let { world.group(it) }?.takeIf { it.named }?.name
                        Line("Proposal" + (parent?.let { " in #$it" } ?: ""))
                    }
                    is RefSeen.Locked -> {
                        Text(lockedWords(seen.kind).replaceFirstChar { it.uppercase() }, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.muted)
                        Line("Only members of ${world.community.name.ifEmpty { "this belfry" }} can open it")
                    }
                    is RefSeen.Deleted -> Text(deletedWords(seen.kind).replaceFirstChar { it.uppercase() }, fontSize = 15.sp, color = c.muted)
                    is RefSeen.Loading -> if (waited) {
                        Text(if (seen.kind == RefKind.CHANNEL) "#channel" else seen.kind.word, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.muted)
                    } else {
                        NameSkeleton(Modifier.padding(vertical = 4.dp), fraction = 0.6f, height = 14.dp)
                    }
                }
            }
        }
    }
}

private fun iconOf(seen: RefSeen): ImageVector = when (seen) {
    is RefSeen.Locked -> BelfryIcons.Lock
    else -> when (seen.kind) {
        RefKind.CHANNEL -> BelfryIcons.Hash
        RefKind.GROUP -> BelfryIcons.Pod
        RefKind.EVENT -> BelfryIcons.Calendar
        RefKind.PROPOSAL -> BelfryIcons.Proposal
    }
}

@Composable private fun Title(t: String) = Text(t, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Belfry.colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
@Composable private fun Line(t: String) = Text(t, fontSize = 13.sp, lineHeight = 17.sp, color = Belfry.colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
@Composable private fun About(t: String) = Text(t, fontSize = 13.sp, lineHeight = 17.sp, color = Belfry.colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))

/** TEXT shown with its end reference as a box: the Markdown before it (none when it was only the reference), then the box. */
@Composable
fun MarkdownWithBox(
    text: String, domain: String, modifier: Modifier = Modifier, color: androidx.compose.ui.graphics.Color = Belfry.colors.text, rich: Boolean = true,
    onTopic: ((String) -> Unit)? = null,
    /** The message shows its event cards under it already (EventCards): an event at the end is not shown twice. */
    eventCards: Boolean = false,
) {
    val split = remember(text, domain) { sh.belfry.core.model.References.endBox(text, domain) }
    if (split == null) {
        MarkdownText(text, modifier, rich = rich, color = color, onTopic = onTopic)
        return
    }
    Column(modifier) {
        if (split.first.isNotBlank()) MarkdownText(split.first, rich = rich, color = color, onTopic = onTopic)
        if (!(eventCards && split.second.target is RefTarget.Event)) RefBox(split.second.target)
    }
}
