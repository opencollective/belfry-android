package sh.belfry.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.BusyButton
import sh.belfry.app.ui.kit.MoreIcons
import sh.belfry.app.ui.kit.Spinner
import sh.belfry.app.ui.kit.rememberBusy
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.nav.Routes
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.engine.Session
import sh.belfry.core.model.Actions
import sh.belfry.core.model.ActivityPreview
import sh.belfry.core.model.PreviewKind
import sh.belfry.core.model.World
import sh.belfry.core.model.needsMyVote
import sh.belfry.core.model.previews
import sh.belfry.core.model.previewsOpenByDefault
import sh.belfry.core.model.taskDue
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.WeakHashMap

/**
 * Which previews are open on this device (lead's ruling 4): only what the person changed is kept,
 * under the session store's key `preview-open` ("key=1,key=0", the latest 300), never synced.
 */
class PreviewMemory internal constructor(private val session: Session) {
    private val set = mutableStateMapOf<String, Boolean>()
    private var loaded = false

    internal suspend fun load() {
        if (loaded) return
        loaded = true
        val raw = withContext(Dispatchers.IO) { session.store.get(KEY) }.orEmpty()
        for (part in raw.split(',')) {
            val k = part.substringBeforeLast('=', "")
            if (k.isNotEmpty() && k !in set) set[k] = part.endsWith("=1")
        }
    }

    fun isOpen(key: String, default: Boolean): Boolean = set[key] ?: default

    internal suspend fun put(key: String, open: Boolean) {
        set.remove(key)
        set[key] = open
        val line = set.entries.toList().takeLast(300).joinToString(",") { "${it.key}=${if (it.value) 1 else 0}" }
        withContext(Dispatchers.IO) { session.store.set(KEY, line) }
    }

    companion object {
        const val KEY = "preview-open"
        private val bySession = WeakHashMap<Session, PreviewMemory>()
        fun of(session: Session): PreviewMemory = synchronized(bySession) { bySession.getOrPut(session) { PreviewMemory(session) } }
    }
}

/** The previews that start open in a snapshot's channel (worked out once per snapshot and channel). */
private val openByDefault = WeakHashMap<World, HashMap<String, Set<String>>>()

private fun World.startsOpen(groupId: String, now: Long): Set<String> = synchronized(openByDefault) {
    openByDefault.getOrPut(this) { HashMap() }.getOrPut(groupId) { previewsOpenByDefault(groupId, previews(groupId).list, now) }
}

private fun PreviewKind.icon(): ImageVector = when (this) {
    PreviewKind.TODO -> MoreIcons.Todo
    PreviewKind.EVENT -> BelfryIcons.Calendar
    PreviewKind.MEETING -> BelfryIcons.Users
    PreviewKind.PROPOSAL -> BelfryIcons.Proposal
}

/** "Ana", or "You" for me: the line's first word. */
internal fun World.firstName(pubkey: String): String =
    if (pubkey == me) "You" else displayName(pubkey).trim().substringBefore(' ').ifEmpty { displayName(pubkey) }

/**
 * An activity line in a channel's feed (canvas one-shape/action-previews): the kind's icon,
 * "<first name> <verb>: <item>", the time and ⌄. A tap unfolds the item's card with its quick
 * action (⌃ folds it back); a tap on the card opens the item's own screen.
 */
@Composable
fun ActivityPreviewRow(groupId: String, p: ActivityPreview, now: Long, memory: PreviewMemory = PreviewMemory.of(LocalSession.current)) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(memory) { memory.load() }
    val open = memory.isOpen(p.key, p.key in world.startsOpen(groupId, now))
    Column(Modifier.fillMaxWidth().testTag("preview-${p.key}")) {
        Row(
            Modifier.fillMaxWidth()
                .clickable(role = Role.Button, onClickLabel = if (open) "Fold" else "Show the ${kindWord(p.kind)}") { scope.launch { memory.put(p.key, !open) } }
                .semantics { stateDescription = if (open) "Expanded" else "Collapsed" }
                .heightIn(min = 40.dp)
                .padding(start = 16.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)
                .testTag("preview-line"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(p.kind.icon(), contentDescription = null, tint = c.muted, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = c.text)) { append(world.firstName(p.who)) }
                    append(" " + p.verb)
                    // open: the card names the item (a meeting keeps its name, its card is small)
                    if (!open || p.kind == PreviewKind.MEETING) append(": " + p.title)
                },
                fontSize = 14.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(ChatFormat.time(world, p.at), fontSize = 12.sp, color = c.faint, maxLines = 1)
            Spacer(Modifier.width(4.dp))
            Icon(if (open) BelfryIcons.ChevronUp else BelfryIcons.ChevronDown, contentDescription = null, tint = c.muted, modifier = Modifier.size(18.dp))
        }
        if (open) Box(Modifier.fillMaxWidth().padding(start = 44.dp, end = 16.dp, bottom = 8.dp).testTag("preview-card")) {
            when (p.kind) {
                PreviewKind.TODO -> TodoPreviewCard(groupId, p.ref, p.title)
                PreviewKind.EVENT, PreviewKind.MEETING -> {
                    val e = world.calEvent(p.ref)
                    if (e != null) sh.belfry.app.ui.cards.EventCardView(e, null) else GoneLine("This event is gone")
                }
                PreviewKind.PROPOSAL -> ProposalPreviewCard(p.ref)
            }
        }
    }
}

