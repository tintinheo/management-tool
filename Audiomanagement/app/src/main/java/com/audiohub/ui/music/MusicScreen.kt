package com.audiohub.ui.music

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.audiohub.appContainer
import com.audiohub.data.db.TrackEntity
import com.audiohub.data.music.MusicScanner
import com.audiohub.playback.TRACK_ID_PREFIX
import com.audiohub.system.AppPermissions
import com.audiohub.system.rememberPermission
import com.audiohub.ui.components.Artwork
import com.audiohub.ui.components.EmptyState
import com.audiohub.ui.components.ScreenHeader
import com.audiohub.ui.components.formatDuration

@Composable
fun MusicScreen() {
    val container = LocalContext.current.appContainer
    val vm: MusicViewModel = viewModel(
        factory = viewModelFactory {
            initializer { MusicViewModel(container.musicRepository, container.playerConnection) }
        },
    )
    val permission = rememberPermission(AppPermissions.AUDIO)

    if (!permission.granted) {
        EmptyState(
            icon = Icons.Rounded.Lock,
            title = "Allow access to music",
            message = "My Audio Hub needs permission to read the audio files in your music folder.",
            action = { Button(onClick = permission.request) { Text("Allow") } },
        )
        return
    }
    LaunchedEffect(Unit) { vm.onPermissionGranted() }

    val tracks by vm.tracks.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val playerState by container.playerConnection.state.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)

    MusicContent(
        tracks = tracks,
        scanning = scanning,
        currentMediaId = playerState.mediaId,
        musicFolder = settings?.musicFolder.orEmpty(),
        onPlay = vm::play,
        onShuffleAll = vm::shuffleAll,
        onRescan = vm::rescan,
        onPlayNext = vm::playNext,
        onAddToQueue = vm::addToQueue,
    )
}

/** Stateless music library UI. [tracks] == null means "still loading". */
@Composable
fun MusicContent(
    tracks: List<TrackEntity>?,
    scanning: Boolean,
    currentMediaId: String?,
    musicFolder: String,
    onPlay: (index: Int) -> Unit,
    onShuffleAll: () -> Unit,
    onRescan: () -> Unit,
    onPlayNext: (TrackEntity) -> Unit,
    onAddToQueue: (TrackEntity) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            "Music",
            subtitle = tracks?.let { "${it.size} songs" },
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FilledTonalButton(
                onClick = { onPlay(0) },
                enabled = !tracks.isNullOrEmpty(),
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Play")
            }
            FilledTonalButton(
                onClick = onShuffleAll,
                enabled = !tracks.isNullOrEmpty(),
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Rounded.Shuffle, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Shuffle")
            }
            IconButton(onClick = onRescan, enabled = !scanning) {
                Icon(Icons.Rounded.Refresh, contentDescription = "Rescan folder")
            }
        }
        if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp))

        val list = tracks
        when {
            list == null -> Unit
            list.isEmpty() -> EmptyState(
                icon = Icons.Rounded.Folder,
                title = "No music yet",
                message = "Copy MP3/FLAC/M4A files into\n/$musicFolder\non your phone, then tap refresh.",
            )
            else -> LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(list, key = { _, t -> t.id }) { index, track ->
                    TrackRow(
                        track = track,
                        isCurrent = currentMediaId == "$TRACK_ID_PREFIX${track.id}",
                        onClick = { onPlay(index) },
                        onPlayNext = { onPlayNext(track) },
                        onAddToQueue = { onAddToQueue(track) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackRow(
    track: TrackEntity,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 20.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(MusicScanner.albumArtUri(track.albumId), Modifier.size(48.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${track.artist} · ${track.album}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            formatDuration(track.durationMs),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Rounded.MoreVert, contentDescription = "More")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Play next") },
                    leadingIcon = { Icon(Icons.Rounded.PlayArrow, null) },
                    onClick = { menuOpen = false; onPlayNext() },
                )
                DropdownMenuItem(
                    text = { Text("Add to queue") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null) },
                    onClick = { menuOpen = false; onAddToQueue() },
                )
            }
        }
    }
}
