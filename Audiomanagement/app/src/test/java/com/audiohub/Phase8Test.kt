package com.audiohub

import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.room.Room
import com.audiohub.data.db.AppDatabase
import com.audiohub.data.db.EpisodeEntity
import com.audiohub.data.db.PodcastEntity
import com.audiohub.data.podcast.NotesRepository
import com.audiohub.data.podcast.NotesRepository.EpisodeHeader
import com.audiohub.ui.theme.AppFonts
import com.audiohub.ui.theme.ArtMotion
import com.audiohub.ui.theme.UiStyle
import com.audiohub.ui.theme.tokens
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

/** Appearance (styles, fonts) and podcast recall notes. */
@RunWith(RobolectricTestRunner::class)
class Phase8Test {

    private lateinit var db: AppDatabase
    private var now = 1_000_000_000_000L
    private lateinit var notes: NotesRepository
    private var showId = 0L
    private lateinit var episodeIds: List<Long>

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        notes = NotesRepository(db.episodeNoteDao()) { now }
        val podcasts = db.podcastDao()
        showId = podcasts.insertPodcast(
            PodcastEntity(feedUrl = "https://example.com/feed", itunesId = null, title = "Cà Phê Sáng", author = "Host",
                description = "", artworkUrl = null, websiteUrl = null, subscribedAt = 0),
        )
        episodeIds = podcasts.insertEpisodes(
            (1..3).map { i ->
                EpisodeEntity(podcastId = showId, guid = "g$i", title = "Tập $i", description = "<p>Notes $i</p>",
                    pubDate = i.toLong(), durationMs = 60_000, enclosureUrl = "https://example.com/$i.mp3",
                    enclosureType = null, enclosureLength = 0, artworkUrl = null, season = null, episodeNumber = i)
            },
        )
        Unit
    }

    @After
    fun tearDown() = db.close()

    private fun header(i: Int) = EpisodeHeader(episodeIds[i], "Tập ${i + 1}", "Cà Phê Sáng", null)

    @Test
    fun finishedEpisodesAwaitRecallUntilAnsweredOrSkipped() = runBlocking {
        val podcasts = db.podcastDao()
        assertTrue(notes.pendingRecall().first().isEmpty())

        podcasts.setPlayed(episodeIds[0], true, now - 1_000)
        podcasts.setPlayed(episodeIds[1], true, now - 2_000)
        podcasts.setPlayed(episodeIds[2], true, now - 30 * NotesRepository.DAY_MS) // too long ago
        assertEquals(listOf(episodeIds[0], episodeIds[1]), notes.pendingRecall().first().map { it.episode.id })

        notes.save(header(0), mainIdeas = "  Thói quen buổi sáng ", highlight = "", takeaway = "", notes = "", recallScore = 3)
        notes.skip(header(1))
        assertTrue(notes.pendingRecall().first().isEmpty())

        // Skipped prompts don't show up as notes; answered ones do, trimmed.
        val all = notes.all.first()
        assertEquals(listOf(episodeIds[0]), all.map { it.episodeId })
        assertEquals("Thói quen buổi sáng", all.single().mainIdeas)
        assertEquals(3, all.single().recallScore)
    }

    @Test
    fun clearingEverythingDeletesTheNoteAndNotesOutliveTheShow() = runBlocking {
        notes.save(header(0), "Ideas", "", "", "", 0)
        notes.save(header(2), "", "", "", "Keep this", 2)
        notes.save(header(0), "", "", "", "", 0)
        assertNull(notes.get(episodeIds[0]))

        db.podcastDao().deletePodcast(showId) // unfollow: episodes go, the note stays
        val kept = notes.all.first().single()
        assertEquals("Keep this", kept.notes)
        assertEquals("Tập 3", kept.episodeTitle)
    }

    @Test
    fun unchangedSaveKeepsTimestamp() = runBlocking {
        notes.save(header(0), "A", "", "", "", 0)
        val first = notes.get(episodeIds[0])!!.updatedAt
        now += 5_000
        notes.save(header(0), "A", "", "", "", 0)
        assertEquals(first, notes.get(episodeIds[0])!!.updatedAt)
        now += 5_000
        notes.save(header(0), "AB", "", "", "", 0)
        assertEquals(now, notes.get(episodeIds[0])!!.updatedAt)
        assertEquals(first, notes.get(episodeIds[0])!!.createdAt)
    }

    @Test
    fun fontFilesAreGroupedByFamilyAndStyle() {
        assertEquals("PlayfairDisplay", AppFonts.familyKey("PlayfairDisplay-SemiBoldItalic.ttf"))
        assertEquals("Playfair Display", AppFonts.displayName("PlayfairDisplay"))
        assertEquals("Be Vietnam Pro", AppFonts.displayName("BeVietnamPro"))
        assertEquals("Cormorant Garamond", AppFonts.displayName("CormorantGaramond"))
        assertEquals(FontWeight.SemiBold to FontStyle.Italic, AppFonts.weightAndStyle("PlayfairDisplay-SemiBoldItalic.ttf"))
        assertEquals(FontWeight.Normal to FontStyle.Italic, AppFonts.weightAndStyle("Lora-Italic.ttf"))
        assertEquals(FontWeight.Normal to FontStyle.Normal, AppFonts.weightAndStyle("Lora-Regular.ttf"))
        assertEquals(FontWeight.Light to FontStyle.Normal, AppFonts.weightAndStyle("Montserrat-Light.ttf"))

        // System fonts are always offered; an unknown id falls back to the style's font.
        val all = AppFonts.all(RuntimeEnvironment.getApplication().assets)
        assertTrue(all.any { it.id == "sys:serif" })
        assertNull(AppFonts.resolve(RuntimeEnvironment.getApplication().assets, "asset:Missing"))
    }

    @Test
    fun stylesHaveDistinctDefaults() {
        assertEquals(UiStyle.Atelier, UiStyle.fromId(""))
        assertEquals(UiStyle.Noir, UiStyle.fromId("noir"))
        assertEquals(3, listOf(UiStyle.Atelier, UiStyle.Noir, UiStyle.Riviera).map { it.defaultMotion }.toSet().size)
        // The layout styles each arrange Now Playing, the tab bar and the mini player in their own way.
        val layouts = listOf(UiStyle.Washi, UiStyle.Bordeaux, UiStyle.Glacier).map { it.tokens() }
        assertEquals(3, layouts.map { it.nowPlaying }.toSet().size)
        assertTrue(layouts.none { it.nowPlaying == com.audiohub.ui.theme.NowPlayingLayout.Classic })
        assertEquals(3, layouts.map { it.navStyle to it.miniStyle }.toSet().size)
        assertEquals(ArtMotion.Spin, ArtMotion.fromId("spin"))
        assertNull(ArtMotion.fromId(""))
    }
}
