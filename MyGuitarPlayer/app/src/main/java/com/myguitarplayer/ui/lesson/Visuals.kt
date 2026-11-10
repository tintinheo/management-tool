package com.myguitarplayer.ui.lesson

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.myguitarplayer.data.Visual
import com.myguitarplayer.music.Chords
import com.myguitarplayer.ui.chords.ChordDiagram
import com.myguitarplayer.ui.theme.Palette

/** Draws a lesson step's illustration. Taps on the neck play that note when [onNote] is given. */
@Composable
fun LessonVisual(visual: Visual, onNote: ((Int) -> Unit)? = null) {
    val tap: ((Int, Int) -> Unit)? = onNote?.let { play -> { s, f -> play(Fret.midi(s, f)) } }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (visual.type) {
            "strings" -> {
                Fretboard(
                    (1..6).map { FretDot(it, 0, Fret.noteName(it, 0), if (it == 6) DotKind.ROOT else DotKind.NORMAL) },
                    frets = 3,
                    onTap = tap,
                )
                Text(
                    "Dây 1 mảnh nhất (gần sàn nhà khi ôm đàn), dây 6 to nhất (gần mặt bạn). " +
                        "Thứ tự từ dây 6 → 1: E A D G B E — nhớ câu \"Em Ăn Dưa Gang Bỏ Em\".",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.Muted,
                )
            }
            "chord" -> {
                val chord = Chords.find(visual.chord.orEmpty()) ?: return@Column
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    ChordDiagram(chord, width = 110.dp)
                    Text(
                        "Sơ đồ dọc như trên sách: dây 6 bên trái, đầu đàn ở trên, số trong chấm là ngón tay. " +
                            "Gảy từ dây ${chord.firstString}.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Palette.Muted,
                        modifier = Modifier.weight(1f),
                    )
                }
                ChordBreakdown(chord)
                Fretboard(Fret.chordDots(chord), frets = maxOf(4, chord.frets.max()), muted = Fret.mutedStrings(chord), onTap = tap)
                Text(
                    "Cần đàn ngang: dây 1 ở trên, như khi bạn cúi nhìn xuống đàn. Chấm trắng là nốt bass. Chạm vào chấm để nghe từng nốt.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.Muted,
                )
            }
            "fretboard" -> Fretboard(
                visual.dots.map { FretDot(it.s, it.f, it.label.ifEmpty { Fret.noteName(it.s, it.f) }, if (it.root) DotKind.ROOT else DotKind.NORMAL) },
                frets = visual.frets,
                muted = visual.muted.toSet(),
                onTap = tap,
            )
            "notes" -> Fretboard(
                Fret.naturalNotes(visual.frets).filter { it.string in visual.strings }
                    .map { it.copy(kind = DotKind.NORMAL) },
                frets = visual.frets,
                onTap = tap,
            )
            "press" -> PressDiagram()
            "strum" -> StrumGrid(visual.pattern.orEmpty())
            "capo" -> {
                Fretboard(
                    (1..6).map { FretDot(it, visual.capo, Fret.noteName(it, visual.capo)) },
                    frets = maxOf(4, visual.capo + 2),
                    capo = visual.capo,
                    onTap = tap,
                )
                Text(
                    "Capo kẹp ngăn ${visual.capo} = thay cho đầu đàn. Dây buông giờ kêu như các nốt trên — " +
                        "bấm thế hợp âm cũ nhưng tông cao lên ${visual.capo} nửa cung.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.Muted,
                )
            }
        }
        visual.caption?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Palette.Muted) }
    }
}

/**
 * Side view of one string between two frets, three fingertip spots: just behind the fret wire (clean),
 * far back (buzzes unless you press very hard), on top of the wire (muffled).
 */
@Composable
fun PressDiagram() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PressPanel(0.66f, Palette.Right, "Sát thanh phím\n→ kêu trong, nhẹ tay", Modifier.weight(1f))
        PressPanel(0.36f, Palette.Accent, "Giữa ô, xa phím\n→ dễ rè, phải ấn mạnh", Modifier.weight(1f))
        PressPanel(0.80f, Palette.Wrong, "Đè lên thanh phím\n→ tiếng tịt, câm", Modifier.weight(1f))
    }
    Text(
        "Ấn bằng đầu ngón (không phải bụng ngón), ngón cong như cầm quả bóng, ngón cái đặt sau cần đàn. " +
            "Thùng đàn ở phía bên phải hình.",
        style = MaterialTheme.typography.bodyMedium,
        color = Palette.Muted,
    )
}

@Composable
private fun PressPanel(fingerAt: Float, color: Color, label: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(1.2f)) {
            val w = size.width
            val h = size.height
            // Fretboard wood and two fret wires.
            drawRect(Palette.SurfaceHigh, Offset(0f, h * 0.55f), Size(w, h * 0.3f))
            for (x in listOf(0.12f, 0.82f)) {
                drawRect(Palette.Muted, Offset(w * x - w * 0.025f, h * 0.45f), Size(w * 0.05f, h * 0.12f))
            }
            // String, pushed down where the finger presses.
            val fx = w * fingerAt
            val pressed = h * 0.53f
            val rest = h * 0.42f
            drawLine(Palette.Text, Offset(0f, rest), Offset(w * 0.12f, h * 0.45f), strokeWidth = 4f)
            drawLine(Palette.Text, Offset(w * 0.12f, h * 0.45f), Offset(fx, pressed), strokeWidth = 4f)
            drawLine(Palette.Text, Offset(fx, pressed), Offset(w * 0.82f, h * 0.45f), strokeWidth = 4f)
            drawLine(Palette.Text, Offset(w * 0.82f, h * 0.45f), Offset(w, rest), strokeWidth = 4f)
            // Fingertip.
            drawCircle(color, w * 0.11f, Offset(fx, pressed - w * 0.11f))
            drawRect(color, Offset(fx - w * 0.11f, pressed - w * 0.11f - h * 0.4f), Size(w * 0.22f, h * 0.4f))
            drawCircle(color, w * 0.11f, Offset(fx, pressed - w * 0.11f), style = Stroke(3f))
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = color, textAlign = TextAlign.Center)
    }
}

/** A strum pattern on its count grid: "1 & 2 & 3 & 4 &" with ↓ / ↑ / (miss) / B (bass note) under each count. */
@Composable
fun StrumGrid(pattern: String) {
    val counts = List(pattern.length) { i -> if (i % 2 == 0) "${i / 2 + 1}" else "&" }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        pattern.uppercase().forEachIndexed { i, c ->
            val on = c == 'D' || c == 'U' || c == 'B'
            Column(
                Modifier
                    .weight(1f)
                    .background(if (on) Palette.SurfaceHigh else Color.Transparent, RoundedCornerShape(10.dp))
                    .border(1.dp, Palette.Hairline, RoundedCornerShape(10.dp))
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(counts[i], style = MaterialTheme.typography.labelMedium, color = if (i % 2 == 0) Palette.Text else Palette.Muted)
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        when (c) { 'D' -> "↓"; 'U' -> "↑"; 'B' -> "B"; else -> "·" },
                        fontSize = 26.sp,
                        color = when (c) { 'D' -> Palette.Accent; 'U' -> Palette.Text; 'B' -> Palette.Right; else -> Palette.Hairline },
                    )
                }
            }
        }
    }
    Text(
        "↓ quạt xuống · ↑ quạt lên · \"·\" tay vẫn đưa nhưng không chạm dây" + if ('B' in pattern.uppercase()) " · B gảy riêng nốt bass" else "",
        style = MaterialTheme.typography.bodyMedium,
        color = Palette.Muted,
    )
}
