package com.myguitarplayer.data

import com.myguitarplayer.platform.Platform
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate

/** The 8-week course in assets/curriculum.json. */
@Serializable
data class Curriculum(val weeks: List<Week>) {
    val sessions: List<Session> get() = weeks.flatMap { it.sessions }
    fun week(n: Int): Week = weeks.first { it.number == n }
    fun weekOf(sessionId: String): Week = weeks.first { w -> w.sessions.any { it.id == sessionId } }
    fun session(id: String): Session = sessions.first { it.id == id }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(text: String): Curriculum = json.decodeFromString(serializer(), text)

        @Volatile
        private var cached: Curriculum? = null
        fun load(): Curriculum = cached ?: parse(Platform.current.readAsset("curriculum.json")).also { cached = it }
    }
}

@Serializable
data class Week(
    val number: Int,
    val title: String,
    val goal: String,
    val sessions: List<Session>,
    /** All criteria must be met to open the next week. */
    val test: List<Criterion>,
)

@Serializable
data class Session(val id: String, val title: String, val goal: String, val steps: List<Step>) {
    val minutes: Int get() = steps.sumOf { it.minutes }
}

/** One timed step of a session; [tool] opens a practice screen preset with the step's chord / tempo / song. */
@Serializable
data class Step(
    val minutes: Int,
    val title: String,
    val text: String,
    val tool: String? = null,
    val chord: String? = null,
    val chordB: String? = null,
    val bpm: Int? = null,
    val pattern: String? = null,
    val song: String? = null,
    val visual: Visual? = null,
)

/**
 * Illustration for a step. [type]:
 * - "strings": the six open strings with names and numbers
 * - "chord": chord box + the chord laid on the neck with every note named + per-string breakdown
 * - "fretboard": explicit [dots] on the neck (notes to find, finger exercise, bass notes…)
 * - "notes": every natural note on [strings] up to [frets]
 * - "press": where the fingertip goes relative to the fret wire
 * - "strum": a strum [pattern] on its count grid
 * - "capo": the capo on fret [capo] and what it does
 */
@Serializable
data class Visual(
    val type: String,
    val chord: String? = null,
    val pattern: String? = null,
    val frets: Int = 4,
    val capo: Int = 0,
    val strings: List<Int> = listOf(1, 2, 3, 4, 5, 6),
    val dots: List<Dot> = emptyList(),
    val muted: List<Int> = emptyList(),
    val caption: String? = null,
)

/** [s] string 1–6, [f] fret, [root] = draw as the bass/root note. */
@Serializable
data class Dot(val s: Int, val f: Int, val label: String = "", val root: Boolean = false)

/**
 * A week-test item. [type]:
 * - "tuned": all six strings tuned once in the tuner
 * - "chord": [chord] passed in Chord Check (3 correct strums in a row)
 * - "changes": best one-minute changes [chord]↔[chordB] ≥ [min]
 * - "quiz": a note-quiz round with ≥ [min] of 10 correct
 * - "rhythm": a strumming take of [pattern] at ≥ [bpm] within [maxMs] average and ≥ 80 % strokes
 * - "rubric": a recording rated by the learner with every criterion ≥ [min] (2 = ổn, 3 = tốt)
 * - "self": the learner ticks it off honestly
 */
@Serializable
data class Criterion(
    val type: String,
    val text: String,
    val chord: String? = null,
    val chordB: String? = null,
    val min: Int = 0,
    val bpm: Int = 0,
    val pattern: String? = null,
    val maxMs: Int = 60,
)

/** A scored strumming take, reduced to what the rules need. */
data class RhythmResult(val pattern: String, val bpm: Int, val meanAbsMs: Int, val hitPercent: Int)

/** What the learner has done, reduced to plain values so the rules below are easy to test. */
data class Record(
    val doneSessions: Set<String>,
    val practiceDays: Set<LocalDate>,
    val restDays: Set<LocalDate>,
    val passedWeeks: Set<Int>,
    val tuned: Boolean,
    val chordsPassed: Set<String>,
    val bestChanges: Map<Set<String>, Int>,
    val bestQuiz: Int,
    val selfChecks: Set<String>,
    val rhythm: List<RhythmResult> = emptyList(),
    /** For each fully rated recording, its lowest criterion rating. */
    val rubricMins: List<Int> = emptyList(),
)

/** Course rules: which week is open, what to do next, whether a test is met, and the streak. */
class Course(val curriculum: Curriculum, val record: Record) {
    /** The first week not passed yet (the last week once everything is passed). */
    val currentWeek: Week = curriculum.weeks.firstOrNull { it.number !in record.passedWeeks } ?: curriculum.weeks.last()

    val finished: Boolean get() = curriculum.weeks.all { it.number in record.passedWeeks }

    fun isUnlocked(week: Week): Boolean = week.number <= currentWeek.number

    /** Next session to do in the current week; null when its sessions are all done (time for the test). */
    val nextSession: Session? get() = if (finished) null else currentWeek.sessions.firstOrNull { it.id !in record.doneSessions }

    fun doneIn(week: Week): Int = week.sessions.count { it.id in record.doneSessions }

    fun selfKey(week: Week, index: Int) = "w${week.number}:$index"

    fun met(week: Week, index: Int): Boolean {
        val c = week.test[index]
        return when (c.type) {
            "tuned" -> record.tuned
            "chord" -> c.chord in record.chordsPassed
            "changes" -> (record.bestChanges[setOf(c.chord, c.chordB)] ?: 0) >= c.min
            "quiz" -> record.bestQuiz >= c.min
            "rhythm" -> record.rhythm.any { it.pattern == c.pattern && it.bpm >= c.bpm && it.meanAbsMs <= c.maxMs && it.hitPercent >= 80 }
            "rubric" -> record.rubricMins.any { it >= c.min }
            else -> selfKey(week, index) in record.selfChecks
        }
    }

    fun testMet(week: Week): Boolean = week.test.indices.all { met(week, it) }

    /**
     * Days in a row with practice, counting back from today (or yesterday, if today isn't done yet).
     * A rest day ("nghỉ tay" — sore fingertips are normal) keeps the streak alive without adding to it.
     */
    fun streak(today: LocalDate): Int {
        val active = record.practiceDays + record.restDays
        var day = if (today in active) today else today.minusDays(1)
        var n = 0
        while (day in active) {
            if (day in record.practiceDays) n++
            day = day.minusDays(1)
        }
        return n
    }
}
