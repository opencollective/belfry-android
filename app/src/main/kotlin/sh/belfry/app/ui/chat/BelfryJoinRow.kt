package sh.belfry.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.ui.kit.Avatar
import sh.belfry.app.ui.kit.MoreIcons
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.nav.Routes
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.model.BelfryJoin
import sh.belfry.core.model.isAgent

/**
 * one-shape/townsquare-join (SPEC 19.69): "Lina Moreau joined Riverside Commons" in the main channel,
 * an activity line (nobody sent it: no menu, no replies). Their face, the name in semi-bold, the time;
 * the whole line opens their profile. An agent without a picture: a robot on a dark disc.
 */
@Composable
fun BelfryJoinRow(j: BelfryJoin) {
    val world = LocalWorld.current
    val nav = LocalNav.current
    val c = Belfry.colors
    val (name, line) = world.belfryJoinLine(j)
    val face = world.face(j.pubkey)
    val agent = j.agent || world.isAgent(j.pubkey)
    Row(
        Modifier.fillMaxWidth()
            .clickable(role = Role.Button) { nav.go(Routes.profile(j.pubkey)) }
            .semantics { contentDescription = "$line. Open the profile" }
            .padding(start = 26.dp, end = 16.dp, top = 6.dp, bottom = 6.dp)
            .testTag("belfry-join"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (agent && face.picture == null) {
            Box(Modifier.size(22.dp).clip(CircleShape).background(c.text), contentAlignment = Alignment.Center) {
                Icon(MoreIcons.Bot, contentDescription = null, tint = c.bg, modifier = Modifier.size(14.dp))
            }
        } else {
            Avatar(face.copy(name = name), size = 22.dp)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            buildAnnotatedString {
                if (line.startsWith(name)) {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(name) }
                    append(line.substring(name.length))
                } else append(line)
            },
            fontSize = 14.sp, color = c.text, modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(ChatFormat.time(world, j.at), fontSize = 12.sp, color = c.faint)
    }
}
