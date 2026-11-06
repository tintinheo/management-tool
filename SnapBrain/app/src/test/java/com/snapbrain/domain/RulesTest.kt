package com.snapbrain.domain

import com.snapbrain.data.content.Card
import com.snapbrain.data.content.Mcq
import com.snapbrain.data.content.Topic
import com.snapbrain.data.content.TrueFalse
import com.snapbrain.data.content.Visual
import com.snapbrain.data.content.VisualType
import com.snapbrain.data.db.CardProgress
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesTest {

    @Test
    fun srs_climbsOnCorrect_dropsOnWrong() {
        assertEquals(Srs.Step(1, 101), Srs.next(null, correct = true, today = 100))
        assertEquals(Srs.Step(3, 107), Srs.next(2, correct = true, today = 100))
        assertEquals(Srs.Step(5, 160), Srs.next(5, correct = true, today = 100))
        assertEquals(Srs.Step(0, 101), Srs.next(4, correct = false, today = 100))
    }

    @Test
    fun points_onlyFirstCorrectPerCardPerDay() {
        assertEquals(1, Rules.pointsFor(true, lastPointDay = null, today = 10))
        assertEquals(1, Rules.pointsFor(true, lastPointDay = 9, today = 10))
        assertEquals(0, Rules.pointsFor(true, lastPointDay = 10, today = 10))
        assertEquals(0, Rules.pointsFor(false, lastPointDay = null, today = 10))
    }

    @Test
    fun skips_resetDaily_noneInStrictMode() {
        assertEquals(3, Rules.skipsLeft(strict = false, usedDay = -1, used = 0, today = 10))
        assertEquals(1, Rules.skipsLeft(strict = false, usedDay = 10, used = 2, today = 10))
        assertEquals(0, Rules.skipsLeft(strict = false, usedDay = 10, used = 5, today = 10))
        assertEquals(3, Rules.skipsLeft(strict = false, usedDay = 9, used = 3, today = 10))
        assertEquals(0, Rules.skipsLeft(strict = true, usedDay = -1, used = 0, today = 10))
    }

    @Test
    fun freeTime_ticketsStack_passNeverShortens() {
        val min = 60_000L
        assertEquals(1_000 + 10 * min, Rules.extendFreeTime(freeUntil = 0, now = 1_000, tickets = 1))
        assertEquals(5_000 + 20 * min, Rules.extendFreeTime(freeUntil = 5_000, now = 1_000, tickets = 2))
        assertEquals(1_000 + 5 * min, Rules.pass(freeUntil = 0, now = 1_000))
        assertEquals(1_000 + 30 * min, Rules.pass(freeUntil = 1_000 + 30 * min, now = 1_000))
    }

    @Test
    fun streak_countsFromTodayOrYesterday() {
        val s1 = Streak.compute(setOf(8, 9, 10), today = 10)
        assertEquals(3, s1.current)
        assertTrue(s1.studiedToday)
        assertNull(s1.repairableDay)

        val s2 = Streak.compute(setOf(8, 9), today = 10) // not studied yet today: still alive
        assertEquals(2, s2.current)
        assertFalse(s2.studiedToday)
        assertNull(s2.repairableDay)
    }

    @Test
    fun streak_missedYesterday_isRepairable() {
        val s = Streak.compute(setOf(5, 6, 7, 8), today = 10)
        assertEquals(0, s.current)
        assertEquals(9L, s.repairableDay)
        assertEquals(4, s.brokenLength)

        // Studied today already: current counts only today, yesterday is still repairable.
        val s2 = Streak.compute(setOf(7, 8, 10), today = 10)
        assertEquals(1, s2.current)
        assertEquals(9L, s2.repairableDay)
        assertEquals(2, s2.brokenLength)
        // Two missed days: gone for good.
        assertNull(Streak.compute(setOf(6, 7), today = 10).repairableDay)
    }

    @Test
    fun intercept_policy() {
        val blocked = setOf("tiktok")
        assertEquals(InterceptPolicy.Decision.Allow, InterceptPolicy.decide("maps", 100, blocked, 0))
        assertEquals(InterceptPolicy.Decision.Intercept, InterceptPolicy.decide("tiktok", 100, blocked, 0))
        assertEquals(InterceptPolicy.Decision.Intercept, InterceptPolicy.decide("tiktok", 100, blocked, 100))
        assertEquals(InterceptPolicy.Decision.AllowUntil(500), InterceptPolicy.decide("tiktok", 100, blocked, 500))
        assertTrue(InterceptPolicy.isTransient("com.android.systemui", "com.snapbrain", emptySet()))
        assertTrue(InterceptPolicy.isTransient("com.snapbrain", "com.snapbrain", emptySet()))
        assertTrue(InterceptPolicy.isTransient("com.gboard", "com.snapbrain", setOf("com.gboard")))
        assertFalse(InterceptPolicy.isTransient("com.miui.home", "com.snapbrain", emptySet()))
    }

    @Test
    fun feed_dueFirst_thenNewMixed_thenRest() {
        val cards = Topic.entries.flatMap { t -> (1..3).map { card("${t.name}$it", t) } }
        val progress = mapOf(
            "OFFICE1" to progress("OFFICE1", box = 0, due = 9),
            "OFFICE2" to progress("OFFICE2", box = 2, due = 5),
            "AI1" to progress("AI1", box = 3, due = 20),
        )
        val plan = FeedPlanner.plan(cards, progress, today = 10, favorites = emptySet(), limit = 100, random = Random(1))
        assertEquals(listOf("OFFICE2", "OFFICE1"), plan.take(2).map { it.id })
        assertEquals("AI1", plan.last().id)
        assertEquals(cards.size, plan.size)
        // New cards: no two neighbours share a topic while several topics remain.
        val fresh = plan.subList(2, 2 + 6)
        assertEquals(6, fresh.map { it.topic }.toSet().size)
    }

    @Test
    fun feed_favoritesFilter() {
        val cards = listOf(card("a", Topic.AI), card("b", Topic.FINANCE))
        val plan = FeedPlanner.plan(cards, emptyMap(), 10, favorites = setOf(Topic.FINANCE), limit = 10)
        assertEquals(listOf("b"), plan.map { it.id })
    }

    private fun card(id: String, topic: Topic) = Card(
        id, topic, "hook", listOf("a", "b", "c"), Visual(VisualType.EMOJI, listOf("x")),
        TrueFalse("s", true, "e"), Mcq("q", listOf("1", "2", "3"), 0),
    )

    private fun progress(id: String, box: Int, due: Long) =
        CardProgress(id, box, due, seen = 1, correct = 0, wrong = 0, lastAnsweredAt = 0, lastPointDay = null)
}
