package com.snapbrain.data

import android.content.Context
import androidx.room.Room
import com.snapbrain.data.content.CardRepository
import com.snapbrain.data.db.AppDatabase
import com.snapbrain.data.db.InterceptEvent
import com.snapbrain.domain.Rules
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class StudyRepositoryTest {

    private class FakeClock(var ms: Long) : AppClock() {
        override fun now() = ms
    }

    private lateinit var db: AppDatabase
    private lateinit var repo: StudyRepository
    private lateinit var settings: SettingsStore
    private val clock = FakeClock(1_800_000_000_000L)
    private val day = 86_400_000L

    @Before
    fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
        settings = SettingsStore(app.getSharedPreferences("test", Context.MODE_PRIVATE).also { it.edit().clear().commit() })
        val cards = CardRepository({ File("src/main/assets/cards.json").readText() })
        repo = StudyRepository(db.studyDao(), cards, clock, settings)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun correctAnswer_earnsOncePerDay_andCountsTheDay() = runBlocking {
        val card = repo.cards.all.first()
        assertEquals(1, repo.answer(card, true, Source.FEED).points)
        assertEquals(0, repo.answer(card, true, Source.FEED).points)
        assertEquals(1, repo.balance.first())
        assertTrue(repo.streakNow().studiedToday)

        clock.ms += day
        assertEquals(1, repo.answer(card, true, Source.WIDGET).points)
        assertEquals(2, repo.balance.first())
        assertEquals(2, repo.streakNow().current)
    }

    @Test
    fun wrongAnswer_goesBackToBoxZero_andIsReviewedFirst() = runBlocking {
        val (a, b) = repo.cards.all.take(2)
        repo.answer(a, true, Source.FEED)
        repo.answer(b, true, Source.FEED)
        assertEquals(0, repo.answer(a, false, Source.FEED).box)
        assertEquals(a.id, repo.review(limit = 2).first().id)
    }

    @Test
    fun tickets_costPoints_andExtendFreeTime() = runBlocking {
        assertFalse(repo.buyTickets(1))
        repo.cards.all.take(Rules.POINTS_PER_TICKET).forEach { repo.answer(it, true, Source.FEED) }
        assertTrue(repo.buyTickets(1))
        assertEquals(0, repo.balance.first())
        assertEquals(clock.ms + Rules.TICKET_MINUTES * 60_000L, settings.value.freeUntil)
        assertTrue(settings.value.freeIsTicket)
    }

    @Test
    fun streakSaver_repairsYesterdayOnly() = runBlocking {
        val card = repo.cards.all.first()
        repo.answer(card, true, Source.FEED)          // day 0
        clock.ms += day
        repo.answer(card, true, Source.FEED)          // day 1
        clock.ms += 2 * day                           // day 3: day 2 missed
        assertEquals(2, repo.streakNow().brokenLength)
        repo.answer(card, true, Source.SAVER)
        assertTrue(repo.repairYesterday())
        assertEquals(4, repo.streakNow().current)
        assertFalse(repo.repairYesterday())
    }

    @Test
    fun interceptStats() = runBlocking {
        repo.logIntercept("tiktok", InterceptEvent.LEARNED)
        repo.logIntercept("tiktok", InterceptEvent.SKIPPED)
        repo.logIntercept("fb", InterceptEvent.CLOSED)
        val stats = repo.allTimeStats.first()
        assertEquals(3, stats.intercepted)
        assertEquals(1, stats.learned)
        assertEquals(1, stats.closed)
    }
}
