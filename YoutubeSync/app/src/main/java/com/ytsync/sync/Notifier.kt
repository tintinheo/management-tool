package com.ytsync.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.ytsync.R
import com.ytsync.ui.MainActivity

object Notifier {
    const val CH_PROGRESS = "progress"
    const val CH_EVENTS = "events"
    const val ID_PROGRESS = 1
    private const val ID_AUTH = 2
    private const val ID_DONE = 3

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_PROGRESS, "Đang tải lên", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_EVENTS, "Kết quả tải lên", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun openApp(context: Context, reconnect: Boolean = false): PendingIntent = PendingIntent.getActivity(
        context, if (reconnect) 1 else 0,
        Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_RECONNECT, reconnect)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun progress(context: Context, name: String, sent: Long, total: Long, left: Int, bytesPerSec: Double = 0.0): Notification {
        val pct = if (total > 0) (sent * 100 / total).toInt() else 0
        val speed = if (bytesPerSec > 0) String.format(java.util.Locale.US, " · %.1f MB/s", bytesPerSec / 1e6) else ""
        return NotificationCompat.Builder(context, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_upload)
            .setContentTitle("Đang tải lên YouTube · $pct%$speed")
            .setContentText(if (left > 1) "$name · còn $left video" else name)
            .setProgress(100, pct, total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(context))
            .build()
    }

    fun needsAuth(context: Context) = post(
        context, ID_AUTH,
        NotificationCompat.Builder(context, CH_EVENTS)
            .setSmallIcon(R.drawable.ic_stat_upload)
            .setContentTitle("Cần kết nối lại YouTube")
            .setContentText("Video đang chờ tải lên. Chạm để cấp quyền lại.")
            .setAutoCancel(true)
            .setContentIntent(openApp(context, reconnect = true))
            .build(),
    )

    fun uploaded(context: Context, count: Int) = post(
        context, ID_DONE,
        NotificationCompat.Builder(context, CH_EVENTS)
            .setSmallIcon(R.drawable.ic_stat_upload)
            .setContentTitle("Đã tải lên $count video (riêng tư)")
            .setContentText("Chạm để xem danh sách")
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build(),
    )

    private fun post(context: Context, id: Int, n: Notification) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        try { nm.notify(id, n) } catch (_: SecurityException) { } // permission revoked in between
    }
}
