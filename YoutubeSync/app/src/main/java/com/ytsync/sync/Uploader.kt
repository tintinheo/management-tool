package com.ytsync.sync

import com.ytsync.data.Settings
import com.ytsync.data.UploadDao
import com.ytsync.data.UploadItem
import com.ytsync.data.UploadState
import com.ytsync.youtube.ResumeStatus
import com.ytsync.youtube.VideoMetadata
import com.ytsync.youtube.YouTubeApi
import com.ytsync.youtube.YouTubeException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream

/** Where the access token comes from — real impl is [com.ytsync.auth.YouTubeAuth]. */
interface TokenSource {
    /** null = the user has to open the app and re-consent. */
    suspend fun token(): String?
    suspend fun invalidate(token: String)
}

/** Access to the phone's video files — real impl reads MediaStore. */
interface VideoFiles {
    fun size(mediaId: Long): Long?
    fun open(mediaId: Long, offset: Long): InputStream
}

enum class QueueOutcome {
    /** Nothing left to upload. */
    DRAINED,
    /** No token without user interaction — notify the user. */
    NEEDS_AUTH,
    /** Daily quota / channel upload limit hit — retry in a few hours. */
    QUOTA,
    /** Network or 5xx — let WorkManager back off and retry. */
    RETRY,
}

/**
 * Uploads queued camera videos one by one with YouTube's resumable protocol. The session URI and
 * byte offset are saved after every chunk, so a killed worker / lost Wi‑Fi resumes where it stopped.
 */
