package sh.belfry.app.ui.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import sh.belfry.app.ui.chat.ChatFormat
import sh.belfry.app.ui.chat.face
import sh.belfry.app.ui.kit.ActionGroup
import sh.belfry.app.ui.kit.ActionRow
import sh.belfry.app.ui.kit.Avatar
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.BelfrySheet
import sh.belfry.app.ui.kit.BusyButton
import sh.belfry.app.ui.kit.ButtonKind
import sh.belfry.app.ui.kit.LinkText
import sh.belfry.app.ui.kit.rememberBusy
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.nav.Routes
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.engine.ActionException
import sh.belfry.core.model.CardAuthors
import sh.belfry.core.model.Group
import sh.belfry.core.model.GroupState
import sh.belfry.core.model.Message
import sh.belfry.core.model.groupState
import sh.belfry.core.net.GroupCard
import sh.belfry.core.net.GroupCards

// Printed cards in the town (SPEC 19.75 / 19.76; canvas "Belfry · Group cards": cards/townsquare-new-topic,
// cards/steward): the town square's card of a topic started from a card, the first line of such a
// group, and the steward's section "The card" in its Settings.

/**
 * cards/townsquare-new-topic: New topic · #hashtag · the topic · its description · Started by … ·
 * N following · Open. The belfry writes the topic's about as "<topic> · <description>"; while the
 * channel is not known here the post's own first line says the same. The card and Open both show
 * the topic.
 */
@Composable
internal fun TopicCard(channelId: String, message: Message, modifier: Modifier = Modifier) {
    val world = LocalWorld.current
    val nav = LocalNav.current
    val c = Belfry.colors
    val g = world.group(channelId)
    val gone = world.groupState(channelId) == GroupState.DELETED
    // onb-town-topic (SPEC 19.89): #hashtag · its description · Steward: … · N following · Open. The about is shown
    // whole (the web's TopicCard since e965481); an older topic's "<topic> · <description>" reads as it is.
    val description = g?.about ?: CardAuthors.aboutInPost(message.text)
    // the channel is named by its hashtag; a renamed one keeps its id as the hashtag
    val hashtag = g?.rawName?.takeIf { it.isNotEmpty() && !it.contains(' ') } ?: channelId
    val by = message.author
    val who = if (by == world.me) "you" else world.realName(by).ifEmpty { world.displayName(by) }
    val open = { nav.openChannel(channelId) }
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .padding(top = 4.dp)
            .widthIn(max = 360.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(c.surface)
            .border(1.dp, c.line, shape)
            .then(if (gone) Modifier else Modifier.clickable(onClickLabel = "Open the topic", onClick = open))
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag("topic-card"),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(BelfryIcons.Hash, contentDescription = null, tint = c.muted, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(5.dp))
            Text("NEW TOPIC", fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp, color = c.muted, maxLines = 1)
        }
        Text("#$hashtag", fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (description.isNotEmpty()) LinkText(description, fontSize = 15.sp, lineHeight = 20.sp, color = c.text)
        Text(
            if (gone) "This topic has been deleted" else CardAuthors.stewardLine(who, pod = false, count = g?.members?.size),
            fontSize = 13.sp, lineHeight = 18.sp, color = c.muted, modifier = Modifier.padding(top = 2.dp).testTag("topic-card-meta"),
        )
        if (!gone) {
            Spacer(Modifier.height(5.dp))
            BusyButton("Open", onClick = { open() }, modifier = Modifier.fillMaxWidth().testTag("topic-card-open"), small = true)
        }
    }
}

/**
 * onb-town-group (SPEC 19.89): the town square's post of a group a printed card made (the groups key
 * wrote it for the person, `by`): NEW GROUP · its name · its description · Steward: … · N people ·
 * Join (Open when I'm in). A deleted, archived or private group: the usual group card.
 */
@Composable
internal fun CardGroupCard(podId: String, message: Message, modifier: Modifier = Modifier) {
    val world = LocalWorld.current
    val session = LocalSession.current
    val nav = LocalNav.current
    val c = Belfry.colors
    val g = world.group(podId)
    if (g == null || g.isPrivate || world.groupState(podId) != GroupState.OPEN) return PodCard(podId, message.text, modifier)
    val by = message.author
    val who = if (by == world.me) "you" else world.realName(by).ifEmpty { world.displayName(by) }
    val inIt = world.me in g.members
    val open = { nav.go(Routes.pod(podId)) }
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .padding(top = 4.dp)
            .widthIn(max = 360.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(c.surface)
            .border(1.dp, c.line, shape)
            .clickable(onClickLabel = "Open the group", onClick = open)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag("card-group-card"),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(BelfryIcons.Pod, contentDescription = null, tint = c.muted, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(5.dp))
            Text("NEW GROUP", fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp, color = c.muted, maxLines = 1)
        }
        Text(g.name, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (g.about.isNotEmpty()) LinkText(g.about, fontSize = 15.sp, lineHeight = 20.sp, color = c.text)
        Text(CardAuthors.stewardLine(who, pod = true, count = g.members.size), fontSize = 13.sp, lineHeight = 18.sp, color = c.muted, modifier = Modifier.padding(top = 2.dp).testTag("card-group-card-meta"))
        Spacer(Modifier.height(5.dp))
        when {
            inIt -> BusyButton("Open", onClick = { open() }, modifier = Modifier.fillMaxWidth().testTag("card-group-card-open"), small = true)
            g.closed -> BusyButton("Open", onClick = { open() }, kind = ButtonKind.Ghost, modifier = Modifier.fillMaxWidth().testTag("card-group-card-open"), small = true)
            else -> BusyButton("Join", onClick = { session.send(sh.belfry.core.model.Actions.join(podId)) }, modifier = Modifier.fillMaxWidth().testTag("card-group-card-join"), small = true)
        }
    }
}

