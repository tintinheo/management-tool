package com.audiohub.data.podcast

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.audiohub.data.db.EpisodeWithPodcast
import com.audiohub.data.db.PodcastDao
import com.audiohub.data.settings.SettingsRepository
import com.audiohub.data.storage.AudioFolders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object DownloadState {
    const val NONE = 0
    const val QUEUED = 1
    const val DOWNLOADING = 2
    const val DONE = 3
    const val FAILED = 4
}

const val LOCAL_FEED_PREFIX = "local://"

fun isLocalFeed(feedUrl: String) = feedUrl.startsWith(LOCAL_FEED_PREFIX)

/** Where downloaded episode files go. On device: MediaStore (shared /Podcasts folder). */
interface EpisodeFileStore {
    /** Creates a hidden (pending) file and returns its URI. */
    fun create(relativeDir: String, displayName: String, mimeType: String): Uri
    fun openOutput(uri: Uri): OutputStream
    /** Makes a finished file visible to other apps. */
    fun publish(uri: Uri)
    fun delete(uri: Uri): Boolean
}

class MediaStoreEpisodeStore(private val context: Context) : EpisodeFileStore {
    private val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    override fun create(relativeDir: String, displayName: String, mimeType: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
            put(MediaStore.Audio.Media.RELATIVE_PATH, relativeDir)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
            put(MediaStore.Audio.Media.IS_PODCAST, 1)
        }
        return context.contentResolver.insert(collection, values) ?: throw IOException("MediaStore refused $relativeDir$displayName")
    }

    override fun openOutput(uri: Uri): OutputStream =
        context.contentResolver.openOutputStream(uri, "w") ?: throw IOException("Can't write $uri")

    override fun publish(uri: Uri) {
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
    }

    /** Only works for files this install created; files from an old install are left in place. */
    override fun delete(uri: Uri): Boolean =
        runCatching { context.contentResolver.delete(uri, null, null) > 0 }.getOrDefault(false)
}

/** In-memory download progress (0..1) by episode id, for the UI. */
class DownloadProgress {
    private val _progress = MutableStateFlow<Map<Long, Float>>(emptyMap())
    val progress: StateFlow<Map<Long, Float>> = _progress.asStateFlow()
    fun set(episodeId: Long, value: Float) = _progress.update { it + (episodeId to value) }
    fun clear(episodeId: Long) = _progress.update { it - episodeId }
}

/** Streams an episode enclosure into the podcast folder. Pure logic; scheduling lives in workers. */
class EpisodeDownloader(
    private val dao: PodcastDao,
    private val http: HttpClient,
    private val store: EpisodeFileStore,
    private val settings: SettingsRepository,
    val progress: DownloadProgress,
) {
    /** @return true when the file is on disk (or nothing to do), false on a retryable failure. */
    suspend fun download(episodeId: Long, onProgress: suspend (Float) -> Unit = {}): Boolean {
        val item = dao.episodeWithPodcast(episodeId) ?: return true
        val episode = item.episode
        if (episode.localUri != null) return true

        dao.setDownloadState(episodeId, DownloadState.DOWNLOADING)
        progress.set(episodeId, 0f)
        var target: Uri? = null
        try {
            val folder = AudioFolders.normalize(settings.current().podcastFolder) + safeName(item.podcastTitle, 60) + "/"
            val (name, mime) = fileNameFor(item)
            http.stream(episode.enclosureUrl) { code, length, body ->
                if (code !in 200..299) throw HttpException(code, episode.enclosureUrl)
                val uri = store.create(folder, name, mime)
                target = uri
                store.openOutput(uri).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    val total = if (length > 0) length else episode.enclosureLength
                    var copied = 0L
                    var lastReported = -1
                    while (true) {
                        val n = body.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        copied += n
                        if (total > 0) {
                            val pct = (copied * 100 / total).toInt()
                            if (pct != lastReported) {
                                lastReported = pct
                                val p = (copied.toFloat() / total).coerceIn(0f, 1f)
                                progress.set(episodeId, p)
                                onProgress(p)
                            }
                        }
                    }
                }
            }
            val uri = target ?: throw IOException("No file created")
            // Record ownership before publishing so the local-file scanner never adopts it.
            dao.setDownload(episodeId, uri.toString(), DownloadState.DONE)
            store.publish(uri)
            return true
        } catch (e: CancellationException) {
            target?.let(store::delete)
            dao.setDownloadState(episodeId, DownloadState.NONE)
            throw e
        } catch (e: Exception) {
            target?.let(store::delete)
            dao.setDownloadState(episodeId, DownloadState.FAILED)
            return false
        } finally {
            progress.clear(episodeId)
        }
    }

    /** Deletes the downloaded file (never touches user-dropped local files). */
    suspend fun delete(episodeId: Long) {
        val item = dao.episodeWithPodcast(episodeId) ?: return
        val podcast = dao.get(item.episode.podcastId) ?: return
        if (isLocalFeed(podcast.feedUrl)) return
        item.episode.localUri?.let { store.delete(Uri.parse(it)) }
        dao.setDownload(episodeId, null, DownloadState.NONE)
    }

    suspend fun deleteAllFor(podcastId: Long) {
        val podcast = dao.get(podcastId) ?: return
        if (isLocalFeed(podcast.feedUrl)) return
        dao.downloadedEpisodes(podcastId).forEach { e -> e.localUri?.let { store.delete(Uri.parse(it)) } }
    }

    /** Called when an episode is marked played: frees space if the user wants that. */
    suspend fun onPlayed(episodeId: Long) {
        if (settings.current().deletePlayedDownloads) delete(episodeId)
    }

    companion object {
        private val unsafe = Regex("""[\\/:*?"<>|\u0000-\u001F]+""")
        private val spaces = Regex("\\s+")
        private val audioExtensions = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac", "mp4")

        fun safeName(raw: String, max: Int): String =
            raw.replace(unsafe, " ").replace(spaces, " ").trim().trim('.').take(max).trim().ifEmpty { "Podcast" }

        /** "2026-11-04 Episode title.mp3" + MIME type. */
        fun fileNameFor(item: EpisodeWithPodcast): Pair<String, String> {
            val e = item.episode
            val type = e.enclosureType?.lowercase()
            val urlExt = Uri.parse(e.enclosureUrl).lastPathSegment?.substringAfterLast('.', "")?.lowercase().orEmpty()
            val ext = when {
                type == "audio/mpeg" || type == "audio/mp3" -> "mp3"
                type == "audio/mp4" || type == "audio/x-m4a" || type == "audio/m4a" || type == "audio/aac" -> "m4a"
                type == "audio/ogg" -> "ogg"
                type == "audio/opus" -> "opus"
                urlExt in audioExtensions -> urlExt
                else -> "mp3"
            }
            val mime = when (ext) {
                "m4a", "mp4" -> "audio/mp4"
                "ogg", "oga" -> "audio/ogg"
                "opus" -> "audio/opus"
                "wav" -> "audio/wav"
                "flac" -> "audio/flac"
                "aac" -> "audio/aac"
                else -> "audio/mpeg"
            }
            val date = if (e.pubDate > 0) {
                SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(e.pubDate)) + " "
            } else ""
            return "$date${safeName(e.title, 100)}.$ext" to mime
        }
    }
}
