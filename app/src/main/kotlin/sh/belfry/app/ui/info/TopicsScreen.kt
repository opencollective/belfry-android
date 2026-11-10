package sh.belfry.app.ui.info

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import sh.belfry.app.ui.chat.TopicRequest
import sh.belfry.app.ui.kit.*
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.model.Actions
import sh.belfry.core.model.Topics

/**
 * A channel's topics: the pinned ones (they show under the channel's name, for everyone) and
 * every other #hashtag used in it, with how many messages carry each. Tap a topic to open the
 * channel on it; the pin on the right pins or unpins it.
 */
@Composable
fun TopicsScreen(id: String) {
    val world = LocalWorld.current
    val session = LocalSession.current
    val nav = LocalNav.current
    val g = world.group(id)
    val pinned = remember(world, id) { world.pinnedTopics(id).filter { it != Topics.ANNOUNCE } }
    val counts = remember(world, id) {
        val n = LinkedHashMap<String, Int>()
        for (m in world.messages(id)) for (t in m.topics) if (t != Topics.ANNOUNCE) n[t] = (n[t] ?: 0) + 1
        n
    }
    val others = counts.keys.filter { it !in pinned }.sortedWith(compareByDescending<String> { counts[it] ?: 0 }.thenBy { it })
    val announcements = remember(world, id) { world.messages(id).count { world.isAnnouncement(it, g) } }

    fun open(topic: String?) {
        TopicRequest.pending = id to topic
        nav.openChannel(id)
    }

    fun count(t: String): String {
        val n = counts[t] ?: 0
        return if (n == 1) "1 message" else "$n messages"
    }

    InfoPage("Topics", subtitle = channelLabel(g, id)) {
        ActionGroup {
            ActionRow("All messages", { open(null) }, icon = BelfryIcons.Hash, chevron = true)
            if (announcements > 0) {
                ActionRow("Announcements", { open(Topics.ANNOUNCE) }, icon = BelfryIcons.Megaphone, value = announcements.toString(), chevron = true)
            }
        }
        SectionTitle("Pinned")
        if (pinned.isEmpty()) {
            Hint("No pinned topics yet. Pin one below, or write a #hashtag in a message.")
        } else {
            ActionGroup {
                pinned.forEach { t ->
                    ActionRow(
                        "#$t", { open(t) }, icon = BelfryIcons.Pin, value = count(t), modifier = Modifier.testTag("topic-$t"),
                        trailing = {
                            BusyIconButton(BelfryIcons.Close, "Unpin #$t", { session.send(Actions.pinTopic(id, world.pinnedTopics(id), t, false)) }, tint = Belfry.colors.muted)
                        },
                    )
                }
            }
        }
        if (others.isNotEmpty()) {
            SectionTitle("Used in this channel")
            ActionGroup {
                others.forEach { t ->
                    ActionRow(
                        "#$t", { open(t) }, icon = BelfryIcons.Hash, value = count(t), modifier = Modifier.testTag("topic-$t"),
                        trailing = {
                            BusyIconButton(BelfryIcons.Pin, "Pin #$t to this channel", { session.send(Actions.pinTopic(id, world.pinnedTopics(id), t, true)) }, tint = Belfry.colors.muted)
                        },
                    )
                }
            }
        }
        Hint("Pinned topics show under the channel’s name, for everyone. Opening a topic shows its messages, and what you send there carries it.", Modifier.padding(top = 12.dp))
        Spacer(Modifier.height(24.dp))
    }
}
