package com.audiohub.data.db

import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class TrackDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: TrackDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.trackDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun replaceAll_insertsUpdatesAndRemovesStale() = runBlocking {
        dao.replaceAll(listOf(track(1, "Zebra"), track(2, "apple"), track(3, "Mango")))
        assertEquals(listOf("apple", "Mango", "Zebra"), dao.observeAll().first().map { it.title })

        // Track 2 retitled, track 3 deleted from disk, track 4 added.
        dao.replaceAll(listOf(track(1, "Zebra"), track(2, "Banana"), track(4, "Cherry")))
        val after = dao.observeAll().first()
        assertEquals(listOf("Banana", "Cherry", "Zebra"), after.map { it.title })
        assertEquals(setOf(1L, 2L, 4L), after.map { it.id }.toSet())
    }

    @Test
    fun replaceAll_unchangedRescanWritesNothingAndDoesNotNotifyObservers() = runBlocking {
        val tracks = listOf(track(1, "A"), track(2, "B"), track(3, "C"))
        assertEquals(3, dao.replaceAll(tracks))

        // Count emissions of the observed list while rescanning with identical data.
        val emissions = java.util.concurrent.atomic.AtomicInteger()
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            dao.observeAll().collect { emissions.incrementAndGet() }
        }
        kotlinx.coroutines.delay(300)
        val initial = emissions.get()
        assertEquals(0, dao.replaceAll(tracks))
        assertEquals(0, dao.replaceAll(tracks.reversed()))
        kotlinx.coroutines.delay(300)
        assertEquals("no reload for an unchanged rescan", initial, emissions.get())

        assertEquals(1, dao.replaceAll(listOf(track(1, "A"), track(2, "B2"), track(3, "C")))) // one retitle
        kotlinx.coroutines.delay(300)
        assertTrue(emissions.get() > initial)
        job.cancel()
    }

    @Test
    fun replaceAll_withEmptyListClearsTable() = runBlocking {
        dao.replaceAll(listOf(track(1, "A"), track(2, "B")))
        dao.replaceAll(emptyList())
        assertEquals(0, dao.observeAll().first().size)
    }

    @Test
    fun replaceAll_handlesMoreThanSqliteParameterLimit() = runBlocking {
        dao.replaceAll((1L..1500L).map { track(it, "T$it") })
        dao.replaceAll(listOf(track(7, "Only")))
        assertEquals(listOf(7L), dao.observeAll().first().map { it.id })
    }

    private fun track(id: Long, title: String) = TrackEntity(
        id = id,
        uri = "content://media/external/audio/media/$id",
        title = title,
        artist = "Artist",
        albumArtist = null,
        album = "Album",
        albumId = 1,
        trackNumber = 1,
        discNumber = 0,
        year = 2024,
        genre = null,
        durationMs = 180_000,
        dateAddedSec = 0,
        relativePath = "Music/AudioHub/",
        displayName = "$title.mp3",
    )
}
