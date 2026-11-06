package com.snapbrain.ui.feed

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.snapbrain.AppContainer
import com.snapbrain.data.Source
import com.snapbrain.data.content.Card
import com.snapbrain.ui.card.CardPlayer
import com.snapbrain.ui.theme.Palette
import kotlinx.coroutines.launch

/** The Anti-Feed: TikTok-style vertical swipe through cards. Refills itself near the end. */
@Composable
fun FeedScreen(container: AppContainer, onSpeak: ((String) -> Unit)?, modifier: Modifier = Modifier) {
    val cards = remember { mutableStateListOf<Card>() }
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState { cards.size }

    LaunchedEffect(Unit) { if (cards.isEmpty()) cards += container.study.feed() }
    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage }.collect { page ->
            if (cards.isNotEmpty() && page >= cards.size - 3) {
                val recent = cards.takeLast(10).map { it.id }.toSet()
                cards += container.study.feed().filter { it.id !in recent }.ifEmpty { container.study.feed() }
            }
        }
    }

    if (cards.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Palette.Accent) }
        return
    }
    VerticalPager(state = pager, modifier = modifier.fillMaxSize(), key = { "$it-${cards[it].id}" }) { page ->
        val card = cards[page]
        CardPlayer(
            card = card,
            active = pager.settledPage == page,
            onAnswer = { correct -> container.study.answer(card, correct, Source.FEED).points },
            onNext = { scope.launch { pager.animateScrollToPage(page + 1) } },
            haptics = container.haptics,
            onSpeak = onSpeak,
        )
    }
}
