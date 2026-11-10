package com.myguitarplayer.ui.progress

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.myguitarplayer.ui.theme.Palette
import kotlin.math.ceil

/**
 * Chart series colour: the app's gold stepped down into the dark-mode lightness band (OKLCH L 0.48–0.67)
 * and checked with the dataviz validator against the card surface. Marks only — text stays in text colours.
 */
val ChartGold = Color(0xFFB98A2C)

/** One plotted value: [x] label (date / take), [y] value, [label] what the tooltip and table say. */
data class Point(val x: String, val y: Float, val label: String)

/** A horizontal reference: target line or pass band edge. */
data class Reference(val y: Float, val label: String, val shadeBelow: Boolean = false)

private val axisStyle = TextStyle(color = Palette.Muted, fontSize = 11.sp)
private val valueStyle = TextStyle(color = Palette.Text, fontSize = 12.sp)

/** Clean tick step for an axis that reaches [max] (1, 2, 5 × 10ⁿ), aiming for ~4 ticks. */
fun niceStep(max: Float): Float {
    if (max <= 0f) return 1f
    val raw = max / 4
    var mag = 1f
    while (mag * 10 <= raw) mag *= 10
    while (mag > raw) mag /= 10
    return listOf(1f, 2f, 5f, 10f).map { it * mag }.first { it >= raw }
}

private class Frame(val w: Float, val h: Float, val left: Float, val bottom: Float, val yMax: Float, val n: Int, val inset: Float = 0f) {
    val plotW get() = w - left - 8f
    val plotH get() = h - bottom - 8f
    /** Points sit [inset] inside the plot so end dots don't touch the axis labels. */
    private val pointsW get() = plotW - 2 * inset
    fun x(i: Int): Float = left + inset + if (n <= 1) pointsW / 2 else pointsW * i / (n - 1)
    fun y(v: Float): Float = 8f + plotH * (1 - v / yMax)
    fun nearest(px: Float): Int = if (n <= 1) 0 else ((px - left - inset) / pointsW * (n - 1)).let { Math.round(it) }.coerceIn(0, n - 1)
}

private fun DrawScope.axes(f: Frame, measurer: TextMeasurer, step: Float, unit: String) {
    var v = 0f
    while (v <= f.yMax + 0.01f) {
        val y = f.y(v)
        drawLine(Palette.Hairline, Offset(f.left, y), Offset(f.w - 8f, y), strokeWidth = 1f)
        val t = measurer.measure("${v.toInt()}$unit", axisStyle)
        drawText(t, topLeft = Offset(f.left - t.size.width - 6f, y - t.size.height / 2f))
        v += step
    }
}

/** X labels: always the last one, then every [every]-th going back, skipping any that would overlap. */
private fun DrawScope.xLabels(f: Frame, measurer: TextMeasurer, points: List<Point>, every: Int) {
    var leftEdge = Float.MAX_VALUE
    for (i in points.indices.reversed()) {
        if ((points.lastIndex - i) % every != 0) continue
        val t = measurer.measure(points[i].x, axisStyle)
        val x = (f.x(i) - t.size.width / 2f).coerceIn(f.left, f.w - t.size.width.toFloat())
        if (x + t.size.width + 6f * density > leftEdge) continue
        drawText(t, topLeft = Offset(x, f.h - f.bottom + 6f))
        leftEdge = x
    }
}

private fun DrawScope.reference(f: Frame, measurer: TextMeasurer, r: Reference) {
    val y = f.y(r.y)
    if (r.shadeBelow) drawRect(Palette.Text.copy(alpha = 0.05f), Offset(f.left, y), Size(f.plotW, f.y(0f) - y))
    drawLine(Palette.Muted, Offset(f.left, y), Offset(f.w - 8f, y), strokeWidth = 1.5f)
    // Label at the left end: the latest point (and its value label) always sits at the right.
    val t = measurer.measure(r.label, axisStyle)
    drawText(t, topLeft = Offset(f.left + 4f, y - t.size.height - 2f))
}

/**
 * Line chart (2 px line, 8 px dots with a 2 px surface ring). Labels only the last point and the best one;
 * tap or drag to read any point. "Xem bảng" shows every value as text.
 */
