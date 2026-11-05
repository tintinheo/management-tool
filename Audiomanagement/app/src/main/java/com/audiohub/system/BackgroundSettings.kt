package com.audiohub.system

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.net.toUri

/**
 * Shortcuts into the system screens that stop HyperOS/MIUI from killing background playback
 * and downloads. Xiaomi-specific screens are tried first, falling back to stock Android ones.
 */
object BackgroundSettings {

    val isXiaomi: Boolean
        get() = listOf(Build.MANUFACTURER, Build.BRAND).any {
            it.equals("Xiaomi", ignoreCase = true) ||
                it.equals("Redmi", ignoreCase = true) ||
                it.equals("POCO", ignoreCase = true)
        }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    @SuppressLint("BatteryLife") // Sideloaded personal app; uninterrupted playback is the core feature.
    fun requestIgnoreBatteryOptimizations(context: Context) = launchFirst(
        context,
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri()),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
    )

    /** HyperOS "Battery saver" per-app page, where "No restrictions" must be chosen. */
    fun openBatterySaver(context: Context) = launchFirst(
        context,
        Intent().setComponent(
            ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"),
        )
            .putExtra("package_name", context.packageName)
            .putExtra("package_label", context.applicationInfo.loadLabel(context.packageManager).toString()),
        appDetails(context),
    )

    /** HyperOS "Autostart" list. */
    fun openAutostart(context: Context) = launchFirst(
        context,
        Intent().setComponent(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        ),
        Intent("miui.intent.action.OP_AUTO_START"),
        appDetails(context),
    )

    fun openAppDetails(context: Context) = launchFirst(context, appDetails(context))

    private fun appDetails(context: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())

    private fun launchFirst(context: Context, vararg intents: Intent): Boolean {
        for (intent in intents) {
            val started = runCatching {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            if (started) return true
        }
        return false
    }
}
