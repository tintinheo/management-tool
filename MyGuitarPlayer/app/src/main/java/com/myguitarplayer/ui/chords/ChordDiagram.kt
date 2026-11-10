package com.myguitarplayer.ui.chords

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.myguitarplayer.music.Chord
import com.myguitarplayer.ui.theme.Palette

/**
 * Standard chord box: vertical lines = strings (6 on the left), horizontal = frets, nut on top.
 * × / ○ above the nut mark muted / open strings; dots carry the finger number.
 */
@Composable
fun ChordDiagram(chord: Chord, width: Dp = 200.dp, showFingers: Boolean = true) {
    val measurer = rememberTextMeasurer()
    val fretsShown = maxOf(4, chord.frets.max())
    val scale = width.value / 200f
    Canvas(Modifier.width(width).aspectRatio(0.82f)) {
        val top = size.height * 0.16f
        val left = size.width * 0.12f
        val right = size.width * 0.88f
        val bottom = size.height * 0.97f
        val dx = (right - left) / 5
        val dy = (bottom - top) / fretsShown
        val line = Palette.Muted

        // Nut and frets.
        drawLine(Palette.Text, Offset(left - 2, top), Offset(right + 2, top), strokeWidth = 8f * scale)
        for (f in 1..fretsShown) drawLine(line, Offset(left, top + f * dy), Offset(right, top + f * dy), strokeWidth = 2f * scale)
        for (s in 0 until 6) drawLine(line, Offset(left + s * dx, top), Offset(left + s * dx, bottom), strokeWidth = (3f - s * 0.35f) * scale)

        val markY = top - size.height * 0.08f
        val markR = dx * 0.2f
        chord.frets.forEachIndexed { s, fret ->
            val x = left + s * dx
            when (fret) {
                -1 -> {
                    drawLine(Palette.Wrong, Offset(x - markR, markY - markR), Offset(x + markR, markY + markR), strokeWidth = 3f * scale)
                    drawLine(Palette.Wrong, Offset(x - markR, markY + markR), Offset(x + markR, markY - markR), strokeWidth = 3f * scale)
                }
                0 -> drawCircle(Palette.Text, markR, Offset(x, markY), style = Stroke(2.5f * scale))
            }
        }

        // Barre: same finger on the same fret across several strings.
        chord.frets.indices
            .filter { chord.frets[it] > 0 && chord.fingers[it] > 0 }
            .groupBy { chord.fingers[it] to chord.frets[it] }
            .filter { it.value.size > 1 }
            .forEach { (key, strings) ->
                val y = top + (key.second - 0.5f) * dy
                val x0 = left + strings.min() * dx
                val x1 = left + strings.max() * dx
                drawRoundRect(
                    Palette.Accent,
                    topLeft = Offset(x0 - dx * 0.32f, y - dx * 0.32f),
                    size = Size(x1 - x0 + dx * 0.64f, dx * 0.64f),
                    cornerRadius = CornerRadius(dx * 0.32f),
                )
            }

        val textStyle = TextStyle(color = Palette.OnAccent, fontSize = (15 * scale).sp, fontWeight = FontWeight.Bold)
        chord.frets.forEachIndexed { s, fret ->
            if (fret <= 0) return@forEachIndexed
            val c = Offset(left + s * dx, top + (fret - 0.5f) * dy)
            drawCircle(Palette.Accent, dx * 0.34f, c)
            val finger = chord.fingers[s]
            if (showFingers && finger > 0) {
                val layout = measurer.measure("$finger", textStyle)
                drawText(layout, topLeft = Offset(c.x - layout.size.width / 2f, c.y - layout.size.height / 2f))
            }
        }
    }
}
