package com.myguitarplayer.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Course progress: session logs + milestones + change scores → [Course]. */
class ProgressRepo(private val db: AppDb, val curriculum: Curriculum) {
    private val dao = db.progress()

    fun course(zone: ZoneId = ZoneId.systemDefault()): Flow<Course> =
        combine(dao.logs(), dao.milestones(), db.changes().all(), db.practice().rhythm(), db.practice().recordings()) { logs, ms, changes, rhythm, recs ->
            Course(curriculum, record(logs, ms, changes, zone, rhythm, recs))
        }

    suspend fun finishSession(sessionId: String, rating: Int) = withContext(Dispatchers.IO) {
        dao.log(SessionLog(sessionId = sessionId, rating = rating))
    }

    /** Records a milestone; [once] skips it when the same kind + key is already there. */
    suspend fun mark(kind: String, key: String = "", value: Int = 0, once: Boolean = true) = withContext(Dispatchers.IO) {
        if (!once || dao.count(kind, key) == 0) dao.add(Milestone(kind = kind, key = key, value = value))
    }

    suspend fun setSelfCheck(key: String, done: Boolean) = withContext(Dispatchers.IO) {
        if (done) mark(Milestone.SELF, key) else dao.remove(Milestone.SELF, key)
    }

    suspend fun restToday(today: LocalDate = LocalDate.now()) = mark(Milestone.REST, today.toString())

    suspend fun passWeek(number: Int) = mark(Milestone.WEEK, number.toString())

    companion object {
        fun record(
            logs: List<SessionLog>,
            ms: List<Milestone>,
            changes: List<ChangeScore>,
            zone: ZoneId,
            rhythm: List<RhythmScore> = emptyList(),
            recordings: List<Recording> = emptyList(),
        ): Record {
            fun day(at: Long) = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
            fun keys(kind: String) = ms.filter { it.kind == kind }.map { it.key }.toSet()
            return Record(
                doneSessions = logs.map { it.sessionId }.toSet(),
                practiceDays = logs.map { day(it.at) }.toSet(),
                restDays = keys(Milestone.REST).mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.toSet(),
                passedWeeks = keys(Milestone.WEEK).mapNotNull { it.toIntOrNull() }.toSet(),
                tuned = ms.any { it.kind == Milestone.TUNED },
                chordsPassed = keys(Milestone.CHORD),
                bestChanges = changes.groupBy { setOf(it.chordA, it.chordB) }.mapValues { (_, v) -> v.maxOf { it.count } },
                bestQuiz = ms.filter { it.kind == Milestone.QUIZ }.maxOfOrNull { it.value } ?: 0,
                selfChecks = keys(Milestone.SELF),
                rhythm = rhythm.map { RhythmResult(it.pattern, it.bpm, it.meanAbsMs, it.hitPercent) },
                rubricMins = recordings.filter { it.rated }.map { it.ratings.min() },
            )
        }
    }
}
