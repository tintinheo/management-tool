package com.audiohub.ui.library

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.audiohub.appContainer
import com.audiohub.data.db.AlbumSummary
import com.audiohub.data.db.ArtistSummary
import com.audiohub.data.db.GenreSummary
import com.audiohub.data.db.PlaylistSummary
import com.audiohub.data.db.TrackEntity
import com.audiohub.data.music.MusicRepository
import com.audiohub.data.music.PlaylistRepository
import com.audiohub.data.music.StatsRepository
import com.audiohub.playback.PlayerConnection
import com.audiohub.playback.toMediaItem
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Activity-scoped state and actions for the whole music library. */
class LibraryViewModel(
    private val music: MusicRepository,
    private val playlists: PlaylistRepository,
    private val stats: StatsRepository,
    private val player: PlayerConnection,
) : ViewModel() {

    private fun <T> Flow<T>.state(initial: T) = stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    /** null = not loaded yet. */
    val tracks: StateFlow<List<TrackEntity>?> = music.tracks.state(null)
    val albums: StateFlow<List<AlbumSummary>> = music.albums.state(emptyList())
    val artists: StateFlow<List<ArtistSummary>> = music.artists.state(emptyList())
    val genres: StateFlow<List<GenreSummary>> = music.genres.state(emptyList())
    val recentlyAddedAlbums: StateFlow<List<AlbumSummary>> = music.recentlyAddedAlbums(20).state(emptyList())
    val playlistSummaries: StateFlow<List<PlaylistSummary>> = playlists.summaries.state(emptyList())
    val favoriteIds: StateFlow<Set<Long>> = stats.favoriteIds.state(emptySet())
    val scanning: StateFlow<Boolean> = music.scanning

    fun album(albumId: Long) = music.album(albumId)
    fun albumTracks(albumId: Long) = music.albumTracks(albumId)
    fun artistTracks(name: String) = music.artistTracks(name)
    fun artistAlbums(name: String) = music.artistAlbums(name)
    fun genreTracks(name: String) = music.genreTracks(name)
    fun favorites() = music.favorites
    fun mostPlayed() = music.mostPlayed()
    fun recentlyPlayed() = music.recentlyPlayed()
    fun playlist(id: Long) = playlists.playlist(id)
    fun playlistTracks(id: Long) = playlists.tracks(id)

    private var watching = false

    /** Call once the audio permission is granted: scans now and re-scans when files change. */
    @OptIn(FlowPreview::class)
    fun onPermissionGranted() {
        if (watching) return
        watching = true
        viewModelScope.launch { music.rescan() }
        viewModelScope.launch { music.mediaStoreChanges().debounce(2_000).collect { music.rescan() } }
    }

    fun rescan() {
        viewModelScope.launch { music.rescan() }
    }

    // ---- playback

    fun play(tracks: List<TrackEntity>, index: Int) = player.playQueue(tracks.map { it.toMediaItem() }, index)

    fun shuffle(tracks: List<TrackEntity>) {
        if (tracks.isNotEmpty()) player.playQueue(tracks.map { it.toMediaItem() }, tracks.indices.random(), shuffle = true)
    }

    fun playNext(tracks: List<TrackEntity>) = player.playNext(tracks.map { it.toMediaItem() })

    fun addToQueue(tracks: List<TrackEntity>) = player.addToQueue(tracks.map { it.toMediaItem() })

    fun toggleFavorite(trackId: Long) {
        val now = trackId !in favoriteIds.value
        viewModelScope.launch { stats.setFavorite(trackId, now) }
    }

    // ---- playlists

    fun createPlaylist(name: String, trackIds: List<Long> = emptyList(), onCreated: (Long) -> Unit = {}) {
        viewModelScope.launch { onCreated(playlists.create(name, trackIds)) }
    }

    fun addToPlaylist(playlistId: Long, trackIds: List<Long>) {
        viewModelScope.launch { playlists.add(playlistId, trackIds) }
    }

    fun renamePlaylist(id: Long, name: String) {
        viewModelScope.launch { playlists.rename(id, name) }
    }

    fun deletePlaylist(id: Long) {
        viewModelScope.launch { playlists.delete(id) }
    }

    fun removeFromPlaylist(id: Long, entryId: Long) {
        viewModelScope.launch { playlists.remove(id, entryId) }
    }

    fun movePlaylistEntry(id: Long, from: Int, to: Int) {
        viewModelScope.launch { playlists.move(id, from, to) }
    }

    fun exportPlaylist(id: Long, target: Uri, onDone: (Result<Unit>) -> Unit) {
        viewModelScope.launch { onDone(runCatching { playlists.exportM3u(id, target) }) }
    }

    fun importPlaylist(source: Uri, fallbackName: String, onDone: (Result<PlaylistRepository.ImportResult>) -> Unit) {
        viewModelScope.launch { onDone(runCatching { playlists.importM3u(source, fallbackName) }) }
    }
}

/** The single LibraryViewModel shared by every screen of the activity. */
@Composable
fun libraryViewModel(): LibraryViewModel {
    val container = LocalContext.current.appContainer
    val owner = LocalActivity.current as ComponentActivity
    return viewModel(
        viewModelStoreOwner = owner,
        factory = viewModelFactory {
            initializer {
                LibraryViewModel(
                    container.musicRepository,
                    container.playlistRepository,
                    container.statsRepository,
                    container.playerConnection,
                )
            }
        },
    )
}
