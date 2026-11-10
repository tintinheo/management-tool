package com.myguitarplayer.ui.metronome

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.myguitarplayer.audio.Metronome
import com.myguitarplayer.music.StrumPattern
import com.myguitarplayer.ui.common.KeepScreenOn
import com.myguitarplayer.ui.common.ScreenHeader
import com.myguitarplayer.ui.theme.Palette

@Composable
fun MetronomeScreen(onBack: () -> Unit, initialBpm: Int = 60, initialPattern: StrumPattern = StrumPattern.DOWN_4) {
    KeepScreenOn()
    val scope = rememberCoroutineScope()
    val metronome = remember { Metronome() }
    var bpm by rememberSaveable { mutableIntStateOf(initialBpm) }
    var pattern by rememberSaveable { mutableStateOf(initialPattern) }
    var running by remember { mutableStateOf(false) }
    var step by remember { mutableLongStateOf(-1L) }

    DisposableEffect(Unit) { onDispose { metronome.stop() } }
    // Restart on tempo change so the grid stays sample-accurate.
    LaunchedEffect(running, bpm) {
        if (running) {
            metronome.start(scope, bpm)
            while (true) withFrameNanos { step = metronome.currentStep() }
        } else {
            metronome.stop()
            step = -1
        }
    }

    MetronomeContent(
        bpm = bpm,
        pattern = pattern,
        running = running,
        step = step,
        onBpm = { bpm = it.coerceIn(MIN_BPM, MAX_BPM) },
        onPattern = { pattern = it },
        onToggle = { running = !running },
        onBack = onBack,
    )
}

const val MIN_BPM = 40
const val MAX_BPM = 160

@Composable
fun MetronomeContent(
    bpm: Int,
    pattern: StrumPattern,
    running: Boolean,
    step: Long,
    onBpm: (Int) -> Unit,
    onPattern: (StrumPattern) -> Unit,
    onToggle: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        ScreenHeader("Nhịp & quạt", "Quạt theo mũi tên, đúng tiếng click", onBack)

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            OutlinedButton(onClick = { onBpm(bpm - 5) }) { Text("−5") }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("$bpm", style = MaterialTheme.typography.displayLarge, color = Palette.Accent)
                Text("BPM", style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
            }
            OutlinedButton(onClick = { onBpm(bpm + 5) }) { Text("+5") }
        }
        Slider(
            value = bpm.toFloat(),
            onValueChange = { onBpm(it.toInt()) },
            valueRange = MIN_BPM.toFloat()..MAX_BPM.toFloat(),
        )

        Text("Mẫu quạt", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StrumPattern.entries.forEach {
                FilterChip(
                    selected = it == pattern,
                    onClick = { onPattern(it) },
                    label = { Text(it.title) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Palette.Accent,
                        selectedLabelColor = Palette.OnAccent,
                        labelColor = Palette.Text,
                    ),
                )
            }
        }
        Text(pattern.tip, style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)

        val current = if (step < 0) -1 else (step % 8).toInt()
        PatternGrid(pattern, current)

        Spacer(Modifier.height(4.dp))
        Button(onClick = onToggle, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text(if (running) "Dừng" else "Bắt đầu", style = MaterialTheme.typography.titleLarge)
        }
    }
}

/** 8 eighth-note cells "1 & 2 & 3 & 4 &" with ↓/↑ where the hand strikes; the playing cell lights up. */
@Composable
private fun PatternGrid(pattern: StrumPattern, current: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (i in 0 until 8) {
            val stroke = pattern.strokes[i]
            val lit = i == current
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.7f)
                        .background(if (lit) Palette.Accent else Palette.Surface, RoundedCornerShape(10.dp))
                        .border(1.dp, if (i % 2 == 0) Palette.Muted else Palette.Hairline, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        when (stroke) { 'D' -> "↓"; 'U' -> "↑"; else -> "" },
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (lit) Palette.OnAccent else Palette.Text,
                    )
                }
                Text(
                    if (i % 2 == 0) "${i / 2 + 1}" else "&",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (i % 2 == 0) Palette.Text else Palette.Muted,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
