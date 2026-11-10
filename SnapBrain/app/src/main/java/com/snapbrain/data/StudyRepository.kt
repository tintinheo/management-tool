package com.snapbrain.data

import com.snapbrain.data.content.Card
import com.snapbrain.data.content.CardRepository
import com.snapbrain.data.db.CardProgress
import com.snapbrain.data.db.InterceptEvent
import com.snapbrain.data.db.OutcomeCount
import com.snapbrain.data.db.PointEvent
import com.snapbrain.data.db.StudyDao
import com.snapbrain.data.db.StudyDay
import com.snapbrain.domain.FeedPlanner
import com.snapbrain.domain.Rules
import com.snapbrain.domain.Srs
import com.snapbrain.domain.Streak
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class Source { FEED, INTERCEPT, WIDGET, SAVER }

data class AnswerResult(val points: Int, val box: Int)

/** Stats for the home screen. */
data class DayStats(val intercepted: Int, val learned: Int, val skipped: Int, val closed: Int) {
    companion object {
        fun from(rows: List<OutcomeCount>): DayStats {
            val m = rows.associate { it.outcome to it.n }
            val learned = m[InterceptEvent.LEARNED] ?: 0
            val skipped = m[InterceptEvent.SKIPPED] ?: 0
            val closed = m[InterceptEvent.CLOSED] ?: 0
            val ticket = m[InterceptEvent.TICKET] ?: 0
            return DayStats(learned + skipped + closed + ticket, learned, skipped, closed)
        }
    }
}

class StudyRepository(
    private val dao: StudyDao,
    val cards: CardRepository,
    private val clock: AppClock,
    private val settings: SettingsStore,
) {
    private val lock = Mutex()

    val balance: Flow<Int> = dao.observeBalance()

    val streak: Flow<Streak.Status> = dao.observeStudyDays().map { Streak.compute(it.toSet(), clock.today()) }

    @OptIn(ExperimentalCoroutinesApi::class)
    val todayStats: Flow<DayStats> = flow {
        while (true) {
            emit(clock.today())
            delay(60_000)
        }
    }.distinctUntilChanged()
        .flatMapLatest { day -> dao.observeOutcomes(day) }
        .map(DayStats::from)

    val allTimeStats: Flow<DayStats> = dao.observeAllOutcomes().map(DayStats::from)

    /** Card id → progress, for the stats screen. */
    val progress: Flow<Map<String, CardProgress>> = dao.observeProgress().map { list -> list.associateBy { it.cardId } }

    val masteredCount: Flow<Int> = progress.map { p -> p.values.count { it.box >= 3 } }

    suspend fun feed(limit: Int = 40, exclude: Set<String> = emptySet()): List<Card> =
        FeedPlanner.plan(cards.all, progressMap(), clock.today(), settings.value.topics, limit, exclude = exclude)

    suspend fun nextCard(excluding: Set<String> = emptySet()): Card {
        val plan = FeedPlanner.plan(cards.all, progressMap(), clock.today(), settings.value.topics, limit = 10)
        return plan.firstOrNull { it.id !in excluding } ?: plan.firstOrNull() ?: cards.all.random()
    }

    suspend fun review(limit: Int = 12): List<Card> = FeedPlanner.review(cards.all, progressMap(), limit)

    private suspend fun progressMap() = dao.allProgress().associateBy { it.cardId }

    suspend fun answer(card: Card, correct: Boolean, source: Source): AnswerResult = lock.withLock {
        val now = clock.now()
        val today = clock.dayOf(now)
        val old = dao.progress(card.id)
        val step = Srs.next(old?.box, correct, today)
        val points = Rules.pointsFor(correct, old?.lastPointDay, today)
        dao.upsertProgress(
            CardProgress(
                cardId = card.id,
                box = step.box,
                dueDay = step.dueDay,
                seen = (old?.seen ?: 0) + 1,
                correct = (old?.correct ?: 0) + if (correct) 1 else 0,
                wrong = (old?.wrong ?: 0) + if (correct) 0 else 1,
                lastAnsweredAt = now,
                lastPointDay = if (points > 0) today else old?.lastPointDay,
            ),
        )
        if (points > 0) dao.insertPoint(PointEvent(at = now, delta = points, reason = "correct:${source.name.lowercase()}"))
        val day = dao.studyDay(today)
        dao.putStudyDay(
            StudyDay(
                day = today,
                answers = (day?.answers ?: 0) + 1,
                correct = (day?.correct ?: 0) + if (correct) 1 else 0,
                repaired = day?.repaired ?: false,
            ),
        )
        AnswerResult(points, step.box)
    }

    /** Spends points on free scrolling time. Returns false if the balance is too low. */
    suspend fun buyTickets(count: Int): Boolean = lock.withLock {
        val cost = count * Rules.POINTS_PER_TICKET
        if (count <= 0 || dao.balance() < cost) return@withLock false
        val now = clock.now()
        dao.insertPoint(PointEvent(at = now, delta = -cost, reason = "ticket:$count"))
        settings.update { it.copy(freeUntil = Rules.extendFreeTime(it.freeUntil, now, count), freeIsTicket = true) }
        true
    }

    /** Marks yesterday as studied after a successful streak-saver run. */
    suspend fun repairYesterday(): Boolean = lock.withLock {
        val status = Streak.compute(dao.studyDays().toSet(), clock.today())
        val day = status.repairableDay ?: return@withLock false
        dao.putStudyDay(StudyDay(day = day, answers = 0, correct = 0, repaired = true))
        true
    }

    suspend fun streakNow(): Streak.Status = Streak.compute(dao.studyDays().toSet(), clock.today())

    suspend fun logIntercept(pkg: String, outcome: String) {
        val now = clock.now()
        dao.insertIntercept(InterceptEvent(at = now, day = clock.dayOf(now), pkg = pkg, outcome = outcome))
    }
}
