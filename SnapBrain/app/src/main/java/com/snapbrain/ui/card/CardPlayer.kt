package com.snapbrain.ui.card

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snapbrain.data.content.Card
import com.snapbrain.haptics.HapticsKit
import com.snapbrain.ui.theme.Palette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class Phase { HOOK, BODY, QUIZ, RESULT }

data class QuizOutcome(val correct: Boolean, val points: Int)

/**
 * Plays one card on a ~15 s timeline: hook (≈2.5 s) → body lines one by one → true/false quiz → result.
 * Tapping skips ahead. Timers only run while [active] (e.g. the settled page of a pager).
 */
@Composable
fun CardPlayer(
    card: Card,
    active: Boolean,
    onAnswer: suspend (Boolean) -> Int,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    nextLabel: String = "Thẻ tiếp theo",
    haptics: HapticsKit? = null,
    onSpeak: ((String) -> Unit)? = null,
    startPhase: Phase = Phase.HOOK,
) {
    var phase by remember(card.id) { mutableStateOf(startPhase) }
    var revealed by remember(card.id) { mutableIntStateOf(0) }
    var outcome by remember(card.id) { mutableStateOf<QuizOutcome?>(null) }
    val scope = rememberCoroutineScope()

    fun advance() {
        when (phase) {
            Phase.HOOK -> { phase = Phase.BODY; revealed = 1 }
            Phase.BODY -> if (revealed < card.body.size) revealed++ else phase = Phase.QUIZ
            else -> Unit
        }
    }

    LaunchedEffect(card.id, active, phase, revealed) {
        if (!active) return@LaunchedEffect
        when (phase) {
            Phase.HOOK -> { delay(2_600); advance() }
            Phase.BODY -> { delay(if (revealed < card.body.size) 2_400 else 3_000); advance() }
            else -> Unit
        }
    }
    LaunchedEffect(card.id, active, phase == Phase.BODY) {
        if (active && phase == Phase.BODY) onSpeak?.invoke((listOf(card.hook) + card.body).joinToString(". "))
    }

    CardContent(
        card = card,
        phase = phase,
        revealed = revealed,
        outcome = outcome,
        onTap = ::advance,
        onAnswer = { value ->
            scope.launch {
                val correct = value == card.tf.answer
                if (correct) haptics?.success() else haptics?.failure()
                val points = onAnswer(correct)
                outcome = QuizOutcome(correct, points)
                phase = Phase.RESULT
            }
        },
        onThreshold = { haptics?.tick() },
        onNext = onNext,
        nextLabel = nextLabel,
        modifier = modifier,
    )
}

/** Stateless rendering of a card at a given phase (used directly by screenshot tests). */
@Composable
fun CardContent(
    card: Card,
    phase: Phase,
    revealed: Int,
    outcome: QuizOutcome?,
    onTap: () -> Unit,
    onAnswer: (Boolean) -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    nextLabel: String = "Thẻ tiếp theo",
    onThreshold: () -> Unit = {},
) {
    Box(modifier.fillMaxSize().background(Palette.Ink)) {
        Column(
            Modifier
                .fillMaxSize()
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onTap)
                .padding(horizontal = 24.dp, vertical = 20.dp),
        ) {
            Header(card, phase)
            Spacer(Modifier.height(28.dp))
            AnimatedContent(
                targetState = phase == Phase.QUIZ || phase == Phase.RESULT,
                transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(150)) },
                label = "stage",
                modifier = Modifier.weight(1f),
            ) { quizStage ->
                if (!quizStage) {
                    LessonStage(card, phase, revealed)
                } else if (phase == Phase.RESULT && outcome != null) {
                    ResultStage(card, outcome, onNext, nextLabel)
                } else {
                    QuizStage(card, onAnswer, onThreshold)
                }
            }
            if (phase == Phase.HOOK || phase == Phase.BODY) {
                Text(
                    "Chạm để tiếp",
                    style = MaterialTheme.typography.labelMedium,
                    color = Palette.Muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (outcome?.correct == true) ConfettiBurst(seed = card.id.hashCode())
    }
}

