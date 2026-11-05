package com.audiohub.ui.podcasts

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.audiohub.data.podcast.LocalPodcastScanner
import com.audiohub.data.podcast.Opml
import com.audiohub.data.podcast.PodcastInput
import com.audiohub.data.podcast.PodcastRepository
import com.audiohub.system.AppPermissions
import com.audiohub.ui.Routes
import com.audiohub.ui.components.EmptyState
import com.audiohub.ui.components.Hairline
import com.audiohub.ui.components.ScreenHeader
import com.audiohub.ui.components.SectionLabel
import com.audiohub.ui.components.plural
import com.audiohub.ui.library.DetailTopBar
import com.audiohub.ui.library.NavRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PodcastsViewModel(
    private val repo: PodcastRepository,
    private val localScanner: LocalPodcastScanner,
    private val canReadAudio: () -> Boolean,
) : ViewModel() {

    val shows: StateFlow<List<PodcastSummary>?> =
        repo.summaries.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val inProgress: StateFlow<List<EpisodeUi>> = repo.inProgress(10).map { l -> l.map { it.toUi() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val latest: StateFlow<List<EpisodeUi>> = repo.latest(days = 14, limit = 30).map { l -> l.map { it.toUi() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val downloadedCount: StateFlow<Int> = repo.downloaded.map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

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
                if (canReadAudio()) runCatching { localScanner.scan() }
                repo.refreshAll(maxAgeMs)
            } finally {
                _refreshing.value = false
            }
        }
    }

    fun importOpml(context: Context, uri: Uri) = viewModelScope.launch {
        _refreshing.value = true
        val message = try {
            val entries = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { Opml.parse(it) } ?: emptyList()
            }
            val r = repo.importOpml(entries)
            buildString {
                append("Followed ${plural(r.added, "show")}")
                if (r.alreadyFollowed > 0) append(", ${r.alreadyFollowed} already followed")
                if (r.failed.isNotEmpty()) append(", ${r.failed.size} couldn't be loaded")
            }
        } catch (e: Exception) {
            "Import failed: ${e.message}"
        } finally {
            _refreshing.value = false
        }
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    fun exportOpml(context: Context, uri: Uri) = viewModelScope.launch {
        val message = try {
            val text = Opml.format(repo.opmlEntries())
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) }
            }
            "Subscriptions exported"
        } catch (e: Exception) {
            "Export failed: ${e.message}"
        }
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
}