@Composable
fun LineChart(
    points: List<Point>,
    modifier: Modifier = Modifier,
    unit: String = "",
    reference: Reference? = null,
    lowerIsBetter: Boolean = false,
    height: Dp = 180.dp,
) {
    val measurer = rememberTextMeasurer()
    var selected by remember(points) { mutableStateOf<Int?>(null) }
    var table by remember { mutableStateOf(false) }
    val yMaxData = maxOf(points.maxOfOrNull { it.y } ?: 1f, reference?.y ?: 0f) * 1.15f
    val step = niceStep(yMaxData)
    val yMax = ceil(yMaxData / step) * step
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            selected?.let { points.getOrNull(it)?.label } ?: " ",
            style = MaterialTheme.typography.bodyMedium,
            color = Palette.Text,
        )
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .pointerInput(points) {
                    detectTapGestures { p -> selected = Frame(size.width.toFloat(), size.height.toFloat(), 44f * density, 24f * density, yMax, points.size, 12f * density).nearest(p.x) }
                }
                .pointerInput(points) {
                    detectDragGestures { change, _ -> selected = Frame(size.width.toFloat(), size.height.toFloat(), 44f * density, 24f * density, yMax, points.size, 12f * density).nearest(change.position.x) }
                },
        ) {
            val f = Frame(size.width, size.height, 44f * density, 24f * density, yMax, points.size, 12f * density)
            axes(f, measurer, step, unit)
            reference?.let { reference(f, measurer, it) }
            if (points.isEmpty()) return@Canvas
            xLabels(f, measurer, points, every = maxOf(1, points.size / 4))
            val path = Path()
            points.forEachIndexed { i, p -> if (i == 0) path.moveTo(f.x(i), f.y(p.y)) else path.lineTo(f.x(i), f.y(p.y)) }
            drawPath(path, ChartGold, style = Stroke(2f * density, cap = StrokeCap.Round, join = StrokeJoin.Round))
            selected?.let { i -> drawLine(Palette.Muted, Offset(f.x(i), 8f), Offset(f.x(i), f.y(0f)), strokeWidth = 1f) }
            points.forEachIndexed { i, p ->
                val c = Offset(f.x(i), f.y(p.y))
                drawCircle(Palette.Surface, 6f * density, c) // 2 px surface ring
                drawCircle(ChartGold, 4f * density, c)
            }
            // Selective labels: the latest value and the best one.
            val best = if (lowerIsBetter) points.indices.minBy { points[it].y } else points.indices.maxBy { points[it].y }
            setOf(points.lastIndex, best).forEach { i ->
                val t = measurer.measure("${points[i].y.toInt()}$unit", valueStyle)
                val x = (f.x(i) - t.size.width / 2f).coerceIn(f.left, f.w - t.size.width.toFloat())
                val above = f.y(points[i].y) - t.size.height - 6f * density
                drawText(t, topLeft = Offset(x, if (above < 0) f.y(points[i].y) + 6f * density else above))
            }
        }
        TableToggle(points, table) { table = it }
    }
}

/** Column chart: ≤ 24 px columns, 4 px rounded tops, square at the baseline, 2 px gaps. Value on the tallest. */
@Composable
fun ColumnChart(points: List<Point>, modifier: Modifier = Modifier, unit: String = "", height: Dp = 160.dp) {
    val measurer = rememberTextMeasurer()
    var selected by remember(points) { mutableStateOf<Int?>(null) }
    var table by remember { mutableStateOf(false) }
    val yMaxData = maxOf(points.maxOfOrNull { it.y } ?: 0f, 10f) * 1.15f
    val step = niceStep(yMaxData)
    val yMax = ceil(yMaxData / step) * step
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(selected?.let { points.getOrNull(it)?.label } ?: " ", style = MaterialTheme.typography.bodyMedium)
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .pointerInput(points) {
                    detectTapGestures { p ->
                        val left = 44f * density
                        val slot = (size.width - left - 8f) / points.size.coerceAtLeast(1)
                        selected = ((p.x - left) / slot).toInt().coerceIn(0, points.size - 1)
                    }
                },
        ) {
            val left = 44f * density
            val f = Frame(size.width, size.height, left, 24f * density, yMax, points.size)
            axes(f, measurer, step, unit)
            if (points.isEmpty()) return@Canvas
            val slot = f.plotW / points.size
            val barW = minOf(24f * density, slot - 2f * density)
            val base = f.y(0f)
            val r = 4f * density
            points.forEachIndexed { i, p ->
                if (p.y <= 0f) return@forEachIndexed
                val cx = left + slot * (i + 0.5f)
                val top = f.y(p.y)
                val color = if (selected == null || selected == i) ChartGold else ChartGold.copy(alpha = 0.45f)
                // Rounded top, square bottom: a rounded rect plus a square patch over its lower corners.
                drawRoundRect(color, Offset(cx - barW / 2, top), Size(barW, base - top), CornerRadius(r))
                if (base - top > r) drawRect(color, Offset(cx - barW / 2, base - r), Size(barW, r))
            }
            points.forEachIndexed { i, p ->
                if (i % 2 != points.lastIndex % 2) return@forEachIndexed
                val t = measurer.measure(p.x, axisStyle)
                drawText(t, topLeft = Offset(left + slot * (i + 0.5f) - t.size.width / 2f, f.h - f.bottom + 6f))
            }
            val top = points.indices.maxBy { points[it].y }
            if (points[top].y > 0) {
                val t = measurer.measure("${points[top].y.toInt()}$unit", valueStyle)
                drawText(t, topLeft = Offset(left + slot * (top + 0.5f) - t.size.width / 2f, f.y(points[top].y) - t.size.height - 4f))
            }
        }
        TableToggle(points, table) { table = it }
    }
}

@Composable
private fun TableToggle(points: List<Point>, open: Boolean, onToggle: (Boolean) -> Unit) {
    if (points.isEmpty()) return
    TextButton(onClick = { onToggle(!open) }) { Text(if (open) "Ẩn bảng" else "Xem dạng bảng") }
    if (open) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            points.asReversed().forEach { p ->
                Row(Modifier.fillMaxWidth()) {
                    Text(p.label, style = MaterialTheme.typography.bodyMedium, color = Palette.Text)
                }
            }
        }
    }
}

/** Stat tile: label · value (sans semibold, proportional figures) · optional note. */
@Composable
fun StatTile(label: String, value: String, note: String?, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
        Text(value, style = TextStyle(fontSize = 28.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = Palette.Text))
        if (note != null) Text(note, style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
    }
}
