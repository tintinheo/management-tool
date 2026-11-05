package com.audiohub.ui.podcasts

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material.icons.rounded.Download
import com.audiohub.data.podcast.EpisodeDownloader
import com.audiohub.data.podcast.isLocalFeed
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.audiohub.appContainer
import com.audiohub.data.podcast.ParsedFeed
import com.audiohub.data.EpisodeSorter
import com.audiohub.data.SortOrder
import com.audiohub.data.settings.SettingsRepository
import com.audiohub.ui.components.Hairline
import com.audiohub.ui.components.SectionLabel
import com.audiohub.ui.components.SortButton
import com.audiohub.ui.components.plural
import androidx.compose.runtime.rememberCoroutineScope
import com.audiohub.data.podcast.PodcastRepository
import com.audiohub.ui.components.Artwork
import com.audiohub.ui.components.EmptyState
import com.audiohub.ui.library.DetailTopBar
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ShowHeader(
    val title: String,
    val author: String,
    val description: String,
    val artworkUrl: String?,
)

sealed interface ShowUiState {
    data object Loading : ShowUiState
    data class Error(val message: String) : ShowUiState
    data class Ready(
        val header: ShowHeader,
        val episodes: List<EpisodeUi>,
        val podcastId: Long?,
        val busy: Boolean = false,
        val autoDownload: Boolean = false,
        /** A folder of audio files on the phone rather than an RSS feed. */
        val isLocal: Boolean = false,
    ) : ShowUiState {
        val following get() = podcastId != null
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PodcastShowViewModel(
    private val repo: PodcastRepository,
    private val downloader: EpisodeDownloader,
    podcastId: Long?,
    private var feedUrl: String?,
    private val itunesId: Long?,
) : ViewModel() {

    private val subscribedId = MutableStateFlow(podcastId)
    private val preview = MutableStateFlow<ParsedFeed?>(null)
    private val error = MutableStateFlow<String?>(null)
    private val busy = MutableStateFlow(false)

    val state: StateFlow<ShowUiState> = subscribedId.flatMapLatest { id ->
        if (id != null) {
            combine(repo.podcast(id), repo.episodes(id), busy) { p, eps, b ->
                if (p == null) ShowUiState.Loading
                else ShowUiState.Ready(
                    ShowHeader(p.title, p.author, p.description, p.artworkUrl),
                    eps.map { it.toUi(p.title, p.artworkUrl, p.author) },
                    podcastId = p.id,
                    busy = b,
                    autoDownload = p.autoDownload,
                    isLocal = isLocalFeed(p.feedUrl),
                )
            }
        } else {
            combine(preview, error, busy) { feed, err, b ->
                when {
                    err != null -> ShowUiState.Error(err)
                    feed == null -> ShowUiState.Loading
                    else -> ShowUiState.Ready(
                        ShowHeader(feed.title, feed.author, feed.description, feed.artworkUrl),
                        feed.episodes.map { it.toPreviewUi(feed.title, feed.artworkUrl, feed.author) },
                        podcastId = null,
                        busy = b,
                    )
                }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ShowUiState.Loading)

    init {
        if (podcastId == null) resolve()
    }

    private fun resolve() = viewModelScope.launch {
        error.value = null
        try {
            var url = feedUrl
            if (url == null && itunesId != null) {
                repo.findByItunesId(itunesId)?.let { subscribedId.value = it.id; return@launch }
                url = repo.lookup(itunesId)?.feedUrl
                    ?: throw IllegalStateException("This show doesn't publish a public RSS feed, so it can't be played outside Apple Podcasts.")
            }
            if (url == null) throw IllegalStateException("No feed address.")
            feedUrl = url
            repo.findByFeedUrl(url)?.let { subscribedId.value = it.id; return@launch }
            preview.value = repo.fetchFeed(url)
        } catch (e: Exception) {
            error.value = e.message ?: "Couldn't load this show."
        }
    }

    fun retry() = resolve()

    fun follow() {
        val url = feedUrl ?: return
        viewModelScope.launch {
            busy.value = true
            try {
                subscribedId.value = repo.subscribe(url, itunesId, preview.value)
            } catch (e: Exception) {
                error.value = e.message
            } finally {
                busy.value = false
            }
        }
    }

    fun unfollow(onDone: () -> Unit) {
        val id = subscribedId.value ?: return
        viewModelScope.launch {
            downloader.deleteAllFor(id) // free the space used by this show's downloads
            repo.unsubscribe(id)
            onDone()
        }
    }

    fun setAutoDownload(enabled: Boolean) {
        val id = subscribedId.value ?: return
        viewModelScope.launch { repo.setAutoDownload(id, enabled) }
    }

    fun refresh() {
        val id = subscribedId.value
        if (id == null) {
            resolve()
            return
        }
        viewModelScope.launch {
            busy.value = true
            try {
                repo.refresh(id)
            } catch (_: Exception) {
                // Offline etc.: keep showing what we have.
            } finally {
                busy.value = false
            }
        }
    }
}

@Composable
fun PodcastShowScreen(podcastId: Long?, feedUrl: String?, itunesId: Long?) {
    val container = LocalContext.current.appContainer
    val vm: PodcastShowViewModel = viewModel(
        key = "show:$podcastId:$feedUrl:$itunesId",
        factory = viewModelFactory {
            initializer { PodcastShowViewModel(container.podcastRepository, container.episodeDownloader, podcastId, feedUrl, itunesId) }
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val player by container.playerConnection.state.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    val actions = LocalPodcastActions.current
    PodcastShowContent(
        state = state,
        currentMediaId = player.mediaId,
        isPlaying = player.isPlaying,
        actions = actions,
        onFollow = vm::follow,
        onUnfollow = { vm.unfollow(onDone = actions::back) },
        onRefresh = vm::refresh,
        onRetry = vm::retry,
        onAutoDownloadChange = vm::setAutoDownload,
        sort = settings?.episodeSort ?: SettingsRepository.DEFAULT_EPISODE_SORT,
        onSortChange = { scope.launch { container.settings.setEpisodeSort(it) } },
    )
}

@Composable
fun PodcastShowContent(
    state: ShowUiState,
    currentMediaId: String?,
    isPlaying: Boolean,
    actions: PodcastActions,
    onFollow: () -> Unit,
    onUnfollow: () -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onAutoDownloadChange: (Boolean) -> Unit = {},
    sort: SortOrder = SettingsRepository.DEFAULT_EPISODE_SORT,
    onSortChange: (SortOrder) -> Unit = {},
) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirmUnfollow by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        DetailTopBar("", onBack = actions::back) {
            if (state is ShowUiState.Ready && state.following && !state.isLocal) {
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "Show options") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Refresh") },
                            leadingIcon = { Icon(Icons.Rounded.Refresh, null) },
                            onClick = { menuOpen = false; onRefresh() },
                        )
                        DropdownMenuItem(
                            text = { Text("Auto-download new episodes") },
                            leadingIcon = { Icon(Icons.Rounded.Download, null) },
                            trailingIcon = {
                                Checkbox(checked = state.autoDownload, onCheckedChange = null)
                            },
                            onClick = { menuOpen = false; onAutoDownloadChange(!state.autoDownload) },
                        )
                        DropdownMenuItem(
                            text = { Text("Unfollow") },
                            leadingIcon = { Icon(Icons.Rounded.Check, null) },
                            onClick = { menuOpen = false; confirmUnfollow = true },
                        )
                    }
                }
            }
        }
        when (state) {
            ShowUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            is ShowUiState.Error -> EmptyState(
                Icons.Rounded.CloudOff,
                "Couldn't load this show",
                state.message,
                action = { Button(onClick = onRetry) { Text("Try again") } },
            )
            is ShowUiState.Ready -> {
                val sortedEpisodes = remember(state.episodes, sort) { EpisodeSorter.sort(state.episodes, sort) }
                LazyColumn(Modifier.fillMaxSize()) {
                item { ShowHeaderBlock(state, onFollow) }
                if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) }
                item {
                    Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        SectionLabel(plural(state.episodes.size, "episode"), Modifier.weight(1f))
                        SortButton(sort, onSortChange, Modifier.padding(top = 20.dp), labels = mapOf(com.audiohub.data.SortField.ALBUM to "Show", com.audiohub.data.SortField.ARTIST to "Author", com.audiohub.data.SortField.DATE_ADDED to "Release date"))
                    }
                }
                if (state.episodes.isEmpty()) {
                    item { EmptyState(Icons.Rounded.Podcasts, "No episodes", "This feed has no playable episodes.") }
                }
                items(sortedEpisodes, key = { it.key }) { e ->
                    EpisodeRow(e, actions, e.mediaId == currentMediaId, isPlaying, showArtwork = false)
                    Hairline()
                }
                }
            }
        }
    }
    if (confirmUnfollow && state is ShowUiState.Ready) {
        AlertDialog(
            onDismissRequest = { confirmUnfollow = false },
            title = { Text("Unfollow \"${state.header.title}\"?") },
            text = { Text("Listening progress for this show will be removed.") },
            confirmButton = { TextButton(onClick = { confirmUnfollow = false; onUnfollow() }) { Text("Unfollow") } },
            dismissButton = { TextButton(onClick = { confirmUnfollow = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ShowHeaderBlock(state: ShowUiState.Ready, onFollow: () -> Unit) {
    val actions = LocalPodcastActions.current
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Artwork(artworkUri(state.header.artworkUrl), Modifier.size(220.dp), cornerRadius = 12.dp, placeholder = Icons.Rounded.Podcasts)
        Spacer(Modifier.height(16.dp))
        Text(state.header.title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        if (state.header.author.isNotBlank()) {
            Text(state.header.author, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val latest = state.episodes.firstOrNull()
            FilledTonalButton(onClick = { latest?.let(actions::play) }, enabled = latest != null) {
                Icon(Icons.Rounded.PlayArrow, null)
                Spacer(Modifier.width(6.dp))
                Text("Latest")
            }
            if (state.following) {
                OutlinedButton(onClick = {}, enabled = false) {
                    Icon(Icons.Rounded.Check, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Following")
                }
            } else {
                Button(onClick = onFollow, enabled = !state.busy) {
                    Icon(Icons.Rounded.Add, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Follow")
                }
            }
        }
        val about = remember(state.header.description) { notesPreview(state.header.description) }
        if (about.isNotEmpty()) {
            Text(
                about,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = 12.dp)
                    .animateContentSize()
                    .clickable { expanded = !expanded },
            )
        }
        if (!state.following) {
            Text(
                "Follow to save your listening progress and get new episodes.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
