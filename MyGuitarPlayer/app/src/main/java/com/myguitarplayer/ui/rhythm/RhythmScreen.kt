package com.myguitarplayer.ui.rhythm

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.myguitarplayer.data.Repos
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myguitarplayer.audio.Hit
import com.myguitarplayer.audio.Metronome
import com.myguitarplayer.audio.MicStream
import com.myguitarplayer.audio.OnsetDetector
import com.myguitarplayer.audio.RhythmScorer
import com.myguitarplayer.audio.RhythmSummary
import com.myguitarplayer.data.RhythmScore
import com.myguitarplayer.music.StrumPattern
import com.myguitarplayer.ui.common.Card
import com.myguitarplayer.ui.common.KeepScreenOn
import com.myguitarplayer.ui.common.ScreenHeader
import com.myguitarplayer.ui.common.rememberMicPermission
import com.myguitarplayer.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

enum class RhythmPhase { SETUP, CALIBRATING, RUNNING, DONE }

/** Steps of one bar the metronome plays before scoring starts. */
const val COUNT_IN_STEPS = 8L
const val CALIBRATION_BEATS = 12

/** Tones of the metronome's two clicks (accent, normal): filtered out of the mic signal. */
val CLICK_TONES = listOf(1760.0, 1175.0)

data class RhythmUi(
    val phase: RhythmPhase,
    val bpm: Int,
    val pattern: StrumPattern,
    val bars: Int,
    val muted: Boolean,
    val latencyMs: Int?,
    /** Eighth-note step being played, counted from the start of the count-in (−1 = not playing). */
    val step: Long,
    val hits: List<Hit>,
    val summary: RhythmSummary?,
    val history: List<RhythmScore>,
    val micGranted: Boolean,
    /** Calibration: onsets heard so far / failed message. */
    val calibrationHeard: Int = 0,
    val calibrationFailed: Boolean = false,
)

@Composable
fun RhythmScreen(onBack: () -> Unit, initialBpm: Int = 70, initialPattern: StrumPattern = StrumPattern.DOWN_UP) {
    KeepScreenOn()
    val repo = Repos.practice
    val scope = rememberCoroutineScope()
    val (granted, ask) = rememberMicPermission()
    val metronome = remember { Metronome() }
    DisposableEffect(Unit) { onDispose { metronome.stop() } }

    var bpm by rememberSaveable { mutableIntStateOf(initialBpm) }
    var pattern by rememberSaveable { mutableStateOf(initialPattern) }
    var bars by rememberSaveable { mutableIntStateOf(8) }
    var muted by rememberSaveable { mutableStateOf(false) }
    var latency by remember { mutableStateOf(repo.latencyMs) }
    var phase by remember { mutableStateOf(RhythmPhase.SETUP) }
    var step by remember { mutableLongStateOf(-1L) }
    var hits by remember { mutableStateOf(emptyList<Hit>()) }
    var summary by remember { mutableStateOf<RhythmSummary?>(null) }
    var heard by remember { mutableIntStateOf(0) }
    var calFailed by remember { mutableStateOf(false) }
    val history by remember { repo.rhythm() }.collectAsStateWithLifecycle(initialValue = emptyList())

    LaunchedEffect(phase) {
        when (phase) {
            RhythmPhase.RUNNING -> {
                hits = emptyList(); summary = null
                val total = 8L * bars
                val stepNanos = RhythmScorer.stepNanos(bpm)
                metronome.start(scope, bpm, 4, muted)
                // Let the output timestamp settle, then fix where scoring starts (after the count-in bar).
                delay(400)
                val origin = metronome.stepTimeNanos(COUNT_IN_STEPS) ?: return@LaunchedEffect
                val scorer = RhythmScorer(origin, stepNanos, pattern.strokes, (latency ?: 0) * 1_000_000L)
                val detector = OnsetDetector(MicStream.SAMPLE_RATE, ignoreHz = CLICK_TONES)
                val end = COUNT_IN_STEPS + total + 1
                launch { while (true) { step = metronome.currentStep(); delay(30) } }
                MicStream.chunks()
                    .takeWhile { metronome.currentStep() < end }
                    .flowOn(Dispatchers.Default)
                    .collect { c ->
                        val found = detector.push(c.samples)
                        for (f in found) scorer.add(c.clock.nanosOf(f))?.let { hits = scorer.hits }
                    }
                metronome.stop()
                val s = scorer.summary(total)
                summary = s
                repo.saveRhythm(
                    RhythmScore(
                        bpm = bpm, pattern = pattern.strokes, bars = bars,
                        meanAbsMs = s.meanAbsMs.roundToInt(), meanSignedMs = s.meanSignedMs.roundToInt(),
                        hitPercent = (s.hitRate * 100).roundToInt(), extras = s.extras,
                    ),
                )
                phase = RhythmPhase.DONE
            }
            RhythmPhase.CALIBRATING -> {
                heard = 0; calFailed = false
                val calBpm = 80
                metronome.start(scope, calBpm, 4, muted = false)
                delay(400)
                val first = metronome.stepTimeNanos(COUNT_IN_STEPS) ?: return@LaunchedEffect
                val beat = 2 * RhythmScorer.stepNanos(calBpm)
                val detector = OnsetDetector(MicStream.SAMPLE_RATE, ignoreHz = CLICK_TONES)
                val onsets = mutableListOf<Long>()
                val end = COUNT_IN_STEPS + 2L * CALIBRATION_BEATS + 1
                launch { while (true) { step = metronome.currentStep(); delay(30) } }
                MicStream.chunks()
                    .takeWhile { metronome.currentStep() < end }
                    .flowOn(Dispatchers.Default)
                    .collect { c ->
                        detector.push(c.samples).forEach { f ->
                            val t = c.clock.nanosOf(f)
                            if (t > first - beat / 2) { onsets += t; heard = onsets.size }
                        }
                    }
                metronome.stop()
                val lat = RhythmScorer.latencyFrom(onsets, first, beat)
                if (lat != null && lat in 0..400_000_000L) {
                    latency = (lat / 1_000_000L).toInt()
                    repo.latencyMs = latency
                } else {
                    calFailed = true
                }
                phase = RhythmPhase.SETUP
            }
            else -> {
                metronome.stop()
                step = -1
            }
        }
    }

    RhythmContent(
        ui = RhythmUi(phase, bpm, pattern, bars, muted, latency, step, hits, summary, history, granted, heard, calFailed),
        onAskMic = ask,
        onBpm = { bpm = it.coerceIn(40, 160) },
        onPattern = { pattern = it },
        onBars = { bars = it },
        onMuted = { muted = it },
        onStart = { if (granted) phase = RhythmPhase.RUNNING else ask() },
        onCalibrate = { if (granted) phase = RhythmPhase.CALIBRATING else ask() },
        onStop = { phase = RhythmPhase.SETUP },
        onAgain = { scope.launch { phase = RhythmPhase.RUNNING } },
        onBack = onBack,
    )
}

