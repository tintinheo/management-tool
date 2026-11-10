package com.myguitarplayer.ui.setup

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.myguitarplayer.data.Repos
import com.myguitarplayer.platform.Platform
import androidx.compose.ui.unit.dp
import com.myguitarplayer.CrashLog
import com.myguitarplayer.audio.AudioConfig
import com.myguitarplayer.audio.MicStream
import com.myguitarplayer.audio.OnsetDetector
import com.myguitarplayer.audio.Yin
import com.myguitarplayer.ui.common.Card
import com.myguitarplayer.ui.common.KeepScreenOn
import com.myguitarplayer.ui.common.ScreenHeader
import com.myguitarplayer.ui.common.rememberMicPermission
import com.myguitarplayer.ui.rhythm.CLICK_TONES
import com.myguitarplayer.ui.theme.Palette
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlin.math.log10
import kotlin.math.roundToInt

/** Seconds of silence used to measure the room noise. */
private const val NOISE_SECONDS = 3

const val METER_MIN_DB = -70f

fun dbOf(rms: Double): Float = if (rms <= 0) METER_MIN_DB else (20 * log10(rms)).toFloat().coerceIn(METER_MIN_DB, 0f)

data class MicSetupUi(
    val micGranted: Boolean,
    val levelDb: Float,
    val peakDb: Float,
    val noiseRms: Double?,
    /** 0..1 while measuring the room noise, null otherwise. */
    val measuring: Float?,
    val strums: Int,
    val flash: Boolean,
    val chordLevel: Int,
    val onsetLevel: Int,
    val source: AudioConfig.Source,
    val rawSupported: Boolean,
    /** False on Windows: the system default input is used, so the source chooser becomes a hint. */
    val sourceChoice: Boolean = true,
    val latencyMs: Int?,
) {
    val noiseDb: Float? get() = noiseRms?.let { dbOf(it) }
    val chordGateDb: Float get() = dbOf(chordGate)
    private val chordGate: Double
        get() = noiseRms?.let { (it * 3).coerceIn(0.0015, 0.03) } ?: AudioConfig.DEFAULT_CHROMA_SILENCE
}

/**
 * One-time per-phone setup: room noise sets the listening gates, a live strum counter checks the onset
 * sensitivity, plus chord strictness, mic source and a manual latency nudge. Everything is saved at once.
 */
@Composable
fun MicSetupScreen(onBack: () -> Unit) {
    KeepScreenOn()
    val practice = Repos.practice
    val (granted, ask) = rememberMicPermission()
    val rawSupported = remember { AudioConfig.supportsRaw() }

    var noise by remember { mutableStateOf(AudioConfig.noiseRms) }
    var chordLevel by remember { mutableIntStateOf(AudioConfig.chordLevel) }
    var onsetLevel by remember { mutableIntStateOf(AudioConfig.onsetLevel) }
    var source by remember { mutableStateOf(AudioConfig.source) }
    var latency by remember { mutableStateOf(practice.latencyMs) }

    var level by remember { mutableFloatStateOf(METER_MIN_DB) }
    var peak by remember { mutableFloatStateOf(METER_MIN_DB) }
    var measuring by remember { mutableStateOf<Float?>(null) }
    var measureRequest by remember { mutableIntStateOf(0) }
    var strums by remember { mutableIntStateOf(0) }
    var lastStrum by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(0L) }

    fun persist() {
        AudioConfig.noiseRms = noise
        AudioConfig.chordLevel = chordLevel
        AudioConfig.onsetLevel = onsetLevel
        AudioConfig.source = source
        AudioConfig.save()
    }

    if (granted) {
        // Restart the stream when the source or onset sensitivity changes; both are read when it opens.
        LaunchedEffect(source, onsetLevel, noise, measureRequest) {
            val detector = OnsetDetector(MicStream.SAMPLE_RATE, ignoreHz = CLICK_TONES)
            val noiseSamples = if (measureRequest > 0 && measuring != null) ArrayList<Double>() else null
            val needed = NOISE_SECONDS * MicStream.SAMPLE_RATE / 512
            MicStream.chunks()
                .flowOn(Dispatchers.Default)
                .collect { c ->
                    val rms = Yin.rms(c.samples)
                    val db = dbOf(rms)
                    level = db
                    peak = maxOf(db, peak - 0.25f) // ~20 dB/s peak-hold decay
                    now = System.nanoTime()
                    if (detector.push(c.samples).isNotEmpty() && measuring == null) {
                        strums++
                        lastStrum = now
                    }
                    if (noiseSamples != null) {
                        noiseSamples += rms
                        measuring = noiseSamples.size.toFloat() / needed
                        if (noiseSamples.size >= needed) {
                            // Median: a stray knock or breath during the measurement does not raise the gate.
                            noise = noiseSamples.sorted()[noiseSamples.size / 2].coerceAtLeast(1e-5)
                            measuring = null
                            persist()
                            strums = 0
                        }
                    }
                }
        }
    }

    MicSetupContent(
        ui = MicSetupUi(
            micGranted = granted,
            levelDb = level,
            peakDb = peak,
            noiseRms = noise,
            measuring = measuring,
            strums = strums,
            flash = lastStrum != 0L && now - lastStrum < 150_000_000L,
            chordLevel = chordLevel,
            onsetLevel = onsetLevel,
            source = source,
            rawSupported = rawSupported,
            sourceChoice = Platform.current.micSourceChoice,
            latencyMs = latency,
        ),
        onAskMic = ask,
        onMeasure = { measuring = 0f; measureRequest++ },
        onResetStrums = { strums = 0 },
        onChordLevel = { chordLevel = it; persist() },
        onOnsetLevel = { onsetLevel = it; strums = 0; persist() },
        onSource = { source = it; persist() },
        onLatency = { v -> latency = v?.coerceIn(0, 400); practice.latencyMs = latency },
        onReset = {
            AudioConfig.reset()
            noise = null; chordLevel = 1; onsetLevel = 1; source = AudioConfig.Source.STANDARD; strums = 0
        },
        onCopyReport = {
            val text = CrashLog.describe() +
                "\nỒn phòng: ${noise?.let { "${dbOf(it).roundToInt()} dB" } ?: "chưa đo"} · trễ ${latency ?: "chưa đo"} ms" +
                " · nguồn thô ${if (rawSupported) "có" else "không"}"
            Platform.current.copyToClipboard("MyGuitarPlayer", text)
        },
        onBack = onBack,
    )
}

