package com.audiohub.ui.search

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.audiohub.appContainer
import com.audiohub.ui.podcasts.LocalPodcastActions
import com.audiohub.ui.podcasts.PodcastActions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.audiohub.data.music.LibrarySearch
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.audiohub.data.music.SearchResults
import com.audiohub.ui.Routes
import com.audiohub.ui.components.EmptyState
import com.audiohub.ui.components.ScreenHeader
import com.audiohub.ui.library.AlbumTile
import com.audiohub.ui.library.ArtistRow
import com.audiohub.ui.library.LocalTrackActions
import com.audiohub.ui.library.TrackActions
import com.audiohub.ui.library.TrackListState
import com.audiohub.ui.library.TrackRow
import com.audiohub.ui.library.libraryViewModel
import com.audiohub.ui.library.rememberTrackListState

enum class SearchMode { Library, Podcasts }

@Composable
fun SearchScreen() {
    val container = LocalContext.current.appContainer
    val vm = libraryViewModel()
    val podcastVm: PodcastSearchViewModel = viewModel(
        factory = viewModelFactory { initializer { PodcastSearchViewModel(container.podcastRepository) } },
    )
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    var mode by rememberSaveable { mutableStateOf(SearchMode.Library) }
    var query by rememberSaveable { mutableStateOf("") }
    var podcastQuery by rememberSaveable { mutableStateOf("") }
    // Debounced and off the main thread so typing stays smooth on big libraries.
    val results by produceState(SearchResults.EMPTY, tracks, query) {
        delay(120)
        value = withContext(Dispatchers.Default) { LibrarySearch.search(tracks.orEmpty(), query) }
    }
    val podcastState by podcastVm.state.collectAsStateWithLifecycle()
    SearchContent(
        query = if (mode == SearchMode.Library) query else podcastQuery,
        onQueryChange = {
            if (mode == SearchMode.Library) query = it else { podcastQuery = it; podcastVm.query.value = it }
        },
        results = results,
        state = rememberTrackListState(),
        actions = LocalTrackActions.current,
        mode = mode,
        onModeChange = { mode = it; if (it == SearchMode.Podcasts) podcastVm.query.value = podcastQuery },
        podcastState = podcastState,
        podcastActions = LocalPodcastActions.current,
    )
}

@Composable
fun SearchContent(
    query: String,
    onQueryChange: (String) -> Unit,
    results: SearchResults,
    state: TrackListState,
    actions: TrackActions,
    mode: SearchMode = SearchMode.Library,
    onModeChange: (SearchMode) -> Unit = {},
    podcastState: PodcastSearchState = PodcastSearchState(),
    podcastActions: PodcastActions = PodcastActions.None,
) {
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Search")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            SearchMode.entries.forEachIndexed { i, m ->
                SegmentedButton(
                    selected = mode == m,
                    onClick = { onModeChange(m) },
                    shape = SegmentedButtonDefaults.itemShape(i, SearchMode.entries.size),
                ) { Text(if (m == SearchMode.Library) "Your Library" else "Apple Podcasts") }
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text(if (mode == SearchMode.Library) "Songs, albums, artists" else "Search shows") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Rounded.Clear, contentDescription = "Clear") }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )
        when {
            mode == SearchMode.Podcasts -> PodcastSearchResults(podcastState, podcastActions)
            query.isBlank() -> EmptyState(
                Icons.Rounded.Search,
                "Search your library",
                "Accents are optional — \"lac troi\" finds \"Lạc Trôi\".",
            )
            results.isEmpty -> EmptyState(Icons.Rounded.SearchOff, "No results", "Nothing matches \"$query\".")
            else -> LazyColumn(Modifier.fillMaxSize()) {
                if (results.artists.isNotEmpty()) {
                    item { SectionTitle("Artists") }
                    items(results.artists.take(5), key = { "ar:" + it.name }) { artist ->
                        ArtistRow(artist) { actions.navigate(Routes.artist(artist.name)) }
                    }
                }
                if (results.albums.isNotEmpty()) {
                    item { SectionTitle("Albums") }
                    item {
                        LazyRow(contentPadding = PaddingValues(horizontal = 14.dp)) {
                            items(results.albums, key = { "al:" + it.albumId }) { album ->
                                AlbumTile(album, onClick = { actions.navigate(Routes.album(album.albumId)) }, modifier = Modifier.width(150.dp))
                            }
                        }
                    }
                }
                if (results.songs.isNotEmpty()) {
                    item { SectionTitle("Songs") }
                    itemsIndexed(results.songs, key = { _, t -> "s:" + t.id }) { index, track ->
                        TrackRow(track, state, actions, onClick = { actions.play(results.songs, index) })
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp))
}
