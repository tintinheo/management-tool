package com.audiohub.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class PlaylistDao {

    @Query(
        """SELECT p.id AS id, p.name AS name, COUNT(t.id) AS trackCount,
           (SELECT t2.albumId FROM playlist_entries e2 JOIN tracks t2 ON t2.id = e2.trackId
            WHERE e2.playlistId = p.id ORDER BY e2.position LIMIT 1) AS coverAlbumId
           FROM playlists p
           LEFT JOIN playlist_entries e ON e.playlistId = p.id
           LEFT JOIN tracks t ON t.id = e.trackId
           GROUP BY p.id ORDER BY p.name COLLATE NOCASE""",
    )
    abstract fun observeSummaries(): Flow<List<PlaylistSummary>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    abstract fun observe(id: Long): Flow<PlaylistEntity?>

    @Query(
        """SELECT t.*, e.entryId AS entryId, e.position AS position
           FROM playlist_entries e JOIN tracks t ON t.id = e.trackId
           WHERE e.playlistId = :playlistId ORDER BY e.position""",
    )
    abstract fun observeTracks(playlistId: Long): Flow<List<PlaylistTrack>>

    @Query("SELECT * FROM playlist_entries WHERE playlistId = :playlistId ORDER BY position")
    abstract suspend fun entries(playlistId: Long): List<PlaylistEntryEntity>

    @Insert
    abstract suspend fun insert(playlist: PlaylistEntity): Long

    @Insert
    abstract suspend fun insertEntries(entries: List<PlaylistEntryEntity>)

    @Query("UPDATE playlists SET name = :name, updatedAt = :now WHERE id = :id")
    abstract suspend fun rename(id: Long, name: String, now: Long)

    @Query("UPDATE playlists SET updatedAt = :now WHERE id = :id")
    abstract suspend fun touch(id: Long, now: Long)

    @Query("DELETE FROM playlists WHERE id = :id")
    abstract suspend fun delete(id: Long)

    @Query("DELETE FROM playlist_entries WHERE entryId = :entryId")
    abstract suspend fun deleteEntry(entryId: Long)

    @Query("UPDATE playlist_entries SET position = :position WHERE entryId = :entryId")
    abstract suspend fun setPosition(entryId: Long, position: Int)

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_entries WHERE playlistId = :playlistId")
    abstract suspend fun maxPosition(playlistId: Long): Int

    @Transaction
    open suspend fun create(name: String, trackIds: List<Long>, now: Long): Long {
        val id = insert(PlaylistEntity(name = name, createdAt = now, updatedAt = now))
        append(id, trackIds, now)
        return id
    }

    @Transaction
    open suspend fun append(playlistId: Long, trackIds: List<Long>, now: Long) {
        if (trackIds.isEmpty()) return
        val start = maxPosition(playlistId) + 1
        insertEntries(trackIds.mapIndexed { i, trackId -> PlaylistEntryEntity(playlistId = playlistId, trackId = trackId, position = start + i) })
        touch(playlistId, now)
    }

    @Transaction
    open suspend fun removeEntry(playlistId: Long, entryId: Long, now: Long) {
        deleteEntry(entryId)
        renumber(entries(playlistId))
        touch(playlistId, now)
    }

    /** Moves the entry at list index [from] to index [to] (indices in position order). */
    @Transaction
    open suspend fun move(playlistId: Long, from: Int, to: Int, now: Long) {
        val list = entries(playlistId).toMutableList()
        if (from !in list.indices || to !in list.indices || from == to) return
        list.add(to, list.removeAt(from))
        renumber(list)
        touch(playlistId, now)
    }

    private suspend fun renumber(list: List<PlaylistEntryEntity>) {
        list.forEachIndexed { i, e -> if (e.position != i) setPosition(e.entryId, i) }
    }
}