@Composable
fun MicSetupContent(
    ui: MicSetupUi,
    onAskMic: () -> Unit,
    onMeasure: () -> Unit,
    onResetStrums: () -> Unit,
    onChordLevel: (Int) -> Unit,
    onOnsetLevel: (Int) -> Unit,
    onSource: (AudioConfig.Source) -> Unit,
    onLatency: (Int?) -> Unit,
    onReset: () -> Unit,
    onCopyReport: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenHeader("Hiệu chỉnh micro", "Làm một lần trên máy này, khoảng 1 phút", onBack)
        if (!ui.micGranted) {
            Card {
                Text("Cần quyền micro để nghe tiếng đàn. Âm thanh chỉ xử lý trên máy.", style = MaterialTheme.typography.bodyLarge)
                Button(onClick = onAskMic) { Text("Cho phép micro") }
            }
        }

        // 1 + 2: level and room noise.
        Card {
            Text("1. Tiếng ồn phòng", style = MaterialTheme.typography.titleMedium)
            LevelMeter(ui)
            Text(
                "Đang nghe: ${ui.levelDb.roundToInt()} dB" + (ui.noiseDb?.let { " · ồn phòng ${it.roundToInt()} dB" } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.Muted,
            )
            val m = ui.measuring
            if (m != null) {
                Text("Giữ yên lặng, không chạm đàn…", style = MaterialTheme.typography.bodyLarge, color = Palette.Accent)
                LinearProgressIndicator(progress = { m.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            } else {
                Text(noiseAdvice(ui), style = MaterialTheme.typography.bodyMedium)
                Button(onClick = onMeasure, enabled = ui.micGranted) {
                    Text(if (ui.noiseRms == null) "Đo tiếng ồn ($NOISE_SECONDS giây)" else "Đo lại")
                }
            }
        }

        // 3: strum counter for onset sensitivity.
        Card {
            Text("2. Thử quạt", style = MaterialTheme.typography.titleMedium)
            Text(
                "Quạt chậm 8 cái (cả xuống lẫn lên). App phải đếm đúng 8. Rồi ngồi im vài giây — số không được tăng.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(
                    Modifier.size(22.dp).background(if (ui.flash) Palette.Accent else Palette.Hairline, CircleShape),
                )
                Text("${ui.strums}", style = MaterialTheme.typography.displaySmall)
                Text("cú quạt", style = MaterialTheme.typography.bodyLarge, color = Palette.Muted, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = onResetStrums) { Text("Đếm lại") }
            }
            Choice(listOf("Ít nhạy", "Vừa", "Nhạy"), ui.onsetLevel, onOnsetLevel)
            Text(
                "Đếm thiếu cú quạt lên → chọn Nhạy. Đếm thừa khi không quạt (tiếng ồn, tiếng click) → chọn Ít nhạy.",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.Muted,
            )
        }

        Card {
            Text("3. Độ chặt khi chấm hợp âm", style = MaterialTheme.typography.titleMedium)
            Choice(listOf("Dễ", "Vừa", "Chặt"), ui.chordLevel, onChordLevel)
            Text(
                when (ui.chordLevel) {
                    0 -> "Dễ: hợp âm bấm đúng mà app hay báo sai thì chọn mức này (máy thu kém, đàn cũ, phòng vang)."
                    2 -> "Chặt: chỉ báo đúng khi mọi dây kêu rõ. Dùng khi đã quen tay."
                    else -> "Vừa: mức mặc định."
                },
                style = MaterialTheme.typography.bodySmall,
                color = Palette.Muted,
            )
        }

        Card {
            Text("4. Nguồn micro", style = MaterialTheme.typography.titleMedium)
            if (!ui.sourceChoice) {
                Text(
                    "App dùng micro mặc định của Windows. Muốn đổi micro (ví dụ micro cài áo, sound card): " +
                        "Cài đặt › Hệ thống › Âm thanh › Đầu vào. Nếu có \"Audio enhancements\", tắt đi để app nghe đàn rõ hơn.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (ui.sourceChoice) AudioConfig.Source.entries.forEach { s ->
                val enabled = s != AudioConfig.Source.RAW || ui.rawSupported
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = s == ui.source,
                        onClick = { onSource(s) },
                        enabled = enabled,
                        label = { Text(s.label) },
                        colors = chipColors(),
                    )
                    Text(
                        if (enabled) s.detail else "Máy này không hỗ trợ.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.Muted,
                        modifier = Modifier.padding(start = 10.dp).weight(1f),
                    )
                }
            }
        }

        Card {
            Text("5. Độ trễ khi chấm nhịp", style = MaterialTheme.typography.titleMedium)
            val lat = ui.latencyMs
            if (lat == null) {
                Text("Chưa đo. Vào Công cụ → Chấm nhịp quạt → \"Đo ngay\" (20 giây).", style = MaterialTheme.typography.bodyMedium)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { onLatency(lat - 5) }) { Text("−5") }
                    Text("$lat ms", style = MaterialTheme.typography.headlineSmall)
                    OutlinedButton(onClick = { onLatency(lat + 5) }) { Text("+5") }
                }
                Text(
                    "Nếu quạt đúng click mà app luôn báo \"muộn\", giảm số này; luôn báo \"sớm\" thì tăng lên. Đeo tai nghe thì nên đo lại.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Muted,
                )
                TextButton(onClick = { onLatency(null) }) { Text("Xoá, đo lại từ đầu") }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onCopyReport) { Text("Copy thông số máy") }
            TextButton(onClick = onReset) { Text("Khôi phục mặc định") }
        }
    }
}

