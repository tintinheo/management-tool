package com.audiohub.system

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect

object AppPermissions {
    val AUDIO: String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    /** Null below Android 13, where notifications need no runtime permission. */
    val NOTIFICATIONS: String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.POST_NOTIFICATIONS
        else null

    fun isGranted(context: Context, permission: String?): Boolean =
        permission == null ||
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

class PermissionHandle(val granted: Boolean, val request: () -> Unit)

/** Tracks a runtime permission, re-checking on every resume (user may change it in Settings). */
@Composable
fun rememberPermission(permission: String?): PermissionHandle {
    val context = LocalContext.current
    var granted by remember(permission) { mutableStateOf(AppPermissions.isGranted(context, permission)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
    }
    LifecycleResumeEffect(permission) {
        granted = AppPermissions.isGranted(context, permission)
        onPauseOrDispose { }
    }
    return PermissionHandle(granted) { if (permission != null) launcher.launch(permission) }
}
