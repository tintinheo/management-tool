package com.myguitarplayer.data

import com.myguitarplayer.music.Chords
import com.myguitarplayer.music.StrumPattern
import com.myguitarplayer.ui.lesson.DotKind
import com.myguitarplayer.ui.lesson.Fret
import com.myguitarplayer.ui.notes.NoteQuestion
import com.myguitarplayer.ui.notes.NoteQuiz
import com.myguitarplayer.ui.notes.QUIZ_LENGTH
import com.myguitarplayer.ui.notes.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

fun loadTestCurriculum(): Curriculum =
    Curriculum.parse(listOf("src/main/assets/curriculum.json", "app/src/main/assets/curriculum.json").map(::File).first { it.exists() }.readText())

fun emptyRecord() = Record(emptySet(), emptySet(), emptySet(), emptySet(), false, emptySet(), emptyMap(), 0, emptySet())

class CurriculumTest {
    private val cur = loadTestCurriculum()
    private val songDir = listOf("src/main/assets/songs", "app/src/main/assets/songs").map(::File).first { it.isDirectory }

    @Test
    fun eightWeeksOfFiveSessionsThatFitTheDailyTime() {
        assertEquals((1..8).toList(), cur.weeks.map { it.number })
        cur.weeks.forEach { assertEquals("week ${it.number}", 5, it.sessions.size) }
        assertEquals(40, cur.sessions.map { it.id }.toSet().size)
        cur.sessions.forEach { assertTrue("${it.id} = ${it.minutes} min", it.minutes in 20..30) }
    }

    @Test
    fun everyReferenceResolves() {
        val tools = setOf("TUNER", "METRONOME", "CHORD_CHECK", "CHANGES", "NOTES", "QUIZ", "SONG", "RHYTHM", "RECORD")
        val visuals = setOf("strings", "chord", "fretboard", "notes", "press", "strum", "capo")
        for (s in cur.sessions) for (st in s.steps) {
            val where = "${s.id} / ${st.title}"
            st.tool?.let { assertTrue("$where tool $it", it in tools) }
            if (st.tool == "CHORD_CHECK") assertTrue(where, Chords.find(st.chord.orEmpty()) != null)
            if (st.tool == "CHANGES") assertTrue(where, Chords.find(st.chord.orEmpty()) != null && Chords.find(st.chordB.orEmpty()) != null)
            st.song?.let { assertTrue("$where song $it", File(songDir, it).exists()) }
            st.pattern?.let { p -> assertTrue("$where pattern $p", StrumPattern.entries.any { it.name == p || it.strokes == p }) }
            st.visual?.let { v ->
                assertTrue("$where visual ${v.type}", v.type in visuals)
                v.chord?.let { assertTrue("$where visual chord $it", Chords.find(it) != null) }
                if (v.type == "chord") assertTrue(where, v.chord != null)
                v.dots.forEach { d -> assertTrue("$where dot $d", d.s in 1..6 && d.f in 0..v.frets) }
                v.pattern?.let { p -> assertTrue("$where strum $p", p.isNotEmpty() && p.all { it in "DUB." }) }
            }
        }
        for (w in cur.weeks) for (c in w.test) {
            assertTrue("week ${w.number}: ${c.type}", c.type in setOf("tuned", "chord", "changes", "quiz", "rhythm", "rubric", "self"))
            if (c.type == "rhythm") assertTrue(c.text, StrumPattern.entries.any { it.strokes == c.pattern } && c.bpm > 0)
            if (c.type == "rubric") assertTrue(c.text, c.min in 1..3)
            if (c.type == "chord" || c.type == "changes") assertTrue(c.text, Chords.find(c.chord.orEmpty()) != null)
            if (c.type == "changes") assertTrue(c.text, Chords.find(c.chordB.orEmpty()) != null && c.min > 0)
        }
    }

    @Test
    fun chordsAreTaughtBeforeTheyAreTested() {
        val seen = mutableSetOf<String>()
        for (w in cur.weeks) {
            w.sessions.flatMap { it.steps }.forEach { st -> listOfNotNull(st.chord, st.chordB, st.visual?.chord).forEach { seen += it } }
            w.test.forEach { c -> listOfNotNull(c.chord, c.chordB).forEach { assertTrue("week ${w.number} tests $it before teaching it", it in seen) } }
        }
    }