@Composable
private fun Header(card: Card, phase: Phase) {
    Column {
        Text(
            "${card.topic.emoji}  ${card.topic.label.uppercase()}",
            style = MaterialTheme.typography.labelMedium,
            color = Palette.Accent,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val stage = when (phase) { Phase.HOOK -> 0; Phase.BODY -> 1; Phase.QUIZ -> 2; Phase.RESULT -> 3 }
            repeat(3) { i ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(3.dp)
                        .background(if (i < stage || (i == stage && stage < 3)) Palette.Accent.copy(alpha = if (i < stage) 1f else 0.45f) else Palette.Hairline, RoundedCornerShape(2.dp)),
                )
            }
        }
    }
}

@Composable
private fun LessonStage(card: Card, phase: Phase, revealed: Int) {
    Column(Modifier.fillMaxSize()) {
        Text(
            card.hook,
            style = if (phase == Phase.HOOK) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineMedium,
            color = Palette.Text,
        )
        Spacer(Modifier.height(32.dp))
        AnimatedVisibility(phase != Phase.HOOK, enter = fadeIn(tween(300)) + slideInVertically { it / 4 }) {
            CardVisual(card.visual)
        }
        Spacer(Modifier.height(28.dp))
        card.body.forEachIndexed { i, line ->
            AnimatedVisibility(phase == Phase.BODY && i < revealed, enter = fadeIn(tween(300)) + slideInVertically { it / 2 }) {
                Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
                    Box(
                        Modifier.size(26.dp).border(1.dp, Palette.Accent.copy(alpha = 0.6f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Text("${i + 1}", color = Palette.Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                    Spacer(Modifier.width(14.dp))
                    Text(line, style = MaterialTheme.typography.bodyLarge, color = Palette.Text)
                }
            }
        }
    }
}

@Composable
private fun QuizStage(card: Card, onAnswer: (Boolean) -> Unit, onThreshold: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Text(card.hook, style = MaterialTheme.typography.titleMedium, color = Palette.Muted)
        Spacer(Modifier.height(20.dp))
        Text("Nhận định này đúng hay sai?", style = MaterialTheme.typography.titleLarge, color = Palette.Text)
        // Card sits in the lower half, where the thumb swipes.
        Spacer(Modifier.weight(1f))
        SwipeQuizCard(card.tf.statement, onAnswer = onAnswer, onThreshold = onThreshold)
        Spacer(Modifier.weight(0.4f))
    }
}

@Composable
private fun ResultStage(card: Card, outcome: QuizOutcome, onNext: () -> Unit, nextLabel: String) {
    val tint: Color = if (outcome.correct) Palette.Right else Palette.Wrong
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(24.dp))
        Box(
            Modifier.size(84.dp).background(tint.copy(alpha = 0.12f), CircleShape).border(2.dp, tint, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text(if (outcome.correct) "✓" else "✕", color = tint, fontSize = 40.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(20.dp))
        Text(
            if (outcome.correct) "Chính xác!" else "Chưa đúng rồi",
            style = MaterialTheme.typography.headlineMedium,
            color = Palette.Text,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            when {
                outcome.points > 0 -> "+${outcome.points} điểm tri thức"
                outcome.correct -> "Thẻ này hôm nay đã tính điểm rồi"
                else -> "Thẻ đã vào kho ôn tập · #Chưa_Thuộc"
            },
            style = MaterialTheme.typography.titleMedium,
            color = if (outcome.points > 0) Palette.Accent else Palette.Muted,
        )
        Spacer(Modifier.height(28.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .background(Palette.Surface, RoundedCornerShape(20.dp))
                .border(1.dp, Palette.Hairline, RoundedCornerShape(20.dp))
                .padding(20.dp),
        ) {
            Text(card.tf.statement, style = MaterialTheme.typography.bodyLarge, color = Palette.Text)
            Spacer(Modifier.height(10.dp))
            Text(
                "Đáp án: ${if (card.tf.answer) "ĐÚNG" else "SAI"}",
                style = MaterialTheme.typography.labelLarge,
                color = if (card.tf.answer) Palette.Right else Palette.Wrong,
            )
            Spacer(Modifier.height(6.dp))
            Text(card.tf.explain, style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
        }
        Spacer(Modifier.weight(1f))
        Button(
            onClick = onNext,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(18.dp),
        ) { Text(nextLabel, style = MaterialTheme.typography.labelLarge) }
    }
}
