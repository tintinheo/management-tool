package com.myguitarplayer.ui.lesson

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.myguitarplayer.music.Chord
import com.myguitarplayer.music.GuitarString
import com.myguitarplayer.music.Note
import com.myguitarplayer.ui.theme.Palette

enum class DotKind { NORMAL, ROOT, GHOST }

/** One marked position: [string] 1 (high E, top row) … 6 (low E), [fret] 0 = open. */
data class FretDot(val string: Int, val fret: Int, val label: String = "", val kind: DotKind = DotKind.NORMAL)

object Fret {
    fun stringOf(number: Int): GuitarString = GuitarString.entries.first { it.number == number }
    fun midi(string: Int, fret: Int): Int = stringOf(string).midi + fret
    fun noteName(string: Int, fret: Int): String = Note.name(midi(string, fret))

    val FINGER_NAMES = listOf("", "trỏ", "giữa", "áp út", "út")

    /** A chord laid on the neck with each sounding note named; the lowest (bass) note stands out. */
    fun chordDots(chord: Chord): List<FretDot> {
        val bass = chord.frets.indexOfFirst { it >= 0 }
        return chord.frets.toList().mapIndexedNotNull { i, f ->
            if (f < 0) null else FretDot(6 - i, f, noteName(6 - i, f), if (i == bass) DotKind.ROOT else DotKind.NORMAL)
        }
    }

    fun mutedStrings(chord: Chord): Set<Int> = chord.frets.indices.filter { chord.frets[it] < 0 }.map { 6 - it }.toSet()

    /** Every natural note (no sharps) in frets 0…[frets] — the beginner's map of the neck. */
    fun naturalNotes(frets: Int, strings: IntRange = 1..6): List<FretDot> = strings.flatMap { s ->
        (0..frets).mapNotNull { f -> noteName(s, f).takeIf { '#' !in it }?.let { FretDot(s, f, it, DotKind.GHOST) } }
    }
}

/**
 * The neck seen like guitar tab: string 1 (thinnest) on top, string 6 (thickest) at the bottom, the nut on the
 * left, frets counted to the right. Open-string names on the left edge; fret numbers underneath.
 */
@Composable
fun Fretboard(
    dots: List<FretDot>,
    modifier: Modifier = Modifier,
    frets: Int = 5,
    muted: Set<Int> = emptySet(),
    capo: Int = 0,
    rowHeight: Dp = 30.dp,
    onTap: ((string: Int, fret: Int) -> Unit)? = null,
) {
    val measurer = rememberTextMeasurer()
    val geometry = { w: Float, rowPx: Float -> Geometry(w, rowPx, frets) }
    var tapMod: Modifier = Modifier
    if (onTap != null) {
        tapMod = Modifier.pointerInput(frets, onTap) {
            detectTapGestures { p ->
                val g = geometry(size.width.toFloat(), rowHeight.toPx())
                g.hit(p)?.let { (s, f) -> onTap(s, f) }
            }
        }
    }
    Canvas(modifier.fillMaxWidth().height(rowHeight * 6 + 24.dp).then(tapMod)) {
        val g = geometry(size.width, rowHeight.toPx())
        drawBoard(g, measurer, dots, muted, capo)
    }
}

private class Geometry(val width: Float, val row: Float, val frets: Int) {
    val labelW = row * 1.6f
    val openW = row * 1.1f
    val nutX = labelW + openW
    val cell = (width - nutX - row * 0.3f) / frets
    val top = 0f
    fun y(string: Int) = top + (string - 0.5f) * row
    fun x(fret: Int) = if (fret == 0) labelW + openW / 2 else nutX + (fret - 0.5f) * cell
    val bottom get() = top + 6 * row

    fun hit(p: Offset): Pair<Int, Int>? {
        if (p.y < top || p.y > bottom || p.x < labelW) return null
        val s = ((p.y - top) / row).toInt().coerceIn(0, 5) + 1
        val f = if (p.x < nutX) 0 else (((p.x - nutX) / cell).toInt() + 1).coerceIn(1, frets)
        return s to f
    }
}

