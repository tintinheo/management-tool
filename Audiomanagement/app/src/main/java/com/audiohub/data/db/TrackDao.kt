package com.audiohub.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
abstract class TrackDao {

    @Query("SELECT * FROM tracks ORDER BY title COLLATE NOCASE")
    abstract fun observeAll(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE id = :id")
    abstract suspend fun byId(id: Long): TrackEntity?

    @Query("SELECT * FROM tracks WHERE albumId = :albumId ORDER BY discNumber, trackNumber, title COLLATE NOCASE")
    abstract fun observeAlbumTracks(albumId: Long): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE artist = :artist ORDER BY album COLLATE NOCASE, discNumber, trackNumber")
    abstract fun observeArtistTracks(artist: String): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE genre = :genre ORDER BY artist COLLATE NOCASE, album COLLATE NOCASE, trackNumber")
    abstract fun observeGenreTracks(genre: String): Flow<List<TrackEntity>>

    @Query(
        """SELECT albumId, MAX(album) AS title, COALESCE(MAX(albumArtist), MAX(artist)) AS artist,
           MAX(year) AS year, COUNT(*) AS trackCount
           FROM tracks GROUP BY albumId ORDER BY MAX(album) COLLATE NOCASE""",
    )
    abstract fun observeAlbums(): Flow<List<AlbumSummary>>

    @Query(
        """SELECT albumId, MAX(album) AS title, COALESCE(MAX(albumArtist), MAX(artist)) AS artist,
           MAX(year) AS year, COUNT(*) AS trackCount
           FROM tracks GROUP BY albumId ORDER BY MAX(dateAddedSec) DESC LIMIT :limit""",
    )
    abstract fun observeRecentlyAddedAlbums(limit: Int): Flow<List<AlbumSummary>>

    @Query(
        """SELECT albumId, MAX(album) AS title, COALESCE(MAX(albumArtist), MAX(artist)) AS artist,
           MAX(year) AS year, COUNT(*) AS trackCount
           FROM tracks WHERE albumId = :albumId GROUP BY albumId""",
    )
    abstract fun observeAlbum(albumId: Long): Flow<AlbumSummary?>

    @Query(
        """SELECT albumId, MAX(album) AS title, COALESCE(MAX(albumArtist), MAX(artist)) AS artist,
           MAX(year) AS year, COUNT(*) AS trackCount
           FROM tracks WHERE artist = :artist GROUP BY albumId ORDER BY MAX(year) DESC, MAX(album) COLLATE NOCASE""",
    )
    abstract fun observeArtistAlbums(artist: String): Flow<List<AlbumSummary>>

    @Query(
        """SELECT artist AS name, COUNT(DISTINCT albumId) AS albumCount, COUNT(*) AS trackCount
           FROM tracks GROUP BY artist ORDER BY artist COLLATE NOCASE""",
    )
    abstract fun observeArtists(): Flow<List<ArtistSummary>>

    @Query(
        """SELECT genre AS name, COUNT(*) AS trackCount FROM tracks
           WHERE genre IS NOT NULL GROUP BY genre ORDER BY genre COLLATE NOCASE""",
    )
    abstract fun observeGenres(): Flow<List<GenreSummary>>

    @Query(
        """SELECT t.* FROM tracks t JOIN play_stats s ON s.trackId = t.id
           WHERE s.favorite = 1 ORDER BY s.favoritedAt DESC""",
    )
    abstract fun observeFavorites(): Flow<List<TrackEntity>>

    @Query(
        """SELECT t.* FROM tracks t JOIN play_stats s ON s.trackId = t.id
           WHERE s.playCount > 0 ORDER BY s.playCount DESC, s.lastPlayedAt DESC LIMIT :limit""",
    )
    abstract fun observeMostPlayed(limit: Int): Flow<List<TrackEntity>>

    @Query(
        """SELECT t.* FROM tracks t JOIN play_stats s ON s.trackId = t.id
           WHERE s.lastPlayedAt > 0 ORDER BY s.lastPlayedAt DESC LIMIT :limit""",
    )
    abstract fun observeRecentlyPlayed(limit: Int): Flow<List<TrackEntity>>

    /** Every folder that directly contains audio, with a cover. */
    @Query(
        """SELECT relativePath AS path, COUNT(*) AS trackCount, MIN(albumId) AS coverAlbumId
           FROM tracks GROUP BY relativePath ORDER BY relativePath COLLATE NOCASE""",
    )
    abstract fun observeFolders(): Flow<List<FolderSummary>>

    @Query("SELECT * FROM tracks WHERE relativePath = :path")
    abstract fun observeFolderTracks(path: String): Flow<List<TrackEntity>>

    @Query("SELECT id FROM tracks")
    abstract suspend fun allIds(): List<Long>

    @Upsert
    abstract suspend fun upsertAll(tracks: List<TrackEntity>)

    @Query("DELETE FROM tracks WHERE id IN (:ids)")
    abstract suspend fun deleteByIds(ids: List<Long>)

    @Query("SELECT * FROM tracks")
    abstract suspend fun allTracks(): List<TrackEntity>

    /**
     * Makes the table mirror [tracks] exactly (insert/update new ones, drop missing ones).
     * Only rows that actually differ are written: an unchanged rescan writes nothing, so Room
     * doesn't invalidate and every open list doesn't reload/re-sort. Returns rows changed.
     */
    @Transaction
    open suspend fun replaceAll(tracks: List<TrackEntity>): Int {
        val existing = allTracks().associateBy { it.id }
        val changed = tracks.filter { existing[it.id] != it }
        val keep = tracks.mapTo(HashSet()) { it.id }
        val stale = existing.keys.filterNot { it in keep }
        if (changed.isNotEmpty()) upsertAll(changed)
        // SQLite caps bound parameters per statement, so delete in chunks.
        stale.chunked(500).forEach { deleteByIds(it) }
        return changed.size + stale.size
    }
}
