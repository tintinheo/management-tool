package com.audiohub.ui.nowplaying

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.audiohub.playback.PlayerUiState
import com.audiohub.ui.components.Artwork
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import com.audiohub.ui.theme.ArtMotion
import com.audiohub.ui.theme.MiniStyle
import com.audiohub.ui.theme.LocalUiTokens

/** Floating "now playing" card above the tab bar. */
@Composable
fun MiniPlayer(
    state: PlayerUiState,
    positionProvider: () -> Long,
    onOpen: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
) {
    // Read only inside the progress lambda: the card itself doesn't recompose on every tick.
    val position = rememberPolledPositionState(state.isPlaying, positionProvider)
    val progress = { if (state.durationMs > 0) (position.value.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f }
    when (LocalUiTokens.current.miniStyle) {
        MiniStyle.Card -> Unit
        MiniStyle.Bar -> return MiniBar(state, progress, onOpen, onPlayPause, onNext)
        MiniStyle.Pill -> return MiniPill(state, progress, onOpen, onPlayPause, onNext)
    }
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shadowElevation = 10.dp,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        ) {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 10.dp, end = 6.dp, top = 9.dp, bottom = 8.dp),
                ) {
                    val tokens = LocalUiTokens.current
                    if (tokens.motion == ArtMotion.Spin) {
                        // The small disc spins along with the big one; other motions stay still here.
                        AnimatedArtwork(state.artworkUri, state.isPlaying, Modifier.size(42.dp))
                    } else {
                        Artwork(state.artworkUri, Modifier.size(42.dp), cornerRadius = 8.dp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        // Scrolls only while playing; paused it's a calm ellipsis (and costs no frames).
                        MarqueeText(state.title, style = MaterialTheme.typography.bodyMedium, animate = state.isPlaying)
                        Text(
                            state.artist,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = onPlayPause) {
                        Icon(
                            if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (state.isPlaying) "Pause" else "Play",
                        )
                    }
                    IconButton(onClick = onNext, enabled = state.hasNext) {
                        Icon(Icons.Rounded.SkipNext, contentDescription = "Next")
                    }
                }
                LinearProgressIndicator(
                    progress = progress,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 6.dp).height(2.dp),
                    trackColor = MaterialTheme.colorScheme.outlineVariant,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
            }
        }
    }
}

@Composable
private fun MiniArt(state: PlayerUiState, size: androidx.compose.ui.unit.Dp, shape: androidx.compose.ui.graphics.Shape? = null) {
    if (LocalUiTokens.current.motion == ArtMotion.Spin) {
        AnimatedArtwork(state.artworkUri, state.isPlaying, Modifier.size(size))
    } else {
        Artwork(state.artworkUri, Modifier.size(size), cornerRadius = 8.dp, shape = shape)
    }
}

/** Washi: flat, edge to edge, a hairline progress across the very top. No card, no shadow. */
@Composable
private fun MiniBar(state: PlayerUiState, progress: () -> Float, onOpen: () -> Unit, onPlayPause: () -> Unit, onNext: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).clickable(onClick = onOpen)) {
        LinearProgressIndicator(
            progress = progress,
            modifier = Modifier.fillMaxWidth().height(1.5.dp),
            trackColor = MaterialTheme.colorScheme.outlineVariant,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        ) {
            MiniArt(state, 40.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                MarqueeText(state.title, style = MaterialTheme.typography.bodyMedium, animate = state.isPlaying)
                Text(
                    state.artist.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onPlayPause) {
                Icon(if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = if (state.isPlaying) "Pause" else "Play")
            }
            IconButton(onClick = onNext, enabled = state.hasNext) { Icon(Icons.Rounded.SkipNext, contentDescription = "Next") }
        }
    }
}

/** Bordeaux: a compact capsule; round art wrapped in a progress ring, filled play button. */
@Composable
private fun MiniPill(state: PlayerUiState, progress: () -> Float, onOpen: () -> Unit, onPlayPause: () -> Unit, onNext: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 10.dp,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(6.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = progress,
                        modifier = Modifier.size(48.dp),
                        strokeWidth = 2.dp,
                        trackColor = MaterialTheme.colorScheme.outlineVariant,
                        gapSize = 0.dp,
                    )
                    MiniArt(state, 40.dp, CircleShape)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    MarqueeText(state.title, style = MaterialTheme.typography.titleSmall, animate = state.isPlaying)
                    Text(state.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onNext, enabled = state.hasNext) { Icon(Icons.Rounded.SkipNext, contentDescription = "Next") }
                FilledIconButton(
                    onClick = onPlayPause,
                    modifier = Modifier.size(44.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Icon(if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = if (state.isPlaying) "Pause" else "Play")
                }
            }
        }
    }
}
