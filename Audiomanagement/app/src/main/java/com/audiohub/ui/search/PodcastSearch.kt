package com.audiohub.ui.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.audiohub.data.podcast.PodcastRepository
import com.audiohub.data.podcast.PodcastSearchResult
import com.audiohub.ui.Routes
import com.audiohub.ui.components.EmptyState
import com.audiohub.ui.components.plural
import com.audiohub.ui.podcasts.PodcastActions
import com.audiohub.ui.podcasts.SearchResultRow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest

data class PodcastSearchState(
    val loading: Boolean = false,
    val results: List<PodcastSearchResult> = emptyList(),
    /** True when showing Top Charts (empty query). */
    val isCharts: Boolean = true,
    val error: String? = null,
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class PodcastSearchViewModel(private val repo: PodcastRepository) : ViewModel() {

    val query = MutableStateFlow("")
    private var charts: List<PodcastSearchResult>? = null

    val state: StateFlow<PodcastSearchState> = query
        .map { it.trim() }
        .distinctUntilChanged()
        .debounce { if (it.isEmpty()) 0 else 450 }
        .transformLatest { q ->
            emit(PodcastSearchState(loading = true, isCharts = q.isEmpty()))
            emit(
                try {
                    val results = if (q.isEmpty()) charts ?: repo.topCharts().also { charts = it } else repo.search(q)
                    PodcastSearchState(results = results, isCharts = q.isEmpty())
                } catch (e: Exception) {
                    PodcastSearchState(isCharts = q.isEmpty(), error = "Can't reach Apple Podcasts. Check your connection.")
                },
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PodcastSearchState(loading = true))
}

@Composable
fun PodcastSearchResults(state: PodcastSearchState, actions: PodcastActions) {
    when {
        state.loading -> Box(Modifier.fillMaxSize().padding(top = 48.dp), contentAlignment = Alignment.TopCenter) {
            CircularProgressIndicator()
        }
        state.error != null -> EmptyState(Icons.Rounded.CloudOff, "Offline", state.error)
        state.results.isEmpty() -> EmptyState(Icons.Rounded.SearchOff, "No shows found", "Try another name, or paste the show's RSS link in Podcasts → +.")
        else -> LazyColumn(Modifier.fillMaxSize()) {
            if (state.isCharts) {
                item {
                    Text(
                        "Top Shows",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
                    )
                }
            }
            itemsIndexed(state.results, key = { _, r -> r.itunesId }) { index, r ->
                SearchResultRow(
                    title = r.title,
                    author = r.author,
                    detail = listOfNotNull(r.genre, r.episodeCount.takeIf { it > 0 }?.let { plural(it, "episode") }).joinToString(" · "),
                    artworkUrl = r.artworkUrl,
                    rank = if (state.isCharts) index + 1 else null,
                    onClick = { actions.navigate(Routes.show(feedUrl = r.feedUrl, itunesId = r.itunesId)) },
                )
            }
        }
    }
}
