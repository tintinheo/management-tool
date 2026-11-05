package com.audiohub.ui.settings

import android.widget.Toast
import com.audiohub.system.SoundEffectsLauncher
import android.media.AudioManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.audiohub.appContainer
import com.audiohub.playback.AudioEffects
import com.audiohub.playback.EqualizerInfo
import com.audiohub.ui.components.EmptyState
import com.audiohub.ui.components.Hairline
import com.audiohub.ui.components.ScreenHeader
import com.audiohub.ui.components.SectionLabel
import com.audiohub.ui.components.ThinSeekBar
import com.audiohub.ui.library.DetailTopBar
import com.audiohub.ui.library.LocalTrackActions
import kotlinx.coroutines.launch

/** Preset index used to mean "custom band levels". */
const val CUSTOM_PRESET = -1

@Composable
fun EqualizerScreen() {
    val context = LocalContext.current
    val container = context.appContainer
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val info = remember { AudioEffects.info(context.getSystemService(AudioManager::class.java)) }
    val scope = rememberCoroutineScope()
    val s = settings ?: return
    EqualizerContent(
        info = info,
        enabled = s.eqEnabled,
        preset = s.eqPreset,
        bands = s.eqBands,
        onChange = { enabled, preset, bands -> scope.launch { container.settings.setEqualizer(enabled, preset, bands) } },
        onOpenSystem = {
            val message = when (SoundEffectsLauncher.open(context)) {
                SoundEffectsLauncher.Target.SOUND_SETTINGS ->
                    "Opened Sound settings — tap \"Sound effects\" (Dolby Atmos) there"
                null -> "Couldn't open the sound settings on this phone"
                else -> null
            }
            message?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
        },
    )
}

@Composable
fun EqualizerContent(
    info: EqualizerInfo?,
    enabled: Boolean,
    preset: Int,
    bands: List<Int>,
    onChange: (enabled: Boolean, preset: Int, bands: List<Int>) -> Unit,
    onOpenSystem: () -> Unit,
) {
    val actions = LocalTrackActions.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        DetailTopBar("", onBack = actions::back)
        ScreenHeader("Equalizer", subtitle = "Applies to music and podcasts")
        if (info == null) {
            EmptyState(
                Icons.Rounded.GraphicEq,
                "No built-in equalizer",
                "This device doesn't expose one. Use the system sound effects instead.",
                modifier = Modifier.padding(top = 24.dp),
                action = { TextButton(onClick = onOpenSystem) { Text("Open sound effects") } },
            )
            return
        }
        val levels = remember(bands, info) {
            List(info.bandFrequencies.size) { i -> bands.getOrElse(i) { 0 } }
        }
        Row(
            Modifier.fillMaxWidth().clickable { onChange(!enabled, preset, levels) }.padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Equalizer", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(
                checked = enabled,
                onCheckedChange = { onChange(it, preset, levels) },
                colors = SwitchDefaults.colors(
                    checkedTrackColor = MaterialTheme.colorScheme.onSurface,
                    checkedThumbColor = MaterialTheme.colorScheme.surface,
                    uncheckedBorderColor = MaterialTheme.colorScheme.outline,
                ),
            )
        }
        Hairline()

        SectionLabel("Presets")
        (info.presets.mapIndexed { i, name -> i to name } + (CUSTOM_PRESET to "Custom")).forEach { (index, name) ->
            Row(
                Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(true, index, levels) }
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (index == preset) {
                    Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                }
            }
        }

        if (preset == CUSTOM_PRESET) {
            SectionLabel("Bands")
            var draft by remember(levels) { mutableStateOf(levels) }
            val range = (info.maxLevel - info.minLevel).coerceAtLeast(1)
            info.bandFrequencies.forEachIndexed { i, hz ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (hz >= 1000) "${hz / 1000} kHz" else "$hz Hz",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(64.dp),
                    )
                    ThinSeekBar(
                        value = (draft[i] - info.minLevel).toFloat() / range,
                        onValueChange = { v ->
                            draft = draft.toMutableList().also { it[i] = (info.minLevel + v * range).toInt() }
                        },
                        onValueChangeFinished = { onChange(true, CUSTOM_PRESET, draft) },
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "%+d dB".format(draft[i] / 100),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(52.dp).padding(start = 8.dp),
                    )
                }
            }
        }

        SectionLabel("System")
        TextButton(onClick = onOpenSystem, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text("Open device sound effects (Dolby, Mi Sound…)")
        }
        Text(
            "On Xiaomi you can also go to Settings → Sound & vibration → Sound effects. " +
                "Prefer the phone's own effects there: they work in every app and don't clash with Dolby.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
    }
}
