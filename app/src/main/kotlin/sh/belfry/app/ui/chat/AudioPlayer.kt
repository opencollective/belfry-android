package sh.belfry.app.ui.chat

import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import sh.belfry.app.ui.kit.Spinner
import sh.belfry.app.ui.kit.isSafeLink
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.model.Media

/** The recording that is playing (its address): starting another one pauses it. */
private object NowPlaying {
    var url by mutableStateOf<String?>(null)
}

private enum class AudioState { Idle, Loading, Playing, Paused, Failed }

private fun clock(ms: Int): String {
    val s = (ms / 1000).coerceAtLeast(0)
    fun two(n: Int) = n.toString().padStart(2, '0')
    return if (s >= 3600) "${s / 3600}:${two(s / 60 % 60)}:${two(s % 60)}" else "${s / 60}:${two(s % 60)}"
}

/**
 * An audio file in a message (web 2026-11-12: recordings play in the feed): play / pause, how far
 * it is, how long it is. Streamed from the file server when played, not before; one at a time.
 * The player is let go when the row leaves the screen. What this phone cannot play opens in the
 * browser instead.
 */
@Composable
fun AudioRow(media: Media, modifier: Modifier = Modifier) {
    val c = Belfry.colors
    val uri = LocalUriHandler.current
    val url = media.url
    var state by remember(url) { mutableStateOf(AudioState.Idle) }
    var position by remember(url) { mutableIntStateOf(0) }
    var length by remember(url) { mutableIntStateOf(0) }
    val holder = remember(url) { arrayOfNulls<MediaPlayer>(1) }

    fun release() {
        val p = holder[0]
        holder[0] = null
        try { p?.release() } catch (e: Exception) { }
        if (NowPlaying.url == url) NowPlaying.url = null
    }
    DisposableEffect(url) { onDispose { release() } }

    // another recording started: this one waits
    val playing = NowPlaying.url
    LaunchedEffect(playing) {
        if (playing != url && state == AudioState.Playing) {
            try { holder[0]?.pause() } catch (e: Exception) { }
            state = AudioState.Paused
        }
    }
    LaunchedEffect(state) {
        while (state == AudioState.Playing) {
            try { holder[0]?.let { position = it.currentPosition } } catch (e: Exception) { }
            delay(250)
        }
    }

    fun toggle() {
        when (state) {
            AudioState.Playing -> {
                try { holder[0]?.pause() } catch (e: Exception) { }
                state = AudioState.Paused
            }
            AudioState.Paused -> {
                try { holder[0]?.start(); NowPlaying.url = url; state = AudioState.Playing } catch (e: Exception) { release(); state = AudioState.Failed }
            }
            AudioState.Idle -> {
                if (!url.startsWith("https://")) { state = AudioState.Failed; return }
                state = AudioState.Loading
                try {
                    val p = MediaPlayer()
                    holder[0] = p
                    p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    p.setOnPreparedListener { mp ->
                        if (holder[0] === mp) {
                            try { length = mp.duration.coerceAtLeast(0); mp.start(); NowPlaying.url = url; state = AudioState.Playing } catch (e: Exception) { release(); state = AudioState.Failed }
                        }
                    }
                    p.setOnCompletionListener { mp ->
                        if (holder[0] === mp) {
                            try { mp.seekTo(0) } catch (e: Exception) { }
                            position = 0
                            state = AudioState.Paused
                            if (NowPlaying.url == url) NowPlaying.url = null
                        }
                    }
                    p.setOnErrorListener { mp, _, _ ->
                        if (holder[0] === mp) { release(); state = AudioState.Failed }
                        true
                    }
                    p.setDataSource(url)
                    p.prepareAsync()
                } catch (e: Exception) {
                    release()
                    state = AudioState.Failed
                }
            }
            AudioState.Loading -> Unit
            // this phone can't play it: the browser may
            AudioState.Failed -> if (isSafeLink(url)) try { uri.openUri(url) } catch (e: Exception) { }
        }
    }

    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier.padding(top = 4.dp).widthIn(max = 320.dp).fillMaxWidth().clip(shape).border(1.dp, c.line, shape).padding(10.dp).testTag("audio-row"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(c.accent)
                .clickable(
                    enabled = state != AudioState.Loading, role = Role.Button,
                    onClickLabel = when (state) { AudioState.Playing -> "Pause"; AudioState.Failed -> "Open in the browser"; else -> "Play" },
                ) { toggle() }
                .testTag("audio-play"),
            contentAlignment = Alignment.Center,
        ) {
            when (state) {
                AudioState.Loading -> Spinner(size = 18.dp, color = c.onAccent)
                AudioState.Playing -> Row { Box(Modifier.width(4.dp).height(14.dp).background(c.onAccent)); Spacer(Modifier.width(4.dp)); Box(Modifier.width(4.dp).height(14.dp).background(c.onAccent)) }
                else -> Text(if (state == AudioState.Failed) "↗" else "▶", fontSize = 15.sp, color = c.onAccent)
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(media.name?.ifBlank { null } ?: "Recording", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            val done = if (length > 0) (position.toFloat() / length).coerceIn(0f, 1f) else 0f
            Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(c.surface2)) {
                Box(Modifier.fillMaxWidth(done).height(3.dp).background(c.text))
            }
            Spacer(Modifier.height(4.dp))
            Text(
                when (state) {
                    AudioState.Failed -> "This phone can’t play it. Tap to open it in the browser."
                    AudioState.Loading -> "Loading…"
                    AudioState.Idle -> "Audio"
                    else -> clock(position) + " / " + clock(length)
                },
                fontSize = 12.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