private fun noiseAdvice(ui: MicSetupUi): String {
    val n = ui.noiseDb ?: return "Tắt quạt máy, TV; đặt điện thoại cách đàn 30–50 cm, hướng micro (cạnh dưới) về phía lỗ thoát âm."
    return when {
        n > -35f -> "Phòng khá ồn. Hợp âm và nhịp vẫn chấm được nhưng dễ sai — tìm chỗ yên tĩnh hơn nếu có thể."
        ui.peakDb > METER_MIN_DB + 1 && ui.peakDb < ui.chordGateDb + 6 && ui.levelDb > n + 3 ->
            "Tiếng đàn hơi nhỏ so với ngưỡng nghe. Đưa điện thoại lại gần đàn hơn."
        else -> "Đã lưu. Gảy thử đàn: vạch vàng phải vượt qua vạch trắng (ngưỡng nghe hợp âm)."
    }
}

/** Horizontal dBFS meter: live level bar, peak tick, and the room-noise / chord-listening gates. */
@Composable
private fun LevelMeter(ui: MicSetupUi) {
    Canvas(Modifier.fillMaxWidth().height(28.dp)) {
        fun x(db: Float) = size.width * ((db - METER_MIN_DB) / -METER_MIN_DB).coerceIn(0f, 1f)
        val r = CornerRadius(4.dp.toPx())
        val barTop = 6.dp.toPx()
        val barH = size.height - 2 * barTop
        drawRoundRect(Palette.SurfaceHigh, Offset(0f, barTop), Size(size.width, barH), r)
        drawRoundRect(Palette.Accent, Offset(0f, barTop), Size(x(ui.levelDb), barH), r)
        val tick = 2.dp.toPx()
        drawRect(Palette.Accent.copy(alpha = 0.6f), Offset(x(ui.peakDb) - tick / 2, barTop), Size(tick, barH))
        ui.noiseDb?.let { drawRect(Palette.Muted, Offset(x(it) - tick / 2, 0f), Size(tick, size.height)) }
        drawRect(Palette.Text, Offset(x(ui.chordGateDb) - tick / 2, 0f), Size(tick, size.height))
    }
}

@Composable
private fun chipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = Palette.Accent,
    selectedLabelColor = Palette.OnAccent,
    labelColor = Palette.Text,
)

@Composable
private fun Choice(labels: List<String>, selected: Int, onPick: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { i, l ->
            FilterChip(selected = i == selected, onClick = { onPick(i) }, label = { Text(l) }, colors = chipColors())
        }
    }
}
