package com.audiohub.ui.ringtone

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PhoneInTalk
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.audiohub.appContainer
import com.audiohub.data.db.TrackEntity
import com.audiohub.data.music.MusicScanner
import com.audiohub.data.ringtone.RingtoneClip
import com.audiohub.data.ringtone.RingtoneMaker
import com.audiohub.data.ringtone.RingtoneTarget
import com.audiohub.ui.components.Artwork
import com.audiohub.ui.components.Hairline
import com.audiohub.ui.components.ScreenHeader
import com.audiohub.ui.components.SectionLabel
import com.audiohub.ui.library.DetailTopBar
import com.audiohub.ui.library.LocalTrackActions
import kotlinx.coroutines.launch

/** Where the editor is in its flow. */
sealed interface RingtoneStatus {
    data object Editing : RingtoneStatus
    data object Saving : RingtoneStatus
    data class Saved(val uri: Uri) : RingtoneStatus
}

@Composable
fun RingtoneScreen(trackId: Long) {
    val context = LocalContext.current
    val container = context.appContainer
    val track by produceState<TrackEntity?>(null, trackId) { value = container.musicRepository.track(trackId) }
    val t = track ?: return
    val maker = remember { RingtoneMaker(context.applicationContext) }
    val scope = rememberCoroutineScope()

    val initial = remember(t.id) { RingtoneClip.defaultRange(t.durationMs) }
    var start by remember(t.id) { mutableLongStateOf(initial.first) }
    var end by remember(t.id) { mutableLongStateOf(initial.second) }
    var status by remember { mutableStateOf<RingtoneStatus>(RingtoneStatus.Editing) }

    // Separate preview player so the user's queue isn't touched (audio focus pauses the main player).
    val preview = remember { ExoPlayer.Builder(context).setHandleAudioBecomingNoisy(true).build() }
    var previewing by remember { mutableStateOf(false) }
    DisposableEffect(preview) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { previewing = isPlaying }
        }
        preview.addListener(listener)
        onDispose { preview.removeListener(listener); preview.release() }
    }
    LaunchedEffect(start, end) { if (preview.isPlaying) preview.stop() }

    RingtoneContent(
        track = t,
        startMs = start,
        endMs = end,
        onRangeChange = { s, e -> start = s; end = e; status = RingtoneStatus.Editing },
        previewing = previewing,
        onTogglePreview = {
            if (preview.isPlaying) {
                preview.stop()
            } else {
                preview.setMediaItem(
                    MediaItem.Builder().setUri(t.uri).setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder().setStartPositionMs(start).setEndPositionMs(end).build(),
                    ).build(),
                )
                preview.prepare()
                preview.play()
            }
        },
        status = status,
        onSave = {
            preview.stop()
            status = RingtoneStatus.Saving
            scope.launch {
                status = try {
                    RingtoneStatus.Saved(maker.export(Uri.parse(t.uri), start, end, "${t.title} (ringtone)"))
                } catch (e: Exception) {
                    Toast.makeText(context, "Couldn't create the ringtone: ${e.message}", Toast.LENGTH_LONG).show()
                    RingtoneStatus.Editing
                }
            }
        },
        onSetAs = { uri, target ->
            if (!maker.canSetSystemSounds()) {
                Toast.makeText(context, "Allow \"Modify system settings\", then tap again", Toast.LENGTH_LONG).show()
                runCatching { context.startActivity(maker.systemSettingsPermissionIntent()) }
            } else {
                val ok = runCatching { maker.setAsDefault(uri, target) }.isSuccess
                Toast.makeText(context, if (ok) "Set as ${target.label.lowercase()}" else "Couldn't change the ${target.label.lowercase()}", Toast.LENGTH_SHORT).show()
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RingtoneContent(
    track: TrackEntity,
    startMs: Long,
    endMs: Long,
    onRangeChange: (Long, Long) -> Unit,
    previewing: Boolean,
    onTogglePreview: () -> Unit,
    status: RingtoneStatus,
    onSave: () -> Unit,
    onSetAs: (Uri, RingtoneTarget) -> Unit,
) {
    val actions = LocalTrackActions.current
    val duration = track.durationMs.coerceAtLeast(1)
    val error = RingtoneClip.validate(startMs, endMs, track.durationMs)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        DetailTopBar("", onBack = actions::back)
        ScreenHeader("Make ringtone", subtitle = "Choose the part of the song to use")

        Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(MusicScanner.albumArtUri(track.albumId), Modifier.size(56.dp), cornerRadius = 8.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(track.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${track.artist} · ${com.audiohub.ui.components.formatDuration(track.durationMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SectionLabel("Clip")
        RangeSlider(
            value = startMs.toFloat()..endMs.toFloat(),
            onValueChange = { r -> onRangeChange(r.start.toLong(), r.endInclusive.toLong()) },
            valueRange = 0f..duration.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.onSurface,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant,
            ),
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TimeField("Start", startMs, Modifier.weight(1f)) { onRangeChange(it, endMs) }
            TimeField("End", endMs, Modifier.weight(1f)) { onRangeChange(startMs, it) }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row {
                Nudge("−0.5s") { onRangeChange((startMs - 500).coerceAtLeast(0), endMs) }
                Nudge("+0.5s") { onRangeChange((startMs + 500).coerceAtMost(endMs - 500), endMs) }
            }
            Text(
                "Length ${RingtoneClip.formatTime(endMs - startMs)}",
                style = MaterialTheme.typography.labelMedium,
                color = if (error == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
            Row {
                Nudge("−0.5s") { onRangeChange(startMs, (endMs - 500).coerceAtLeast(startMs + 500)) }
                Nudge("+0.5s") { onRangeChange(startMs, (endMs + 500).coerceAtMost(track.durationMs)) }
            }
        }
        if (error != null) {
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp))
        }

        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = onTogglePreview,
                enabled = error == null && status !is RingtoneStatus.Saving,
                shape = MaterialTheme.shapes.medium,
                border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outline),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                modifier = Modifier.weight(1f).height(48.dp),
            ) {
                Icon(if (previewing) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(if (previewing) "Stop" else "Preview")
            }
            Button(
                onClick = onSave,
                enabled = error == null && status is RingtoneStatus.Editing,
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.onSurface,
                    contentColor = MaterialTheme.colorScheme.surface,
                ),
                modifier = Modifier.weight(1f).height(48.dp),
            ) {
                if (status is RingtoneStatus.Saving) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.surface)
                } else {
                    Text("Save ringtone")
                }
            }
        }

        if (status is RingtoneStatus.Saved) {
            SectionLabel("Saved to Ringtones · use it as")
            listOf(
                Triple(RingtoneTarget.RINGTONE, Icons.Rounded.PhoneInTalk, "Calls"),
                Triple(RingtoneTarget.NOTIFICATION, Icons.Rounded.Notifications, "Messages & apps"),
                Triple(RingtoneTarget.ALARM, Icons.Rounded.Alarm, "Alarms"),
            ).forEach { (target, icon, hint) ->
                TextButton(
                    onClick = { onSetAs(status.uri, target) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                ) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(target.label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Hairline(start = 56.dp)
            }
            Text(
                "It's also available in Settings → Sound for contacts or other sounds.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * m:ss.s entry. Typing is free-form; the value is committed on Done or when the field loses focus
 * (committing per keystroke would reformat "1:2" to "1:02.0" mid-typing). Follows the slider otherwise.
 */
@Composable
internal fun TimeField(label: String, valueMs: Long, modifier: Modifier, onCommit: (Long) -> Unit) {
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf(RingtoneClip.formatTime(valueMs)) }
    LaunchedEffect(valueMs, focused) { if (!focused) text = RingtoneClip.formatTime(valueMs) }
    val parsed = RingtoneClip.parseTime(text)
    OutlinedTextField(
        value = text,
        onValueChange = { v -> text = v.filter { it.isDigit() || it == ':' || it == '.' }.take(9) },
        label = { Text(label) },
        singleLine = true,
        isError = parsed == null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        modifier = modifier.onFocusChanged { state ->
            if (focused && !state.isFocused) parsed?.let(onCommit)
            focused = state.isFocused
        },
    )
}

@Composable
private fun Nudge(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
