package com.myguitarplayer.ui.tuner

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.myguitarplayer.data.Repos
import com.myguitarplayer.audio.MicStream
import com.myguitarplayer.data.Milestone
import com.myguitarplayer.audio.Yin
import com.myguitarplayer.music.GuitarString
import com.myguitarplayer.music.TunerReading
import com.myguitarplayer.ui.common.KeepScreenOn
import com.myguitarplayer.ui.common.ScreenHeader
import com.myguitarplayer.ui.common.rememberMicPermission
import com.myguitarplayer.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlin.math.abs
import kotlin.math.roundToInt

/** Smooths raw pitches and decides when a string counts as tuned (held in tune ~0.4 s). */
class TunerTracker {
    private val recent = ArrayDeque<Double>()
    private var inTuneFrames = 0
    val tuned = mutableSetOf<GuitarString>()

    fun push(freq: Double?, locked: GuitarString?): TunerReading? {
        if (freq == null) {
            inTuneFrames = 0
            if (recent.isNotEmpty()) recent.removeFirst()
            return null
        }
        recent.addLast(freq)
        if (recent.size > 5) recent.removeFirst()
        val median = recent.sorted()[recent.size / 2]
        val reading = TunerReading.of(median, locked)
        inTuneFrames = if (reading.inTune) inTuneFrames + 1 else 0
        if (inTuneFrames >= 8) tuned += reading.string
        return reading
    }
}

@Composable
fun TunerScreen(onBack: () -> Unit) {
    KeepScreenOn()
    val (granted, ask) = rememberMicPermission()
    var reading by remember { mutableStateOf<TunerReading?>(null) }
    var tuned by remember { mutableStateOf(emptySet<GuitarString>()) }
    var locked by remember { mutableStateOf<GuitarString?>(null) }

    if (granted) {
        LaunchedEffect(locked) {
            val tracker = TunerTracker().also { it.tuned += tuned }
            val yin = Yin(MicStream.SAMPLE_RATE, 4096)
            MicStream.windows()
                .map { w -> yin.detect(w)?.takeIf { it.confidence > 0.8 }?.freq }
                .flowOn(Dispatchers.Default)
                .collect { f ->
                    // On silence keep the last reading on screen so the result stays readable as the string fades.
                    tracker.push(f, locked)?.let { reading = it }
                    tuned = tracker.tuned.toSet()
                }
        }
    }
    // All six strings in tune once = the week-1 tuning test.
    LaunchedEffect(tuned.size == GuitarString.entries.size) {
        if (tuned.size == GuitarString.entries.size) Repos.progress.mark(Milestone.TUNED)
    }
    TunerContent(
        reading = reading,
        tuned = tuned,
        locked = locked,
        micGranted = granted,
        onAskMic = ask,
        onLock = { locked = if (locked == it) null else it },
        onBack = onBack,
    )
}

@Composable
fun TunerContent(
    reading: TunerReading?,
    tuned: Set<GuitarString>,
    locked: GuitarString?,
    micGranted: Boolean,
    onAskMic: () -> Unit,
    onLock: (GuitarString) -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        ScreenHeader("Lên dây", "Gảy từng dây một, để dây ngân", onBack)

        if (!micGranted) {
            Text(
                "Tuner cần nghe tiếng đàn qua micro. Âm thanh chỉ xử lý trên máy, không ghi lại, không gửi đi đâu.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Button(onClick = onAskMic) { Text("Cho phép micro") }
            return@Column
        }

        val color = when {
            reading == null -> Palette.Muted
            reading.inTune -> Palette.Right
            abs(reading.centsFromString) <= 20 -> Palette.Accent
            else -> Palette.Wrong
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(reading?.noteName ?: "–", style = MaterialTheme.typography.displayLarge, color = color)
            Text(
                reading?.let { "Dây ${it.string.number} · ${it.string.label}   ${"%.1f".format(it.freq)} Hz" } ?: "Đang nghe…",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.Muted,
            )
        }

        CentsMeter(reading?.centsFromString, color)

        Text(
            text = when {
                reading == null -> "Gảy một dây buông"
                reading.inTune -> "Chuẩn rồi ✓"
                reading.centsFromString < 0 -> "Thấp ${abs(reading.centsFromString).roundToInt()} cents — vặn căng dây lên"
                else -> "Cao ${reading.centsFromString.roundToInt()} cents — nới dây xuống"
            },
            style = MaterialTheme.typography.titleLarge,
            color = color,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.weight(1f))
        Text(
            if (locked == null) "Tự nhận dây · chạm để khoá một dây" else "Đang khoá dây ${locked.number} · chạm lại để bỏ khoá",
            style = MaterialTheme.typography.labelMedium,
            color = Palette.Muted,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            GuitarString.entries.forEach { s ->
                StringChip(s, done = s in tuned, active = reading?.string == s, locked = locked == s, onClick = { onLock(s) })
            }
        }
        Text(
            "${tuned.size}/6 dây đã chuẩn",
            style = MaterialTheme.typography.titleMedium,
            color = if (tuned.size == 6) Palette.Right else Palette.Text,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun StringChip(s: GuitarString, done: Boolean, active: Boolean, locked: Boolean, onClick: () -> Unit) {
    val border = when {
        locked -> Palette.Accent
        active -> Palette.Text
        else -> Palette.Hairline
    }
    Box(
        Modifier
            .size(50.dp)
            .background(if (done) Palette.Right.copy(alpha = 0.18f) else Palette.Surface, CircleShape)
            .border(if (locked || active) 2.dp else 1.dp, border, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(s.label.dropLast(1), fontWeight = FontWeight.SemiBold, color = if (done) Palette.Right else Palette.Text)
            Text("${s.number}", style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
        }
    }
}

/** Horizontal −50…+50 cents scale with a needle; green zone is ±5 cents. */
@Composable
private fun CentsMeter(cents: Double?, color: androidx.compose.ui.graphics.Color) {
    Canvas(Modifier.fillMaxWidth().height(70.dp)) {
        val mid = size.width / 2
        val y = size.height / 2
        val perCent = size.width / 100f
        drawRect(
            Palette.Right.copy(alpha = 0.15f),
            topLeft = Offset(mid - 5 * perCent, 0f),
            size = androidx.compose.ui.geometry.Size(10 * perCent, size.height),
        )
        for (c in -50..50 step 10) {
            val x = mid + c * perCent
            val h = if (c == 0) size.height * 0.45f else size.height * 0.2f
            drawLine(if (c == 0) Palette.Text else Palette.Muted, Offset(x, y - h), Offset(x, y + h), strokeWidth = if (c == 0) 4f else 2f)
        }
        if (cents != null) {
            val x = mid + cents.toFloat().coerceIn(-50f, 50f) * perCent
            drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = 10f, cap = StrokeCap.Round)
        }
    }
}
