package com.myguitarplayer.ui.changes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.myguitarplayer.data.Repos
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.myguitarplayer.audio.ChangeCounter
import com.myguitarplayer.audio.ChordFrames
import com.myguitarplayer.data.ChangeScore
import com.myguitarplayer.music.Chord
import com.myguitarplayer.music.Chords
import com.myguitarplayer.ui.chords.ChordDiagram
import com.myguitarplayer.ui.common.Card
import com.myguitarplayer.ui.common.KeepScreenOn
import com.myguitarplayer.ui.common.ScreenHeader
import com.myguitarplayer.ui.common.rememberMicPermission
import com.myguitarplayer.ui.theme.Palette
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ChangesPhase { SETUP, COUNTDOWN, RUNNING, DONE }

const val ROUND_SECONDS = 60

@Composable
fun ChangesScreen(initialA: String, initialB: String, onBack: () -> Unit) {
    KeepScreenOn()
    val dao = Repos.changes
    val (granted, ask) = rememberMicPermission()
    var aName by rememberSaveable { mutableStateOf(initialA) }
    var bName by rememberSaveable { mutableStateOf(initialB) }
    val a = Chords[aName]
    val b = Chords[bName]
    var phase by remember { mutableStateOf(ChangesPhase.SETUP) }
    var left by remember { mutableIntStateOf(ROUND_SECONDS) }
    var mic by remember { mutableIntStateOf(0) }
    var manual by remember { mutableIntStateOf(0) }
    var heard by remember { mutableStateOf<Chord?>(null) }
    val history by remember(aName, bName) { dao.forPair(aName, bName) }.collectAsState(initial = emptyList())
    var bestBefore by remember { mutableIntStateOf(0) }

    LaunchedEffect(phase) {
        when (phase) {
            ChangesPhase.COUNTDOWN -> {
                bestBefore = history.maxOfOrNull { it.count } ?: 0
                mic = 0; manual = 0; heard = null
                for (s in 3 downTo 1) { left = s; delay(1000) }
                left = ROUND_SECONDS
                phase = ChangesPhase.RUNNING
            }
            ChangesPhase.RUNNING -> {
                while (left > 0) { delay(1000); left-- }
                dao.insert(ChangeScore(chordA = aName, chordB = bName, count = (mic + manual).coerceAtLeast(0), micCount = mic))
                phase = ChangesPhase.DONE
            }
            else -> Unit
        }
    }
    if (granted && phase == ChangesPhase.RUNNING) {
        LaunchedEffect(Unit) {
            val counter = ChangeCounter(a, b)
            ChordFrames.fromMic().collect { f ->
                heard = counter.push(f.chroma)
                mic = counter.changes
            }
        }
    }

    ChangesContent(
        a = a, b = b, phase = phase, secondsLeft = left, count = (mic + manual).coerceAtLeast(0), heard = heard,
        history = history, bestBefore = bestBefore, micGranted = granted,
        onAskMic = ask,
        onPickA = { if (it.name != bName) aName = it.name },
        onPickB = { if (it.name != aName) bName = it.name },
        onStart = { phase = ChangesPhase.COUNTDOWN },
        onPlusOne = { manual++ },
        onMinusOne = { manual-- },
        onStop = { phase = ChangesPhase.SETUP },
        onDone = { phase = ChangesPhase.SETUP },
        onBack = onBack,
    )
}