/** Colour for a timing offset: green ≤ 30 ms, gold ≤ 60 ms, red beyond. */
fun offsetColor(ms: Double): Color = when {
    abs(ms) <= 30 -> Palette.Right
    abs(ms) <= 60 -> Palette.Accent
    else -> Palette.Wrong
}

@Composable
fun RhythmContent(
    ui: RhythmUi,
    onAskMic: () -> Unit,
    onBpm: (Int) -> Unit,
    onPattern: (StrumPattern) -> Unit,
    onBars: (Int) -> Unit,
    onMuted: (Boolean) -> Unit,
    onStart: () -> Unit,
    onCalibrate: () -> Unit,
    onStop: () -> Unit,
    onAgain: () -> Unit,
    onBack: () -> Unit,
) {
    val chip = FilterChipDefaults.filterChipColors(
        selectedContainerColor = Palette.Accent,
        selectedLabelColor = Palette.OnAccent,
        labelColor = Palette.Text,
    )
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenHeader("Chấm nhịp quạt", "Micro nghe từng cú quạt, so với tiếng click", onBack)

        when (ui.phase) {
            RhythmPhase.SETUP -> {
                if (!ui.micGranted) {
                    Card {
                        Text("Cần micro để nghe tiếng quạt. Âm thanh chỉ xử lý trên máy.", style = MaterialTheme.typography.bodyLarge)
                        Button(onClick = onAskMic) { Text("Cho phép micro") }
                    }
                }
                CalibrationCard(ui, onCalibrate)
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { onBpm(ui.bpm - 5) }) { Text("−5") }
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${ui.bpm}", style = MaterialTheme.typography.displaySmall, color = Palette.Accent)
                            Text("BPM", style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
                        }
                        OutlinedButton(onClick = { onBpm(ui.bpm + 5) }) { Text("+5") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StrumPattern.entries.forEach { p ->
                            FilterChip(selected = p == ui.pattern, onClick = { onPattern(p) }, label = { Text(p.title) }, colors = chip)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Độ dài", style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
                        listOf(4, 8, 16).forEach { b ->
                            FilterChip(selected = b == ui.bars, onClick = { onBars(b) }, label = { Text("$b ô nhịp") }, colors = chip)
                        }
                    }
                    FilterChip(
                        selected = ui.muted,
                        onClick = { onMuted(!ui.muted) },
                        label = { Text("Tắt tiếng click (chỉ nhìn đèn)") },
                        colors = chip,
                    )
                    Text(
                        "Đeo tai nghe là chính xác nhất. Không có tai nghe thì app tự lọc tiếng click khỏi micro; " +
                            "nếu vẫn bị đếm nhầm, tắt tiếng click và quạt theo ô sáng.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Palette.Muted,
                    )
                }
                Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text("Bắt đầu (1 ô nhịp đếm vào)", style = MaterialTheme.typography.titleMedium)
                }
                History(ui)
            }
            RhythmPhase.CALIBRATING -> Card {
                Text("Đo độ trễ", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Giữ một hợp âm (hoặc chặn dây), quạt xuống ĐÚNG mỗi tiếng click, $CALIBRATION_BEATS lần. " +
                        "Bỏ qua 4 click đầu (đếm vào).",
                    style = MaterialTheme.typography.bodyLarge,
                )
                BeatLights(ui.step)
                Text("Đã nghe ${ui.calibrationHeard} cú quạt", style = MaterialTheme.typography.titleMedium, color = Palette.Accent)
                OutlinedButton(onClick = onStop) { Text("Huỷ") }
            }
            RhythmPhase.RUNNING -> {
                val inCountIn = ui.step in 0 until COUNT_IN_STEPS
                val scored = ui.step - COUNT_IN_STEPS
                Card {
                    Text(
                        if (inCountIn || ui.step < 0) "Đếm vào… ${if (ui.step < 0) "" else (ui.step / 2 + 1).toString()}"
                        else "Ô nhịp ${(scored / 8 + 1).coerceAtMost(ui.bars.toLong())}/${ui.bars}",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    StrumCells(ui.pattern.strokes, if (inCountIn || ui.step < 0) -1 else (scored % 8).toInt(), ui.hits.filter { it.step / 8 == scored / 8 })
                    TimingStrip(ui.hits.takeLast(12))
                    val recent = ui.hits.filter { it.expected }.takeLast(8)
                    if (recent.isNotEmpty()) {
                        val avg = recent.map { abs(it.offsetMs) }.average()
                        Text("Lệch gần đây: ${avg.roundToInt()} ms", style = MaterialTheme.typography.titleMedium, color = offsetColor(avg))
                    }
                }
                OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("Dừng") }
            }
            RhythmPhase.DONE -> {
                ui.summary?.let { ResultCard(it, ui) }
                Button(onClick = onAgain, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Làm lại") }
                OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("Đổi tốc độ / mẫu quạt") }
                History(ui)
            }
        }
    }
}