private fun kindWord(k: PreviewKind) = when (k) {
    PreviewKind.TODO -> "to-do"
    PreviewKind.EVENT -> "event"
    PreviewKind.MEETING -> "meeting"
    PreviewKind.PROPOSAL -> "proposal"
}

@Composable
private fun GoneLine(text: String) = Text(text, fontSize = 14.sp, color = Belfry.colors.muted, modifier = Modifier.padding(vertical = 6.dp))

@Composable
private fun PreviewFrame(onClick: () -> Unit, tag: String, content: @Composable () -> Unit) {
    val c = Belfry.colors
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier.widthIn(max = 420.dp).fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.line, shape)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 6.dp, vertical = 6.dp).testTag(tag),
    ) { content() }
}

private val DUE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

/** A to-do's card: its tick (done = a filled circle with a check, open = an empty ring), title, who has it, due date. */
@Composable
private fun TodoPreviewCard(groupId: String, taskId: String, fallback: String) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val session = LocalSession.current
    val nav = LocalNav.current
    val t = world.tasks(groupId).firstOrNull { it.id == taskId } ?: return GoneLine("“$fallback” was deleted")
    val due = world.taskDue(groupId, taskId)
    val busy = rememberBusy()
    PreviewFrame({ nav.go(Routes.todo(groupId, taskId)) }, "preview-todo") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { busy.run { session.send(Actions.taskAction(groupId, taskId, if (t.done) "undone" else "done")) } },
                enabled = !busy.busy,
                modifier = Modifier.testTag("preview-tick").semantics {
                    contentDescription = if (t.done) "Reopen “${t.title}”" else "Mark “${t.title}” done"
                    stateDescription = if (t.done) "Done" else "Not done"
                },
            ) {
                when {
                    busy.busy -> Spinner(size = 18.dp, color = c.text)
                    t.done -> Box(Modifier.size(22.dp).clip(CircleShape).background(c.text), contentAlignment = Alignment.Center) {
                        Icon(BelfryIcons.Check, contentDescription = null, tint = c.bg, modifier = Modifier.size(16.dp))
                    }
                    else -> Box(Modifier.size(22.dp).clip(CircleShape).border(2.dp, c.text, CircleShape))
                }
            }
            Column(Modifier.weight(1f).padding(end = 6.dp)) {
                Text(
                    t.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    textDecoration = if (t.done) TextDecoration.LineThrough else null,
                )
                val who = t.assignee?.let { if (it == world.me) "You" else world.displayName(it) } ?: "Nobody has it yet"
                val dueText = due?.let { " · due " + DUE.format(Instant.ofEpochSecond(it).atZone(world.ctx.zone)) } ?: ""
                Text(who + dueText, fontSize = 13.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A proposal's card: the yes / no bar with the counts in words, its state, and Vote while my vote is missing. */
@Composable
private fun ProposalPreviewCard(proposalGroup: String) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val nav = LocalNav.current
    val p = world.proposalFor(proposalGroup) ?: return GoneLine("This proposal is gone")
    val yes = p.tally["yes"] ?: 0
    val no = p.tally["no"] ?: 0
    val mine = world.myVote(p)
    PreviewFrame({ nav.go(Routes.proposal(proposalGroup)) }, "preview-proposal") {
        Column(Modifier.padding(6.dp)) {
            Text(p.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (yes + no > 0) {
                Spacer(Modifier.height(6.dp))
                // the bar: yes filled, no left as an outline (e-ink: the words below carry the counts)
                Row(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).border(1.dp, c.text, RoundedCornerShape(4.dp))) {
                    if (yes > 0) Box(Modifier.weight(yes.toFloat()).height(8.dp).background(c.text))
                    if (no > 0) Box(Modifier.weight(no.toFloat()).height(8.dp))
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$yes yes · $no no · " + p.status.label + (mine?.let { " · you voted ${sh.belfry.app.ui.cards.voteLabel(it).lowercase()}" } ?: ""),
                    fontSize = 13.sp, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                if (world.needsMyVote(p)) {
                    Spacer(Modifier.width(8.dp))
                    BusyButton("Vote", onClick = { nav.go(Routes.proposal(proposalGroup)) }, small = true, modifier = Modifier.testTag("preview-vote"))
                }
            }
        }
    }
}
