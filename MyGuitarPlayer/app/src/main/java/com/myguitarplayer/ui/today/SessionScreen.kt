package com.myguitarplayer.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.myguitarplayer.audio.ChordPlayer
import com.myguitarplayer.data.Session
import com.myguitarplayer.data.Step
import com.myguitarplayer.data.Week
import com.myguitarplayer.ui.common.Card
import com.myguitarplayer.ui.common.KeepScreenOn
import com.myguitarplayer.ui.common.ScreenHeader
import com.myguitarplayer.ui.lesson.LessonVisual
import com.myguitarplayer.ui.theme.Palette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Button label for a step's practice tool. */
fun toolLabel(step: Step): String? = when (step.tool) {
    "TUNER" -> "Mở máy lên dây"
    "METRONOME" -> "Mở metronome ${step.bpm ?: 60} BPM"
    "CHORD_CHECK" -> "Kiểm tra ${step.chord} bằng micro"
    "CHANGES" -> "Chuyển ${step.chord} ↔ ${step.chordB} trong 1 phút"
    "NOTES" -> "Mở cần đàn"
    "QUIZ" -> "Đố nốt (10 câu)"
    "RHYTHM" -> "Chấm nhịp ${step.bpm ?: 70} BPM bằng micro"
    "RECORD" -> "Mở ghi âm & tự chấm"
    "SONG" -> if (step.song == null) "Mở danh sách bài hát" else "Mở bài hát"
    else -> null
}

@Composable
fun SessionScreen(
    week: Week,
    session: Session,
    stepIndex: Int,
    onStep: (Int) -> Unit,
    onOpenTool: () -> Unit,
    onFinish: (rating: Int) -> Unit,
    onBack: () -> Unit,
) {
    KeepScreenOn()
    val scope = rememberCoroutineScope()
    val player = remember { ChordPlayer() }
    DisposableEffect(Unit) { onDispose { player.stop() } }
    val step = session.steps[stepIndex]
    var left by rememberSaveable(session.id, stepIndex) { mutableIntStateOf(step.minutes * 60) }
    var running by rememberSaveable(session.id, stepIndex) { mutableStateOf(false) }
    var rating by remember { mutableStateOf(false) }
    LaunchedEffect(running, stepIndex) {
        while (running && left > 0) { delay(1000); left-- }
        if (left == 0) running = false
    }

    SessionContent(
        week, session, stepIndex, left, running,
        onToggleTimer = { running = !running },
        onPrev = { if (stepIndex > 0) onStep(stepIndex - 1) },
        onNext = { if (stepIndex < session.steps.lastIndex) onStep(stepIndex + 1) else rating = true },
        onOpenTool = onOpenTool,
        onNote = { m -> scope.launch { player.playNote(m) } },
        onBack = onBack,
    )
    if (rating) {
        AlertDialog(
            onDismissRequest = { rating = false },
            title = { Text("Xong buổi! Thấy thế nào?") },
            text = { Text("Tự đánh giá giúp bạn biết lúc nào nên ôn lại. Buổi \"khó\" có thể tập lại ngày mai trước khi sang buổi mới.") },
            confirmButton = {
                Row {
                    TextButton(onClick = { rating = false; onFinish(1) }) { Text("Khó") }
                    TextButton(onClick = { rating = false; onFinish(2) }) { Text("Vừa") }
                    TextButton(onClick = { rating = false; onFinish(3) }) { Text("Dễ") }
                }
            },
            containerColor = Palette.Surface,
        )
    }
}

@Composable
fun SessionContent(
    week: Week,
    session: Session,
    index: Int,
    secondsLeft: Int,
    running: Boolean,
    onToggleTimer: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onOpenTool: () -> Unit,
    onNote: ((Int) -> Unit)?,
    onBack: () -> Unit,
) {
    val step = session.steps[index]
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val n = week.sessions.indexOf(session) + 1
        ScreenHeader(session.title, "Tuần ${week.number} · Buổi $n · ${session.minutes} phút", onBack)
        // Step progress.
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            session.steps.indices.forEach { i ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(6.dp)
                        .background(
                            when {
                                i < index -> Palette.Accent
                                i == index -> Palette.Text
                                else -> Palette.Hairline
                            },
                            RoundedCornerShape(3.dp),
                        ),
                )
            }
        }
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Bước ${index + 1}/${session.steps.size}", style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
                    Text(step.title, style = MaterialTheme.typography.headlineSmall)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "%d:%02d".format(secondsLeft / 60, secondsLeft % 60),
                        style = MaterialTheme.typography.titleLarge,
                        color = if (secondsLeft == 0) Palette.Right else Palette.Accent,
                    )
                    IconButton(onClick = onToggleTimer) {
                        Icon(
                            if (running) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (running) "Dừng đồng hồ" else "Chạy đồng hồ",
                        )
                    }
                }
            }
            Text(step.text, style = MaterialTheme.typography.bodyLarge)
        }
        step.visual?.let { v -> Card { LessonVisual(v, onNote) } }
        toolLabel(step)?.let { label ->
            Button(onClick = onOpenTool, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(label) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onPrev, enabled = index > 0, modifier = Modifier.weight(1f).height(48.dp)) { Text("Bước trước") }
            Button(onClick = onNext, modifier = Modifier.weight(1f).height(48.dp)) {
                Text(if (index == session.steps.lastIndex) "Hoàn thành buổi" else "Bước tiếp")
            }
        }
        Text("Mục tiêu buổi: ${session.goal}", style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
    }
}