@Composable
private fun CalibrationCard(ui: RhythmUi, onCalibrate: () -> Unit) {
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Độ trễ âm thanh", style = MaterialTheme.typography.titleMedium)
                Text(
                    when {
                        ui.calibrationFailed -> "Chưa đo được — quạt to, rõ, đúng mỗi click rồi thử lại."
                        ui.latencyMs == null -> "Chưa đo. Làm một lần (20 giây) để điểm nhịp chính xác."
                        else -> "${ui.latencyMs} ms — đã bù vào điểm nhịp."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (ui.calibrationFailed) Palette.Wrong else Palette.Muted,
                )
            }
            TextButton(onClick = onCalibrate) { Text(if (ui.latencyMs == null) "Đo ngay" else "Đo lại") }
        }
    }
}

/** The pattern's 8 cells; the playing one lights up, played cells take the colour of their timing. */
@Composable
fun StrumCells(strokes: String, current: Int, barHits: List<Hit>) {
    val bySlot = barHits.associateBy { (it.step % 8).toInt() }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        strokes.forEachIndexed { i, c ->
            val hit = bySlot[i]
            val fill = when {
                hit != null -> offsetColor(hit.offsetMs).copy(alpha = if (hit.expected) 0.9f else 0.35f)
                i == current -> Palette.SurfaceHigh
                else -> Color.Transparent
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.75f)
                        .background(fill, RoundedCornerShape(10.dp))
                        .border(if (i == current) 2.dp else 1.dp, if (i == current) Palette.Text else Palette.Hairline, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        when (c) { 'D' -> "↓"; 'U' -> "↑"; else -> "" },
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (hit != null) Palette.Ink else Palette.Text,
                    )
                }
                Text(if (i % 2 == 0) "${i / 2 + 1}" else "&", style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
            }
        }
    }
}

