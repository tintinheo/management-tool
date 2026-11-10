package com.ytsync.ui

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Xiaomi/HyperOS kills background work aggressively. These deep links aren't public APIs, so each
 * falls back to the system "App info" page.
 */
object PhoneSetup {
    val isXiaomi: Boolean
        get() = Build.MANUFACTURER.lowercase() in setOf("xiaomi", "redmi", "poco")

    fun ignoresBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    /** System dialog "Allow app to always run in background?" — also lets uploads start a foreground service. */
    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations(context: Context) = launch(
        context,
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
    )

    fun openAutostart(context: Context) = launch(
        context,
        Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
    )

    fun openBattery(context: Context) = launch(
        context,
        Intent()
            .setComponent(ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"))
            .putExtra("package_name", context.packageName)
            .putExtra("package_label", "Youtube Sync"),
    )

    fun openAppInfo(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun openUrl(context: Context, url: String) = launch(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    private fun launch(context: Context, intent: Intent) {
        val ok = runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        if (!ok) runCatching { openAppInfo(context) }
    }
}
