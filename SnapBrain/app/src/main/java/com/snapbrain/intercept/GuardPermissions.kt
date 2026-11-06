package com.snapbrain.intercept

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process
import android.provider.Settings

/**
 * The guard needs two special permissions, both granted by the user in system settings:
 * Usage access (which app is in the foreground) and Display over other apps (show the cover screen).
 */
object GuardPermissions {

    fun usageAccess(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) ==
            AppOpsManager.MODE_ALLOWED
    }

    fun overlay(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun ready(context: Context): Boolean = usageAccess(context) && overlay(context)

    fun openUsageAccess(context: Context) {
        // Some ROMs accept the package URI and jump straight to SnapBrain's row.
        val direct = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        val ok = runCatching { context.startActivity(direct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        if (!ok) context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openOverlay(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Opens whichever permission is still missing. */
    fun openMissing(context: Context) {
        if (!usageAccess(context)) openUsageAccess(context) else openOverlay(context)
    }
}
