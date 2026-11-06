package com.snapbrain.ui.saver

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.snapbrain.AppContainer
import com.snapbrain.data.Source
import com.snapbrain.data.content.Card
import com.snapbrain.domain.Rules
import com.snapbrain.ui.card.CardPlayer
import com.snapbrain.ui.card.ConfettiBurst
import com.snapbrain.ui.card.Phase
import com.snapbrain.ui.theme.Palette

/** Streak saver: review questions until [Rules.SAVER_STREAK] are right in a row, then yesterday is restored. */
@Composable
fun SaverScreen(container: AppContainer, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val queue = remember { mutableStateListOf<Card>() }
    var index by remember { mutableIntStateOf(0) }
    var inARow by remember { mutableIntStateOf(0) }
    var saved by remember { mutableStateOf<Boolean?>(null) }
    var brokenLength by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        brokenLength = container.study.streakNow().brokenLength
        queue += container.study.review(limit = 30)
    }

    Column(modifier.fillMaxSize().background(Palette.Ink)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("CỨU CHUỖI 🔥", style = MaterialTheme.typography.labelMedium, color = Palette.Accent, modifier = Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(Rules.SAVER_STREAK) { i ->
                    Box(Modifier.size(12.dp).background(if (i < inARow) Palette.Right else Palette.Hairline, CircleShape))
                }
            }
        }
        val done = saved
        when {
            done != null -> SaverResult(done, brokenLength, onDone)
            queue.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Palette.Accent)
            }
            else -> {
                val card = queue[index % queue.size]
                CardPlayer(
                    card = card,
                    active = true,
                    startPhase = Phase.QUIZ,
                    onAnswer = { correct ->
                        val points = container.study.answer(card, correct, Source.SAVER).points
                        inARow = if (correct) inARow + 1 else 0
                        points
                    },
                    onNext = {
                        if (inARow >= Rules.SAVER_STREAK) {
                            saved = true
                        } else {
                            index++
                        }
                    },
                    nextLabel = if (inARow >= Rules.SAVER_STREAK) "Hoàn tất" else "Câu tiếp (${inARow}/${Rules.SAVER_STREAK})",
                    haptics = container.haptics,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    LaunchedEffect(saved) {
        if (saved == true) {
            if (!container.study.repairYesterday()) saved = false
        }
    }
}

@Composable
private fun SaverResult(saved: Boolean, brokenLength: Int, onDone: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        if (saved) ConfettiBurst(seed = 7)
        Column(
            Modifier.fillMaxSize().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(if (saved) "🔥" else "🙂", style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(16.dp))
            Text(
                if (saved) "Chuỗi ${brokenLength + 2} ngày đã được cứu!" else "Không còn chuỗi nào cần cứu",
                style = MaterialTheme.typography.headlineMedium,
                color = Palette.Text,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (saved) "Hôm qua đã được tính. Hôm nay bạn cũng đã học rồi." else "Hôm nay vẫn được tính là một ngày học.",
                style = MaterialTheme.typography.bodyLarge,
                color = Palette.Muted,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))
            Button(onClick = onDone, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().height(54.dp)) {
                Text("Về trang chính")
            }
        }
    }
}

