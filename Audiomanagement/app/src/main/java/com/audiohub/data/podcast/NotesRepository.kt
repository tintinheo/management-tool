package com.audiohub.data.podcast

import com.audiohub.data.db.EpisodeNoteDao
import com.audiohub.data.db.EpisodeNoteEntity
import com.audiohub.data.db.EpisodeWithPodcast
import kotlinx.coroutines.flow.Flow

/** Recall answers + notes per podcast episode ("test your memory" after finishing an episode). */
class NotesRepository(private val dao: EpisodeNoteDao, private val now: () -> Long = System::currentTimeMillis) {

    val all: Flow<List<EpisodeNoteEntity>> = dao.observeAll()

    fun observe(episodeId: Long): Flow<EpisodeNoteEntity?> = dao.observe(episodeId)

    suspend fun get(episodeId: Long): EpisodeNoteEntity? = dao.get(episodeId)

    /** Episodes finished in the last [days] days that still await a recall. */
    fun pendingRecall(days: Int = 14, limit: Int = 3): Flow<List<EpisodeWithPodcast>> =
        dao.observePendingRecall(since = now() - days * DAY_MS, limit = limit)

    /** Saves answers; an entry with nothing written is removed so the episode can be prompted again. */
    suspend fun save(
        episode: EpisodeHeader,
        mainIdeas: String,
        highlight: String,
        takeaway: String,
        notes: String,
        recallScore: Int,
    ) {
        val existing = dao.get(episode.episodeId)
        val t = now()
        val note = EpisodeNoteEntity(
            episodeId = episode.episodeId,
            episodeTitle = episode.title,
            showTitle = episode.showTitle,
            artworkUrl = episode.artworkUrl,
            mainIdeas = mainIdeas.trim(),
            highlight = highlight.trim(),
            takeaway = takeaway.trim(),
            notes = notes.trim(),
            recallScore = recallScore.coerceIn(0, 4),
            skipped = false,
            createdAt = existing?.createdAt ?: t,
            updatedAt = t,
        )
        if (!note.hasContent) {
            if (existing != null && !existing.skipped) dao.delete(episode.episodeId)
            return
        }
        // Unchanged → don't bump updatedAt (keeps the notes list order stable).
        if (existing != null && existing.copy(updatedAt = t) == note) return
        dao.upsert(note)
    }

    /** "Not now" on the recall prompt. */
    suspend fun skip(episode: EpisodeHeader) {
        if (dao.get(episode.episodeId) != null) return
        val t = now()
        dao.upsert(
            EpisodeNoteEntity(
                episodeId = episode.episodeId, episodeTitle = episode.title, showTitle = episode.showTitle,
                artworkUrl = episode.artworkUrl, skipped = true, createdAt = t, updatedAt = t,
            ),
        )
    }

    suspend fun delete(episodeId: Long) = dao.delete(episodeId)

    /** The bits of an episode a note keeps (so notes outlive the episode row). */
    data class EpisodeHeader(val episodeId: Long, val title: String, val showTitle: String, val artworkUrl: String?)

    companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000

        val SCORE_LABELS = listOf("A little", "Some", "Most", "All of it")
    }
}
