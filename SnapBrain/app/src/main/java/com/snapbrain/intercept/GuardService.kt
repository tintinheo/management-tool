package com.snapbrain.intercept

import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.snapbrain.MainActivity
import com.snapbrain.R
import com.snapbrain.appContainer
import com.snapbrain.work.Reminders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service that watches which app is in front (Usage access: app-switch events only, never
 * screen content) and covers blocked apps with [InterceptActivity] unless free time is running.
 * Polls every ~0.7 s while the screen is on; sleeps while it's off.
 */
class GuardService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    private lateinit var engine: GuardEngine

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        engine = GuardEngine(packageName)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The specialUse type exists from Android 14; earlier versions take no type for this service.
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
        if (loop == null) loop = scope.launch { watch() }
        return START_STICKY
    }

    private suspend fun watch() {
        val usage = getSystemService(UsageStatsManager::class.java) ?: return
        val power = getSystemService(PowerManager::class.java)
        val container = applicationContext.appContainer
        var since = System.currentTimeMillis() - 60_000
        val event = UsageEvents.Event()
        while (scope.isActive) {
            if (power?.isInteractive == false) {
                delay(SCREEN_OFF_DELAY)
                continue
            }
            val now = System.currentTimeMillis()
            val events = runCatching { usage.queryEvents(since, now) }.getOrNull()
            while (events != null && events.getNextEvent(event)) {
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) engine.onForeground(event.packageName)
                since = maxOf(since, event.timeStamp + 1)
            }
            // Events can land a moment late; never look further back than a few seconds.
            since = maxOf(since, now - 5_000)

            val settings = container.settings.value
            val action = engine.tick(container.clock.now(), settings.blocked, settings.freeUntil, InterceptActivity.visible)
            if (action != null) {
                withContext(Dispatchers.Main) {
                    runCatching { startActivity(InterceptActivity.intent(this@GuardService, action.pkg, action.expired)) }
                }
            }
            delay(POLL_DELAY)
        }
    }

    private fun notification() = NotificationCompat.Builder(this, Reminders.CHANNEL_GUARD)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("SnapBrain đang bảo vệ bạn")
        .setContentText("Mở app bị chặn sẽ hiện thẻ kiến thức trước.")
        .setOngoing(true)
        .setSilent(true)
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .setContentIntent(
            PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE),
        )
        .build()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 42
        private const val POLL_DELAY = 700L
        private const val SCREEN_OFF_DELAY = 2_000L

        /** Starts the guard if both permissions are granted. Safe to call repeatedly. */
        fun start(context: Context): Boolean {
            if (!GuardPermissions.ready(context)) return false
            return runCatching {
                ContextCompat.startForegroundService(context, Intent(context, GuardService::class.java))
            }.isSuccess
        }
    }
}

/** Restarts the guard after a reboot or an app update (Xiaomi: needs Autostart enabled). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            if (context.appContainer.settings.value.onboarded) GuardService.start(context)
        }
    }
}
