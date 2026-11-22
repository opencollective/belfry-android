package sh.belfry.app.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import sh.belfry.app.ui.kit.Hairline
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.model.isPreview
import sh.belfry.core.model.townList

/** "#garden" (a name that already starts with # is kept). */
internal fun hashName(name: String): String = if (name.startsWith("#")) name else "#$name"

/**
 * Previewing a channel (nav2/channel-preview, seq-3-interact): a channel of the town that is not
 * in my menu shows a light bar above the composer. Writing, reacting, being mentioned or pinning
 * it for myself brings it in (OneShape's Town list): the bar goes and a short note says
 * "#x is in your menu" for a few seconds. Nothing for guests (their menu is another list).
 */
@Composable
fun PreviewBar(groupId: String) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val session = LocalSession.current
    val guest = world.amGuest || session.guest.collectAsState().value != null
    if (guest) return
    val pins by session.pins.collectAsState()
    val now = session.now()
    val list = remember(world, pins, now / 3600) { world.townList(pins.c, now) }
    val preview = world.isPreview(groupId, list)
    val name = hashName(world.group(groupId)?.name ?: "this channel")
    // saveable: pinning it from its settings (a pushed screen) shows the note on the way back
    var was by androidx.compose.runtime.saveable.rememberSaveable(groupId) { mutableStateOf(preview) }
    var joined by remember(groupId) { mutableStateOf(false) }
    LaunchedEffect(preview) {
        val came = was && !preview
        was = preview
        if (came) { joined = true; delay(3_500); joined = false }
    }
    if (preview) {
        Hairline()
        Row(
            Modifier.fillMaxWidth().background(c.surface).padding(horizontal = 16.dp, vertical = 10.dp).testTag("preview-bar"),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(Icons.Outlined.Info, contentDescription = null, tint = c.muted, modifier = Modifier.padding(top = 1.dp).size(18.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("You're previewing $name.", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                Text("Write or react and it joins your menu, or pin it for yourself in its settings.", fontSize = 13.sp, color = c.muted)
            }
        }
    } else if (joined) {
        // a toast-like note, dark on light (and light on dark): readable on e-ink without colour
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
            Text(
                "$name is in your menu",
                fontSize = 14.sp, fontWeight = FontWeight.Medium, color = c.bg,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(c.text).padding(horizontal = 14.dp, vertical = 8.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite }.testTag("preview-joined"),
            )
        }
    }
}
