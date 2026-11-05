package com.audiohub.ui

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.audiohub.appContainer
import com.audiohub.data.db.TrackEntity
import com.audiohub.data.podcast.PodcastRepository
import com.audiohub.data.settings.SettingsRepository
import com.audiohub.playback.PlayerConnection
import com.audiohub.playback.episodeIdOf
import com.audiohub.playback.isPodcastMediaId
import com.audiohub.playback.trackContentUri
import com.audiohub.playback.trackIdOf
import com.audiohub.AppContainer
import com.audiohub.ui.podcasts.DownloadsScreen
import com.audiohub.ui.podcasts.EpisodeUi
import com.audiohub.ui.podcasts.LocalDownloadProgress
import com.audiohub.ui.podcasts.LocalPodcastActions
import com.audiohub.ui.podcasts.PodcastActions
import com.audiohub.ui.podcasts.PodcastShowScreen
import kotlinx.coroutines.CoroutineScope
import com.audiohub.ui.library.AddToPlaylistDialog
import com.audiohub.ui.library.AlbumDetailScreen
import com.audiohub.ui.library.AlbumsScreen
import com.audiohub.ui.library.ArtistDetailScreen
import com.audiohub.ui.library.ArtistsScreen
import com.audiohub.ui.library.FavoritesScreen
import com.audiohub.ui.library.FolderDetailScreen
import com.audiohub.ui.settings.EqualizerScreen
import com.audiohub.ui.library.GenreDetailScreen
import com.audiohub.ui.library.GenresScreen
import com.audiohub.ui.library.LibraryHomeScreen
import com.audiohub.ui.library.LibraryViewModel
import com.audiohub.ui.library.LocalTrackActions
import com.audiohub.ui.library.MostPlayedScreen
import com.audiohub.ui.library.PlaylistDetailScreen
import com.audiohub.ui.library.PlaylistsScreen
import com.audiohub.ui.library.RecentlyPlayedScreen
import com.audiohub.ui.library.SongsScreen
import com.audiohub.ui.library.TrackActions
import com.audiohub.ui.library.libraryViewModel
import com.audiohub.ui.nowplaying.MiniPlayer
import com.audiohub.ui.nowplaying.NowPlayingSheet
import com.audiohub.ui.onboarding.OnboardingScreen
import com.audiohub.ui.podcasts.PodcastsScreen
import com.audiohub.ui.search.SearchScreen
import com.audiohub.ui.settings.SettingsScreen
import kotlinx.coroutines.launch

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    Music(Routes.LIBRARY, "Music", Icons.Rounded.LibraryMusic),
    Podcasts(Routes.PODCASTS, "Podcasts", Icons.Rounded.Podcasts),
    Search(Routes.SEARCH, "Search", Icons.Rounded.Search),
    Settings(Routes.SETTINGS, "Settings", Icons.Rounded.Settings),
}

@Composable
fun AppRoot() {
    val container = LocalContext.current.appContainer
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()

    val current = settings
    when {
        current == null -> Box(Modifier.fillMaxSize())
        !current.onboardingDone -> OnboardingScreen(onFinished = {
            scope.launch { container.settings.setOnboardingDone(true) }
        })
        else -> MainScaffold()
    }
}

/** Bridges [TrackActions] to the ViewModel, the NavController and the add-to-playlist dialog. */
private class AppTrackActions(
    private val vm: LibraryViewModel,
    private val nav: NavHostController,
    private val onAddToPlaylist: (List<Long>) -> Unit,
) : TrackActions {
    override fun play(tracks: List<TrackEntity>, index: Int) = vm.play(tracks, index)
    override fun shuffle(tracks: List<TrackEntity>) = vm.shuffle(tracks)
    override fun playNext(tracks: List<TrackEntity>) = vm.playNext(tracks)
    override fun addToQueue(tracks: List<TrackEntity>) = vm.addToQueue(tracks)
    override fun addToPlaylist(tracks: List<TrackEntity>) = onAddToPlaylist(tracks.map { it.id })
    override fun toggleFavorite(track: TrackEntity) = vm.toggleFavorite(track.id)
    override fun navigate(route: String) = nav.navigate(route) { launchSingleTop = true }
    override fun back() {
        nav.popBackStack()
    }
}

