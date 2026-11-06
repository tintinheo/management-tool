package com.audiohub.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
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
        queue = tracks.mapIndexed { i, t -> QueueItem(i, "track:${t.id}", t.title, t.artist, null) },
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
        override fun setSleepTimer(durationMs: Long) = Unit
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
    fun songs() = shot("songs") {
        TrackListContent("Songs", tracks, listState, TrackActions.None, sort = com.audiohub.data.SortOrder(com.audiohub.data.SortField.TITLE))
    }

    @Test
    fun playlistsWithFolders() = shot("playlists_folders") {
        com.audiohub.ui.library.PlaylistsContent(
            playlists = listOf(com.audiohub.data.db.PlaylistSummary(1, "Road Trip", 24, 1)),
            actions = TrackActions.None,
            onNew = {}, onImport = {},
            folders = listOf(
                com.audiohub.data.db.FolderSummary("Music/AudioHub/Jazz/", 31, 4),
                com.audiohub.data.db.FolderSummary("Music/AudioHub/Nhạc Việt/", 112, 3),
                com.audiohub.data.db.FolderSummary("Music/AudioHub/Nhạc Việt/Trịnh Công Sơn/", 18, 3),
            ),
            musicRoot = "Music/AudioHub/",
        )
    }

    @Test
    fun equalizer() = shot("equalizer") {
        com.audiohub.ui.settings.EqualizerContent(
            info = com.audiohub.playback.EqualizerInfo(
                presets = listOf("Normal", "Classical", "Dance", "Jazz", "Pop", "Rock"),
                bandFrequencies = listOf(60, 230, 910, 3600, 14000),
                minLevel = -1500, maxLevel = 1500,
            ),
            enabled = true, preset = -1, bands = listOf(400, 200, 0, 300, 600),
            onChange = { _, _, _ -> }, onOpenSystem = {},
        )
    }

    @Test
    fun youtubeConverter() = shot("youtube", manualClock = true) {
        com.audiohub.ui.settings.YoutubeContent(
            link = "https://youtu.be/dQw4w9WgXcQ",
            onLinkChange = {}, onPaste = {}, onFetch = {},
            loading = false, error = null,
            video = com.audiohub.data.youtube.VideoInfo(
                url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                title = "Sơn Tùng M-TP | Lạc Trôi | Official Music Video",
                uploader = "Sơn Tùng M-TP Official",
                durationMs = 274_000,
                thumbnailUrl = null,
                audio = com.audiohub.data.youtube.AudioCandidate("x", 160, com.audiohub.data.youtube.AudioContainer.WEBM_OPUS),
            ),
            startMs = 12_000, endMs = 245_500,
            onRangeChange = { _, _ -> },
            convertState = com.audiohub.data.youtube.ConvertState.Downloading("Lạc Trôi", 0.42f),
            onConvert = {}, onCancel = {}, onPlay = {}, onAnother = {},
        )
    }

    @Test
    fun ringtoneEditor() = shot("ringtone_editor", manualClock = true) {
        com.audiohub.ui.ringtone.RingtoneContent(
            track = tracks[2],
            startMs = 45_000,
            endMs = 75_500,
            onRangeChange = { _, _ -> },
            previewing = false,
            onTogglePreview = {},
            status = com.audiohub.ui.ringtone.RingtoneStatus.Saved(android.net.Uri.parse("content://media/external/audio/media/1")),
            onSave = {},
            onSetAs = { _, _ -> },
        )
    }

    @Test
    fun sleepTimerDialog() = shot("sleep_timer", manualClock = true) {
        com.audiohub.ui.nowplaying.SleepTimerPanel(active = true, onPick = {}, onDismiss = {})
    }

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
    fun playingQueue() = shot("playing_queue", manualClock = true) {
        Column {
            Box(Modifier.weight(1f)) {
                com.audiohub.ui.nowplaying.QueueContent(playing.queue, playing.queueIndex, false, fakeControls, onBack = {})
            }
            MiniPlayer(playing, { 84_000L }, {}, {}, {})
        }
    }

    @Test
    fun playingQueueSearch() = shot("playing_queue_search", manualClock = true) {
        // Accent-insensitive: "lac" finds "Lạc Trôi".
        com.audiohub.ui.nowplaying.QueueContent(playing.queue, playing.queueIndex, false, fakeControls, onBack = {}, initialQuery = "lac")
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
            pendingRecall = listOf(episodes[2].copy(played = true)),
            notesCount = 4,
        )
    }

    // ------------------------------------------------------------------ Phase 8: styles, motion, recall

    private val longTitle = "Một Bài Hát Có Cái Tên Rất Dài Để Kiểm Tra Chữ Chạy (Live at Hòa Bình Theater)"

    @Test
    fun nowPlayingNoir() = shot("now_playing_noir", manualClock = true, style = com.audiohub.ui.theme.UiStyle.Noir) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            NowPlayingContent(playing.copy(title = longTitle), fakeControls, isFavorite = true)
        }
    }

    @Test
    fun nowPlayingRivieraDark() = shot("now_playing_riviera_dark", dark = true, manualClock = true, style = com.audiohub.ui.theme.UiStyle.Riviera) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            NowPlayingContent(playing, fakeControls, isFavorite = true)
        }
    }

    @Test
    fun libraryHomeRiviera() = shot("library_home_riviera", manualClock = true, style = com.audiohub.ui.theme.UiStyle.Riviera) {
        Column {
            Box(Modifier.weight(1f)) {
                LibraryHomeContent(1234, false, "Music/AudioHub/", albums, TrackActions.None, onRescan = {})
            }
            MiniPlayer(playing.copy(title = longTitle), { 84_000L }, {}, {}, {})
        }
    }

    @Test
    fun libraryHomeNoirDark() = shot("library_home_noir_dark", dark = true, manualClock = true, style = com.audiohub.ui.theme.UiStyle.Noir) {
        LibraryHomeContent(1234, false, "Music/AudioHub/", albums, TrackActions.None, onRescan = {})
    }

    @Test
    fun appearanceSettings() = shot("settings_appearance", manualClock = true) {
        Column(Modifier.padding(20.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
            com.audiohub.ui.settings.AppearanceSection(
                settings = com.audiohub.data.settings.AppSettings(
                    onboardingDone = true, musicFolder = "Music/AudioHub/", podcastFolder = "Podcasts/AudioHub/",
                    podcastCountry = "vn", podcastSpeed = 1f, uiStyle = "atelier", headingFont = "sys:serif",
                ),
                onStyle = {}, onMotion = {}, onHeadingFont = {}, onBodyFont = {},
            )
        }
    }

    @Test
    fun recall() = shot("podcast_recall", manualClock = true) {
        val note = com.audiohub.data.db.EpisodeNoteEntity(
            episodeId = 1, episodeTitle = "Tập 42: Đà Lạt mùa sương", showTitle = "Chuyện Cà Phê", artworkUrl = null,
            mainIdeas = "Đà Lạt thay đổi theo mùa; quán cà phê nhỏ giữ nhịp sống chậm.",
            highlight = "Sương xuống lúc 5 giờ sáng ở hồ Tuyền Lâm.",
            recallScore = 3, createdAt = 0, updatedAt = 0,
        )
        com.audiohub.ui.podcasts.RecallContent(
            episode = com.audiohub.ui.podcasts.RecallEpisode(
                com.audiohub.data.podcast.NotesRepository.EpisodeHeader(1, note.episodeTitle, note.showTitle, null),
                "<p>Trong tập này: <b>Đà Lạt</b> mùa sương, quán cà phê ven hồ và nhịp sống chậm.</p>",
                note,
            ),
            form = com.audiohub.ui.podcasts.RecallForm(note),
            onBack = {}, onDelete = {},
        )
    }

    @Test
    fun notesList() = shot("podcast_notes") {
        com.audiohub.ui.podcasts.NotesContent(
            listOf(
                com.audiohub.data.db.EpisodeNoteEntity(1, "Tập 42: Đà Lạt mùa sương", "Chuyện Cà Phê", null,
                    mainIdeas = "Đà Lạt thay đổi theo mùa.", recallScore = 3, createdAt = 0, updatedAt = 0),
                com.audiohub.data.db.EpisodeNoteEntity(2, "The Fall of Rome, Part 2", "Hardcore History", null,
                    notes = "Look up the Battle of Adrianople.", recallScore = 1, createdAt = 0, updatedAt = 0),
            ),
            PodcastActions.None,
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

    private fun shot(
        name: String,
        dark: Boolean = false,
        manualClock: Boolean = false,
        style: com.audiohub.ui.theme.UiStyle = com.audiohub.ui.theme.UiStyle.Default,
        content: @Composable () -> Unit,
    ) {
        // Screens with a text field can have a blinking cursor, which never lets the test clock go idle.
        if (manualClock) compose.mainClock.autoAdvance = false
        compose.setContent {
            AudioHubTheme(darkTheme = dark, style = style, dynamicColor = false) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
            }
        }
        if (manualClock) {
            // A few steps, so work handed to background threads (e.g. queue search) can land in between.
            repeat(5) {
                compose.mainClock.advanceTimeBy(200)
                Thread.sleep(50)
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