    @Test
    fun courseWalksSessionByThenGatesOnTheTest() {
        var r = emptyRecord()
        var course = Course(cur, r)
        assertEquals(1, course.currentWeek.number)
        assertEquals("w1s1", course.nextSession!!.id)

        r = r.copy(doneSessions = setOf("w1s1", "w1s2"))
        assertEquals("w1s3", Course(cur, r).nextSession!!.id)

        // All week-1 sessions done: no next session until the test is passed.
        r = r.copy(doneSessions = cur.week(1).sessions.map { it.id }.toSet())
        course = Course(cur, r)
        assertNull(course.nextSession)
        assertFalse(course.testMet(cur.week(1)))
        assertFalse(course.isUnlocked(cur.week(2)))

        r = r.copy(tuned = true, chordsPassed = setOf("Em", "Am"), bestQuiz = 8)
        course = Course(cur, r)
        assertTrue(course.testMet(cur.week(1)))

        r = r.copy(passedWeeks = setOf(1))
        course = Course(cur, r)
        assertEquals(2, course.currentWeek.number)
        assertTrue(course.isUnlocked(cur.week(2)))
        assertEquals("w2s1", course.nextSession!!.id)
    }

    @Test
    fun changesAndSelfCriteria() {
        val w2 = cur.week(2)
        val iChanges = w2.test.indexOfFirst { it.type == "changes" }
        val iSelf = w2.test.indexOfFirst { it.type == "self" }
        val base = emptyRecord()
        assertFalse(Course(cur, base.copy(bestChanges = mapOf(setOf("G", "C") to 19))).met(w2, iChanges))
        // Either chord order counts.
        assertTrue(Course(cur, base.copy(bestChanges = mapOf(setOf("G", "C") to 20))).met(w2, iChanges))
        val c = Course(cur, base)
        assertTrue(Course(cur, base.copy(selfChecks = setOf(c.selfKey(w2, iSelf)))).met(w2, iSelf))
    }

    @Test
    fun rhythmAndRubricCriteria() {
        val w3 = cur.week(3)
        val i = w3.test.indexOfFirst { it.type == "rhythm" }
        val base = emptyRecord()
        fun met(vararg r: RhythmResult) = Course(cur, base.copy(rhythm = r.toList())).met(w3, i)
        assertFalse(met())
        assertTrue(met(RhythmResult("DUDUDUDU", 70, 55, 90)))
        assertTrue(met(RhythmResult("DUDUDUDU", 80, 40, 85))) // faster also counts
        assertFalse(met(RhythmResult("DUDUDUDU", 60, 20, 100))) // too slow
        assertFalse(met(RhythmResult("DUDUDUDU", 70, 75, 100))) // too uneven
        assertFalse(met(RhythmResult("DUDUDUDU", 70, 40, 70))) // too many strums missed
        assertFalse(met(RhythmResult("D.DU.UDU", 70, 40, 100))) // other pattern

        val w8 = cur.week(8)
        val j = w8.test.indexOfFirst { it.type == "rubric" }
        assertFalse(Course(cur, base.copy(rubricMins = listOf(1))).met(w8, j))
        assertTrue(Course(cur, base.copy(rubricMins = listOf(1, 2))).met(w8, j))

        // Recordings: only fully rated ones count, with their lowest rating.
        val recs = listOf(
            Recording(file = "a", title = "a", durationMs = 1, rhythm = 3, clean = 2, changes = 2, voice = 3, flow = 2),
            Recording(file = "b", title = "b", durationMs = 1, rhythm = 3),
        )
        assertEquals(listOf(2), ProgressRepo.record(emptyList(), emptyList(), emptyList(), java.time.ZoneOffset.UTC, recordings = recs).rubricMins)
    }