private class AppPodcastActions(
    private val container: AppContainer,
    private val nav: NavHostController,
    private val scope: CoroutineScope,
) : PodcastActions {
    private val repo = container.podcastRepository
    private val player = container.playerConnection

    override fun play(episode: EpisodeUi) =
        player.playQueue(listOf(episode.toMediaItem()), startPositionMs = episode.resumePositionMs)
    override fun playNext(episode: EpisodeUi) = player.playNext(episode.toMediaItem())
    override fun addToQueue(episode: EpisodeUi) = player.addToQueue(episode.toMediaItem())
    override fun setPlayed(episode: EpisodeUi, played: Boolean) {
        val id = episode.episodeId ?: return
        scope.launch {
            repo.setPlayed(id, played)
            if (played) container.episodeDownloader.onPlayed(id)
        }
    }
    override fun download(episode: EpisodeUi) {
        val id = episode.episodeId ?: return
        scope.launch { container.downloadScheduler.enqueue(id) }
    }
    override fun cancelDownload(episode: EpisodeUi) {
        val id = episode.episodeId ?: return
        scope.launch { container.downloadScheduler.cancel(id) }
    }
    override fun removeDownload(episode: EpisodeUi) {
        val id = episode.episodeId ?: return
        scope.launch { container.episodeDownloader.delete(id) }
    }
    override fun navigate(route: String) = nav.navigate(route) { launchSingleTop = true }
    override fun back() {
        nav.popBackStack()
    }
}

