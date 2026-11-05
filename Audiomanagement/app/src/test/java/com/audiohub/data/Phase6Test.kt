package com.audiohub.data

import androidx.room.Room
import com.audiohub.data.db.AppDatabase
import com.audiohub.data.db.EpisodeFeedData
import com.audiohub.data.db.PodcastEntity
import com.audiohub.data.db.TrackEntity
import com.audiohub.data.music.MusicRepository
import com.audiohub.data.music.MusicScanner
import com.audiohub.data.music.PlaylistRepository
import com.audiohub.data.podcast.HttpClient
import com.audiohub.data.podcast.HttpResponse
import com.audiohub.data.podcast.ItunesApi
import com.audiohub.data.podcast.PodcastRepository
import com.audiohub.data.settings.SettingsRepository
import com.audiohub.playback.LibraryTree
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

class SortingTest {

    private fun t(id: Long, title: String, artist: String, album: String, file: String, added: Long, modified: Long, track: Int = 1) =
        TrackEntity(
            id = id, uri = "content://x/$id", title = title, artist = artist, albumArtist = null, album = album,
            albumId = id, trackNumber = track, discNumber = 0, year = 2000, genre = null, durationMs = 1,
            dateAddedSec = added, relativePath = "Music/AudioHub/", displayName = file, dateModifiedSec = modified,
        )

    private val songs = listOf(
        t(1, "Đà Lạt", "Hà Anh Tuấn", "Sketch", "10 Da Lat.mp3", added = 300, modified = 100),
        t(2, "Bohemian Rhapsody", "Queen", "A Night at the Opera", "2 Bohemian.mp3", added = 100, modified = 300),
        t(3, "Ánh Nắng", "Ánh", "Zebra", "1 Anh nang.flac", added = 200, modified = 200),
    )

    private fun ids(order: SortOrder) = TrackSorter.sort(songs, order).map { it.id }

    @Test
    fun naturalOrderHandlesNumbersAndAccents() {
        val sorted = listOf("Track 10", "track 2", "Track 1", "Đà Lạt", "Da Nang").sortedWith(NaturalOrder)
        assertEquals(listOf("Đà Lạt", "Da Nang", "Track 1", "track 2", "Track 10"), sorted) // "da lat" < "da nang"
    }

    @Test
    fun songsSortByEveryField() {
        assertEquals(listOf(3L, 2L, 1L), ids(SortOrder(SortField.NAME))) // "1 …", "2 …", "10 …"
        assertEquals(listOf(3L, 2L, 1L), ids(SortOrder(SortField.TITLE))) // Ánh, Bohemian, Đà
        assertEquals(listOf(1L, 2L, 3L), ids(SortOrder(SortField.TITLE, descending = true)))
        assertEquals(listOf(2L, 1L, 3L), ids(SortOrder(SortField.ALBUM))) // A Night, Sketch, Zebra
        assertEquals(listOf(3L, 1L, 2L), ids(SortOrder(SortField.ARTIST))) // Ánh, Hà, Queen
        assertEquals(listOf(1L, 3L, 2L), ids(SortOrder(SortField.DATE_ADDED))) // dates default newest first
        assertEquals(listOf(2L, 3L, 1L), ids(SortOrder(SortField.DATE_MODIFIED)))
        assertEquals(listOf(1L, 3L, 2L), ids(SortOrder(SortField.DATE_MODIFIED, descending = false)))
    }

    @Test
    fun sortOrderSelectAndPersistence() {
        val title = SortOrder(SortField.TITLE)
        assertFalse(title.descending)
        assertTrue(title.select(SortField.TITLE).descending) // same field flips
        assertTrue(title.select(SortField.DATE_ADDED).descending) // dates start newest-first
        val encoded = SortOrder(SortField.ALBUM, descending = true).encode()
        assertEquals(SortOrder(SortField.ALBUM, true), SortOrder.decode(encoded, title))
        assertEquals(title, SortOrder.decode("garbage", title))
        assertEquals(title, SortOrder.decode(null, title))
    }

    private data class Ep(
        override val title: String,
        override val showTitle: String,
        override val showAuthor: String,
        override val pubDate: Long,
        override val modifiedAt: Long = 0,
    ) : SortableEpisode {
        override val sortName get() = title
    }

    @Test
    fun episodesSortByEveryField() {
        val eps = listOf(
            Ep("Tập 10", "B show", "Zed", pubDate = 300),
            Ep("Tập 2", "A show", "Amy", pubDate = 100, modifiedAt = 900),
            Ep("Intro", "C show", "Mia", pubDate = 200),
        )
        fun titles(o: SortOrder) = EpisodeSorter.sort(eps, o).map { it.title }
        assertEquals(listOf("Intro", "Tập 2", "Tập 10"), titles(SortOrder(SortField.NAME)))
        assertEquals(listOf("Tập 2", "Tập 10", "Intro"), titles(SortOrder(SortField.ALBUM)))
        assertEquals(listOf("Tập 2", "Intro", "Tập 10"), titles(SortOrder(SortField.ARTIST)))
        assertEquals(listOf("Tập 10", "Intro", "Tập 2"), titles(SortOrder(SortField.DATE_ADDED)))
        assertEquals(listOf("Tập 2", "Tập 10", "Intro"), titles(SortOrder(SortField.DATE_MODIFIED))) // 900 > 300 > 200
    }

