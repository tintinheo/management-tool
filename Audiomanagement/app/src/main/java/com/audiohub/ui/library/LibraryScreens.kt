package com.audiohub.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.audiohub.appContainer
import com.audiohub.data.db.AlbumSummary
import com.audiohub.data.db.ArtistSummary
import com.audiohub.data.db.GenreSummary
import com.audiohub.data.db.TrackEntity
import com.audiohub.data.music.MusicScanner
import com.audiohub.system.AppPermissions
import com.audiohub.system.rememberPermission
import com.audiohub.ui.Routes
import com.audiohub.ui.components.Artwork
import com.audiohub.ui.components.EmptyState
import com.audiohub.ui.components.Hairline
import com.audiohub.ui.components.ScreenHeader
import com.audiohub.ui.components.SortButton
import com.audiohub.data.SortOrder
import com.audiohub.data.TrackSorter
import com.audiohub.playback.LibraryTree
import com.audiohub.ui.components.SectionLabel
import com.audiohub.ui.components.plural
import com.audiohub.ui.components.formatDuration

/** Favorites + now-playing id, needed by every track list. */
@Composable
fun rememberTrackListState(): TrackListState {
    val vm = libraryViewModel()
    val favorites by vm.favoriteIds.collectAsStateWithLifecycle()
    val player by LocalContext.current.appContainer.playerConnection.state.collectAsStateWithLifecycle()
    return TrackListState(favorites, player.mediaId)
}

// ------------------------------------------------------------------ Library home

@Composable
fun LibraryHomeScreen() {
    val vm = libraryViewModel()
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

    val container = LocalContext.current.appContainer
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val recent by vm.recentlyAddedAlbums.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)

    LibraryHomeContent(
        trackCount = tracks?.size,
        scanning = scanning,
        musicFolder = settings?.musicFolder.orEmpty(),
        recentlyAdded = recent,
        actions = LocalTrackActions.current,
        onRescan = vm::rescan,
    )
}

@Composable
fun LibraryHomeContent(
    trackCount: Int?,
    scanning: Boolean,
    musicFolder: String,
    recentlyAdded: List<AlbumSummary>,
    actions: TrackActions,
    onRescan: () -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(bottom = 16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ScreenHeader("Library", Modifier.weight(1f), subtitle = trackCount?.let { plural(it, "song") })
                IconButton(onClick = onRescan, enabled = !scanning, modifier = Modifier.padding(end = 8.dp)) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Rescan folder")
                }
            }
        }
        if (scanning) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp))
            }
        }
        if (trackCount == 0) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyState(
                    icon = Icons.Rounded.Folder,
                    title = "No music yet",
                    message = "Copy MP3/FLAC/M4A files into\n/$musicFolder\non your phone, then tap refresh.",
                    modifier = Modifier.height(420.dp),
                )
            }
            return@LazyVerticalGrid
        }
        val rows = listOf(
            Triple(Icons.AutoMirrored.Rounded.QueueMusic, "Playlists", Routes.PLAYLISTS),
            Triple(Icons.Rounded.Person, "Artists", Routes.ARTISTS),
            Triple(Icons.Rounded.Album, "Albums", Routes.ALBUMS),
            Triple(Icons.Rounded.MusicNote, "Songs", Routes.SONGS),
            Triple(Icons.Rounded.Category, "Genres", Routes.GENRES),
            Triple(Icons.Rounded.Favorite, "Favorites", Routes.FAVORITES),
            Triple(Icons.Rounded.Star, "Most Played", Routes.MOST_PLAYED),
            Triple(Icons.Rounded.History, "Recently Played", Routes.RECENTLY_PLAYED),
        )
        rows.forEach { (icon, label, route) ->
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    NavRow(icon, label, onClick = { actions.navigate(route) })
                    Hairline(start = 56.dp)
                }
            }
        }
        if (recentlyAdded.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SectionLabel("Recently Added")
            }
            items(recentlyAdded, key = { it.albumId }) { album ->
                AlbumTile(
                    album,
                    onClick = { actions.navigate(Routes.album(album.albumId)) },
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }
    }
}

// ------------------------------------------------------------------ Generic track list

