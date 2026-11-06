package com.audiohub.ui.nowplaying

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Forward30
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.IconButtonDefaults
import com.audiohub.ui.components.ThinSeekBar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.audiohub.data.lyrics.Lyrics
import com.audiohub.playback.PlayerControls
import com.audiohub.playback.PlayerUiState
import com.audiohub.ui.components.Artwork
import com.audiohub.ui.components.formatDuration
import kotlinx.coroutines.delay

/** Position isn't pushed by Media3, so poll it while playing (and once when paused). */
@Composable
fun rememberPolledPosition(isPlaying: Boolean, provider: () -> Long, intervalMs: Long = 500): Long =
    rememberPolledPositionState(isPlaying, provider, intervalMs).value

/**
 * Same as [rememberPolledPosition] but returns the State itself, so callers can read it inside a
 * draw/layout lambda (e.g. a progress bar) and avoid recomposing everything around it twice a second.
 */
@Composable
fun rememberPolledPositionState(isPlaying: Boolean, provider: () -> Long, intervalMs: Long = 500): androidx.compose.runtime.State<Long> {
    val position = remember { mutableLongStateOf(provider()) }
    LaunchedEffect(isPlaying, intervalMs) {
        position.longValue = provider()
        while (isPlaying) {
            delay(intervalMs)
            position.longValue = provider()
        }
    }
    return position
}

/** What fills the square above the controls. The queue has its own full screen ([QueueScreen]). */
enum class NowPlayingPanel { Artwork, Lyrics }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingSheet(
    state: PlayerUiState,
    player: PlayerControls,
    isFavorite: Boolean,
    canFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    lyrics: Lyrics?,
    isPodcast: Boolean,
    podcastSpeed: Float,
    onCycleSpeed: () -> Unit,
    showNotes: String?,
    onOpenQueue: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var panel by rememberSaveable { mutableStateOf(NowPlayingPanel.Artwork) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
        dragHandle = {
            Box(
                Modifier.padding(top = 10.dp, bottom = 6.dp).size(width = 36.dp, height = 4.dp)
                    .clip(CircleShape).background(MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
            )
        },
    ) {
        Box {
            // Soft, blurred artwork glow behind everything — the "gallery" backdrop.
            ArtworkBackdrop(state.artworkUri, Modifier.matchParentSize())
            NowPlayingContent(
                state, player, isFavorite, canFavorite, onToggleFavorite, lyrics, panel, { panel = it },
                isPodcast = isPodcast, podcastSpeed = podcastSpeed, onCycleSpeed = onCycleSpeed, showNotes = showNotes,
                onOpenQueue = onOpenQueue,
            )
        }
    }
}

@Composable
private fun ArtworkBackdrop(uri: android.net.Uri?, modifier: Modifier) {
    if (uri == null) return
    val bg = MaterialTheme.colorScheme.background
    Box(modifier) {
        coil3.compose.AsyncImage(
            model = uri,
            contentDescription = null,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            alpha = 0.45f,
            modifier = Modifier.fillMaxSize().blur(80.dp),
        )
        Box(
            Modifier.fillMaxSize().background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    0f to bg.copy(alpha = 0.2f), 0.55f to bg.copy(alpha = 0.75f), 1f to bg,
                ),
            ),
        )
    }
}

