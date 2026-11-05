package com.audiohub.ui.library

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlaylistRemove
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.audiohub.data.db.PlaylistSummary
import com.audiohub.data.db.PlaylistTrack
import com.audiohub.data.music.MusicScanner
import com.audiohub.ui.Routes
import com.audiohub.ui.components.Artwork
import com.audiohub.ui.components.EmptyState
import com.audiohub.ui.components.ScreenHeader
import com.audiohub.ui.components.plural
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

// ------------------------------------------------------------------ Playlist list

@Composable
fun PlaylistsScreen() {
    val vm = libraryViewModel()
    val context = LocalContext.current
    val playlists by vm.playlistSummaries.collectAsStateWithLifecycle()
    val actions = LocalTrackActions.current
    var creating by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = displayName(context, uri)?.substringBeforeLast('.') ?: "Imported playlist"
        vm.importPlaylist(uri, name) { result ->
            result.onSuccess {
                toast(context, "Imported \"${it.name}\": ${it.matched} of ${it.total} songs found")
                actions.navigate(Routes.playlist(it.playlistId))
            }.onFailure { toast(context, "Import failed: ${it.message}") }
        }
    }

    PlaylistsContent(
        playlists = playlists,
        actions = actions,
        onNew = { creating = true },
        onImport = { importLauncher.launch(arrayOf("audio/x-mpegurl", "audio/mpegurl", "application/vnd.apple.mpegurl", "*/*")) },
    )

    if (creating) {
        TextInputDialog(
            title = "New playlist",
            initial = "",
            confirmLabel = "Create",
            onConfirm = { name ->
                creating = false
                vm.createPlaylist(name) { id -> actions.navigate(Routes.playlist(id)) }
            },
            onDismiss = { creating = false },
        )
    }
}

@Composable
fun PlaylistsContent(
    playlists: List<PlaylistSummary>,
    actions: TrackActions,
    onNew: () -> Unit,
    onImport: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            DetailTopBar("", onBack = actions::back) {
                IconButton(onClick = onImport) { Icon(Icons.Rounded.FileDownload, contentDescription = "Import .m3u") }
            }
        }
        item { ScreenHeader("Playlists", subtitle = plural(playlists.size, "playlist")) }
        item { NavRow(Icons.Rounded.Add, "New Playlist…", onClick = onNew) }
        if (playlists.isEmpty()) {
            item {
                EmptyState(
                    Icons.AutoMirrored.Rounded.QueueMusic,
                    "No playlists yet",
                    "Create one, or import an .m3u/.m3u8 file exported from another player.",
                )
            }
        }
        items(playlists, key = { it.id }) { p ->
            Row(
                Modifier.fillMaxWidth().clickable { actions.navigate(Routes.playlist(p.id)) }
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(
                    p.coverAlbumId?.let(MusicScanner::albumArtUri),
                    Modifier.size(56.dp),
                    placeholder = Icons.AutoMirrored.Rounded.QueueMusic,
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        plural(p.trackCount, "song"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Playlist detail

@Composable
fun PlaylistDetailScreen(playlistId: Long) {
    val vm = libraryViewModel()
    val context = LocalContext.current
    val actions = LocalTrackActions.current
    val playlist by remember(playlistId) { vm.playlist(playlistId) }.collectAsStateWithLifecycle(initialValue = null)
    val tracks by remember(playlistId) { vm.playlistTracks(playlistId) }.collectAsStateWithLifecycle(initialValue = null)
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/x-mpegurl")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        vm.exportPlaylist(playlistId, uri) { result ->
            result.onSuccess { toast(context, "Playlist exported") }
                .onFailure { toast(context, "Export failed: ${it.message}") }
        }
    }

    val name = playlist?.name.orEmpty()
    PlaylistDetailContent(
        name = name,
        tracks = tracks,
        state = rememberTrackListState(),
        actions = actions,
        onMove = { from, to -> vm.movePlaylistEntry(playlistId, from, to) },
        onRemove = { entryId -> vm.removeFromPlaylist(playlistId, entryId) },
        onRename = { renaming = true },
        onDelete = { confirmDelete = true },
        onExport = { exportLauncher.launch("$name.m3u8") },
    )

    if (renaming) {
        TextInputDialog(
            title = "Rename playlist",
            initial = name,
            confirmLabel = "Rename",
            onConfirm = { vm.renamePlaylist(playlistId, it); renaming = false },
            onDismiss = { renaming = false },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete \"$name\"?") },
            text = { Text("The songs stay in your library; only the playlist is removed.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.deletePlaylist(playlistId)
                    actions.back()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun PlaylistDetailContent(
    name: String,
    tracks: List<PlaylistTrack>?,
    state: TrackListState,
    actions: TrackActions,
    onMove: (from: Int, to: Int) -> Unit,
    onRemove: (entryId: Long) -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
) {
    // Local copy so drag feedback is instant; resynced whenever the database emits.
    var items by remember(tracks) { mutableStateOf(tracks.orEmpty()) }
    var dragFrom by remember { mutableIntStateOf(-1) }
    var menuOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val a = items.indexOfFirst { it.entryId == from.key }
        val b = items.indexOfFirst { it.entryId == to.key }
        if (a >= 0 && b >= 0) items = items.toMutableList().apply { add(b, removeAt(a)) }
    }
    val plainTracks = items.map { it.track }

    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        item(key = "top") {
            DetailTopBar("", onBack = actions::back) {
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "Playlist options") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                            onClick = { menuOpen = false; onRename() },
                        )
                        DropdownMenuItem(
                            text = { Text("Export .m3u8") },
                            leadingIcon = { Icon(Icons.Rounded.FileUpload, null) },
                            onClick = { menuOpen = false; onExport() },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete playlist") },
                            leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                            onClick = { menuOpen = false; onDelete() },
                        )
                    }
                }
            }
        }
        item(key = "header") { ScreenHeader(name, subtitle = tracks?.let { plural(it.size, "song") }) }
        item(key = "buttons") { PlayShuffleButtons(plainTracks, actions) }
        if (tracks != null && tracks.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    Icons.AutoMirrored.Rounded.QueueMusic,
                    "This playlist is empty",
                    "Add songs with \"Add to playlist…\" from any song's menu.",
                )
            }
        }
        itemsIndexed(items, key = { _, e -> e.entryId }) { index, entry ->
            ReorderableItem(reorderState, key = entry.entryId) { isDragging ->
                Surface(tonalElevation = if (isDragging) 6.dp else 0.dp) {
                    TrackRow(
                        entry.track, state, actions,
                        onClick = { actions.play(plainTracks, index) },
                        extraMenu = listOf(
                            ExtraMenuItem("Remove from playlist", Icons.Rounded.PlaylistRemove) { onRemove(entry.entryId) },
                        ),
                        trailing = {
                            Icon(
                                Icons.Rounded.DragHandle,
                                contentDescription = "Reorder",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.draggableHandle(
                                    onDragStarted = { dragFrom = tracks.orEmpty().indexOfFirst { it.entryId == entry.entryId } },
                                    onDragStopped = {
                                        val to = items.indexOfFirst { it.entryId == entry.entryId }
                                        if (dragFrom >= 0 && to >= 0 && dragFrom != to) onMove(dragFrom, to)
                                        dragFrom = -1
                                    },
                                ).padding(8.dp),
                            )
                        },
                    )
                }
            }
        }
    }
}

private fun displayName(context: Context, uri: Uri): String? =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

private fun toast(context: Context, message: String) =
    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
