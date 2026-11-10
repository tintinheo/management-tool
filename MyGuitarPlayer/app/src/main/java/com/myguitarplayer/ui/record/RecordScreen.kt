package com.myguitarplayer.ui.record

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.myguitarplayer.audio.AudioPlayback
import com.myguitarplayer.audio.AudioRecorder
import com.myguitarplayer.data.Repos
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myguitarplayer.data.Recording
import com.myguitarplayer.ui.common.Card
import com.myguitarplayer.ui.common.KeepScreenOn
import com.myguitarplayer.ui.common.ScreenHeader
import com.myguitarplayer.ui.common.rememberMicPermission
import com.myguitarplayer.ui.theme.Palette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RecordScreen(onBack: () -> Unit) {
    KeepScreenOn()
    val repo = Repos.practice
    val scope = rememberCoroutineScope()
    val (granted, ask) = rememberMicPermission()
    val list by remember { repo.recordings() }.collectAsStateWithLifecycle(initialValue = emptyList())

    var recorder by remember { mutableStateOf<AudioRecorder?>(null) }
    var file by remember { mutableStateOf<File?>(null) }
    var startedAt by remember { mutableLongStateOf(0L) }
    var elapsed by remember { mutableLongStateOf(0L) }
    val player = remember { AudioPlayback() }
    var playingId by remember { mutableStateOf<Long?>(null) }
    var expandedId by remember { mutableStateOf<Long?>(null) }
    var confirmDelete by remember { mutableStateOf<Recording?>(null) }

    fun stopPlayback() {
        player.stop()
        playingId = null
    }

    fun startRecording() {
        stopPlayback()
        val f = repo.newRecordingFile()
        val r = AudioRecorder()
        if (!r.start(f)) return
        recorder = r
        file = f
        startedAt = System.currentTimeMillis()
        elapsed = 0
    }

    fun stopRecording() {
        val r = recorder ?: return
        val f = file
        val ms = System.currentTimeMillis() - startedAt
        val ok = r.stop()
        recorder = null
        if (f == null) return
        if (!ok || ms < 1000) { f.delete(); return }
        val title = "Bản ghi " + SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(startedAt))
        scope.launch { expandedId = repo.addRecording(Recording(file = f.name, title = title, durationMs = ms)) }
    }

    fun togglePlay(rec: Recording) {
        if (playingId == rec.id) { stopPlayback(); return }
        stopPlayback()
        if (!player.play(repo.fileOf(rec)) { stopPlayback() }) return
        playingId = rec.id
    }

    DisposableEffect(Unit) {
        onDispose {
            recorder?.stop()
            player.stop()
        }
    }
    LaunchedEffect(recorder) {
        while (recorder != null) { elapsed = System.currentTimeMillis() - startedAt; delay(200) }
    }

    RecordContent(
        recording = recorder != null,
        elapsedMs = elapsed,
        list = list,
        playingId = playingId,
        expandedId = expandedId,
        micGranted = granted,
        onAskMic = ask,
        onRecord = { if (recorder == null) startRecording() else stopRecording() },
        onPlay = ::togglePlay,
        onExpand = { expandedId = if (expandedId == it.id) null else it.id },
        onRate = { rec, i, v -> scope.launch { repo.updateRecording(rec.withRating(i, v)) } },
        onDelete = { confirmDelete = it },
        onBack = onBack,
    )

    confirmDelete?.let { rec ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Xoá bản ghi?") },
            text = { Text("\"${rec.title}\" sẽ bị xoá khỏi máy.") },
            confirmButton = {
                TextButton(onClick = {
                    if (playingId == rec.id) stopPlayback()
                    scope.launch { repo.deleteRecording(rec) }
                    confirmDelete = null
                }) { Text("Xoá", color = Palette.Wrong) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Huỷ") } },
            containerColor = Palette.Surface,
        )
    }
}

fun formatDuration(ms: Long): String = "%d:%02d".format(ms / 60_000, ms / 1000 % 60)

