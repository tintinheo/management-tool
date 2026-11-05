package com.audiohub.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * One slot in a playlist (the same track may appear twice). No FK to tracks on purpose: a file
 * that briefly disappears from MediaStore must not silently drop out of playlists.
 */
@Entity(
    tableName = "playlist_entries",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("playlistId"), Index("trackId")],
)
data class PlaylistEntryEntity(
    @PrimaryKey(autoGenerate = true) val entryId: Long = 0,
    val playlistId: Long,
    val trackId: Long,
    val position: Int,
)

data class PlaylistSummary(
    val id: Long,
    val name: String,
    val trackCount: Int,
    /** Album of the first track, used as the playlist cover. */
    val coverAlbumId: Long?,
)

data class PlaylistTrack(
    @Embedded val track: TrackEntity,
    val entryId: Long,
    val position: Int,
)

@Entity(tableName = "play_stats")
data class PlayStatsEntity(
    @PrimaryKey val trackId: Long,
    val playCount: Int = 0,
    val lastPlayedAt: Long = 0,
    val favorite: Boolean = false,
    val favoritedAt: Long = 0,
)