@Composable
private fun MainScaffold() {
    val container = LocalContext.current.appContainer
    val player = container.playerConnection
    val playerState by player.state.collectAsStateWithLifecycle()
    val vm = libraryViewModel()
    val favoriteIds by vm.favoriteIds.collectAsStateWithLifecycle()
    val playlists by vm.playlistSummaries.collectAsStateWithLifecycle()

    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    var currentTab by rememberSaveable { mutableStateOf(Routes.LIBRARY) }
    LaunchedEffect(backStackEntry) {
        backStackEntry?.destination?.route?.takeIf { it in Routes.TABS }?.let { currentTab = it }
    }

    var showNowPlaying by rememberSaveable { mutableStateOf(false) }
    var addToPlaylistIds by remember { mutableStateOf<List<Long>?>(null) }
    val actions = remember(vm, navController) { AppTrackActions(vm, navController) { addToPlaylistIds = it } }
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val podcastActions = remember(navController, scope) { AppPodcastActions(container, navController, scope) }
    val downloadProgress by container.downloadProgress.progress.collectAsStateWithLifecycle()

    // Links opened/shared into the app (podcasts.apple.com, RSS) → show page.
    // Never fail silently: tell the user why a track won't play.
    val context = LocalContext.current
    LaunchedEffect(playerState.errorMessage) {
        playerState.errorMessage?.let { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show() }
    }

    val pendingRoute by container.pendingRoute.collectAsStateWithLifecycle()
    LaunchedEffect(pendingRoute) {
        pendingRoute?.let { route ->
            container.pendingRoute.value = null
            navController.navigate(route) { launchSingleTop = true }
        }
    }

    CompositionLocalProvider(
        LocalTrackActions provides actions,
        LocalPodcastActions provides podcastActions,
        LocalDownloadProgress provides downloadProgress,
    ) {
        Scaffold(
            bottomBar = {
                Column {
                    if (playerState.hasMedia) {
                        MiniPlayer(
                            state = playerState,
                            positionProvider = { player.currentPositionMs },
                            onOpen = { showNowPlaying = true },
                            onPlayPause = player::togglePlayPause,
                            onNext = player::skipNext,
                        )
                    }
                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.background,
                        tonalElevation = 0.dp,
                    ) {
                        Tab.entries.forEach { tab ->
                            NavigationBarItem(
                                colors = NavigationBarItemDefaults.colors(
                                    indicatorColor = Color.Transparent,
                                    selectedIconColor = MaterialTheme.colorScheme.onSurface,
                                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                ),
                                selected = currentTab == tab.route,
                                onClick = {
                                    if (currentTab == tab.route) {
                                        // Re-tapping the active tab returns to its root, like iOS.
                                        navController.popBackStack(tab.route, inclusive = false)
                                    } else {
                                        navController.navigate(tab.route) {
                                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                },
                                icon = { Icon(tab.icon, contentDescription = null) },
                                label = { Text(tab.label) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = Routes.LIBRARY,
                modifier = Modifier.padding(padding),
            ) {
                composable(Routes.LIBRARY) { LibraryHomeScreen() }
                composable(Routes.PODCASTS) { PodcastsScreen() }
                composable(Routes.SEARCH) { SearchScreen() }
                composable(Routes.SETTINGS) { SettingsScreen() }

                composable(Routes.SONGS) { SongsScreen() }
                composable(Routes.ALBUMS) { AlbumsScreen() }
                composable(Routes.ARTISTS) { ArtistsScreen() }
                composable(Routes.GENRES) { GenresScreen() }
                composable(Routes.PLAYLISTS) { PlaylistsScreen() }
                composable(Routes.FAVORITES) { FavoritesScreen() }
                composable(Routes.MOST_PLAYED) { MostPlayedScreen() }
                composable(Routes.RECENTLY_PLAYED) { RecentlyPlayedScreen() }
                composable(Routes.DOWNLOADS) { DownloadsScreen() }
                composable(Routes.FOLDER, listOf(navArgument("path") { type = NavType.StringType; defaultValue = "" })) {
                    FolderDetailScreen(it.arguments?.getString("path").orEmpty())
                }
                composable(Routes.EQUALIZER) { EqualizerScreen() }
                composable(Routes.DIAGNOSTICS) { com.audiohub.ui.settings.DiagnosticsScreen() }
                composable(Routes.YOUTUBE, listOf(navArgument("url") { type = NavType.StringType; defaultValue = "" })) {
                    com.audiohub.ui.settings.YoutubeScreen(it.arguments?.getString("url").orEmpty())
                }
                composable(Routes.RINGTONE, listOf(navArgument("trackId") { type = NavType.LongType })) {
                    com.audiohub.ui.ringtone.RingtoneScreen(it.arguments?.getLong("trackId") ?: 0L)
                }

                composable(Routes.ALBUM, listOf(navArgument("albumId") { type = NavType.LongType })) {
                    AlbumDetailScreen(it.arguments?.getLong("albumId") ?: 0L)
                }
                // Navigation already URL-decodes arguments.
                composable(Routes.ARTIST, listOf(navArgument("name") { type = NavType.StringType })) {
                    ArtistDetailScreen(it.arguments?.getString("name").orEmpty())
                }
                composable(Routes.GENRE, listOf(navArgument("name") { type = NavType.StringType })) {
                    GenreDetailScreen(it.arguments?.getString("name").orEmpty())
                }
                composable(
                    Routes.SHOW,
                    listOf(
                        navArgument("id") { type = NavType.LongType; defaultValue = -1L },
                        navArgument("feed") { type = NavType.StringType; nullable = true; defaultValue = null },
                        navArgument("itunes") { type = NavType.LongType; defaultValue = -1L },
                    ),
                ) {
                    val args = it.arguments
                    PodcastShowScreen(
                        podcastId = args?.getLong("id")?.takeIf { id -> id > 0 },
                        feedUrl = args?.getString("feed")?.takeIf { f -> f.isNotBlank() },
                        itunesId = args?.getLong("itunes")?.takeIf { id -> id > 0 },
                    )
                }
                composable(Routes.PLAYLIST, listOf(navArgument("playlistId") { type = NavType.LongType })) {
                    PlaylistDetailScreen(it.arguments?.getLong("playlistId") ?: 0L)
                }
            }
        }

        if (showNowPlaying && playerState.hasMedia) {
            val trackId = trackIdOf(playerState.mediaId)
            val episodeId = episodeIdOf(playerState.mediaId)
            val lyrics by produceState<com.audiohub.data.lyrics.Lyrics?>(null, trackId) {
                value = trackId?.let { container.lyricsRepository.load(trackContentUri(it)) }
            }
            val showNotes by produceState<String?>(null, episodeId) {
                value = episodeId?.let { container.podcastRepository.episode(it)?.episode?.description }
            }
            NowPlayingSheet(
                state = playerState,
                player = player,
                isFavorite = trackId != null && trackId in favoriteIds,
                canFavorite = trackId != null,
                onToggleFavorite = { trackId?.let(vm::toggleFavorite) },
                lyrics = lyrics,
                isPodcast = isPodcastMediaId(playerState.mediaId),
                podcastSpeed = settings?.podcastSpeed ?: 1f,
                onCycleSpeed = {
                    val speeds = SettingsRepository.PODCAST_SPEEDS
                    val next = speeds[(speeds.indexOf(settings?.podcastSpeed ?: 1f) + 1).mod(speeds.size)]
                    scope.launch { container.settings.setPodcastSpeed(next) }
                },
                showNotes = showNotes,
                onDismiss = { showNowPlaying = false },
            )
        }

        addToPlaylistIds?.let { ids ->
            AddToPlaylistDialog(
                playlists = playlists,
                onPick = { playlistId ->
                    vm.addToPlaylist(playlistId, ids)
                    addToPlaylistIds = null
                },
                onCreate = { name ->
                    vm.createPlaylist(name, ids)
                    addToPlaylistIds = null
                },
                onDismiss = { addToPlaylistIds = null },
            )
        }
    }
}