/** Recent strums on an early ← 0 → late axis (±120 ms). */
@Composable
fun TimingStrip(hits: List<Hit>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(Modifier.fillMaxWidth().height(44.dp)) {
            val mid = size.width / 2
            val scale = size.width / 2 / 120f
            // ±30 and ±60 ms bands.
            drawRect(Palette.Right.copy(alpha = 0.12f), Offset(mid - 30 * scale, 0f), androidx.compose.ui.geometry.Size(60 * scale, size.height))
            drawRect(Palette.Accent.copy(alpha = 0.08f), Offset(mid - 60 * scale, 0f), androidx.compose.ui.geometry.Size(30 * scale, size.height))
            drawRect(Palette.Accent.copy(alpha = 0.08f), Offset(mid + 30 * scale, 0f), androidx.compose.ui.geometry.Size(30 * scale, size.height))
            drawLine(Palette.Text, Offset(mid, 0f), Offset(mid, size.height), strokeWidth = 3f)
            hits.forEachIndexed { i, h ->
                val x = mid + h.offsetMs.toFloat().coerceIn(-120f, 120f) * scale
                val alpha = 0.35f + 0.65f * (i + 1) / hits.size
                drawCircle(offsetColor(h.offsetMs).copy(alpha = alpha), size.height * 0.16f, Offset(x, size.height / 2))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text("← sớm", style = MaterialTheme.typography.labelMedium, color = Palette.Muted, modifier = Modifier.weight(1f))
            Text("đúng phách", style = MaterialTheme.typography.labelMedium, color = Palette.Text)
            Text("muộn →", style = MaterialTheme.typography.labelMedium, color = Palette.Muted, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
    }
}

@Composable
private fun BeatLights(step: Long) {
    val beat = if (step < 0) -1 else ((step / 2) % 4).toInt()
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for (b in 0 until 4) {
            Box(
                Modifier
                    .weight(1f)
                    .height(36.dp)
                    .background(if (b == beat) Palette.Accent else Palette.SurfaceHigh, RoundedCornerShape(8.dp)),
            )
        }
    }
}

/** Plain-language verdict for a take. */
fun verdict(s: RhythmSummary): String = when {
    s.expectedSlots == 0 -> "Chưa nghe được cú quạt nào."
    s.hitRate < 0.5 -> "Micro chỉ nghe được ${(s.hitRate * 100).roundToInt()}% cú quạt. Quạt rõ hơn, đặt điện thoại gần đàn hơn (khoảng 50 cm)."
    s.passes() && s.meanAbsMs <= 30 -> "Rất đều! Tăng 5 BPM ở lần sau."
    s.passes() -> "Đạt — đủ đều để đệm hát. Tập thêm để vào vùng xanh (≤ 30 ms)."
    s.hitRate < 0.8 -> "Thiếu cú quạt: ${s.expectedSlots - s.hits} cú không nghe thấy. Giữ tay đưa đều cả khi không chạm dây."
    s.meanSignedMs < -25 -> "Bạn đang quạt hơi vội (sớm ${abs(s.meanSignedMs).roundToInt()} ms). Thả lỏng, nghe click rồi mới quạt."
    s.meanSignedMs > 25 -> "Bạn đang quạt hơi chậm (muộn ${s.meanSignedMs.roundToInt()} ms). Chuẩn bị tay sớm hơn một chút."
    else -> "Lúc sớm lúc muộn. Giảm 10 BPM, đếm to \"1 & 2 &\" và quạt từ cổ tay."
}

@Composable
private fun ResultCard(s: RhythmSummary, ui: RhythmUi) {
    Card {
        Text("Kết quả · ${ui.bpm} BPM · ${ui.pattern.title}", style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${s.meanAbsMs.roundToInt()}", style = MaterialTheme.typography.displaySmall, color = offsetColor(s.meanAbsMs))
            Text(" ms lệch trung bình", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 6.dp))
        }
        Text(
            "Nghe được ${s.hits}/${s.expectedSlots} cú quạt (${(s.hitRate * 100).roundToInt()}%)" +
                (if (s.extras > 0) " · ${s.extras} cú thừa" else "") +
                " · " + when {
                    s.meanSignedMs < -10 -> "hơi sớm ${abs(s.meanSignedMs).roundToInt()} ms"
                    s.meanSignedMs > 10 -> "hơi muộn ${s.meanSignedMs.roundToInt()} ms"
                    else -> "không sớm không muộn"
                },
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(verdict(s), style = MaterialTheme.typography.bodyLarge, color = if (s.passes()) Palette.Right else Palette.Accent)
        TimingStrip(ui.hits.filter { it.expected })
    }
}

@Composable
private fun History(ui: RhythmUi) {
    val same = ui.history.filter { it.pattern == ui.pattern.strokes }.take(5)
    if (same.isEmpty()) return
    Text("Lần trước · ${ui.pattern.title}", style = MaterialTheme.typography.titleMedium)
    Card {
        same.forEach { r ->
            Row {
                Text("${r.bpm} BPM", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text("${r.meanAbsMs} ms", style = MaterialTheme.typography.titleMedium, color = offsetColor(r.meanAbsMs.toDouble()))
                Text("  ·  ${r.hitPercent}%", style = MaterialTheme.typography.bodyLarge, color = Palette.Muted)
            }
        }
    }
}
