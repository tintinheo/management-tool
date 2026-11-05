package com.audiohub.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.media3.common.Player
import com.audiohub.data.SearchText
import com.audiohub.data.db.AlbumSummary
import com.audiohub.data.db.PlaylistTrack
import com.audiohub.data.db.TrackEntity
import com.audiohub.data.lyrics.LrcParser
import com.audiohub.data.music.LibrarySearch
import com.audiohub.playback.PlayerControls
import com.audiohub.playback.PlayerUiState
import com.audiohub.playback.QueueItem
import com.audiohub.ui.library.AlbumDetailContent
import com.audiohub.ui.library.LibraryHomeContent
import com.audiohub.ui.library.PlaylistDetailContent
import com.audiohub.ui.library.TrackActions
import com.audiohub.ui.library.TrackListContent
import com.audiohub.ui.library.TrackListState
import com.audiohub.ui.nowplaying.MiniPlayer
import com.audiohub.ui.nowplaying.NowPlayingContent
import com.audiohub.ui.nowplaying.NowPlayingPanel
import com.audiohub.data.db.PodcastSummary
import com.audiohub.data.music.SearchResults
import com.audiohub.data.podcast.PodcastSearchResult
import com.audiohub.ui.podcasts.EpisodeUi
import com.audiohub.ui.podcasts.PodcastActions
import com.audiohub.ui.podcasts.PodcastShowContent
import com.audiohub.ui.podcasts.PodcastsContent
import com.audiohub.ui.podcasts.ShowHeader
import com.audiohub.ui.podcasts.ShowUiState
import com.audiohub.ui.search.PodcastSearchState
import com.audiohub.ui.search.SearchContent
import com.audiohub.ui.search.SearchMode
import com.audiohub.ui.theme.AudioHubTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders screens with sample data and writes PNGs to app/build/screenshots/. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE_QUALIFIERS)
class ScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val tracks = listOf(
        sample(1, "Bohemian Rhapsody", "Queen", "A Night at the Opera", 354_000, track = 11),
        sample(2, "Hotel California", "Eagles", "Hotel California", 391_000, track = 1),
        sample(3, "Lạc Trôi", "Sơn Tùng M-TP", "Lạc Trôi (Single)", 233_000, track = 1),
        sample(4, "Take Five", "The Dave Brubeck Quartet", "Time Out", 324_000, track = 3),
        sample(5, "A Very Long Song Title That Should Be Ellipsized Properly", "Some Artist", "Some Album", 61_000, track = 2),
    )

    private val listState = TrackListState(favoriteIds = setOf(2L, 3L), currentMediaId = "track:3")

    private val playing = PlayerUiState(
        hasMedia = true,
        isPlaying = true,
        mediaId = "track:3",
        title = "Lạc Trôi",
        artist = "Sơn Tùng M-TP",
        album = "Lạc Trôi (Single)",
        durationMs = 233_000,
        shuffleEnabled = false,
        repeatMode = Player.REPEAT_MODE_ALL,
        hasNext = true,
        queueIndex = 2,
        queueSize = 5,
        upNext = tracks.drop(3).mapIndexed { i, t -> QueueItem(3 + i, "track:${t.id}", t.title, t.artist, null) },
    )

    private val fakeControls = object : PlayerControls {
        override val currentPositionMs = 84_000L
        override fun togglePlayPause() = Unit
        override fun skipNext() = Unit
        override fun skipPrevious() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun seekBy(deltaMs: Long) = Unit
        override fun toggleShuffle() = Unit
        override fun cycleRepeat() = Unit
        override fun jumpTo(index: Int) = Unit
        override fun removeAt(index: Int) = Unit
        override fun move(from: Int, to: Int) = Unit
    }

    private val albums = listOf(
        AlbumSummary(1, "A Night at the Opera", "Queen", 1975, 12),
        AlbumSummary(2, "Hotel California", "Eagles", 1976, 9),
        AlbumSummary(3, "Lạc Trôi (Single)", "Sơn Tùng M-TP", 2017, 1),
        AlbumSummary(4, "Time Out", "The Dave Brubeck Quartet", 1959, 7),
    )

    @Test
    fun libraryHome() = shot("library_home") {
        Column {
            Box(Modifier.weight(1f)) {
                LibraryHomeContent(1234, false, "Music/AudioHub/", albums, TrackActions.None, onRescan = {})
            }
            MiniPlayer(playing, { 84_000L }, {}, {}, {})
        }
    }

    @Test
    fun libraryHomeDark() = shot("library_home_dark", dark = true) {
        LibraryHomeContent(1234, false, "Music/AudioHub/", albums, TrackActions.None, onRescan = {})
    }

    @Test
    fun libraryEmpty() = shot("library_empty") {
        LibraryHomeContent(0, false, "Music/AudioHub/", emptyList(), TrackActions.None, onRescan = {})
    }

    @Test
    fun songs() = shot("songs") { TrackListContent("Songs", tracks, listState, TrackActions.None) }

    @Test
    fun albumDetail() = shot("album_detail") {
        AlbumDetailContent(albums[1], tracks.sortedBy { it.trackNumber }, listState, TrackActions.None)
    }

    @Test
    fun playlistDetail() = shot("playlist_detail") {
        PlaylistDetailContent(
            name = "Road Trip",
            tracks = tracks.mapIndexed { i, t -> PlaylistTrack(t, entryId = 100L + i, position = i) },
            state = listState,
            actions = TrackActions.None,
            onMove = { _, _ -> }, onRemove = {}, onRename = {}, onDelete = {}, onExport = {},
        )
    }

    @Test
    fun search() = shot("search") {
        SearchContent("ea", {}, LibrarySearch.search(tracks, "ea"), listState, TrackActions.None)
    }

    @Test
    fun nowPlaying() = shot("now_playing") {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            NowPlayingContent(playing, fakeControls, isFavorite = true)
        }
    }

    @Test
    fun nowPlayingLyrics() = shot("now_playing_lyrics") {
        val lyrics = LrcParser.parse(
            (0 until 14).joinToString("\n") { i -> "[01:%02d.00]Dòng lời bài hát số ${i + 1}".format(i * 5) },
        )
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            NowPlayingContent(playing, fakeControls, lyrics = lyrics, panel = NowPlayingPanel.Lyrics)
        }
    }

    @Test
    fun nowPlayingQueue() = shot("now_playing_queue") {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            NowPlayingContent(playing, fakeControls, panel = NowPlayingPanel.Queue)
        }
    }

    private val day = 24L * 60 * 60 * 1000
    private val nowMs = System.currentTimeMillis()
    private fun ep(id: Long, title: String, show: String, ageDays: Int, durMin: Int, posMin: Int = 0, played: Boolean = false) =
        EpisodeUi(
            episodeId = id, title = title, showTitle = show,
            description = "<p>Trong tập này chúng ta nói về <b>cà phê</b>, âm nhạc và những câu chuyện thú vị khác.</p>",
            pubDate = nowMs - ageDays * day, durationMs = durMin * 60_000L, positionMs = posMin * 60_000L,
            played = played, enclosureUrl = "https://example.com/$id.mp3", localUri = null, artworkUrl = null,
        )

    private val episodes = listOf(
        ep(1, "Tập 42: Đà Lạt mùa sương", "Chuyện Cà Phê", 1, 45),
        ep(2, "The Sunday Read: A Very Long Episode Title That Wraps Onto Two Lines", "The Daily", 2, 38, posMin = 12),
        ep(3, "Tập 41: Sài Gòn về đêm", "Chuyện Cà Phê", 8, 52, played = true),
    )

    @Test
    fun podcastsHome() = shot("podcasts_home") {
        PodcastsContent(
            shows = listOf(
                PodcastSummary(1, "Chuyện Cà Phê", "AudioHub", null, "f1", 3, nowMs),
                PodcastSummary(2, "The Daily", "The New York Times", null, "f2", 12, nowMs),
                PodcastSummary(3, "Hardcore History", "Dan Carlin", null, "f3", 0, nowMs),
            ),
            inProgress = listOf(episodes[1]),
            latest = listOf(episodes[0]),
            refreshing = false,
            currentMediaId = null,
            isPlaying = false,
            actions = PodcastActions.None,
            onRefresh = {},
        )
    }

    @Test
    fun podcastShow() = shot("podcast_show") {
        PodcastShowContent(
            state = ShowUiState.Ready(
                ShowHeader("Chuyện Cà Phê", "AudioHub", "<p>Một podcast về cà phê, con người và những thành phố.</p>", null),
                listOf(episodes[0], episodes[2]),
                podcastId = null,
            ),
            currentMediaId = "episode:1",
            isPlaying = true,
            actions = PodcastActions.None,
            onFollow = {}, onUnfollow = {}, onRefresh = {}, onRetry = {},
        )
    }

    @Test
    fun podcastSearchCharts() = shot("podcast_search_charts") {
        SearchContent(
            query = "",
            onQueryChange = {},
            results = SearchResults.EMPTY,
            state = listState,
            actions = TrackActions.None,
            mode = SearchMode.Podcasts,
            podcastState = PodcastSearchState(
                isCharts = true,
                results = listOf(
                    PodcastSearchResult(1, "The Daily", "The New York Times", null, null, "News", 0),
                    PodcastSearchResult(2, "Chuyện Cà Phê", "AudioHub", null, null, "Society & Culture", 0),
                    PodcastSearchResult(3, "Huberman Lab", "Scicomm Media", null, null, "Health & Fitness", 0),
                ),
            ),
        )
    }

    @Test
    fun nowPlayingPodcast() = shot("now_playing_podcast") {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            NowPlayingContent(
                playing.copy(mediaId = "episode:1", title = "Tập 42: Đà Lạt mùa sương", artist = "Chuyện Cà Phê", album = ""),
                fakeControls,
                canFavorite = false,
                isPodcast = true,
                podcastSpeed = 1.5f,
            )
        }
    }

    private fun shot(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent {
            AudioHubTheme(darkTheme = dark, dynamicColor = false) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
            }
        }
        compose.onRoot().captureRoboImage("build/screenshots/$name.png")
    }

    private fun sample(id: Long, title: String, artist: String, album: String, durationMs: Long, track: Int) = TrackEntity(
        id = id,
        uri = "content://media/external/audio/media/$id",
        title = title,
        artist = artist,
        albumArtist = null,
        album = album,
        albumId = id,
        trackNumber = track,
        discNumber = 0,
        year = 2020,
        genre = null,
        durationMs = durationMs,
        dateAddedSec = 0,
        relativePath = "Music/AudioHub/",
        displayName = "$title.mp3",
        searchText = SearchText.of(title, artist, album),
    )
}

/** Roughly a Xiaomi 14T: 1220x2712 px at ~446 dpi. */
const val PHONE_QUALIFIERS = "w393dp-h873dp-xxhdpi"