@Composable
fun PodcastsScreen() {
    val context = LocalContext.current
    val container = context.appContainer
    val vm: PodcastsViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                PodcastsViewModel(container.podcastRepository, container.localPodcastScanner) {
                    AppPermissions.isGranted(context, AppPermissions.AUDIO)
                }
            }
        },
    )
    val shows by vm.shows.collectAsStateWithLifecycle()
    val inProgress by vm.inProgress.collectAsStateWithLifecycle()
    val latest by vm.latest.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val downloaded by vm.downloadedCount.collectAsStateWithLifecycle()
    val player by container.playerConnection.state.collectAsStateWithLifecycle()

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.importOpml(context, it) }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/x-opml")) { uri ->
        uri?.let { vm.exportOpml(context, it) }
    }

    PodcastsContent(
        shows = shows,
        inProgress = inProgress,
        latest = latest,
        downloadedCount = downloaded,
        refreshing = refreshing,
        currentMediaId = player.mediaId,
        isPlaying = player.isPlaying,
        actions = LocalPodcastActions.current,
        onRefresh = { vm.refresh() },
        onImportOpml = { importLauncher.launch(arrayOf("text/x-opml", "text/xml", "application/xml", "*/*")) },
        onExportOpml = { exportLauncher.launch("My Audio Hub subscriptions.opml") },
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
    downloadedCount: Int = 0,
    onImportOpml: () -> Unit = {},
    onExportOpml: () -> Unit = {},
) {
    var adding by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    LazyColumn(
        contentPadding = PaddingValues(bottom = 24.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ScreenHeader("Podcasts", Modifier.weight(1f))
                IconButton(onClick = onRefresh, enabled = !refreshing) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Refresh all")
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Add by link…") },
                            leadingIcon = { Icon(Icons.Rounded.Add, null) },
                            onClick = { menuOpen = false; adding = true },
                        )
                        DropdownMenuItem(
                            text = { Text("Import OPML…") },
                            leadingIcon = { Icon(Icons.Rounded.FileDownload, null) },
                            onClick = { menuOpen = false; onImportOpml() },
                        )
                        DropdownMenuItem(
                            text = { Text("Export OPML…") },
                            leadingIcon = { Icon(Icons.Rounded.FileUpload, null) },
                            onClick = { menuOpen = false; onExportOpml() },
                        )
                    }
                }
            }
        }
        if (refreshing) {
            item {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 4.dp))
            }
        }
        if (shows != null && shows.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Rounded.Podcasts,
                    title = "No shows yet",
                    message = "Find shows in Search → Apple Podcasts, add one by link, import an OPML file, " +
                        "or copy audio into /Podcasts/AudioHub/<folder>/.",
                    action = { OutlinedButton(onClick = { adding = true }) { Text("Add by link") } },
                )
            }
        }
        if (downloadedCount > 0) {
            item {
                NavRow(Icons.Rounded.Download, "Downloaded", onClick = { actions.navigate(Routes.DOWNLOADS) }, detail = downloadedCount.toString())
                Hairline(start = 56.dp)
            }
        }
        if (inProgress.isNotEmpty()) {
            item { SectionLabel("Continue listening") }
            items(inProgress, key = { "p:" + it.key }) { e ->
                EpisodeRow(e, actions, e.mediaId == currentMediaId, isPlaying, showShowTitle = true)
            }
        }
        if (latest.isNotEmpty()) {
            item { SectionLabel("New episodes") }
            items(latest.take(10), key = { "n:" + it.key }) { e ->
                EpisodeRow(e, actions, e.mediaId == currentMediaId, isPlaying, showShowTitle = true)
            }
        }
        if (!shows.isNullOrEmpty()) {
            item { SectionLabel("Shows") }
            // A 3-column grid inside the list, laid out row by row so margins match the text above.
            items(shows.chunked(3), key = { row -> "s:" + row.first().id }) { row ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
                    row.forEach { show ->
                        ShowTile(
                            show.title,
                            show.artworkUrl,
                            badge = show.unplayedCount,
                            onClick = { actions.navigate(Routes.show(podcastId = show.id)) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(3 - row.size) { Box(Modifier.weight(1f)) }
                }
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

/** All downloaded episodes, newest first. */
@Composable
fun DownloadsScreen() {
    val container = LocalContext.current.appContainer
    val episodes by remember { container.podcastRepository.downloaded.map { l -> l.map { it.toUi() } } }
        .collectAsStateWithLifecycle(initialValue = null)
    val player by container.playerConnection.state.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val sort = settings?.episodeSort ?: com.audiohub.data.settings.SettingsRepository.DEFAULT_EPISODE_SORT
    DownloadsContent(
        episodes?.let { com.audiohub.data.EpisodeSorter.sort(it, sort) }, player.mediaId, player.isPlaying, LocalPodcastActions.current,
        sort = sort,
        onSortChange = { scope.launch { container.settings.setEpisodeSort(it) } },
    )
}

@Composable
fun DownloadsContent(
    episodes: List<EpisodeUi>?,
    currentMediaId: String?,
    isPlaying: Boolean,
    actions: PodcastActions,
    sort: com.audiohub.data.SortOrder? = null,
    onSortChange: (com.audiohub.data.SortOrder) -> Unit = {},
) {
    Column(Modifier.fillMaxSize()) {
        DetailTopBar("", onBack = actions::back)
        ScreenHeader("Downloaded", subtitle = episodes?.let { plural(it.size, "episode") })
        if (sort != null && !episodes.isNullOrEmpty()) {
            com.audiohub.ui.components.SortButton(sort, onSortChange, Modifier.padding(start = 12.dp, bottom = 4.dp), labels = mapOf(com.audiohub.data.SortField.ALBUM to "Show", com.audiohub.data.SortField.ARTIST to "Author", com.audiohub.data.SortField.DATE_ADDED to "Release date"))
        }
        when {
            episodes == null -> Unit
            episodes.isEmpty() -> EmptyState(
                Icons.Rounded.Download,
                "Nothing downloaded",
                "Tap ↓ on an episode to keep it on your phone for offline listening.",
            )
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(episodes, key = { it.key }) { e ->
                    EpisodeRow(e, actions, e.mediaId == currentMediaId, isPlaying, showShowTitle = true)
                    Hairline()
                }
            }
        }
    }
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
                    "Paste an RSS feed URL or an Apple Podcasts link.",
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
