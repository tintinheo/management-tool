package com.audiohub.data.db

import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class LibraryDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var tracks: TrackDao
    private lateinit var playlists: PlaylistDao
    private lateinit var stats: StatsDao

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        tracks = db.trackDao()
        playlists = db.playlistDao()
        stats = db.statsDao()
        tracks.replaceAll(
            listOf(
                t(1, "Hotel California", "Eagles", "Hotel California", 10, track = 1, year = 1976, added = 100, genre = "Rock"),
                t(2, "New Kid in Town", "Eagles", "Hotel California", 10, track = 2, year = 1976, added = 100, genre = "Rock"),
                t(3, "Take It Easy", "Eagles", "Eagles", 11, track = 1, year = 1972, added = 300, genre = "Rock"),
                t(4, "Take Five", "Dave Brubeck", "Time Out", 20, track = 3, year = 1959, added = 200, genre = "Jazz"),
                t(5, "Blue Rondo", "Dave Brubeck", "Time Out", 20, track = 1, year = 1959, added = 200, genre = null),
            ),
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun albumAndArtistAggregates() = runBlocking {
        val albums = tracks.observeAlbums().first()
        assertEquals(listOf("Eagles", "Hotel California", "Time Out"), albums.map { it.title })
        assertEquals(2, albums.first { it.albumId == 10L }.trackCount)

        assertEquals(listOf(11L, 20L, 10L), tracks.observeRecentlyAddedAlbums(10).first().map { it.albumId })

        val artists = tracks.observeArtists().first()
        assertEquals(listOf("Dave Brubeck", "Eagles"), artists.map { it.name })
        assertEquals(2, artists.first { it.name == "Eagles" }.albumCount)
        assertEquals(3, artists.first { it.name == "Eagles" }.trackCount)

        assertEquals(listOf(10L, 11L), tracks.observeArtistAlbums("Eagles").first().map { it.albumId }) // 1976 before 1972
        assertEquals(listOf("Jazz", "Rock"), tracks.observeGenres().first().map { it.name })
        assertEquals(listOf(5L, 4L), tracks.observeAlbumTracks(20).first().map { it.id }) // by track number
    }

    @Test
    fun playlistCreateAppendMoveRemoveDelete() = runBlocking {
        val id = playlists.create("Mix", listOf(4, 1, 2), now = 1)
        playlists.append(id, listOf(4), now = 2) // duplicates allowed
        assertEquals(listOf(4L, 1L, 2L, 4L), ids(id))

        playlists.move(id, from = 3, to = 0, now = 3)
        assertEquals(listOf(4L, 4L, 1L, 2L), ids(id))
        assertEquals(listOf(0, 1, 2, 3), playlists.observeTracks(id).first().map { it.position })

        val entry = playlists.observeTracks(id).first()[2].entryId // the "1"
        playlists.removeEntry(id, entry, now = 4)
        assertEquals(listOf(4L, 4L, 2L), ids(id))
        assertEquals(listOf(0, 1, 2), playlists.observeTracks(id).first().map { it.position })

        val summary = playlists.observeSummaries().first().single()
        assertEquals("Mix", summary.name)
        assertEquals(3, summary.trackCount)
        assertEquals(20L, summary.coverAlbumId)

        playlists.rename(id, "Road Trip", now = 5)
        assertEquals("Road Trip", playlists.observe(id).first()?.name)

        playlists.delete(id)
        assertTrue(playlists.observeSummaries().first().isEmpty())
        assertTrue(playlists.entries(id).isEmpty()) // cascade
    }

    @Test
    fun playlistHidesTracksMissingFromLibrary() = runBlocking {
        val id = playlists.create("Mix", listOf(1, 99), now = 1)
        assertEquals(listOf(1L), ids(id))
        assertEquals(1, playlists.observeSummaries().first().single().trackCount)
    }

    @Test
    fun statsFavoritesAndPlayCounts() = runBlocking {
        stats.recordPlay(4, now = 10)
        stats.recordPlay(4, now = 20)
        stats.recordPlay(1, now = 30)
        stats.setFavorite(2, true, now = 40)
        stats.setFavorite(3, true, now = 50)
        stats.setFavorite(3, false, now = 60)

        assertEquals(2, stats.get(4)?.playCount)
        assertEquals(listOf(4L, 1L), tracks.observeMostPlayed(10).first().map { it.id })
        assertEquals(listOf(1L, 4L), tracks.observeRecentlyPlayed(10).first().map { it.id })
        assertEquals(listOf(2L), tracks.observeFavorites().first().map { it.id })
        assertEquals(listOf(2L), stats.observeFavoriteIds().first())
        assertNull(stats.get(5))
    }

    private suspend fun ids(playlistId: Long) = playlists.observeTracks(playlistId).first().map { it.track.id }

    private fun t(
        id: Long, title: String, artist: String, album: String, albumId: Long,
        track: Int, year: Int, added: Long, genre: String?,
    ) = TrackEntity(
        id = id, uri = "content://x/$id", title = title, artist = artist, albumArtist = null, album = album,
        albumId = albumId, trackNumber = track, discNumber = 0, year = year, genre = genre, durationMs = 200_000,
        dateAddedSec = added, relativePath = "Music/AudioHub/", displayName = "$title.mp3",
    )
}