@Composable
fun NowPlayingContent(
    state: PlayerUiState,
    player: PlayerControls,
    isFavorite: Boolean = false,
    canFavorite: Boolean = true,
    onToggleFavorite: () -> Unit = {},
    lyrics: Lyrics? = null,
    panel: NowPlayingPanel = NowPlayingPanel.Artwork,
    onPanelChange: (NowPlayingPanel) -> Unit = {},
    isPodcast: Boolean = false,
    podcastSpeed: Float = 1f,
    onCycleSpeed: () -> Unit = {},
    showNotes: String? = null,
    onOpenQueue: () -> Unit = {},
) {
    val position = rememberPolledPosition(
        state.isPlaying,
        { player.currentPositionMs },
        intervalMs = if (panel == NowPlayingPanel.Lyrics) 200 else 500,
    )
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val duration = state.durationMs.coerceAtLeast(1L)
    val shown = if (dragging) (dragValue * duration).toLong() else position

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp)
            .navigationBarsPadding()
            .padding(bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).padding(top = 8.dp)) {
            when (panel) {
                NowPlayingPanel.Artwork -> Artwork(
                    state.artworkUri,
                    Modifier.fillMaxSize().shadow(28.dp, MaterialTheme.shapes.medium, spotColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.35f)),
                    cornerRadius = 12.dp,
                    placeholder = if (isPodcast) Icons.Rounded.Podcasts else Icons.Rounded.MusicNote,
                )
                NowPlayingPanel.Lyrics ->
                    if (isPodcast) NotesPanel(showNotes) else LyricsPanel(lyrics, position, onSeek = player::seekTo)
            }
        }
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(state.title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(state.artist, state.album).filter { it.isNotBlank() }.joinToString(" — "),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (canFavorite) {
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        contentDescription = if (isFavorite) "Remove from favorites" else "Favorite",
                        tint = if (isFavorite) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        ThinSeekBar(
            value = (shown.toFloat() / duration).coerceIn(0f, 1f),
            onValueChange = { dragging = true; dragValue = it },
            onValueChangeFinished = {
                player.seekTo((dragValue * duration).toLong())
                dragging = false
            },
            enabled = state.durationMs > 0,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val timeColor = MaterialTheme.colorScheme.onSurfaceVariant
            Text(formatDuration(shown), style = MaterialTheme.typography.labelSmall, color = timeColor)
            Text("-" + formatDuration(state.durationMs - shown), style = MaterialTheme.typography.labelSmall, color = timeColor)
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isPodcast) {
                TextButton(onClick = onCycleSpeed) {
                    Text(speedLabel(podcastSpeed), style = MaterialTheme.typography.titleSmall)
                }
                IconButton(onClick = { player.seekBy(-15_000) }, modifier = Modifier.size(56.dp)) {
                    SkipIcon(Icons.Rounded.Replay, "15", "Back 15 seconds")
                }
            } else {
                IconButton(onClick = player::toggleShuffle) {
                    Icon(
                        Icons.Rounded.Shuffle,
                        contentDescription = "Shuffle",
                        tint = if (state.shuffleEnabled) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                    )
                }
                IconButton(onClick = player::skipPrevious, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(36.dp))
                }
            }
            FilledIconButton(
                onClick = player::togglePlayPause,
                modifier = Modifier.size(68.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.onSurface,
                    contentColor = MaterialTheme.colorScheme.surface,
                ),
            ) {
                Icon(
                    if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (state.isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(36.dp),
                )
            }
            if (isPodcast) {
                IconButton(onClick = { player.seekBy(30_000) }, modifier = Modifier.size(56.dp)) {
                    SkipIcon(Icons.Rounded.Forward30, null, "Forward 30 seconds")
                }
                IconButton(onClick = player::skipNext, enabled = state.hasNext) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = "Next")
                }
            } else {
                IconButton(onClick = player::skipNext, enabled = state.hasNext, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = "Next", modifier = Modifier.size(36.dp))
                }
                IconButton(onClick = player::cycleRepeat) {
                    Icon(
                        if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                        contentDescription = "Repeat",
                        tint = if (state.repeatMode != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary
                        else LocalContentColor.current,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            PanelToggle(
                if (isPodcast) Icons.AutoMirrored.Rounded.Notes else Icons.Rounded.Lyrics,
                if (isPodcast) "Show notes" else "Lyrics",
                panel == NowPlayingPanel.Lyrics,
            ) {
                onPanelChange(if (it) NowPlayingPanel.Lyrics else NowPlayingPanel.Artwork)
            }
            SleepTimerButton(state, player)
            IconButton(onClick = onOpenQueue) {
                Icon(
                    Icons.AutoMirrored.Rounded.QueueMusic,
                    contentDescription = "Playing queue",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ------------------------------------------------------------------ Sleep timer

/** "20" → 20, "1:30" → 90 (hours:minutes); null if invalid or outside 1 min … 24 h. */
fun parseSleepMinutes(text: String): Long? {
    val t = text.trim()
    if (t.isEmpty()) return null
    val minutes = if (':' in t) {
        val (h, m) = t.split(':', limit = 2).map { it.toLongOrNull() ?: return null }
        if (m >= 60) return null
        h * 60 + m
    } else {
        t.toLongOrNull() ?: return null
    }
    return minutes.takeIf { it in 1..24 * 60 }
}

@Composable
private fun SleepTimerButton(state: PlayerUiState, player: PlayerControls) {
    var open by remember { mutableStateOf(false) }
    val tint = if (state.sleepActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    // Re-render once a minute so the remaining time stays fresh.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.sleepEndsAt) {
        while (state.sleepEndsAt > 0) {
            now = System.currentTimeMillis()
            delay(15_000)
        }
    }
    Row(
        Modifier.clip(CircleShape).clickable { open = true }.padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Bedtime, contentDescription = "Sleep timer", tint = tint)
        val label = when {
            state.sleepEndOfItem -> "End"
            state.sleepEndsAt > 0 -> "${((state.sleepEndsAt - now + 59_999) / 60_000).coerceAtLeast(1)}m"
            else -> null
        }
        if (label != null) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = tint, modifier = Modifier.padding(start = 4.dp))
        }
    }
    if (open) {
        SleepTimerDialog(
            active = state.sleepActive,
            onPick = { player.setSleepTimer(it); open = false },
            onDismiss = { open = false },
        )
    }
}

@Composable
fun SleepTimerDialog(active: Boolean, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        SleepTimerPanel(active, onPick, onDismiss)
    }
}

/** Body of the sleep timer dialog (separate so it can be previewed without a dialog window). */
@Composable
fun SleepTimerPanel(active: Boolean, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    val options = listOf(15, 30, 45, 60).map { "$it minutes" to it * 60_000L } +
        ("End of track / episode" to com.audiohub.playback.PlaybackService.SLEEP_END_OF_ITEM)
    var custom by remember { mutableStateOf("") }
    val customMinutes = parseSleepMinutes(custom)
    run {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 12.dp)) {
                Text("Sleep timer", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(16.dp))
                // Any length: "20", "90", "1:30" (h:mm).
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.OutlinedTextField(
                        value = custom,
                        onValueChange = { v -> custom = v.filter { it.isDigit() || it == ':' }.take(5) },
                        label = { Text("Minutes") },
                        placeholder = { Text("e.g. 20 or 1:30") },
                        singleLine = true,
                        isError = custom.isNotEmpty() && customMinutes == null,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                            imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                        ),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                            onDone = { customMinutes?.let { onPick(it * 60_000L) } },
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { customMinutes?.let { onPick(it * 60_000L) } },
                        enabled = customMinutes != null,
                        modifier = Modifier.padding(start = 8.dp),
                    ) { Text("Start") }
                }
                Spacer(Modifier.height(8.dp))
                options.forEach { (label, ms) ->
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { onPick(ms) }.padding(vertical = 12.dp, horizontal = 4.dp),
                    )
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    if (active) TextButton(onClick = { onPick(0) }) { Text("Turn off") }
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
            }
        }
    }
}