@Composable
fun TrackListContent(
    title: String,
    tracks: List<TrackEntity>?,
    state: TrackListState,
    actions: TrackActions,
    emptyIcon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Rounded.MusicNote,
    emptyTitle: String = "Nothing here yet",
    emptyMessage: String = "",
    subtitle: String? = tracks?.let { plural(it.size, "song") },
    /** When set, the list shows a sort control; the caller passes [tracks] already sorted. */
    sort: SortOrder? = null,
    onSortChange: (SortOrder) -> Unit = {},
) {
    Column(Modifier.fillMaxSize()) {
        DetailTopBar("", onBack = actions::back)
        ScreenHeader(title, subtitle = subtitle)
        if (sort != null && !tracks.isNullOrEmpty()) {
            SortButton(sort, onSortChange, Modifier.padding(start = 12.dp, bottom = 4.dp))
        }
        when {
            tracks == null -> Unit
            tracks.isEmpty() -> EmptyState(emptyIcon, emptyTitle, emptyMessage)
            else -> LazyColumn(Modifier.fillMaxSize()) {
                item { PlayShuffleButtons(tracks, actions) }
                itemsIndexed(tracks, key = { i, t -> "$i:${t.id}" }) { index, track ->
                    TrackRow(track, state, actions, onClick = { actions.play(tracks, index) })
                }
            }
        }
    }
}

@Composable
fun SongsScreen() {
    val vm = libraryViewModel()
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    val sort by vm.songSort.collectAsStateWithLifecycle()
    val sorted = remember(tracks, sort) { tracks?.let { TrackSorter.sort(it, sort) } }
    TrackListContent("Songs", sorted, rememberTrackListState(), LocalTrackActions.current, sort = sort, onSortChange = vm::setSongSort)
}

/** A folder of the music library as an automatic playlist (sortable). */
@Composable
fun FolderDetailScreen(path: String) {
    val vm = libraryViewModel()
    val root by vm.musicRoot.collectAsStateWithLifecycle()
    val tracks by remember(path) { vm.folderTracks(path) }.collectAsStateWithLifecycle(initialValue = null)
    val sort by vm.songSort.collectAsStateWithLifecycle()
    val sorted = remember(tracks, sort) { tracks?.let { TrackSorter.sort(it, sort) } }
    TrackListContent(
        LibraryTree.folderDisplayName(path, root), sorted, rememberTrackListState(), LocalTrackActions.current,
        emptyIcon = Icons.Rounded.Folder,
        emptyTitle = "Folder is empty",
        sort = sort,
        onSortChange = vm::setSongSort,
    )
}

@Composable
fun FavoritesScreen() {
    val vm = libraryViewModel()
    val tracks by remember { vm.favorites() }.collectAsStateWithLifecycle(initialValue = null)
    TrackListContent(
        "Favorites", tracks, rememberTrackListState(), LocalTrackActions.current,
        emptyIcon = Icons.Rounded.Favorite,
        emptyTitle = "No favorites yet",
        emptyMessage = "Tap ♥ on the Now Playing screen or choose Favorite from a song's menu.",
    )
}

@Composable
fun MostPlayedScreen() {
    val vm = libraryViewModel()
    val tracks by remember { vm.mostPlayed() }.collectAsStateWithLifecycle(initialValue = null)
    TrackListContent(
        "Most Played", tracks, rememberTrackListState(), LocalTrackActions.current,
        emptyIcon = Icons.Rounded.Star,
        emptyTitle = "Nothing played yet",
    )
}

@Composable
fun RecentlyPlayedScreen() {
    val vm = libraryViewModel()
    val tracks by remember { vm.recentlyPlayed() }.collectAsStateWithLifecycle(initialValue = null)
    TrackListContent(
        "Recently Played", tracks, rememberTrackListState(), LocalTrackActions.current,
        emptyIcon = Icons.Rounded.History,
        emptyTitle = "Nothing played yet",
    )
}

@Composable
fun GenreDetailScreen(name: String) {
    val vm = libraryViewModel()
    val tracks by remember(name) { vm.genreTracks(name) }.collectAsStateWithLifecycle(initialValue = null)
    TrackListContent(name, tracks, rememberTrackListState(), LocalTrackActions.current)
}

// ------------------------------------------------------------------ Albums

@Composable
fun AlbumsScreen() {
    val vm = libraryViewModel()
    val albums by vm.albums.collectAsStateWithLifecycle()
    AlbumsContent(albums, LocalTrackActions.current)
}

@Composable
fun AlbumsContent(albums: List<AlbumSummary>, actions: TrackActions) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                DetailTopBar("", onBack = actions::back)
                ScreenHeader("Albums", subtitle = plural(albums.size, "album"))
            }
        }
        items(albums, key = { it.albumId }) { album ->
            AlbumTile(album, onClick = { actions.navigate(Routes.album(album.albumId)) })
        }
    }
}

