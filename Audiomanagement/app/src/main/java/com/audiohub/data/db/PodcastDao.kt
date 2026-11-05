package com.audiohub.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** Feed data for one episode, as parsed from RSS (no user state). */
data class EpisodeFeedData(
    val guid: String,
    val title: String,
    val description: String,
    val pubDate: Long,
    val durationMs: Long,
    val enclosureUrl: String,
    val enclosureType: String?,
    val enclosureLength: Long,
    val artworkUrl: String?,
    val season: Int?,
    val episodeNumber: Int?,
)

@Dao
abstract class PodcastDao {

    // ---- shows

    @Query(
        """SELECT p.id, p.title, p.author, p.artworkUrl, p.feedUrl,
           COALESCE(SUM(CASE WHEN e.played = 0 THEN 1 ELSE 0 END), 0) AS unplayedCount,
           MAX(e.pubDate) AS latestPubDate
           FROM podcasts p LEFT JOIN episodes e ON e.podcastId = p.id
           GROUP BY p.id ORDER BY MAX(e.pubDate) DESC""",
    )
    abstract fun observeSummaries(): Flow<List<PodcastSummary>>

    @Query("SELECT * FROM podcasts WHERE id = :id")
    abstract fun observe(id: Long): Flow<PodcastEntity?>

    @Query("SELECT * FROM podcasts WHERE id = :id")
    abstract suspend fun get(id: Long): PodcastEntity?

    @Query("SELECT * FROM podcasts WHERE feedUrl = :feedUrl")
    abstract suspend fun byFeedUrl(feedUrl: String): PodcastEntity?

    @Query("SELECT * FROM podcasts WHERE feedUrl = :feedUrl")
    abstract fun observeByFeedUrl(feedUrl: String): Flow<PodcastEntity?>

    @Query("SELECT * FROM podcasts WHERE itunesId = :itunesId LIMIT 1")
    abstract suspend fun byItunesId(itunesId: Long): PodcastEntity?

    @Query("SELECT * FROM podcasts")
    abstract suspend fun all(): List<PodcastEntity>

    @Insert
    abstract suspend fun insertPodcast(podcast: PodcastEntity): Long

    @Query(
        """UPDATE podcasts SET title = :title, author = :author, description = :description,
           artworkUrl = :artworkUrl, websiteUrl = :websiteUrl, lastRefreshedAt = :now,
           etag = :etag, lastModified = :lastModified WHERE id = :id""",
    )
    abstract suspend fun updateFeedInfo(
        id: Long, title: String, author: String, description: String, artworkUrl: String?,
        websiteUrl: String?, now: Long, etag: String?, lastModified: String?,
    )

    @Query("UPDATE podcasts SET lastRefreshedAt = :now WHERE id = :id")
    abstract suspend fun markRefreshed(id: Long, now: Long)

    @Query("DELETE FROM podcasts WHERE id = :id")
    abstract suspend fun deletePodcast(id: Long)

    // ---- episodes

    @Query("SELECT * FROM episodes WHERE podcastId = :podcastId ORDER BY pubDate DESC")
    abstract fun observeEpisodes(podcastId: Long): Flow<List<EpisodeEntity>>

    @Query(
        """SELECT e.*, p.title AS podcastTitle, p.artworkUrl AS podcastArtworkUrl
           FROM episodes e JOIN podcasts p ON p.id = e.podcastId WHERE e.id = :id""",
    )
    abstract suspend fun episodeWithPodcast(id: Long): EpisodeWithPodcast?

    @Query(
        """SELECT e.*, p.title AS podcastTitle, p.artworkUrl AS podcastArtworkUrl
           FROM episodes e JOIN podcasts p ON p.id = e.podcastId WHERE e.id = :id""",
    )
    abstract fun observeEpisodeWithPodcast(id: Long): Flow<EpisodeWithPodcast?>

    /** Started but not finished, most recently listened first. */
    @Query(
        """SELECT e.*, p.title AS podcastTitle, p.artworkUrl AS podcastArtworkUrl
           FROM episodes e JOIN podcasts p ON p.id = e.podcastId
           WHERE e.played = 0 AND e.positionMs > 0 ORDER BY e.lastPlayedAt DESC LIMIT :limit""",
    )
    abstract fun observeInProgress(limit: Int): Flow<List<EpisodeWithPodcast>>

    /** Newest unplayed, unstarted episodes across all shows. */
    @Query(
        """SELECT e.*, p.title AS podcastTitle, p.artworkUrl AS podcastArtworkUrl
           FROM episodes e JOIN podcasts p ON p.id = e.podcastId
           WHERE e.played = 0 AND e.positionMs = 0 AND e.pubDate >= :since
           ORDER BY e.pubDate DESC LIMIT :limit""",
    )
    abstract fun observeLatest(since: Long, limit: Int): Flow<List<EpisodeWithPodcast>>

    @Query("SELECT guid FROM episodes WHERE podcastId = :podcastId")
    abstract suspend fun guids(podcastId: Long): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertEpisodes(episodes: List<EpisodeEntity>): List<Long>

