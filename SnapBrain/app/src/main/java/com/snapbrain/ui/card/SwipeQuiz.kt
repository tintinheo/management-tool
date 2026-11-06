package com.snapbrain.ui.card

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.snapbrain.ui.theme.Palette
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.launch

/**
 * Tinder-style true/false card: drag right = Đúng, left = Sai. Buttons below do the same for one-handed
 * use. Only horizontal drags are consumed, so a vertical pager around it still scrolls.
 */
@Composable
fun SwipeQuizCard(
    statement: String,
    onAnswer: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onThreshold: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val offset = remember(statement) { Animatable(0f) }
    var answered by remember(statement) { mutableStateOf(false) }
    var pastThreshold by remember(statement) { mutableStateOf(false) }
    var widthPx by remember { mutableFloatStateOf(1f) }
    val threshold = widthPx * 0.28f

    fun answer(value: Boolean) {
        if (answered) return
        answered = true
        scope.launch {
            offset.animateTo(if (value) widthPx * 1.2f else -widthPx * 1.2f, tween(220))
            onAnswer(value)
        }
    }

    val progress = (offset.value / threshold).coerceIn(-1f, 1f)
    val borderColor = when {
        progress > 0f -> Palette.Right.copy(alpha = 0.2f + progress * 0.8f)
        progress < 0f -> Palette.Wrong.copy(alpha = 0.2f - progress * 0.8f)
        else -> Palette.Hairline
    }

    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 170.dp)
                .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
                .graphicsLayer {
                    translationX = offset.value
                    rotationZ = offset.value / widthPx * 14f
                }
                .background(Palette.SurfaceHigh, RoundedCornerShape(24.dp))
                .border(if (progress == 0f) 1.dp else 2.dp, borderColor, RoundedCornerShape(24.dp))
                .pointerInput(statement) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (!answered) {
                                when {
                                    offset.value > threshold -> answer(true)
                                    offset.value < -threshold -> answer(false)
                                    else -> scope.launch { offset.animateTo(0f) }
                                }
                            }
                            pastThreshold = false
                        },
                        onDragCancel = { if (!answered) scope.launch { offset.animateTo(0f) } },
                    ) { change, drag ->
                        if (answered) return@detectHorizontalDragGestures
                        change.consume()
                        val next = offset.value + drag
                        scope.launch { offset.snapTo(next) }
                        val past = abs(next) > threshold
                        if (past && !pastThreshold) onThreshold()
                        pastThreshold = past
                    }
                }
                .padding(horizontal = 24.dp, vertical = 28.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                statement,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                color = Palette.Text,
            )
            if (progress > 0.15f) Stamp("ĐÚNG", Palette.Right, Modifier.align(Alignment.TopStart))
            if (progress < -0.15f) Stamp("SAI", Palette.Wrong, Modifier.align(Alignment.TopEnd))
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AnswerButton("✕  Sai", Palette.Wrong, Modifier.weight(1f)) { answer(false) }
            AnswerButton("✓  Đúng", Palette.Right, Modifier.weight(1f)) { answer(true) }
        }
        Spacer(Modifier.height(8.dp))
        Text("Vuốt phải = Đúng · Vuốt trái = Sai", style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
    }
}

@Composable
private fun AnswerButton(label: String, tint: Color, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.5f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = tint),
    ) { Text(label, style = MaterialTheme.typography.labelLarge) }
}

@Composable
private fun Stamp(text: String, color: Color, modifier: Modifier) {
    Text(
        text,
        color = color,
        fontWeight = FontWeight.Black,
        style = MaterialTheme.typography.titleLarge,
        modifier = modifier
            .border(2.dp, color, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 2.dp),
    )
}

/** One-shot burst of gold/green particles, drawn on a Canvas (no image assets). */
@Composable
fun ConfettiBurst(modifier: Modifier = Modifier, seed: Int = 0) {
    val progress = remember(seed) { Animatable(0f) }
    LaunchedEffect(seed) { progress.animateTo(1f, tween(1300, easing = LinearEasing)) }
    val particles = remember(seed) {
        val r = Random(seed)
        List(46) {
            Particle(
                angle = r.nextFloat() * 2f * Math.PI.toFloat(),
                speed = 0.35f + r.nextFloat() * 0.65f,
                size = 4f + r.nextFloat() * 7f,
                color = listOf(Palette.Accent, Palette.Right, Palette.Text, Color(0xFFFFD98A))[r.nextInt(4)],
            )
        }
    }
    Canvas(modifier.fillMaxSize()) {
        val t = progress.value
        if (t >= 1f) return@Canvas
        val center = Offset(size.width / 2f, size.height * 0.45f)
        val reach = size.minDimension * 0.55f
        particles.forEach { p ->
            val d = reach * p.speed * t
            val x = center.x + cos(p.angle) * d
            val y = center.y + sin(p.angle) * d + 600f * t * t
            drawCircle(p.color.copy(alpha = 1f - t), radius = p.size * (1f - t * 0.4f), center = Offset(x, y))
        }
    }
}

private class Particle(val angle: Float, val speed: Float, val size: Float, val color: Color)
