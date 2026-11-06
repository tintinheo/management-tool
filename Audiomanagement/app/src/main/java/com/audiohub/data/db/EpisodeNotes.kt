package com.audiohub.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * The listener's recall answers and notes for one episode.
 *
 * Deliberately no foreign key to `episodes`: unfollowing a show or a feed dropping an old episode must
 * not delete what the user wrote, so the titles are copied here too.
 */
@Entity(tableName = "episode_notes", indices = [Index("updatedAt")])
data class EpisodeNoteEntity(
    @PrimaryKey val episodeId: Long,
    val episodeTitle: String,
    val showTitle: String,
    val artworkUrl: String?,
    /** "What was it about?" */
    val mainIdeas: String = "",
    /** "What stuck with you most?" */
    val highlight: String = "",
    /** "What will you do with it?" */
    val takeaway: String = "",
    /** Free-form notes. */
    val notes: String = "",
    /** Self-rated recall after comparing with the show notes: 0 = not rated yet, 1…4 = little…everything. */
    val recallScore: Int = 0,
    /** The user chose "Not now" for the recall prompt (row exists only to hide the prompt). */
    val skipped: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val hasContent: Boolean
        get() = mainIdeas.isNotBlank() || highlight.isNotBlank() || takeaway.isNotBlank() || notes.isNotBlank() || recallScore > 0
}

@Dao
interface EpisodeNoteDao {
    @Query("SELECT * FROM episode_notes WHERE episodeId = :episodeId")
    fun observe(episodeId: Long): Flow<EpisodeNoteEntity?>

    @Query("SELECT * FROM episode_notes WHERE episodeId = :episodeId")
    suspend fun get(episodeId: Long): EpisodeNoteEntity?

    /** Notes worth showing (skipped prompts without any content are hidden). */
    @Query(
        "SELECT * FROM episode_notes WHERE skipped = 0 OR mainIdeas != '' OR highlight != '' OR takeaway != '' " +
            "OR notes != '' OR recallScore > 0 ORDER BY updatedAt DESC",
    )
    fun observeAll(): Flow<List<EpisodeNoteEntity>>

    @Upsert
    suspend fun upsert(note: EpisodeNoteEntity)

    @Query("DELETE FROM episode_notes WHERE episodeId = :episodeId")
    suspend fun delete(episodeId: Long)

    /** Recently finished episodes the user hasn't recalled (or dismissed) yet. */
    @Query(
        """
        SELECT e.*, p.title AS podcastTitle, p.artworkUrl AS podcastArtworkUrl, p.author AS podcastAuthor
        FROM episodes e JOIN podcasts p ON p.id = e.podcastId
        WHERE e.played = 1 AND e.lastPlayedAt >= :since
          AND e.id NOT IN (SELECT episodeId FROM episode_notes)
        ORDER BY e.lastPlayedAt DESC LIMIT :limit
        """,
    )
    fun observePendingRecall(since: Long, limit: Int): Flow<List<EpisodeWithPodcast>>
}
