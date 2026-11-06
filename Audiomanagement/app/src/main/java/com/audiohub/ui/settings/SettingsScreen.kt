package com.audiohub.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import com.audiohub.ui.theme.SectionLabelStyle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.audiohub.appContainer
import com.audiohub.data.storage.AudioFolders
import com.audiohub.system.BackgroundSettings
import com.audiohub.ui.components.ScreenHeader
import com.audiohub.ui.onboarding.ChecklistItem
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val container = context.appContainer
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    val navActions = com.audiohub.ui.library.LocalTrackActions.current
    var unrestricted by remember { mutableStateOf(BackgroundSettings.isIgnoringBatteryOptimizations(context)) }
    LifecycleResumeEffect(Unit) {
        unrestricted = BackgroundSettings.isIgnoringBatteryOptimizations(context)
        onPauseOrDispose { }
    }
    val current = settings ?: return

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader("Settings")
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle("Appearance")
            AppearanceSection(
                settings = current,
                onStyle = { scope.launch { container.settings.setUiStyle(it.id) } },
                onMotion = { scope.launch { container.settings.setArtMotion(it) } },
                onHeadingFont = { scope.launch { container.settings.setHeadingFont(it) } },
                onBodyFont = { scope.launch { container.settings.setBodyFont(it) } },
            )

            SectionTitle("Folders")
            FolderField(
                label = "Music folder",
                value = current.musicFolder,
                onSave = { path ->
                    scope.launch {
                        container.settings.setMusicFolder(path)
                        container.musicRepository.rescan()
                    }
                },
            )
            FolderField(
                label = "Podcast folder",
                value = current.podcastFolder,
                onSave = { path -> scope.launch { container.settings.setPodcastFolder(path) } },
            )

            SectionTitle("Podcasts")
            RegionPicker(
                current = current.podcastCountry,
                onPick = { code -> scope.launch { container.settings.setPodcastCountry(code) } },
            )
            SettingSwitch(
                title = "Download on Wi-Fi only",
                subtitle = "Episodes wait for Wi-Fi before downloading",
                checked = current.downloadWifiOnly,
                onCheckedChange = { scope.launch { container.settings.setDownloadWifiOnly(it) } },
            )
            SettingSwitch(
                title = "Remove played downloads",
                subtitle = "Delete an episode's file after you finish it",
                checked = current.deletePlayedDownloads,
                onCheckedChange = { scope.launch { container.settings.setDeletePlayedDownloads(it) } },
            )

            SectionTitle("Playback")
            SettingSwitch(
                title = "Skip silence in podcasts",
                subtitle = "Trims pauses so episodes finish sooner",
                checked = current.skipSilence,
                onCheckedChange = { scope.launch { container.settings.setSkipSilence(it) } },
            )
            Row(
                Modifier.fillMaxWidth().clickable { navActions.navigate(com.audiohub.ui.Routes.EQUALIZER) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Equalizer", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (current.eqEnabled) "On" else "Off",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SectionTitle("Background playback")
            ChecklistItem(
                done = unrestricted,
                title = "Ignore battery optimisation",
                description = "Keeps playback and downloads running with the screen off.",
                actionLabel = "Allow",
                onAction = { BackgroundSettings.requestIgnoreBatteryOptimizations(context) },
            )
            if (BackgroundSettings.isXiaomi) {
                ChecklistItem(
                    done = null,
                    title = "HyperOS battery saver",
                    description = "Choose \"No restrictions\".",
                    actionLabel = "Open",
                    onAction = { BackgroundSettings.openBatterySaver(context) },
                )
                ChecklistItem(
                    done = null,
                    title = "HyperOS autostart",
                    description = "Turn on Autostart for My Audio Hub.",
                    actionLabel = "Open",
                    onAction = { BackgroundSettings.openAutostart(context) },
                )
            }
            TextButton(onClick = { BackgroundSettings.openAppDetails(context) }) { Text("Open app info") }

            SectionTitle("Advanced")
            Row(
                Modifier.fillMaxWidth().clickable { navActions.navigate(com.audiohub.ui.Routes.youtube()) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("YouTube to audio", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Save a video's audio (best quality, optional start/end) to /Music/Youtube",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SectionTitle("About")
            val versionName = remember {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }
            Text("My Audio Hub $versionName", style = MaterialTheme.typography.bodyMedium)
            Row(
                Modifier.fillMaxWidth().clickable { navActions.navigate(com.audiohub.ui.Routes.DIAGNOSTICS) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Diagnostics", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "View and share the playback log",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text.uppercase(),
        style = SectionLabelStyle,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 20.dp),
    )
}

@Composable
private fun SettingSwitch(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = MaterialTheme.colorScheme.onSurface,
                checkedThumbColor = MaterialTheme.colorScheme.surface,
                uncheckedBorderColor = MaterialTheme.colorScheme.outline,
            ),
        )
    }
}

private val REGIONS = listOf(
    "vn" to "Vietnam", "au" to "Australia", "us" to "United States", "gb" to "United Kingdom",
    "ca" to "Canada", "sg" to "Singapore", "jp" to "Japan", "kr" to "South Korea",
    "fr" to "France", "de" to "Germany",
)

/** Apple Podcasts storefront used for search results and Top Charts. */
@Composable
private fun RegionPicker(current: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = REGIONS.firstOrNull { it.first == current }?.second ?: current.uppercase()
    Row(
        Modifier.fillMaxWidth().clickable { open = true }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Apple Podcasts region", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Used for search and Top Charts",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                REGIONS.forEach { (code, name) ->
                    DropdownMenuItem(text = { Text(name) }, onClick = { open = false; onPick(code) })
                }
            }
        }
    }
}

@Composable
private fun FolderField(label: String, value: String, onSave: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    val normalized = AudioFolders.normalize(text)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(label) },
            prefix = { Text("/") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = { onSave(normalized) },
            enabled = normalized.isNotEmpty() && normalized != value,
        ) { Text("Save") }
    }
}
