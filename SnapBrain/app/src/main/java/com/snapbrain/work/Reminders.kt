package com.snapbrain.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.snapbrain.MainActivity
import com.snapbrain.R
import com.snapbrain.appContainer
import com.snapbrain.intercept.GuardService
import com.snapbrain.widget.QuizWidget
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/** Hourly check: streak about to break, streak repairable, guard switched off, widget question stale. */
class CheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val container = ctx.appContainer
        val settings = container.settings.value
        if (!settings.onboarded) return Result.success()
        val today = container.clock.today()
        val now = container.clock.now()

        runCatching { QuizWidget.refreshStale(ctx) }

        val streak = container.study.streakNow()
        if (settings.streakNotifiedDay != today) {
            val notice = when {
                streak.repairableDay != null ->
                    Notice("Cứu chuỗi ${streak.brokenLength} ngày 🔥", "Hôm qua bạn lỡ mất. Trả lời đúng 3 câu liên tiếp để giữ chuỗi.", MainActivity.OPEN_SAVER)
                !streak.studiedToday && streak.current > 0 && LocalTime.now().hour >= settings.saverHour ->
                    Notice("Chuỗi ${streak.current} ngày sắp đứt 🔥", "Chỉ cần 1 thẻ (30 giây) để giữ chuỗi hôm nay.", MainActivity.OPEN_FEED)
                else -> null
            }
            if (notice != null && Reminders.notify(ctx, Reminders.ID_STREAK, notice)) {
                container.settings.update { it.copy(streakNotifiedDay = today) }
            }
        }

        // Restart the guard if the system killed it; nag (at most every 6 h) if a permission was revoked.
        val running = GuardService.start(ctx)
        if (!running && now - settings.guardNotifiedAt > 6 * 3_600_000L) {
            val sent = Reminders.notify(
                ctx,
                Reminders.ID_GUARD,
                Notice("Bộ chặn SnapBrain đang tắt", "SnapBrain cần được cấp lại quyền. Chạm để bật lại.", MainActivity.OPEN_HOME),
            )
            if (sent) container.settings.update { it.copy(guardNotifiedAt = now) }
        }
        return Result.success()
    }
}

data class Notice(val title: String, val text: String, val open: String)

object Reminders {
    const val CHANNEL = "reminders"
    const val CHANNEL_GUARD = "guard"
    const val ID_STREAK = 1
    const val ID_GUARD = 2

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Nhắc chuỗi & bộ chặn", NotificationManager.IMPORTANCE_DEFAULT),
        )
        // The always-on guard notification: lowest importance, no sound, collapsed in the shade.
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_GUARD, "Bộ chặn đang chạy", NotificationManager.IMPORTANCE_MIN),
        )
    }

    fun schedule(context: Context) {
        runCatching {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "check",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<CheckWorker>(1, TimeUnit.HOURS).build(),
            )
        }
    }

    fun notify(context: Context, id: Int, notice: Notice): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        val open = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN, notice.open)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(notice.title)
            .setContentText(notice.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notice.text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        return runCatching { NotificationManagerCompat.from(context).notify(id, notification) }.isSuccess
    }
}
