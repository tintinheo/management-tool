package com.audiohub.data.music

import com.audiohub.data.db.AlbumSummary
import com.audiohub.data.db.ArtistSummary
import com.audiohub.data.db.FolderSummary
import com.audiohub.data.db.GenreSummary
import com.audiohub.data.db.TrackDao
import com.audiohub.data.db.TrackEntity
import com.audiohub.data.settings.SettingsRepository
import com.audiohub.data.storage.AudioFolders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class MusicRepository(
    private val scanner: MusicScanner,
    private val trackDao: TrackDao,
    private val settings: SettingsRepository,
) {
    private val scanMutex = Mutex()
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    val tracks: Flow<List<TrackEntity>> = trackDao.observeAll()
    val albums: Flow<List<AlbumSummary>> = trackDao.observeAlbums()
    val artists: Flow<List<ArtistSummary>> = trackDao.observeArtists()
    val genres: Flow<List<GenreSummary>> = trackDao.observeGenres()
    val favorites: Flow<List<TrackEntity>> = trackDao.observeFavorites()
    /** Every folder that directly contains audio (each one is an automatic playlist). */
    val folders: Flow<List<FolderSummary>> = trackDao.observeFolders()

    fun folderTracks(path: String) = trackDao.observeFolderTracks(path)

    fun recentlyAddedAlbums(limit: Int = 20) = trackDao.observeRecentlyAddedAlbums(limit)
    fun mostPlayed(limit: Int = 100) = trackDao.observeMostPlayed(limit)
    fun recentlyPlayed(limit: Int = 100) = trackDao.observeRecentlyPlayed(limit)
    fun album(albumId: Long) = trackDao.observeAlbum(albumId)
    fun albumTracks(albumId: Long) = trackDao.observeAlbumTracks(albumId)
    fun artistTracks(artist: String) = trackDao.observeArtistTracks(artist)
    fun artistAlbums(artist: String) = trackDao.observeArtistAlbums(artist)
    fun genreTracks(genre: String) = trackDao.observeGenreTracks(genre)
    suspend fun track(id: Long) = trackDao.byId(id)

    fun mediaStoreChanges(): Flow<Unit> = scanner.changes()

    /** Re-reads the music folder into the database. Concurrent calls are serialised. */
    suspend fun rescan() = withContext(Dispatchers.IO) {
        scanMutex.withLock {
            _scanning.value = true
            try {
                val folder = settings.current().musicFolder
                AudioFolders.ensureExists(folder)
                trackDao.replaceAll(scanner.scan(folder))
            } finally {
                _scanning.value = false
            }
        }
    }
}
