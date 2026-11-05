package com.audiohub.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A music file in the fixed music folder. [id] is the MediaStore audio id. */
@Entity(
    tableName = "tracks",
    indices = [Index("albumId"), Index("artist"), Index("genre")],
)
data class TrackEntity(
    @PrimaryKey val id: Long,
    val uri: String,
    val title: String,
    val artist: String,
    val albumArtist: String?,
    val album: String,
    val albumId: Long,
    val trackNumber: Int,
    val discNumber: Int,
    val year: Int,
    val genre: String?,
    val durationMs: Long,
    val dateAddedSec: Long,
    val relativePath: String,
    val displayName: String,
    /** Accent-free lowercase "title artist album" used by search; see SearchText. */
    val searchText: String = "",
)

data class AlbumSummary(
    val albumId: Long,
    val title: String,
    val artist: String,
    val year: Int,
    val trackCount: Int,
)

data class ArtistSummary(
    val name: String,
    val albumCount: Int,
    val trackCount: Int,
)

data class GenreSummary(
    val name: String,
    val trackCount: Int,
)
