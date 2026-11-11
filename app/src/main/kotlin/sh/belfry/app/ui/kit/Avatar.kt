package sh.belfry.app.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import sh.belfry.app.ui.theme.Belfry

private val AVATAR_COLORS = listOf(
    Color(0xFFD6FF52), Color(0xFFF89B0E), Color(0xFF7EE0A1), Color(0xFF5EC8E5), Color(0xFFC792EA), Color(0xFFFF9A8A), Color(0xFFF2D06B),
)

/**
 * A person's colour: the web chat's rule exactly (first 6 hex digits of the key modulo 7), so
 * someone has the same colour in every client.
 */
fun avatarColor(pubkey: String): Color {
    val n = pubkey.take(6).toIntOrNull(16) ?: 0
    return AVATAR_COLORS[n % AVATAR_COLORS.size]
}

/** Who to draw: the key (colour), the display name (initial) and an optional https picture. */
data class Face(val pubkey: String, val name: String, val picture: String? = null)

/**
 * A person: their picture when there is one (https only), else their initial on their colour.
 * People are round; channels and apps are rounded squares ([ChannelIcon]).
 */
@Composable
fun Avatar(
    pubkey: String,
    name: String,
    modifier: Modifier = Modifier,
    picture: String? = null,
    size: Dp = 36.dp,
    shape: Shape = CircleShape,
) {
    val fontSize = with(LocalDensity.current) { (size * 0.42f).toSp() }
    Box(modifier.size(size).clip(shape).background(avatarColor(pubkey)), contentAlignment = Alignment.Center) {
        val initial = name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
        Text(initial, color = Color(0xFF1A231D), fontWeight = FontWeight.Bold, fontSize = fontSize, lineHeight = fontSize)
        if (picture != null && picture.startsWith("https://") && LocalNetworkImages.current) {
            AsyncImage(model = picture, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
fun Avatar(face: Face, modifier: Modifier = Modifier, size: Dp = 36.dp) = Avatar(face.pubkey, face.name, modifier, face.picture, size)

/** Up to [max] overlapping faces (thread chips, "active this month"). */
@Composable
fun AvatarStack(faces: List<Face>, modifier: Modifier = Modifier, size: Dp = 20.dp, max: Int = 3) {
    val ring = Belfry.colors.bg
    Row(modifier) {
        faces.take(max).forEachIndexed { i, f ->
            Box(
                Modifier
                    .offset(x = -(size * 0.3f) * i)
                    .clip(CircleShape)
                    .background(ring)
                    .padding(1.5.dp),
            ) { Avatar(f, size = size) }
        }
    }
}

enum class ChannelGlyph { Channel, Public, Event, Pod, Proposal, Mail }

fun ChannelGlyph.icon(): ImageVector = when (this) {
    ChannelGlyph.Channel -> BelfryIcons.Hash
    ChannelGlyph.Public -> BelfryIcons.Globe
    ChannelGlyph.Event -> BelfryIcons.Calendar
    ChannelGlyph.Pod -> BelfryIcons.Pod
    ChannelGlyph.Proposal -> BelfryIcons.Proposal
    ChannelGlyph.Mail -> BelfryIcons.Mail
}

/** The rounded square in front of a channel's name (web: `.chan .hash`). */
@Composable
fun ChannelIcon(glyph: ChannelGlyph, modifier: Modifier = Modifier, size: Dp = 32.dp) {
    val c = Belfry.colors
    val shape = RoundedCornerShape(10.dp)
    Box(modifier.size(size).clip(shape).background(c.bg).border(1.dp, c.line, shape), contentAlignment = Alignment.Center) {
        Icon(glyph.icon(), contentDescription = null, tint = c.muted, modifier = Modifier.size(size * 0.6f))
    }
}
