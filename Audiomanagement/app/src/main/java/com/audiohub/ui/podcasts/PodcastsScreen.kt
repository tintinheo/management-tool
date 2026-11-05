package com.audiohub.ui.podcasts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.audiohub.appContainer
import com.audiohub.data.db.PodcastSummary
import com.audiohub.data.podcast.ApplePodcastLinks
import com.audiohub.data.podcast.PodcastInput
import com.audiohub.data.podcast.PodcastRepository
import com.audiohub.ui.Routes
import com.audiohub.ui.components.EmptyState
import com.audiohub.ui.components.ScreenHeader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PodcastsViewModel(private val repo: PodcastRepository) : ViewModel() {

    val shows: StateFlow<List<PodcastSummary>?> =
        repo.summaries.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val inProgress: StateFlow<List<EpisodeUi>> = repo.inProgress(10).map { l -> l.map { it.toUi() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val latest: StateFlow<List<EpisodeUi>> = repo.latest(days = 14, limit = 30).map { l -> l.map { it.toUi() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    init {
        // Opening the tab refreshes shows that haven't been checked in the last hour.
        refresh(maxAgeMs = 60 * 60 * 1000L)
    }

    fun refresh(maxAgeMs: Long = 0) {
        if (_refreshing.value) return
        viewModelScope.launch {
            _refreshing.value = true
            try {
                repo.refreshAll(maxAgeMs)
            } finally {
                _refreshing.value = false
            }
        }
    }
}

@Composable
fun PodcastsScreen() {
    val container = LocalContext.current.appContainer
    val vm: PodcastsViewModel = viewModel(factory = viewModelFactory { initializer { PodcastsViewModel(container.podcastRepository) } })
    val shows by vm.shows.collectAsStateWithLifecycle()
    val inProgress by vm.inProgress.collectAsStateWithLifecycle()
    val latest by vm.latest.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val player by container.playerConnection.state.collectAsStateWithLifecycle()
    PodcastsContent(
        shows = shows,
        inProgress = inProgress,
        latest = latest,
        refreshing = refreshing,
        currentMediaId = player.mediaId,
        isPlaying = player.isPlaying,
        actions = LocalPodcastActions.current,
        onRefresh = { vm.refresh() },
    )
}

@Composable
fun PodcastsContent(
    shows: List<PodcastSummary>?,
    inProgress: List<EpisodeUi>,
    latest: List<EpisodeUi>,
    refreshing: Boolean,
    currentMediaId: String?,
    isPlaying: Boolean,
    actions: PodcastActions,
    onRefresh: () -> Unit,
) {
    var adding by remember { mutableStateOf(false) }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(bottom = 16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ScreenHeader("Podcasts", Modifier.weight(1f))
                IconButton(onClick = { adding = true }) { Icon(Icons.Rounded.Add, contentDescription = "Add by link") }
                IconButton(onClick = onRefresh, enabled = !refreshing, modifier = Modifier.padding(end = 8.dp)) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Refresh all")
                }
            }
        }
        if (refreshing) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp))
            }
        }
        if (shows != null && shows.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyState(
                    icon = Icons.Rounded.Podcasts,
                    title = "No shows yet",
                    message = "Find shows in Search → Podcasts (Apple Podcasts catalog), or add one by its RSS or Apple Podcasts link.",
                    action = { Button(onClick = { adding = true }) { Text("Add by link") } },
                )
            }
        }
        if (inProgress.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("Continue Listening") }
            inProgress.forEach { e ->
                item(span = { GridItemSpan(maxLineSpan) }, key = "p:" + e.key) {
                    EpisodeRow(e, actions, e.mediaId == currentMediaId, isPlaying, showShowTitle = true)
                }
            }
        }
        if (latest.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("New Episodes") }
            latest.take(10).forEach { e ->
                item(span = { GridItemSpan(maxLineSpan) }, key = "n:" + e.key) {
                    EpisodeRow(e, actions, e.mediaId == currentMediaId, isPlaying, showShowTitle = true)
                }
            }
        }
        if (!shows.isNullOrEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("Shows") }
            items(shows, key = { "s:" + it.id }) { show ->
                ShowTile(
                    show.title,
                    show.artworkUrl,
                    badge = show.unplayedCount,
                    onClick = { actions.navigate(Routes.show(podcastId = show.id)) },
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            }
        }
    }
    if (adding) {
        AddPodcastDialog(
            onOpen = { route -> adding = false; actions.navigate(route) },
            onDismiss = { adding = false },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 4.dp))
}

/** Paste an RSS feed URL or a podcasts.apple.com link. */
@Composable
fun AddPodcastDialog(onOpen: (route: String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a podcast") },
        text = {
            Column {
                Text(
                    "Paste an RSS feed URL or an Apple Podcasts link (Share → Copy Link in Apple Podcasts).",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; error = null },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    placeholder = { Text("https://…") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = {
                    when (val input = ApplePodcastLinks.classify(text)) {
                        is PodcastInput.Apple -> onOpen(Routes.show(itunesId = input.itunesId))
                        is PodcastInput.Feed -> onOpen(Routes.show(feedUrl = input.url))
                        else -> error = "That doesn't look like a link. Use Search to find shows by name."
                    }
                },
            ) { Text("Open") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
