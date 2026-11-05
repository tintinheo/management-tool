package com.audiohub.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.audiohub.data.db.AlbumSummary
import com.audiohub.data.db.PlaylistSummary
import com.audiohub.data.db.TrackEntity
import com.audiohub.data.music.MusicScanner
import com.audiohub.playback.TRACK_ID_PREFIX
import com.audiohub.ui.Routes
import com.audiohub.ui.components.Artwork
import com.audiohub.ui.components.formatDuration

/** What to show at the start of a track row. */
enum class TrackLeading { Artwork, TrackNumber, None }

data class ExtraMenuItem(val label: String, val icon: ImageVector, val onClick: () -> Unit)

@Composable
fun TrackRow(
    track: TrackEntity,
    state: TrackListState,
    actions: TrackActions,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: TrackLeading = TrackLeading.Artwork,
    showAlbumInSubtitle: Boolean = true,
    extraMenu: List<ExtraMenuItem> = emptyList(),
    trailing: (@Composable () -> Unit)? = null,
) {
    val isCurrent = state.currentMediaId == "$TRACK_ID_PREFIX${track.id}"
    val isFavorite = track.id in state.favoriteIds
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 20.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (leading) {
            TrackLeading.Artwork -> {
                Artwork(MusicScanner.albumArtUri(track.albumId), Modifier.size(48.dp))
                Spacer(Modifier.width(14.dp))
            }
            TrackLeading.TrackNumber -> {
                Text(
                    if (track.trackNumber > 0) track.trackNumber.toString() else "–",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(32.dp),
                )
            }
            TrackLeading.None -> Unit
        }
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (showAlbumInSubtitle) "${track.artist} · ${track.album}" else track.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (isFavorite) {
            Icon(
                Icons.Rounded.Favorite,
                contentDescription = "Favorite",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 4.dp).size(16.dp),
            )
        }
        if (trailing != null) {
            trailing()
        } else {
            Text(
                formatDuration(track.durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Rounded.MoreVert, contentDescription = "More")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                val close = { menuOpen = false }
                MenuEntry("Play next", Icons.Rounded.PlayArrow, close) { actions.playNext(listOf(track)) }
                MenuEntry("Add to queue", Icons.AutoMirrored.Rounded.QueueMusic, close) { actions.addToQueue(listOf(track)) }
                MenuEntry("Add to playlist…", Icons.AutoMirrored.Rounded.PlaylistAdd, close) { actions.addToPlaylist(listOf(track)) }
                MenuEntry(
                    if (isFavorite) "Remove from favorites" else "Favorite",
                    if (isFavorite) Icons.Rounded.FavoriteBorder else Icons.Rounded.Favorite,
                    close,
                ) { actions.toggleFavorite(track) }
                HorizontalDivider()
                MenuEntry("Go to album", Icons.Rounded.Album, close) { actions.navigate(Routes.album(track.albumId)) }
                MenuEntry("Go to artist", Icons.Rounded.Person, close) { actions.navigate(Routes.artist(track.artist)) }
                extraMenu.forEach { extra -> MenuEntry(extra.label, extra.icon, close, extra.onClick) }
            }
        }
    }
}

@Composable
private fun MenuEntry(label: String, icon: ImageVector, close: () -> Unit, action: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(icon, null) },
        onClick = { close(); action() },
    )
}

@Composable
fun PlayShuffleButtons(
    tracks: List<TrackEntity>,
    actions: TrackActions,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalButton(onClick = { actions.play(tracks, 0) }, enabled = tracks.isNotEmpty(), shape = MaterialTheme.shapes.medium, modifier = Modifier.weight(1f).height(44.dp)) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Play")
        }
        FilledTonalButton(onClick = { actions.shuffle(tracks) }, enabled = tracks.isNotEmpty(), shape = MaterialTheme.shapes.medium, modifier = Modifier.weight(1f).height(44.dp)) {
            Icon(Icons.Rounded.Shuffle, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Shuffle")
        }
        trailing?.invoke()
    }
}

/** Back arrow + small title for pushed screens; [actions] go on the right. */
@Composable
fun DetailTopBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        actions()
    }
}

@Composable
fun AlbumTile(album: AlbumSummary, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.clickable(onClick = onClick).padding(6.dp)) {
        Artwork(
            MusicScanner.albumArtUri(album.albumId),
            Modifier.fillMaxWidth().aspectRatio(1f),
            cornerRadius = 4.dp,
            placeholder = Icons.Rounded.Album,
        )
        Text(
            album.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            album.artist,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** "Playlists >" style entry on the library home. */
@Composable
fun NavRow(icon: ImageVector, label: String, onClick: () -> Unit, detail: String? = null) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (detail != null) {
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun TextInputDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun AddToPlaylistDialog(
    playlists: List<PlaylistSummary>,
    onPick: (playlistId: Long) -> Unit,
    onCreate: (name: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    if (creating) {
        TextInputDialog("New playlist", "", "Create", onConfirm = onCreate, onDismiss = onDismiss)
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to playlist") },
        text = {
            LazyColumn {
                item {
                    NavRow(Icons.AutoMirrored.Rounded.PlaylistAdd, "New playlist…", onClick = { creating = true })
                }
                items(playlists, key = { it.id }) { p ->
                    NavRow(
                        Icons.AutoMirrored.Rounded.QueueMusic,
                        p.name,
                        onClick = { onPick(p.id) },
                        detail = p.trackCount.toString(),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
