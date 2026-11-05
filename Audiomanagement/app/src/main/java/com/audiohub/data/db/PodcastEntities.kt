package com.audiohub.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A followed show. */
@Entity(
    tableName = "podcasts",
    indices = [Index(value = ["feedUrl"], unique = true)],
)
data class PodcastEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val feedUrl: String,
    val itunesId: Long?,
    val title: String,
    val author: String,
    val description: String,
    val artworkUrl: String?,
    val websiteUrl: String?,
    val subscribedAt: Long,
    val lastRefreshedAt: Long = 0,
    /** HTTP validators for conditional refresh. */
    val etag: String? = null,
    val lastModified: String? = null,
    /** Phase 5: download newest episodes automatically. */
    val autoDownload: Boolean = false,
)

@Entity(
    tableName = "episodes",
    foreignKeys = [
        ForeignKey(
            entity = PodcastEntity::class,
            parentColumns = ["id"],
            childColumns = ["podcastId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["podcastId", "guid"], unique = true), Index("pubDate")],
)
data class EpisodeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val podcastId: Long,
    val guid: String,
    val title: String,
    /** HTML show notes. */
    val description: String,
    val pubDate: Long,
    val durationMs: Long,
    val enclosureUrl: String,
    val enclosureType: String?,
    val enclosureLength: Long,
    val artworkUrl: String?,
    val season: Int?,
    val episodeNumber: Int?,
    val positionMs: Long = 0,
    val played: Boolean = false,
    val lastPlayedAt: Long = 0,
    /** Phase 5: content:// URI of the downloaded file. */
    val localUri: String? = null,
    val downloadState: Int = 0,
    /** For files in the podcast folder: file modification time (ms). Added in schema v4. */
    @ColumnInfo(defaultValue = "0") val fileModifiedAt: Long = 0,
)

/** Episode joined with the show fields the UI needs. */
data class EpisodeWithPodcast(
    @Embedded val episode: EpisodeEntity,
    val podcastTitle: String,
    val podcastArtworkUrl: String?,
    val podcastAuthor: String = "",
)

data class PodcastSummary(
    val id: Long,
    val title: String,
    val author: String,
    val artworkUrl: String?,
    val feedUrl: String,
    val unplayedCount: Int,
    val latestPubDate: Long?,
)
