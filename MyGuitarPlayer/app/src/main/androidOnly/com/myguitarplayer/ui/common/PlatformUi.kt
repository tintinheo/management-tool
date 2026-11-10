package com.myguitarplayer.ui.common

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat

// Android side of the small UI hooks the shared screens use. The Windows build has its own file with the
// same names (desktop/src/main/kotlin/.../PlatformUi.kt).

/** System back gesture / button. */
@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) =
    androidx.activity.compose.BackHandler(enabled, onBack)

/** Keeps the display on while a practice screen is visible (hands are on the guitar). */
@Composable
fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

/** Returns whether RECORD_AUDIO is granted, and a function that asks for it. */
@Composable
fun rememberMicPermission(): Pair<Boolean, () -> Unit> {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    return granted to { launcher.launch(Manifest.permission.RECORD_AUDIO) }
}
