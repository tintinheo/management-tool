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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragHandle
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.audiohub.data.lyrics.Lyrics
import com.audiohub.playback.PlayerControls
import com.audiohub.playback.PlayerUiState
import com.audiohub.playback.QueueItem
import com.audiohub.ui.components.Artwork
import com.audiohub.ui.components.formatDuration
import kotlinx.coroutines.delay
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** Position isn't pushed by Media3, so poll it while playing (and once when paused). */
@Composable
fun rememberPolledPosition(isPlaying: Boolean, provider: () -> Long, intervalMs: Long = 500): Long {
    var position by remember { mutableLongStateOf(provider()) }
    LaunchedEffect(isPlaying, intervalMs) {
        position = provider()
        while (isPlaying) {
            delay(intervalMs)
            position = provider()
        }
    }
    return position
}

enum class NowPlayingPanel { Artwork, Lyrics, Queue }

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
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var panel by rememberSaveable { mutableStateOf(NowPlayingPanel.Artwork) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        NowPlayingContent(
            state, player, isFavorite, canFavorite, onToggleFavorite, lyrics, panel, { panel = it },
            isPodcast = isPodcast, podcastSpeed = podcastSpeed, onCycleSpeed = onCycleSpeed, showNotes = showNotes,
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
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            when (panel) {
                NowPlayingPanel.Artwork -> Artwork(
                    state.artworkUri,
                    Modifier.fillMaxSize(),
                    cornerRadius = 8.dp,
                    placeholder = if (isPodcast) Icons.Rounded.Podcasts else Icons.Rounded.MusicNote,
                )
                NowPlayingPanel.Lyrics ->
                    if (isPodcast) NotesPanel(showNotes) else LyricsPanel(lyrics, position, onSeek = player::seekTo)
                NowPlayingPanel.Queue -> QueuePanel(state, player)
            }
        }
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(state.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            PanelToggle(Icons.AutoMirrored.Rounded.QueueMusic, "Up Next", panel == NowPlayingPanel.Queue) {
                onPanelChange(if (it) NowPlayingPanel.Queue else NowPlayingPanel.Artwork)
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

// ------------------------------------------------------------------ Queue

@Composable
private fun QueuePanel(state: PlayerUiState, player: PlayerControls) {
    var items by remember(state.upNext) { mutableStateOf(state.upNext) }
    var dragFrom by remember { mutableIntStateOf(-1) }
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val a = items.indexOfFirst { it.index == from.key }
        val b = items.indexOfFirst { it.index == to.key }
        if (a >= 0 && b >= 0) items = items.toMutableList().apply { add(b, removeAt(a)) }
    }
    // Reordering only makes sense in list order; with shuffle on, "next" is the shuffle order.
    val canReorder = !state.shuffleEnabled

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Nothing up next", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Surface
        }
        LazyColumn(state = listState, contentPadding = PaddingValues(vertical = 8.dp)) {
            item(key = "title") {
                Text(
                    if (state.shuffleEnabled) "Up Next · Shuffle" else "Up Next",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            itemsIndexed(items, key = { _, q -> q.index }) { pos, q ->
                ReorderableItem(reorderState, key = q.index, enabled = canReorder) { isDragging ->
                    Surface(color = if (isDragging) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.background) {
                        QueueRow(
                            q,
                            onClick = { player.jumpTo(q.index) },
                            onRemove = { player.removeAt(q.index) },
                            handle = {
                                if (canReorder) {
                                    Icon(
                                        Icons.Rounded.DragHandle,
                                        contentDescription = "Reorder",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.draggableHandle(
                                            onDragStarted = { dragFrom = state.upNext.indexOfFirst { it.index == q.index } },
                                            onDragStopped = {
                                                val newPos = items.indexOfFirst { it.index == q.index }
                                                if (dragFrom >= 0 && newPos >= 0 && dragFrom != newPos) {
                                                    player.move(q.index, state.upNext[newPos].index)
                                                }
                                                dragFrom = -1
                                            },
                                        ).padding(8.dp),
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueRow(q: QueueItem, onClick: () -> Unit, onRemove: () -> Unit, handle: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(q.artworkUri, Modifier.size(40.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(q.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                q.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onRemove) { Icon(Icons.Rounded.Close, contentDescription = "Remove from queue") }
        handle()
    }
}