    @Query(
        """UPDATE episodes SET title = :title, description = :description, pubDate = :pubDate,
           durationMs = CASE WHEN :durationMs > 0 THEN :durationMs ELSE durationMs END,
           enclosureUrl = :enclosureUrl, enclosureType = :enclosureType, enclosureLength = :enclosureLength,
           artworkUrl = :artworkUrl, season = :season, episodeNumber = :episodeNumber
           WHERE podcastId = :podcastId AND guid = :guid""",
    )
    abstract suspend fun updateEpisodeFeedData(
        podcastId: Long, guid: String, title: String, description: String, pubDate: Long, durationMs: Long,
        enclosureUrl: String, enclosureType: String?, enclosureLength: Long, artworkUrl: String?,
        season: Int?, episodeNumber: Int?,
    )

    @Query(
        """UPDATE episodes SET positionMs = :positionMs, lastPlayedAt = :now,
           durationMs = CASE WHEN :durationMs > 0 THEN :durationMs ELSE durationMs END
           WHERE id = :id""",
    )
    abstract suspend fun savePosition(id: Long, positionMs: Long, durationMs: Long, now: Long)

    @Query("UPDATE episodes SET played = :played, positionMs = 0, lastPlayedAt = :now WHERE id = :id")
    abstract suspend fun setPlayed(id: Long, played: Boolean, now: Long)

    // ---- downloads

    @Query("SELECT * FROM episodes WHERE id = :id")
    abstract suspend fun getEpisode(id: Long): EpisodeEntity?

    @Query("UPDATE episodes SET downloadState = :state WHERE id = :id")
    abstract suspend fun setDownloadState(id: Long, state: Int)

    @Query("UPDATE episodes SET localUri = :localUri, downloadState = :state WHERE id = :id")
    abstract suspend fun setDownload(id: Long, localUri: String?, state: Int)

    @Query(
        """SELECT e.*, p.title AS podcastTitle, p.artworkUrl AS podcastArtworkUrl
           FROM episodes e JOIN podcasts p ON p.id = e.podcastId
           WHERE e.localUri IS NOT NULL AND p.feedUrl NOT LIKE 'local://%' ORDER BY e.pubDate DESC""",
    )
    abstract fun observeDownloaded(): Flow<List<EpisodeWithPodcast>>

    /** URIs of files this app downloaded (excluded when scanning the podcast folder for local files). */
    @Query(
        """SELECT e.localUri FROM episodes e JOIN podcasts p ON p.id = e.podcastId
           WHERE e.localUri IS NOT NULL AND p.feedUrl NOT LIKE 'local://%'""",
    )
    abstract suspend fun downloadedUris(): List<String>

    @Query("SELECT * FROM episodes WHERE podcastId = :podcastId AND localUri IS NOT NULL")
    abstract suspend fun downloadedEpisodes(podcastId: Long): List<EpisodeEntity>

    @Query("UPDATE podcasts SET autoDownload = :enabled WHERE id = :id")
    abstract suspend fun setAutoDownload(id: Long, enabled: Boolean)

    @Query("SELECT * FROM podcasts WHERE autoDownload = 1")
    abstract suspend fun autoDownloadPodcasts(): List<PodcastEntity>

    /** Newest unplayed, not-yet-downloaded episodes published after [since]. */
    @Query(
        """SELECT * FROM episodes WHERE podcastId = :podcastId AND played = 0 AND localUri IS NULL
           AND downloadState IN (0, 4) AND pubDate >= :since ORDER BY pubDate DESC LIMIT :limit""",
    )
    abstract suspend fun autoDownloadCandidates(podcastId: Long, since: Long, limit: Int): List<EpisodeEntity>

    // ---- local (files dropped into the podcast folder)

    @Query("UPDATE episodes SET localUri = enclosureUrl, downloadState = 3 WHERE podcastId = :podcastId")
    abstract suspend fun markAllLocal(podcastId: Long)

    @Query("DELETE FROM episodes WHERE podcastId = :podcastId AND guid NOT IN (:keepGuids)")
    abstract suspend fun deleteEpisodesExcept(podcastId: Long, keepGuids: List<String>)

    @Query("SELECT * FROM podcasts WHERE feedUrl LIKE 'local://%'")
    abstract suspend fun localPodcasts(): List<PodcastEntity>

    /**
     * Inserts new episodes and refreshes feed fields of known ones, never touching listening state.
     * Returns how many episodes were new.
     */
    @Transaction
    open suspend fun mergeEpisodes(podcastId: Long, items: List<EpisodeFeedData>): Int {
        val known = guids(podcastId).toHashSet()
        val (existing, fresh) = items.distinctBy { it.guid }.partition { it.guid in known }
        existing.forEach {
            updateEpisodeFeedData(
                podcastId, it.guid, it.title, it.description, it.pubDate, it.durationMs, it.enclosureUrl,
                it.enclosureType, it.enclosureLength, it.artworkUrl, it.season, it.episodeNumber,
            )
        }
        insertEpisodes(
            fresh.map {
                EpisodeEntity(
                    podcastId = podcastId, guid = it.guid, title = it.title, description = it.description,
                    pubDate = it.pubDate, durationMs = it.durationMs, enclosureUrl = it.enclosureUrl,
                    enclosureType = it.enclosureType, enclosureLength = it.enclosureLength,
                    artworkUrl = it.artworkUrl, season = it.season, episodeNumber = it.episodeNumber,
                )
            },
        )
        return fresh.size
    }
}
