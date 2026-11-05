package com.audiohub.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp

/**
 * Minimal seek bar: 3 dp track, small round thumb that grows while dragging.
 * Reports values in 0..1; [onValueChangeFinished] fires on release/tap.
 */
@Composable
fun ThinSeekBar(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val active = MaterialTheme.colorScheme.onSurface
    val track = MaterialTheme.colorScheme.outlineVariant
    var dragging by remember { mutableStateOf(false) }
    val change by rememberUpdatedState(onValueChange)
    val finish by rememberUpdatedState(onValueChangeFinished)

    Canvas(
        modifier
            .fillMaxWidth()
            .height(28.dp)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..1f)
                if (enabled) setProgress { change(it.coerceIn(0f, 1f)); finish(); true }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset ->
                    change((offset.x / size.width).coerceIn(0f, 1f))
                    finish()
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { offset -> dragging = true; change((offset.x / size.width).coerceIn(0f, 1f)) },
                    onDragEnd = { dragging = false; finish() },
                    onDragCancel = { dragging = false; finish() },
                    onHorizontalDrag = { c, _ -> change((c.position.x / size.width).coerceIn(0f, 1f)) },
                )
            },
    ) {
        val h = 3.dp.toPx()
        val y = size.height / 2
        val x = size.width * value.coerceIn(0f, 1f)
        drawRoundRect(track, Offset(0f, y - h / 2), Size(size.width, h), CornerRadius(h / 2))
        drawRoundRect(active, Offset(0f, y - h / 2), Size(x, h), CornerRadius(h / 2))
        if (enabled) drawCircle(active, radius = (if (dragging) 8 else 5).dp.toPx(), center = Offset(x, y))
    }
}
