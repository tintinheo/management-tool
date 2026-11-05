package com.audiohub.data.podcast

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.audiohub.data.db.EpisodeFeedData
import com.audiohub.data.db.PodcastDao
import com.audiohub.data.db.PodcastEntity
import com.audiohub.data.settings.SettingsRepository
import com.audiohub.data.storage.AudioFolders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** An audio file found in the podcast folder. */
data class LocalAudio(
    val mediaId: Long,
    val uri: String,
    val title: String?,
    val displayName: String,
    val relativePath: String,
    val durationMs: Long,
    val dateAddedSec: Long,
    val mimeType: String?,
)

/**
 * Turns audio files the user copied into the podcast folder into "local shows": each subfolder
 * becomes a show (feed URL `local://<folder>`), each file an episode, so progress/played state
 * work like any other episode. Files this app downloaded itself are skipped.
 */
class LocalPodcastScanner(
    private val context: Context,
    private val dao: PodcastDao,
    private val settings: SettingsRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    suspend fun scan() = withContext(Dispatchers.IO) {
        val root = AudioFolders.normalize(settings.current().podcastFolder)
        sync(query(root), root)
    }

    private fun query(root: String): List<LocalAudio> {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.RELATIVE_PATH, MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATE_ADDED, MediaStore.Audio.Media.MIME_TYPE,
        )
        val selection = "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Audio.Media.IS_PENDING} = 0"
        val out = ArrayList<LocalAudio>()
        context.contentResolver.query(collection, projection, selection, arrayOf("$root%"), null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                out += LocalAudio(
                    mediaId = id,
                    uri = ContentUris.withAppendedId(collection, id).toString(),
                    title = c.getString(1)?.takeUnless { it.isBlank() || it == MediaStore.UNKNOWN_STRING },
                    displayName = c.getString(2).orEmpty(),
                    relativePath = c.getString(3).orEmpty(),
                    durationMs = c.getLong(4),
                    dateAddedSec = c.getLong(5),
                    mimeType = c.getString(6),
                )
            }
        }
        return out
    }

    /** Mirrors [files] into local shows (visible for testing). */
    internal suspend fun sync(files: List<LocalAudio>, root: String) {
        val ours = dao.downloadedUris().toHashSet()
        val groups = group(files.filterNot { it.uri in ours }, root)
        val existing = dao.localPodcasts().associateBy { it.feedUrl }
        val now = clock()

        for ((folder, list) in groups) {
            val feedUrl = LOCAL_FEED_PREFIX + folder
            val id = existing[feedUrl]?.id ?: dao.insertPodcast(
                PodcastEntity(
                    feedUrl = feedUrl, itunesId = null, title = folder, author = "On this phone",
                    description = "Audio files in /$root$folder", artworkUrl = null, websiteUrl = null,
                    subscribedAt = now, lastRefreshedAt = now,
                ),
            )
            val episodes = list.map { f ->
                EpisodeFeedData(
                    guid = "media:${f.mediaId}",
                    title = f.title ?: f.displayName.substringBeforeLast('.'),
                    description = "",
                    pubDate = f.dateAddedSec * 1000,
                    durationMs = f.durationMs,
                    enclosureUrl = f.uri,
                    enclosureType = f.mimeType,
                    enclosureLength = 0,
                    artworkUrl = null,
                    season = null,
                    episodeNumber = null,
                )
            }
            dao.mergeEpisodes(id, episodes)
            dao.markAllLocal(id)
            dao.deleteEpisodesExcept(id, episodes.map { it.guid })
        }
        // Folders that no longer contain audio disappear.
        existing.values.filter { it.feedUrl.removePrefix(LOCAL_FEED_PREFIX) !in groups.keys }
            .forEach { dao.deletePodcast(it.id) }
    }

    companion object {
        const val LOOSE_FILES_FOLDER = "Other"

        /** Groups files by their first subfolder under [root]; files directly in root go to "Other". */
        fun group(files: List<LocalAudio>, root: String): Map<String, List<LocalAudio>> {
            val normalizedRoot = AudioFolders.normalize(root)
            return files
                .filter { it.relativePath.startsWith(normalizedRoot) }
                .groupBy { f ->
                    f.relativePath.removePrefix(normalizedRoot).trim('/').substringBefore('/').ifEmpty { LOOSE_FILES_FOLDER }
                }
                .toSortedMap(String.CASE_INSENSITIVE_ORDER)
        }
    }
}