class Uploader(
    private val dao: UploadDao,
    private val api: YouTubeApi,
    private val tokens: TokenSource,
    private val files: VideoFiles,
    private val settings: suspend () -> Settings,
    /** In-run waits before handing a network/5xx error to WorkManager's (much slower) backoff. */
    private val retryDelaysMs: List<Long> = listOf(3_000, 10_000, 30_000),
) {
    /** Set when YouTube rejects the read-only contentRating field, so we stop sending it this run. */
    private var ratingRejected = false

    suspend fun runQueue(
        onItem: suspend (item: UploadItem, sent: Long) -> Unit = { _, _ -> },
    ): QueueOutcome = withContext(Dispatchers.IO) {
        var outcome: QueueOutcome? = null
        while (outcome == null) {
            val item = dao.nextPending() ?: break
            outcome = uploadOne(item, onItem)
        }
        outcome ?: QueueOutcome.DRAINED
    }

    /** Returns null to continue with the next item, or an outcome that stops the run. */
    private suspend fun uploadOne(start: UploadItem, onItem: suspend (UploadItem, Long) -> Unit): QueueOutcome? {
        var item = start
        val size = files.size(item.mediaId)
        if (size == null) { // deleted before we got to it
            dao.put(item.copy(state = UploadState.SKIPPED, sessionUrl = null, error = "Video đã bị xoá khỏi máy"))
            return null
        }
        if (size != item.sizeBytes) { // file changed (e.g. edited/trimmed) — restart cleanly
            item = item.copy(sizeBytes = size, sessionUrl = null, bytesSent = 0)
            dao.put(item)
        }

        var retriedAuth = false
        var quickRetries = 0
        while (true) {
            val token = try {
                tokens.token() ?: return QueueOutcome.NEEDS_AUTH
            } catch (e: IOException) {
                return QueueOutcome.RETRY
            }
            try {
                upload(item, token, onItem)
                return null
            } catch (e: YouTubeException) {
                // Re-read: upload() persisted the session URI / offset we must keep for resuming.
                val cur = dao.get(item.mediaId) ?: item
                item = cur
                when {
                    e.unauthorized && !retriedAuth -> { tokens.invalidate(token); retriedAuth = true }
                    e.unauthorized -> return QueueOutcome.NEEDS_AUTH
                    e.quota -> { dao.put(cur.copy(error = "Hết hạn mức YouTube hôm nay — sẽ thử lại sau")); return QueueOutcome.QUOTA }
                    e.transient && quickRetries < retryDelaysMs.size -> delay(retryDelaysMs[quickRetries++])
                    e.transient -> { dao.put(cur.copy(attempts = cur.attempts + 1, error = e.message)); return QueueOutcome.RETRY }
                    else -> {
                        dao.put(cur.copy(state = UploadState.FAILED, sessionUrl = null, bytesSent = 0, error = e.message, attempts = cur.attempts + 1))
                        return null
                    }
                }
            } catch (e: IOException) {
                val cur = dao.get(item.mediaId) ?: item
                item = cur
                // A Wi‑Fi blip shouldn't cost WorkManager's ≥1 min backoff (and the foreground service):
                // wait a few seconds and resume from the server's offset in this same run.
                if (quickRetries < retryDelaysMs.size) {
                    delay(retryDelaysMs[quickRetries++])
                    continue
                }
                dao.put(cur.copy(attempts = cur.attempts + 1, error = "Mất kết nối: ${e.message}"))
                return QueueOutcome.RETRY
            } catch (e: SecurityException) { // lost read permission for this file
                val cur = dao.get(item.mediaId) ?: item
                dao.put(cur.copy(state = UploadState.FAILED, sessionUrl = null, error = "Không có quyền đọc video"))
                return null
            }
        }
    }

    private suspend fun upload(start: UploadItem, token: String, onItem: suspend (UploadItem, Long) -> Unit): UploadItem {
        var item = start
        var from = 0L
        if (item.sessionUrl != null) {
            when (val s = api.queryStatus(token, item.sessionUrl, item.sizeBytes)) {
                is ResumeStatus.Complete -> return finish(item, token, s.videoId)
                is ResumeStatus.Partial -> from = s.nextByte
                ResumeStatus.Expired -> item = item.copy(sessionUrl = null, bytesSent = 0)
            }
        }
        if (item.sessionUrl == null) {
            val title = titleFor(item)
            item = item.copy(sessionUrl = openSession(item, title, token), title = title, bytesSent = 0)
            from = 0
        }
        item = item.copy(state = UploadState.UPLOADING, bytesSent = from, error = null)
        dao.put(item)
        onItem(item, from)

        val videoId = try {
            api.upload(
                token = token,
                sessionUrl = item.sessionUrl!!,
                size = item.sizeBytes,
                mime = item.mimeType,
                startByte = from,
                open = { off -> files.open(item.mediaId, off) },
                // api.upload is blocking (already on Dispatchers.IO), so bridging back is cheap.
                onProgress = { sent -> runBlocking { dao.progress(item.mediaId, sent); onItem(item, sent) } },
            )
        } catch (e: YouTubeException) {
            if (e.reason == "sessionExpired") dao.put(item.copy(sessionUrl = null, bytesSent = 0))
            throw e
        }
        return finish(item, token, videoId)
    }

    /** The user's group-rename title if set, else the auto template filled with the recording time. */
    private suspend fun titleFor(item: UploadItem): String =
        (dao.get(item.mediaId)?.titleOverride ?: item.titleOverride)?.takeIf { it.isNotBlank() }
            ?: VideoMetadata.title(settings().titleTemplate, item.takenAt, item.displayName)

    private suspend fun openSession(item: UploadItem, title: String, token: String): String {
        val s = settings()
        val wantRating = s.ageRestrict && !ratingRejected
        return try {
            api.startSession(token, VideoMetadata.parts(wantRating), VideoMetadata.build(title, item.displayName, item.takenAt, wantRating), item.sizeBytes, item.mimeType)
        } catch (e: YouTubeException) {
            if (!wantRating || e.code != 400) throw e
            ratingRejected = true // YouTube refused contentRating on insert — upload without it
            api.startSession(token, VideoMetadata.parts(false), VideoMetadata.build(title, item.displayName, item.takenAt, false), item.sizeBytes, item.mimeType)
        }
    }

    private suspend fun finish(item: UploadItem, token: String, videoId: String): UploadItem {
        val check = runCatching { api.checkVideo(token, videoId) }.getOrNull()
        val latest = dao.get(item.mediaId)
        val done = item.copy(
            titleOverride = latest?.titleOverride ?: item.titleOverride,
            title = item.title ?: latest?.title,
            state = UploadState.DONE,
            videoId = videoId,
            sessionUrl = null,
            bytesSent = item.sizeBytes,
            ageRestricted = check?.ageRestricted,
            error = null,
            updatedAt = System.currentTimeMillis(),
        )
        dao.put(done)
        return done
    }
}