@Composable
fun RecordContent(
    recording: Boolean,
    elapsedMs: Long,
    list: List<Recording>,
    playingId: Long?,
    expandedId: Long?,
    micGranted: Boolean,
    onAskMic: () -> Unit,
    onRecord: () -> Unit,
    onPlay: (Recording) -> Unit,
    onExpand: (Recording) -> Unit,
    onRate: (Recording, Int, Int) -> Unit,
    onDelete: (Recording) -> Unit,
    onBack: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenHeader("Ghi âm & tự chấm", "Nghe lại mình chơi là cách tiến bộ nhanh nhất", onBack)
        Card {
            if (recording) {
                Text(formatDuration(elapsedMs), style = MaterialTheme.typography.displaySmall, color = Palette.Wrong)
                Text("Đang ghi… chơi trọn bài rồi bấm Dừng.", style = MaterialTheme.typography.bodyLarge)
            } else {
                Text(
                    "Đặt điện thoại cách đàn khoảng 1 m, hướng micro về phía thùng đàn. Ghi trọn bài, sau đó nghe lại và chấm 5 tiêu chí.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Button(
                onClick = if (micGranted) onRecord else onAskMic,
                colors = if (recording) ButtonDefaults.buttonColors(containerColor = Palette.Wrong, contentColor = Palette.Ink) else ButtonDefaults.buttonColors(),
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Icon(if (recording) Icons.Rounded.Stop else Icons.Rounded.FiberManualRecord, null)
                Text(if (!micGranted) "  Cho phép micro" else if (recording) "  Dừng" else "  Bắt đầu ghi", style = MaterialTheme.typography.titleMedium)
            }
        }

        if (list.isNotEmpty()) Text("Các bản ghi", style = MaterialTheme.typography.titleLarge)
        list.forEach { rec ->
            RecordingCard(rec, playingId == rec.id, expandedId == rec.id, onPlay, onExpand, onRate, onDelete)
        }
    }
}

@Composable
private fun RecordingCard(
    rec: Recording,
    playing: Boolean,
    expanded: Boolean,
    onPlay: (Recording) -> Unit,
    onExpand: (Recording) -> Unit,
    onRate: (Recording, Int, Int) -> Unit,
    onDelete: (Recording) -> Unit,
) {
    val chip = FilterChipDefaults.filterChipColors(
        selectedContainerColor = Palette.Accent,
        selectedLabelColor = Palette.OnAccent,
        labelColor = Palette.Text,
    )
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onPlay(rec) }) {
                Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = if (playing) "Dừng nghe" else "Nghe lại", tint = Palette.Accent)
            }
            Column(Modifier.weight(1f).clickable { onExpand(rec) }) {
                Text(rec.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    formatDuration(rec.durationMs) + " · " + if (rec.rated) "tự chấm: ${rubricLabel(rec)}" else "chưa chấm — chạm để chấm",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (rec.rated) Palette.Muted else Palette.Accent,
                )
            }
            IconButton(onClick = { onDelete(rec) }) { Icon(Icons.Rounded.Delete, contentDescription = "Xoá", tint = Palette.Muted, modifier = Modifier.size(20.dp)) }
        }
        if (expanded) {
            Recording.CRITERIA.forEachIndexed { i, (name, hint) ->
                Column(Modifier.padding(top = 4.dp)) {
                    Text(name, style = MaterialTheme.typography.titleMedium)
                    Text(hint, style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Recording.LEVELS.forEachIndexed { l, label ->
                            FilterChip(
                                selected = rec.ratings[i] == l + 1,
                                onClick = { onRate(rec, i, l + 1) },
                                label = { Text(label) },
                                colors = chip,
                            )
                        }
                    }
                }
            }
            if (rec.rated) {
                Text(
                    if (rec.ratings.all { it >= 2 }) "Cả 5 tiêu chí đều ổn trở lên — bài này đã đệm hát được!"
                    else "Tiêu chí \"Chưa\" là chỗ cần tập riêng ở buổi sau.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (rec.ratings.all { it >= 2 }) Palette.Right else Palette.Accent,
                )
            }
        }
    }
}

fun rubricLabel(rec: Recording): String = "${rec.ratings.count { it >= 2 }}/5 ổn trở lên"