    @Test
    fun folderNamesReadAsBreadcrumbs() {
        assertEquals("Rock › 80s", com.audiohub.data.storage.AudioFolders.displayName("Music/AudioHub/Rock/80s/", "Music/AudioHub/"))
        assertEquals("Nhạc Việt", com.audiohub.data.storage.AudioFolders.displayName("Music/AudioHub/Nhạc Việt", "Music/AudioHub"))
        assertEquals("Music", com.audiohub.data.storage.AudioFolders.displayName("Music/AudioHub/", "Music/AudioHub/"))
    }
}

/** The Android Auto / Bluetooth browse tree over a real (in-memory) database. */
@RunWith(RobolectricTestRunner::class)
class LibraryTreeTest {

    private object NoHttp : HttpClient {
        override suspend fun get(url: String, headers: Map<String, String>) = HttpResponse(404, ByteArray(0), emptyMap())
    }

    private fun track(id: Long, title: String, path: String, albumId: Long = id) = TrackEntity(
        id = id, uri = "content://media/external/audio/media/$id", title = title, artist = "Artist", albumArtist = null,
        album = "Album $albumId", albumId = albumId, trackNumber = id.toInt(), discNumber = 0, year = 2000, genre = null,
        durationMs = 1000, dateAddedSec = id, relativePath = path, displayName = "$title.mp3",
    )

    @Test
    fun browseTreeListsFoldersPlaylistsAlbumsAndPodcasts() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val settings = SettingsRepository(app).apply { setMusicFolder("Music/AudioHub/") }
            val music = MusicRepository(MusicScanner(app), db.trackDao(), settings)
            val playlists = PlaylistRepository(app, db.playlistDao(), db.trackDao(), settings)
            val podcasts = PodcastRepository(db.podcastDao(), NoHttp, ItunesApi(NoHttp), settings)
            val tree = LibraryTree(music, playlists, podcasts, settings)

            db.trackDao().replaceAll(
                listOf(
                    track(1, "Root song", "Music/AudioHub/"),
                    track(2, "Rock A", "Music/AudioHub/Rock/", albumId = 7),
                    track(3, "Rock B", "Music/AudioHub/Rock/", albumId = 7),
                    track(4, "Eighties", "Music/AudioHub/Rock/80s/"),
                ),
            )
            val playlistId = playlists.create("Road trip", listOf(4, 2))
            val showId = db.podcastDao().insertPodcast(
                PodcastEntity(feedUrl = "https://x/feed", itunesId = null, title = "Show", author = "Host", description = "",
                    artworkUrl = null, websiteUrl = null, subscribedAt = 0),
            )
            db.podcastDao().mergeEpisodes(showId, listOf(EpisodeFeedData("g", "Ep 1", "", 1, 1000, "https://x/1.mp3", "audio/mpeg", 0, null, null, null)))

            assertEquals(
                listOf(LibraryTree.PLAYLISTS, LibraryTree.FOLDERS, LibraryTree.ALBUMS, LibraryTree.ARTISTS, LibraryTree.SONGS, LibraryTree.PODCASTS),
                tree.children(LibraryTree.ROOT).map { it.mediaId },
            )
            // Each subfolder is a playlist; the root folder itself is not.
            val folders = tree.children(LibraryTree.FOLDERS)
            assertEquals(listOf("Rock", "Rock › 80s"), folders.map { it.mediaMetadata.title.toString() })
            assertTrue(folders.all { it.mediaMetadata.isBrowsable == true })
            assertEquals(listOf("track:2", "track:3"), tree.children(folders[0].mediaId).map { it.mediaId })

            assertEquals(listOf("track:4", "track:2"), tree.children("${LibraryTree.PLAYLIST}$playlistId").map { it.mediaId })
            assertEquals(listOf("track:2", "track:3"), tree.children("${LibraryTree.ALBUM}7").map { it.mediaId })
            assertEquals(listOf("Show"), tree.children(LibraryTree.PODCASTS).map { it.mediaMetadata.title.toString() })
            val episode = tree.children("${LibraryTree.SHOW}$showId").single()
            assertTrue(episode.mediaId.startsWith("episode:"))

            // Bare ids from a car are resolved to playable items with a URI.
            assertNotNull(tree.playable("track:3")?.localConfiguration)
            assertNotNull(tree.playable(episode.mediaId)?.localConfiguration)
            assertEquals(0L, tree.resumePosition(episode.mediaId))
        } finally {
            db.close()
        }
    }
}
