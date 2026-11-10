package com.myguitarplayer.ui.chordcheck

import com.myguitarplayer.data.Repos
import com.myguitarplayer.data.Milestone
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.myguitarplayer.audio.ChordFrames
import com.myguitarplayer.audio.ChordVerdict
import com.myguitarplayer.audio.StrumJudge
import com.myguitarplayer.music.Chord
import com.myguitarplayer.music.Chords
import com.myguitarplayer.music.Note
import com.myguitarplayer.ui.chords.ChordDiagram
import com.myguitarplayer.ui.common.Card
import com.myguitarplayer.ui.common.KeepScreenOn
import com.myguitarplayer.ui.common.ScreenHeader
import com.myguitarplayer.ui.common.rememberMicPermission
import com.myguitarplayer.ui.theme.Palette
import kotlin.math.roundToInt

/** Correct strums in a row that count as "passed" (used by the week tests later). */
const val PASS_STREAK = 3

@Composable
fun ChordCheckScreen(initial: String, onBack: () -> Unit) {
    KeepScreenOn()
    val (granted, ask) = rememberMicPermission()
    var targetName by rememberSaveable { mutableStateOf(initial) }
    val target = Chords[targetName]
    val judge = remember { StrumJudge(target) }
    var state by remember { mutableStateOf(StrumJudge.State(null, 0, 0, false)) }

    LaunchedEffect(target) {
        judge.target = target
        judge.reset()
        state = StrumJudge.State(null, 0, 0, false)
    }
    if (granted) {
        LaunchedEffect(Unit) {
            ChordFrames.fromMic().collect { f -> state = judge.push(f.rms, f.chroma) }
        }
    }
    val passed = state.correctStreak >= PASS_STREAK
    LaunchedEffect(passed, target) {
        if (passed) Repos.progress.mark(Milestone.CHORD, target.name)
    }
    ChordCheckContent(target, state, granted, ask, onPick = { targetName = it.name }, onBack = onBack)
}

@Composable
fun ChordCheckContent(
    target: Chord,
    state: StrumJudge.State,
    micGranted: Boolean,
    onAskMic: () -> Unit,
    onPick: (Chord) -> Unit,
    onBack: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenHeader("Kiểm tra hợp âm", "Bấm, quạt 1 lần chậm rãi, để ngân", onBack)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(Chords.ALL, key = { it.name }) { c ->
                FilterChip(
                    selected = c == target,
                    onClick = { onPick(c) },
                    label = { Text(c.name) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Palette.Accent,
                        selectedLabelColor = Palette.OnAccent,
                        labelColor = Palette.Text,
                    ),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            ChordDiagram(target, width = 130.dp)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(target.name, style = MaterialTheme.typography.displaySmall)
                Text("Gảy từ dây ${target.firstString}", style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
                Text(
                    "Nốt cần kêu: " + target.pitchClasses.sorted().joinToString(" ") { Note.name(it) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.Muted,
                )
            }
        }

        if (!micGranted) {
            Text("Cần micro để nghe hợp âm. Âm thanh chỉ xử lý trên máy.", style = MaterialTheme.typography.bodyLarge)
            Button(onClick = onAskMic) { Text("Cho phép micro") }
            return@Column
        }

        VerdictCard(state.verdict, state.listening)

        Card {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Đúng liên tiếp", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${minOf(state.correctStreak, PASS_STREAK)}/$PASS_STREAK",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (state.correctStreak >= PASS_STREAK) Palette.Right else Palette.Accent,
                )
            }
            Text(
                if (state.correctStreak >= PASS_STREAK) "Đạt! Hợp âm ${target.name} đã sạch. 🎉"
                else "Quạt ${PASS_STREAK} lần liền mà đều đúng là đạt. Đã quạt ${state.strums} lần.",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.Muted,
            )
        }
    }
}

@Composable
private fun VerdictCard(v: ChordVerdict?, listening: Boolean) {
    Card {
        if (v == null) {
            Text(
                if (listening) "Đang nghe…" else "Quạt hợp âm để bắt đầu",
                style = MaterialTheme.typography.headlineSmall,
                color = Palette.Muted,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            return@Card
        }
        val color = if (v.correct) Palette.Right else Palette.Wrong
        Text(
            when {
                v.correct -> "Đúng ${v.target.name} ✓"
                v.missing.isNotEmpty() -> "Thiếu nốt ${v.missing.joinToString(", ")}"
                v.best != v.target && v.bestScore - v.score >= 0.02 -> "Nghe giống ${v.best.name} hơn"
                else -> "Chưa rõ — quạt lại chậm hơn"
            },
            style = MaterialTheme.typography.headlineMedium,
            color = color,
        )
        Text(
            when {
                v.missing.isNotEmpty() -> "Có dây bị tắt tiếng hoặc bấm chưa chạm phím. Dựng đứng đầu ngón, bấm sát phím đàn."
                v.noisy -> "Có tiếng lạ: dây rè, hoặc gảy nhầm dây không thuộc hợp âm (xem dấu ×)."
                v.correct -> "Âm sạch. Nhả tay, bấm lại rồi quạt tiếp."
                else -> "Kiểm tra lại từng ngón theo sơ đồ, rồi gảy chậm từng dây xem dây nào bị tắt."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = Palette.Muted,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Độ khớp", style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
            LinearProgressIndicator(
                progress = { v.score.toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.weight(1f).height(8.dp),
                color = color,
                trackColor = Palette.SurfaceHigh,
            )
            Text("${(v.score * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
        }
        ChromaBars(v.chroma, v.target.pitchClasses)
    }
}

/** 12 pitch-class bars; the target chord's notes are gold, everything else grey. */
@Composable
fun ChromaBars(chroma: DoubleArray, chordTones: Set<Int>) {
    Column {
        Canvas(Modifier.fillMaxWidth().height(70.dp)) {
            val w = size.width / 12
            for (i in 0 until 12) {
                val h = (chroma[i].coerceIn(0.0, 1.0) * size.height).toFloat()
                drawRect(
                    if (i in chordTones) Palette.Accent else Palette.Muted.copy(alpha = 0.6f),
                    topLeft = Offset(i * w + w * 0.15f, size.height - h),
                    size = Size(w * 0.7f, h),
                )
            }
        }
        Row(Modifier.fillMaxWidth()) {
            for (i in 0 until 12) {
                Text(
                    Note.name(i),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (i in chordTones) Palette.Accent else Palette.Muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
