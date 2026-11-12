package com.ytsync.sync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ytsync.auth.YouTubeAuth
import com.ytsync.data.Settings
import com.ytsync.data.SettingsStore
import com.ytsync.data.UploadDb
import com.ytsync.data.UploadState
import com.ytsync.media.CameraVideos
import com.ytsync.youtube.YouTubeApi
import kotlinx.coroutines.CancellationException
import java.io.InputStream
import java.time.Duration

/** Wires MediaStore change triggers, an hourly safety scan and the upload queue together. */
object SyncScheduler {
    private const val TRIGGER = "media-trigger"
    private const val PERIODIC = "periodic-scan"
    private const val UPLOAD = "upload"

    /** Call on app start and whenever settings change. */
    fun ensure(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.enqueueUniquePeriodicWork(
            PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<PeriodicScanWorker>(Duration.ofHours(1)).build(),
        )
        armTrigger(context, ExistingWorkPolicy.KEEP)
    }

    /**
     * A content-URI trigger fires once, so [MediaTriggerWorker] re-arms it (REPLACE) after each run.
     * The 10 s update delay lets the camera finish writing before we look.
     */
    fun armTrigger(context: Context, policy: ExistingWorkPolicy) {
        val req = OneTimeWorkRequestBuilder<MediaTriggerWorker>()
            .setConstraints(
                Constraints.Builder()
                    .addContentUriTrigger(CameraVideos.collection, true)
                    .setTriggerContentUpdateDelay(Duration.ofSeconds(10))
                    .setTriggerContentMaxDelay(Duration.ofMinutes(2))
                    .build(),
            ).build()
        WorkManager.getInstance(context).enqueueUniqueWork(TRIGGER, policy, req)
    }

    /**
     * Queues an upload run. APPEND_OR_REPLACE guarantees another pass after a running one, so videos
     * found mid-upload are never left behind. [replace] restarts it (used when constraints change).
     */
    fun enqueueUpload(context: Context, s: Settings, replace: Boolean = false) {
        val req = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (s.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .setRequiresCharging(s.chargingOnly)
                    .setRequiresStorageNotLow(false)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofMinutes(1))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UPLOAD, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE, req,
        )
    }

    fun hasVideoPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED

    /** Adds unseen camera videos to the queue; returns how many were new. */
    suspend fun scan(context: Context): Int {
        val s = SettingsStore(context).current()
        if (!s.enabled || !hasVideoPermission(context)) return 0
        val dao = UploadDb.get(context).dao()
        val known = dao.knownIds().toHashSet()
        val all = CameraVideos.query(context, s.sinceEpochSec)
        // Videos other apps saved into DCIM/Camera (downloads, edits) were auto-queued before 1.2.0 —
        // take them back out unless they're already uploading/uploaded.
        all.filter { !it.fromCamera && it.mediaId in known }.forEach { v ->
            val row = dao.get(v.mediaId)
            if (row != null && row.state == UploadState.QUEUED && row.bytesSent == 0L) {
                dao.put(row.copy(state = UploadState.SKIPPED, error = "Không phải video quay bằng camera (${v.ownerPackage ?: "không rõ nguồn"})"))
            }
        }
        val inWindow = all.filter { it.fromCamera }
        val fresh = inWindow.filter { it.mediaId !in known }.map { it.toItem() }
        if (fresh.isNotEmpty()) dao.insertNew(fresh)
        // Renamed in the Video tab before the scan saw them → still auto-upload (keeping the title).
        val parked = inWindow.map { it.mediaId }.filter { it in known }
        if (parked.isNotEmpty()) parked.chunked(500).forEach { dao.promoteIdle(it) }
        return fresh.size
    }

    suspend fun scanAndUpload(context: Context) {
        scan(context)
        val s = SettingsStore(context).current()
        if (s.connected && UploadDb.get(context).dao().pendingCount() > 0) enqueueUpload(context, s)
    }
}

class MediaTriggerWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        SyncScheduler.scanAndUpload(applicationContext)
        // Last statement: replacing ourselves cancels this (already finished) run and arms the next one.
        SyncScheduler.armTrigger(applicationContext, ExistingWorkPolicy.REPLACE)
        return Result.success()
    }
}

class PeriodicScanWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        SyncScheduler.scanAndUpload(applicationContext)
        SyncScheduler.armTrigger(applicationContext, ExistingWorkPolicy.KEEP)
        return Result.success()
    }
}

class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo =
        ForegroundInfo(Notifier.ID_PROGRESS, Notifier.progress(applicationContext, "Chuẩn bị…", 0, 0, 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val store = SettingsStore(ctx)
        // Runs for auto-sync AND for videos queued by hand in the Video tab (auto-sync may be off).
        if (!store.current().connected) return Result.success()
        Notifier.ensureChannels(ctx)
        val dao = UploadDb.get(ctx).dao()

        // Long uploads need a foreground service. Android may refuse to start one from the background
        // (unless battery optimisation is off); then we still upload, just within the normal job window.
        runCatching { setForeground(getForegroundInfo()) }

        var lastNotify = 0L
        var uploaded = 0
        // Speed = bytes since the previous sample of the same video, smoothed (EMA) so it doesn't jitter.
        var speedId = -1L; var speedAt = 0L; var speedBytes = 0L; var speed = 0.0
        val uploader = Uploader(dao, YouTubeApi(), AuthTokens(ctx), MediaStoreFiles(ctx), store::current)
        val outcome = uploader.runQueue { item, sent ->
            if (isStopped) throw CancellationException("worker stopped")
            if (sent >= item.sizeBytes) uploaded++
            val now = System.currentTimeMillis()
            if (item.mediaId != speedId || sent < speedBytes) {
                speedId = item.mediaId; speedAt = now; speedBytes = sent; speed = 0.0
            } else if (now - speedAt >= 500) {
                val inst = (sent - speedBytes) * 1000.0 / (now - speedAt)
                speed = if (speed == 0.0) inst else speed * 0.6 + inst * 0.4
                speedAt = now; speedBytes = sent
            }
            if (now - lastNotify > 1000 || sent >= item.sizeBytes) {
                lastNotify = now
                runCatching {
                    setForeground(ForegroundInfo(
                        Notifier.ID_PROGRESS,
                        Notifier.progress(ctx, item.displayName, sent, item.sizeBytes, dao.pendingCount(), speed),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                    ))
                }
            }
        }
        if (uploaded > 0) Notifier.uploaded(ctx, uploaded)

        return when (outcome) {
            QueueOutcome.DRAINED -> Result.success()
            QueueOutcome.NEEDS_AUTH -> { store.setConnected(false); Notifier.needsAuth(ctx); Result.success() }
            QueueOutcome.QUOTA -> Result.success() // the hourly scan re-queues; quota resets daily
            QueueOutcome.RETRY -> if (runAttemptCount < 8) Result.retry() else Result.success()
        }
    }
}

private class AuthTokens(private val ctx: Context) : TokenSource {
    override suspend fun token(): String? = when (val r = YouTubeAuth.token(ctx)) {
        is YouTubeAuth.Result.Token -> r.accessToken
        is YouTubeAuth.Result.NeedsUser -> null
        // Play services hiccup — treat like a network error rather than asking the user to re-login.
        is YouTubeAuth.Result.Error -> throw java.io.IOException("Google auth: ${r.message}")
    }

    override suspend fun invalidate(token: String) = YouTubeAuth.invalidate(ctx, token)
}

private class MediaStoreFiles(private val ctx: Context) : VideoFiles {
    override fun size(mediaId: Long): Long? = CameraVideos.currentSize(ctx, mediaId)

    override fun open(mediaId: Long, offset: Long): InputStream {
        val input = ctx.contentResolver.openInputStream(CameraVideos.uriOf(mediaId))
            ?: throw java.io.FileNotFoundException("video $mediaId")
        var left = offset
        while (left > 0) { // FileInputStream.skip seeks, so this is O(1) in practice
            val n = input.skip(left)
            if (n <= 0) { input.close(); throw java.io.IOException("cannot seek to $offset") }
            left -= n
        }
        return input
    }
}