@Composable
private fun PanelToggle(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    IconToggleButton(checked = checked, onCheckedChange = onCheckedChange) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ------------------------------------------------------------------ Podcast helpers

fun speedLabel(speed: Float): String =
    (if (speed % 1f == 0f) "%.0f" else if ((speed * 10) % 1f == 0f) "%.1f" else "%.2f").format(speed) + "×"

/** Replay/forward icon with the seconds drawn in the middle (Material has no "replay 15"). */
@Composable
private fun SkipIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, seconds: String?, description: String) {
    Box(contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(34.dp))
        if (seconds != null) {
            Text(seconds, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

@Composable
private fun NotesPanel(html: String?) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        val text = remember(html) { html?.takeIf { it.isNotBlank() }?.let { AnnotatedString.fromHtml(it) } }
        if (text == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No show notes", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            )
        }
    }
}

// ------------------------------------------------------------------ Lyrics

@Composable
private fun LyricsPanel(lyrics: Lyrics?, positionMs: Long, onSeek: (Long) -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize(),
    ) {
        when (lyrics) {
            null -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    "No lyrics in this file.\nAdd them to the song's tags (USLT / LYRICS) to see them here.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            is Lyrics.Plain -> Text(
                lyrics.text,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            )
            is Lyrics.Synced -> SyncedLyrics(lyrics, positionMs, onSeek)
        }
    }
}

@Composable
private fun SyncedLyrics(lyrics: Lyrics.Synced, positionMs: Long, onSeek: (Long) -> Unit) {
    val current = lyrics.indexAt(positionMs)
    val listState = rememberLazyListState()
    LaunchedEffect(current) {
        if (current >= 0) listState.animateScrollToItem((current - 2).coerceAtLeast(0))
    }
    LazyColumn(state = listState, contentPadding = PaddingValues(vertical = 24.dp, horizontal = 20.dp)) {
        itemsIndexed(lyrics.lines) { index, line ->
            val active = index == current
            Text(
                line.text.ifEmpty { "♪" },
                style = if (active) MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                else MaterialTheme.typography.titleMedium,
                color = if (active) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxWidth().clickable { onSeek(line.timeMs) }.padding(vertical = 6.dp),
            )
        }
    }
}