private fun DrawScope.drawBoard(g: Geometry, measurer: TextMeasurer, dots: List<FretDot>, muted: Set<Int>, capo: Int) {
    val boardTop = g.y(1) - g.row * 0.35f
    val boardBottom = g.y(6) + g.row * 0.35f
    // Wood.
    drawRect(Palette.SurfaceHigh, Offset(g.nutX, boardTop), Size(g.frets * g.cell, boardBottom - boardTop))
    // Position markers (3, 5, 7, 9; double at 12).
    for (f in listOf(3, 5, 7, 9, 12)) {
        if (f > g.frets) continue
        val cx = g.nutX + (f - 0.5f) * g.cell
        if (f == 12) {
            drawCircle(Palette.Hairline, g.row * 0.16f, Offset(cx, g.y(2) + g.row / 2))
            drawCircle(Palette.Hairline, g.row * 0.16f, Offset(cx, g.y(4) + g.row / 2))
        } else {
            drawCircle(Palette.Hairline, g.row * 0.18f, Offset(cx, g.y(3) + g.row / 2))
        }
    }
    // Nut and frets.
    drawLine(Palette.Text, Offset(g.nutX, boardTop), Offset(g.nutX, boardBottom), strokeWidth = g.row * 0.22f)
    for (f in 1..g.frets) {
        val x = g.nutX + f * g.cell
        drawLine(Palette.Muted, Offset(x, boardTop), Offset(x, boardBottom), strokeWidth = g.row * 0.08f)
    }
    // Strings: thicker towards string 6; muted strings dimmed.
    for (s in 1..6) {
        val color = if (s in muted) Palette.Hairline else Palette.Text.copy(alpha = 0.75f)
        drawLine(color, Offset(g.labelW + g.openW * 0.15f, g.y(s)), Offset(g.width - g.row * 0.3f, g.y(s)), strokeWidth = 1.2f + s * 0.55f)
    }

    val labelStyle = TextStyle(color = Palette.Muted, fontSize = (g.row / density * 0.42f).sp, fontWeight = FontWeight.SemiBold)
    val nameStyle = labelStyle.copy(color = Palette.Text)
    for (s in 1..6) {
        val num = measurer.measure("$s", labelStyle)
        drawText(num, topLeft = Offset(g.row * 0.1f, g.y(s) - num.size.height / 2f))
        val name = measurer.measure(Fret.stringOf(s).label.dropLast(1), nameStyle)
        drawText(name, topLeft = Offset(g.row * 0.7f, g.y(s) - name.size.height / 2f))
    }
    for (f in 1..g.frets) {
        val t = measurer.measure("$f", labelStyle)
        drawText(t, topLeft = Offset(g.nutX + (f - 0.5f) * g.cell - t.size.width / 2f, boardBottom + g.row * 0.15f))
    }

    // Capo: a bar right behind fret [capo], across every string.
    if (capo > 0) {
        val x = g.nutX + (capo - 0.22f) * g.cell
        drawRoundRect(
            Palette.Accent.copy(alpha = 0.85f),
            topLeft = Offset(x - g.row * 0.18f, boardTop - g.row * 0.1f),
            size = Size(g.row * 0.36f, boardBottom - boardTop + g.row * 0.2f),
            cornerRadius = CornerRadius(g.row * 0.18f),
        )
    }

    // Muted strings: × in the open column.
    val r = g.row * 0.18f
    for (s in muted) {
        val c = Offset(g.x(0), g.y(s))
        drawLine(Palette.Wrong, Offset(c.x - r, c.y - r), Offset(c.x + r, c.y + r), strokeWidth = 3f)
        drawLine(Palette.Wrong, Offset(c.x - r, c.y + r), Offset(c.x + r, c.y - r), strokeWidth = 3f)
    }

    val dotR = g.row * 0.42f
    for (d in dots) {
        val c = Offset(g.x(d.fret), g.y(d.string))
        val (fill, text) = when (d.kind) {
            DotKind.NORMAL -> Palette.Accent to Palette.OnAccent
            DotKind.ROOT -> Palette.Text to Palette.Ink
            DotKind.GHOST -> Palette.Ink to Palette.Text
        }
        if (d.fret == 0 && d.kind != DotKind.GHOST) {
            // Open string: a ring, like ○ on a chord chart.
            drawCircle(Palette.Ink, dotR, c)
            drawCircle(fill, dotR, c, style = Stroke(3f))
        } else {
            drawCircle(fill, dotR, c)
            if (d.kind == DotKind.GHOST) drawCircle(Palette.Hairline, dotR, c, style = Stroke(2f))
        }
        if (d.label.isNotEmpty()) {
            val style = TextStyle(
                color = if (d.fret == 0 && d.kind != DotKind.GHOST) fill else text,
                fontSize = (g.row / density * if (d.label.length > 2) 0.32f else 0.42f).sp,
                fontWeight = FontWeight.Bold,
            )
            val t = measurer.measure(d.label, style)
            drawText(t, topLeft = Offset(c.x - t.size.width / 2f, c.y - t.size.height / 2f))
        }
    }
}

/** "Dây nào – nốt gì – bấm thế nào" for every string of a chord, low to high. */
@Composable
fun ChordBreakdown(chord: Chord) {
    val bass = chord.frets.indexOfFirst { it >= 0 }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        chord.frets.forEachIndexed { i, fret ->
            val s = 6 - i
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Dây $s", style = MaterialTheme.typography.bodyMedium, color = Palette.Muted, modifier = Modifier.width(56.dp))
                Text(
                    if (fret < 0) "×" else Fret.noteName(s, fret),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (fret < 0) Palette.Wrong else if (i == bass) Palette.Text else Palette.Accent,
                    modifier = Modifier.width(40.dp),
                )
                Text(
                    when {
                        fret < 0 -> "không gảy"
                        fret == 0 -> "buông, không bấm"
                        else -> "ngăn $fret · ngón ${Fret.FINGER_NAMES[chord.fingers[i]]} (${chord.fingers[i]})"
                    } + if (i == bass) " · bass" else "",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
