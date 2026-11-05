package com.audiohub.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
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
import androidx.work.workDataOf
import com.audiohub.MainActivity
import com.audiohub.R
import com.audiohub.appContainer
import com.audiohub.data.db.PodcastDao
import com.audiohub.data.podcast.DownloadState
import com.audiohub.data.settings.SettingsRepository
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.util.concurrent.TimeUnit

object Notifications {
    const val CHANNEL_DOWNLOADS = "downloads"
    const val CHANNEL_NEW_EPISODES = "new_episodes"
    private const val NEW_EPISODES_ID = 1001

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DOWNLOADS, "Episode downloads", NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_NEW_EPISODES, "New episodes", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun showNewEpisodes(context: Context, count: Int) {
        if (count <= 0) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val n = NotificationCompat.Builder(context, CHANNEL_NEW_EPISODES)
            .setSmallIcon(R.drawable.ic_stat_audiohub)
            .setContentTitle(if (count == 1) "1 new episode" else "$count new episodes")
            .setContentText("From the shows you follow")
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NEW_EPISODES_ID, n) }
    }
}

/** Queues/cancels episode downloads through WorkManager (survives app restarts and reboots). */
class DownloadScheduler(
    private val context: Context,
    private val dao: PodcastDao,
    private val settings: SettingsRepository,
) {
    private fun workName(episodeId: Long) = "download-$episodeId"

    suspend fun enqueue(episodeId: Long) {
        dao.setDownloadState(episodeId, DownloadState.QUEUED)
        val network = if (settings.current().downloadWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
        val request = OneTimeWorkRequestBuilder<EpisodeDownloadWorker>()
            .setInputData(workDataOf(EpisodeDownloadWorker.KEY_EPISODE_ID to episodeId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(EpisodeDownloadWorker.TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(episodeId), ExistingWorkPolicy.KEEP, request)
    }

    suspend fun cancel(episodeId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(episodeId))
        dao.setDownloadState(episodeId, DownloadState.NONE)
    }

    companion object {
        private const val REFRESH_WORK = "feed-refresh"

        /** Periodic background refresh of followed shows (+ auto-downloads, + notification). */
        fun schedulePeriodicRefresh(context: Context) {
            val request = PeriodicWorkRequestBuilder<FeedRefreshWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(REFRESH_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

class EpisodeDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val episodeId = inputData.getLong(KEY_EPISODE_ID, -1)
        if (episodeId < 0) return Result.failure()
        val container = applicationContext.appContainer
        val title = container.podcastRepository.episode(episodeId)?.episode?.title ?: "Episode"

        // Foreground so long downloads aren't killed; may be refused when started from the background.
        runCatching { setForeground(foregroundInfo(title, null)) }

        var lastShown = -10
        val ok = container.episodeDownloader.download(episodeId) { p ->
            val pct = (p * 100).toInt()
            setProgress(workDataOf(KEY_PROGRESS to p))
            if (pct - lastShown >= 5) {
                lastShown = pct
                runCatching { setForeground(foregroundInfo(title, pct)) }
            }
        }
        return when {
            ok -> Result.success()
            runAttemptCount < MAX_ATTEMPTS -> Result.retry()
            else -> Result.failure()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("Downloading episode", null)

    private fun foregroundInfo(title: String, percent: Int?): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, Notifications.CHANNEL_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_stat_audiohub)
            .setContentTitle(title)
            .setContentText("Downloading")
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, percent ?: 0, percent == null)
            .setContentIntent(Notifications.openAppIntent(applicationContext))
            .build()
        return ForegroundInfo(
            NOTIFICATION_BASE + (inputData.getLong(KEY_EPISODE_ID, 0) % 1000).toInt(),
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    companion object {
        const val KEY_EPISODE_ID = "episodeId"
        const val KEY_PROGRESS = "progress"
        const val TAG = "episode-download"
        private const val MAX_ATTEMPTS = 3
        private const val NOTIFICATION_BASE = 2000
    }
}

class FeedRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = applicationContext.appContainer
        val repo = container.podcastRepository
        val newEpisodes = runCatching { repo.refreshAll(maxAgeMs = 60 * 60 * 1000L) }.getOrDefault(0)
        repo.autoDownloadCandidates().forEach { container.downloadScheduler.enqueue(it) }
        Notifications.showNewEpisodes(applicationContext, newEpisodes)
        return Result.success()
    }
}