@Composable
fun ChangesContent(
    a: Chord,
    b: Chord,
    phase: ChangesPhase,
    secondsLeft: Int,
    count: Int,
    heard: Chord?,
    history: List<ChangeScore>,
    bestBefore: Int,
    micGranted: Boolean,
    onAskMic: () -> Unit,
    onPickA: (Chord) -> Unit,
    onPickB: (Chord) -> Unit,
    onStart: () -> Unit,
    onPlusOne: () -> Unit,
    onMinusOne: () -> Unit,
    onStop: () -> Unit,
    onDone: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenHeader("Chuyển hợp âm 1 phút", "Đếm số lần đổi qua lại trong 60 giây", onBack.takeIf { phase == ChangesPhase.SETUP || phase == ChangesPhase.DONE })

        when (phase) {
            ChangesPhase.SETUP -> {
                ChordPicker("Hợp âm 1", a, exclude = b, onPickA)
                ChordPicker("Hợp âm 2", b, exclude = a, onPickB)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    ChordDiagram(a, width = 120.dp)
                    ChordDiagram(b, width = 120.dp)
                }
                Text(
                    "Bấm ${a.name}, quạt 1 cái, đổi sang ${b.name}, quạt 1 cái… cứ thế 60 giây. " +
                        "Micro tự đếm khi nghe rõ hợp âm mới; bỏ sót thì chạm +1.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.Muted,
                )
                if (!micGranted) {
                    OutlinedButton(onClick = onAskMic, modifier = Modifier.fillMaxWidth()) { Text("Cho phép micro để tự đếm") }
                }
                Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text("Bắt đầu 60 giây", style = MaterialTheme.typography.titleLarge)
                }
                History(history)
            }
            ChangesPhase.COUNTDOWN -> {
                Text(
                    "Chuẩn bị bấm ${a.name}",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
                Text(
                    "$secondsLeft",
                    fontSize = 120.sp,
                    style = MaterialTheme.typography.displayLarge,
                    color = Palette.Accent,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
            ChangesPhase.RUNNING -> {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("0:${"%02d".format(secondsLeft)}", style = MaterialTheme.typography.headlineMedium, color = Palette.Muted)
                    Column(horizontalAlignment = Alignment.End) {
                        Text("$count", fontSize = 96.sp, style = MaterialTheme.typography.displayLarge, color = Palette.Accent)
                        Text("lần chuyển", style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ChordTile(a, lit = heard == a, Modifier.weight(1f))
                    ChordTile(b, lit = heard == b, Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onMinusOne, modifier = Modifier.weight(1f).height(64.dp)) { Text("−1", fontSize = 22.sp) }
                    Button(onClick = onPlusOne, modifier = Modifier.weight(2f).height(64.dp)) { Text("+1", fontSize = 26.sp) }
                }
                OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("Dừng (không lưu)") }
            }
            ChangesPhase.DONE -> {
                Card {
                    Text("${a.name} ↔ ${b.name}", style = MaterialTheme.typography.titleLarge)
                    Text("$count", fontSize = 96.sp, style = MaterialTheme.typography.displayLarge, color = Palette.Accent)
                    Text(
                        when {
                            bestBefore == 0 -> "Lần đầu! Đây là mốc để vượt qua lần sau."
                            count > bestBefore -> "Kỷ lục mới! Lần trước tốt nhất là $bestBefore. 🎉"
                            else -> "Tốt nhất vẫn là $bestBefore. Mỗi ngày một vòng, con số sẽ tự tăng."
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        "Mốc tham khảo: 20 lần/phút là chuyển được trong bài chậm; 30+ là trôi chảy.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Palette.Muted,
                    )
                }
                Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Làm thêm 1 vòng") }
                OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Xong") }
            }
        }
    }
}

@Composable
private fun ChordPicker(label: String, selected: Chord, exclude: Chord, onPick: (Chord) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(Chords.ALL.filter { it != exclude }, key = { it.name }) { c ->
                FilterChip(
                    selected = c == selected,
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
    }
}

@Composable
private fun ChordTile(chord: Chord, lit: Boolean, modifier: Modifier) {
    Box(
        modifier
            .height(120.dp)
            .background(if (lit) Palette.Accent else Palette.Surface, RoundedCornerShape(16.dp))
            .border(1.dp, Palette.Hairline, RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(chord.name, fontSize = 48.sp, style = MaterialTheme.typography.displaySmall, color = if (lit) Palette.OnAccent else Palette.Text)
    }
}

@Composable
private fun History(history: List<ChangeScore>) {
    if (history.isEmpty()) return
    val fmt = remember { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()) }
    Card {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Các lần trước", style = MaterialTheme.typography.titleMedium)
            Text("Tốt nhất ${history.maxOf { it.count }}", style = MaterialTheme.typography.titleMedium, color = Palette.Accent)
        }
        history.take(5).forEach {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(fmt.format(Date(it.at)), style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
                Text("${it.count}", style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