@Composable
fun AlbumDetailScreen(albumId: Long) {
    val vm = libraryViewModel()
    val album by remember(albumId) { vm.album(albumId) }.collectAsStateWithLifecycle(initialValue = null)
    val tracks by remember(albumId) { vm.albumTracks(albumId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    AlbumDetailContent(album, tracks, rememberTrackListState(), LocalTrackActions.current)
}

@Composable
fun AlbumDetailContent(
    album: AlbumSummary?,
    tracks: List<TrackEntity>,
    state: TrackListState,
    actions: TrackActions,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        item { DetailTopBar("", onBack = actions::back) }
        item {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 56.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Artwork(
                    album?.let { MusicScanner.albumArtUri(it.albumId) },
                    Modifier.size(260.dp),
                    cornerRadius = 10.dp,
                    placeholder = Icons.Rounded.Album,
                )
                Spacer(Modifier.height(16.dp))
                Text(album?.title.orEmpty(), style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    album?.artist.orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(enabled = album != null) { album?.let { actions.navigate(Routes.artist(it.artist)) } },
                )
                val total = tracks.sumOf { it.durationMs }
                Text(
                    listOfNotNull(
                        album?.year?.takeIf { it > 0 }?.toString(),
                        plural(tracks.size, "song"),
                        formatDuration(total),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
            }
        }
        item { PlayShuffleButtons(tracks, actions) }
        val multiDisc = tracks.map { it.discNumber }.distinct().size > 1
        var lastDisc = -1
        tracks.forEachIndexed { index, track ->
            if (multiDisc && track.discNumber != lastDisc) {
                lastDisc = track.discNumber
                item(key = "disc-${track.discNumber}") {
                    Text(
                        "Disc ${track.discNumber}",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 20.dp, top = 12.dp),
                    )
                }
            }
            item(key = track.id) {
                TrackRow(
                    track, state, actions,
                    onClick = { actions.play(tracks, index) },
                    leading = TrackLeading.TrackNumber,
                    showAlbumInSubtitle = false,
                )
            }
        }
    }
}

// ------------------------------------------------------------------ Artists

@Composable
fun ArtistsScreen() {
    val vm = libraryViewModel()
    val artists by vm.artists.collectAsStateWithLifecycle()
    ArtistsContent(artists, LocalTrackActions.current)
}

@Composable
fun ArtistsContent(artists: List<ArtistSummary>, actions: TrackActions) {
    LazyColumn(Modifier.fillMaxSize()) {
        item { DetailTopBar("", onBack = actions::back) }
        item { ScreenHeader("Artists", subtitle = plural(artists.size, "artist")) }
        items(artists, key = { it.name }) { artist ->
            ArtistRow(artist) { actions.navigate(Routes.artist(artist.name)) }
        }
    }
}

@Composable
fun ArtistRow(artist: ArtistSummary, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(null, Modifier.size(48.dp), cornerRadius = 24.dp, placeholder = Icons.Rounded.Person)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(artist.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${plural(artist.albumCount, "album")} · ${plural(artist.trackCount, "song")}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun ArtistDetailScreen(name: String) {
    val vm = libraryViewModel()
    val albums by remember(name) { vm.artistAlbums(name) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val tracks by remember(name) { vm.artistTracks(name) }.collectAsStateWithLifecycle(initialValue = emptyList())
    ArtistDetailContent(name, albums, tracks, rememberTrackListState(), LocalTrackActions.current)
}

@Composable
fun ArtistDetailContent(
    name: String,
    albums: List<AlbumSummary>,
    tracks: List<TrackEntity>,
    state: TrackListState,
    actions: TrackActions,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        item { DetailTopBar("", onBack = actions::back) }
        item { ScreenHeader(name, subtitle = "${plural(albums.size, "album")} · ${plural(tracks.size, "song")}") }
        item { PlayShuffleButtons(tracks, actions) }
        if (albums.isNotEmpty()) {
            item {
                Text("Albums", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 20.dp, top = 16.dp))
            }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(albums, key = { it.albumId }) { album ->
                        AlbumTile(album, onClick = { actions.navigate(Routes.album(album.albumId)) }, modifier = Modifier.width(150.dp))
                    }
                }
            }
        }
        item {
            Text("Songs", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 20.dp, top = 16.dp))
        }
        itemsIndexed(tracks, key = { _, t -> t.id }) { index, track ->
            TrackRow(track, state, actions, onClick = { actions.play(tracks, index) })
        }
    }
}

// ------------------------------------------------------------------ Genres

@Composable
fun GenresScreen() {
    val vm = libraryViewModel()
    val genres by vm.genres.collectAsStateWithLifecycle()
    GenresContent(genres, LocalTrackActions.current)
}

@Composable
fun GenresContent(genres: List<GenreSummary>, actions: TrackActions) {
    LazyColumn(Modifier.fillMaxSize()) {
        item { DetailTopBar("", onBack = actions::back) }
        item { ScreenHeader("Genres") }
        if (genres.isEmpty()) {
            item { EmptyState(Icons.Rounded.Category, "No genres", "Your files don't have genre tags.") }
        }
        items(genres, key = { it.name }) { genre ->
            NavRow(
                Icons.Rounded.LibraryMusic,
                genre.name,
                onClick = { actions.navigate(Routes.genre(genre.name)) },
                detail = genre.trackCount.toString(),
            )
        }
    }
}