/**
 * The first line of a group or topic started from a printed card: "Sam Carter started this group
 * from a printed card", an activity line in our words (the groups key signed it; never its text,
 * never the card's id). It opens the person's profile.
 */
@Composable
fun CardStartedLine(who: String?, line: String, at: Long) {
    val world = LocalWorld.current
    val nav = LocalNav.current
    val c = Belfry.colors
    val name = line.substringBefore(" created the ", "")
    Row(
        Modifier.fillMaxWidth()
            .then(if (who != null) Modifier.clickable(role = Role.Button) { nav.go(Routes.profile(who)) } else Modifier)
            .semantics { contentDescription = if (who != null) "$line. Open the profile" else line }
            .padding(start = 26.dp, end = 16.dp, top = 6.dp, bottom = 6.dp)
            .testTag("card-started"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (who != null) Avatar(world.face(who), size = 22.dp)
        else Icon(BelfryIcons.Qr, contentDescription = null, tint = c.muted, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            buildAnnotatedString {
                if (name.isNotEmpty()) {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(name) }
                    append(line.substring(name.length))
                } else append(line)
            },
            fontSize = 14.sp, color = c.text, modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(ChatFormat.time(world, at), fontSize = 12.sp, color = c.faint)
    }
}

/**
 * cards/steward: "The card" in the Settings of a group or topic started from a printed card, for
 * its stewards (a guest who started it too) and the belfry's admins: the card, who started it, how
 * many joined by scanning it, its QR to print again, and Retire (asks first). Nothing at all when
 * the belfry says there is no card in use, or that it is not mine to see.
 */
@Composable
fun StewardCardSection(g: Group) {
    val world = LocalWorld.current
    val session = LocalSession.current
    val c = Belfry.colors
    if (!world.canAdmin(g)) return
    var card by remember(g.id) { mutableStateOf<GroupCard?>(null) }
    var retired by remember(g.id) { mutableStateOf(false) }
    var asking by remember(g.id) { mutableStateOf(false) }
    var qr by remember(g.id) { mutableStateOf(false) }
    LaunchedEffect(g.id) {
        card = try { session.groupCards.forGroup(g.id) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
    }
    val gc = card ?: return
    val word = if (gc.topic) "topic" else "group"
    Column(Modifier.fillMaxWidth().padding(top = 14.dp).testTag("card-section")) {
        Text("The card", fontSize = 13.sp, lineHeight = 18.sp, color = c.muted)
        Spacer(Modifier.height(6.dp))
        if (retired) {
            Text(
                "Card ${gc.id} is retired: its QR says the $word is closed to new people. People already in it carry on here. The belfry’s admins can reset it.",
                fontSize = 14.sp, lineHeight = 20.sp, color = c.text, modifier = Modifier.testTag("card-retired"),
            )
            return@Column
        }
        val starter = when {
            gc.startedBy != null && gc.startedBy == world.me -> "you"
            else -> gc.startedBy?.let { pk -> world.realName(pk) }.orEmpty().ifEmpty { gc.startedName }
        }
        ActionGroup {
            ActionRow(
                "Card ${gc.id}", onClick = null, icon = BelfryIcons.Qr,
                sub = (if (starter.isNotEmpty()) "Started by $starter · " else "") + GroupCards.joinedWords(gc.joinedByScan),
                modifier = Modifier.testTag("card-row"),
            )
            ActionRow(
                "Show the card’s QR", onClick = { qr = true }, icon = BelfryIcons.Share, sub = "To print it again", chevron = true,
                modifier = Modifier.testTag("card-qr"),
            )
            ActionRow(
                "Retire this card", onClick = { asking = true }, icon = BelfryIcons.Trash, danger = true,
                sub = "Its QR then says the $word is closed to new people",
                modifier = Modifier.testTag("card-retire"),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text("Only the $word’s stewards and the belfry’s admins see this.", fontSize = 13.sp, lineHeight = 18.sp, color = c.muted)
    }
    if (qr) QrSheet(GroupCards.address(session.community.chatUrl, gc.id), "Card ${gc.id}") { qr = false }
    if (asking) {
        var error by remember { mutableStateOf<String?>(null) }
        val busy = rememberBusy()
        BelfrySheet(onDismiss = { if (!busy.busy) asking = false }) {
            Column(Modifier.fillMaxWidth().padding(bottom = 16.dp).testTag("card-retire-sheet")) {
                Text("Retire this card?", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                Spacer(Modifier.height(6.dp))
                Text("Its QR will say the $word is closed to new people. People already in it carry on here.", fontSize = 15.sp, lineHeight = 21.sp, color = c.muted)
                error?.let { e ->
                    Spacer(Modifier.height(10.dp))
                    ErrorLine(e, Modifier.testTag("card-retire-error"))
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BusyButton("Keep it", onClick = { asking = false }, modifier = Modifier.weight(1f), kind = ButtonKind.Ghost, enabled = !busy.busy)
                    BusyButton(
                        "Retire", modifier = Modifier.weight(1f).testTag("card-retire-confirm"), kind = ButtonKind.Danger, busy = busy,
                        onClick = {
                            error = null
                            try {
                                session.groupCards.retire(gc)
                                retired = true
                                asking = false
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: ActionException) {
                                // the app's own words for the code (never the server's message)
                                error = e.message?.takeIf { m -> m.isNotEmpty() } ?: GroupCards.FALLBACK
                            } catch (e: Exception) {
                                error = GroupCards.FALLBACK
                            }
                        },
                    )
                }
            }
        }
    }
}
