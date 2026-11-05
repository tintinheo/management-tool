package com.audiohub.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.audiohub.data.storage.AudioFolders
import com.audiohub.system.AppPermissions
import com.audiohub.system.BackgroundSettings
import com.audiohub.system.rememberPermission

@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (step) {
            0 -> WelcomeStep(onNext = { step = 1 })
            1 -> PermissionsStep(onNext = { step = 2 })
            else -> BackgroundStep(onDone = {
                AudioFolders.ensureExists(AudioFolders.DEFAULT_MUSIC)
                AudioFolders.ensureExists(AudioFolders.DEFAULT_PODCAST)
                onFinished()
            })
        }
    }
}

@Composable
private fun WelcomeStep(onNext: () -> Unit) {
    Text("Welcome to My Audio Hub", style = MaterialTheme.typography.headlineMedium)
    Text("Your music and podcasts, ad-free and stored on your phone.", style = MaterialTheme.typography.bodyLarge)
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Where files live", style = MaterialTheme.typography.titleMedium)
            FolderLine("Music — copy your files into", AudioFolders.DEFAULT_MUSIC)
            FolderLine("Podcasts — downloads are saved to", AudioFolders.DEFAULT_PODCAST)
            Text(
                "Tip: connect the phone to your PC over USB (File transfer) and copy into Music/AudioHub.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) { Text("Get started") }
}

@Composable
private fun FolderLine(label: String, relativePath: String) {
    Column {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            "/$relativePath",
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun PermissionsStep(onNext: () -> Unit) {
    val audio = rememberPermission(AppPermissions.AUDIO)
    val notifications = rememberPermission(AppPermissions.NOTIFICATIONS)

    Text("Permissions", style = MaterialTheme.typography.headlineMedium)
    ChecklistItem(
        done = audio.granted,
        title = "Music and audio",
        description = "Read songs and podcast files from your folders. Required.",
        actionLabel = "Allow",
        onAction = audio.request,
    )
    ChecklistItem(
        done = notifications.granted,
        title = "Notifications",
        description = "Playback controls, download progress and new-episode alerts.",
        actionLabel = "Allow",
        onAction = notifications.request,
    )
    Button(onClick = onNext, enabled = audio.granted, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
}

@Composable
private fun BackgroundStep(onDone: () -> Unit) {
    val context = LocalContext.current
    var unrestricted by remember { mutableStateOf(BackgroundSettings.isIgnoringBatteryOptimizations(context)) }
    LifecycleResumeEffect(Unit) {
        unrestricted = BackgroundSettings.isIgnoringBatteryOptimizations(context)
        onPauseOrDispose { }
    }

    Text("Keep playing in the background", style = MaterialTheme.typography.headlineMedium)
    Text(
        if (BackgroundSettings.isXiaomi) {
            "HyperOS aggressively stops background apps. Do these steps so music doesn't stop when the screen is off."
        } else {
            "Allow My Audio Hub to run in the background so playback and downloads aren't interrupted."
        },
        style = MaterialTheme.typography.bodyLarge,
    )
    ChecklistItem(
        done = unrestricted,
        title = "Ignore battery optimisation",
        description = "Lets playback and downloads keep running with the screen off.",
        actionLabel = "Allow",
        onAction = { BackgroundSettings.requestIgnoreBatteryOptimizations(context) },
    )
    if (BackgroundSettings.isXiaomi) {
        ChecklistItem(
            done = null,
            title = "Battery saver → No restrictions",
            description = "In the screen that opens, choose \"No restrictions\".",
            actionLabel = "Open",
            onAction = { BackgroundSettings.openBatterySaver(context) },
        )
        ChecklistItem(
            done = null,
            title = "Autostart",
            description = "Turn on Autostart for My Audio Hub so it can resume after a reboot.",
            actionLabel = "Open",
            onAction = { BackgroundSettings.openAutostart(context) },
        )
        Text(
            "Optional: open Recents, long-press My Audio Hub and tap the lock icon.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(8.dp))
    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
}

/** [done] = null means the state can't be detected (Xiaomi-only screens). */
@Composable
fun ChecklistItem(
    done: Boolean?,
    title: String,
    description: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (done == true) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (done == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (done != true) {
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}