    @Test
    fun progressModelSumsMinutesAndBestChanges() {
        val zone = java.time.ZoneOffset.UTC
        val today = LocalDate.of(2026, 11, 10)
        fun at(daysAgo: Long, hour: Int = 9) = today.minusDays(daysAgo).atTime(hour, 0).toInstant(zone).toEpochMilli()
        val logs = listOf(SessionLog(sessionId = "w1s1", rating = 2, at = at(0)), SessionLog(sessionId = "w1s2", rating = 3, at = at(2)))
        val changes = listOf(
            ChangeScore(chordA = "C", chordB = "G", count = 12, micCount = 12, at = at(2, 9)),
            ChangeScore(chordA = "G", chordB = "C", count = 15, micCount = 14, at = at(2, 10)),
            ChangeScore(chordA = "C", chordB = "G", count = 18, micCount = 18, at = at(0)),
            ChangeScore(chordA = "Em", chordB = "Am", count = 9, micCount = 9, at = at(5)),
        )
        val rhythm = listOf(RhythmScore(bpm = 70, pattern = "DUDUDUDU", bars = 8, meanAbsMs = 48, meanSignedMs = -5, hitPercent = 90, extras = 0, at = at(1)))
        val course = Course(cur, ProgressRepo.record(logs, emptyList(), changes, zone, rhythm))
        val m = com.myguitarplayer.ui.progress.buildProgress(course, logs, changes, rhythm, emptyList(), today, zone)

        assertEquals(2, m.sessions)
        assertEquals(cur.session("w1s1").minutes + cur.session("w1s2").minutes, m.minutes)
        assertEquals(14, m.minutesByDay.size)
        assertEquals(cur.session("w1s1").minutes.toFloat(), m.minutesByDay.last().y)
        assertEquals("T3", m.minutesByDay.last().x) // 2026-11-10 is a Tuesday
        // C-G is the most recent pair; both orders merge; best per day.
        assertEquals("C" to "G", m.pairs.first())
        assertEquals(listOf(15f, 18f), m.changesByPair.getValue("C" to "G").map { it.y })
        assertEquals(20, m.changeTargets[setOf("C", "G")])
        assertEquals(1, m.rhythmPassed)
        assertEquals(listOf(48f), m.rhythm.map { it.y })
    }

    @Test
    fun streakCountsPracticeDaysAndRestDaysBridgeIt() {
        val today = LocalDate.of(2026, 11, 10)
        fun streak(practice: List<Int>, rest: List<Int> = emptyList()) = Course(
            cur,
            emptyRecord().copy(practiceDays = practice.map { today.minusDays(it.toLong()) }.toSet(), restDays = rest.map { today.minusDays(it.toLong()) }.toSet()),
        ).streak(today)

        assertEquals(0, streak(emptyList()))
        assertEquals(3, streak(listOf(0, 1, 2)))
        // Not practised yet today: yesterday's streak still stands.
        assertEquals(2, streak(listOf(1, 2)))
        // A gap breaks it.
        assertEquals(1, streak(listOf(0, 2, 3)))
        // A rest day bridges the gap without counting.
        assertEquals(3, streak(listOf(0, 2, 3), rest = listOf(1)))
    }

    @Test
    fun noteQuizRules() {
        val quiz = NoteQuiz(seed = 42)
        assertEquals(QUIZ_LENGTH, quiz.questions.size)
        quiz.questions.zipWithNext().forEach { (a, b) -> assertTrue(a != b) }
        quiz.questions.forEach { assertFalse(it.name, '#' in it.name); assertTrue(it.fret in 0..3) }

        val g = NoteQuestion(string = 1, fret = 3) // G4
        assertEquals("G", g.name)
        assertEquals(Verdict.RIGHT, quiz.judgeTap(g, 1, 3))
        assertEquals(Verdict.WRONG_STRING, quiz.judgeTap(g, 6, 3)) // G2: right name, wrong place
        assertEquals(Verdict.WRONG, quiz.judgeTap(g, 1, 1))
        assertEquals(Verdict.RIGHT, quiz.judgeMidi(g, 67))
        assertEquals(Verdict.WRONG_STRING, quiz.judgeMidi(g, 55))
        assertEquals(Verdict.WRONG, quiz.judgeMidi(g, 65))
    }

    @Test
    fun chordOnTheNeckNamesEveryNote() {
        val c = Chords["C"]
        val dots = Fret.chordDots(c).associateBy { it.string }
        assertEquals(setOf(1, 2, 3, 4, 5), dots.keys)
        assertEquals("C", dots[5]!!.label)
        assertEquals(DotKind.ROOT, dots[5]!!.kind)
        assertEquals("E", dots[4]!!.label)
        assertEquals("G", dots[3]!!.label)
        assertEquals("C", dots[2]!!.label)
        assertEquals("E", dots[1]!!.label)
        assertEquals(setOf(6), Fret.mutedStrings(c))
        // First position natural notes: string 2 = B C D.
        assertEquals(listOf("B", "C", "D"), Fret.naturalNotes(3, 2..2).map { it.label })
    }
}
