package com.myguitarplayer.data

import androidx.room.AutoMigration
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** One "one-minute changes" round between two chords. */
@Entity(tableName = "change_scores")
data class ChangeScore(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chordA: String,
    val chordB: String,
    /** Changes in 60 s (mic count plus the learner's manual corrections). */
    val count: Int,
    /** Part of [count] the mic detected on its own; the rest are manual taps. */
    val micCount: Int,
    val at: Long = System.currentTimeMillis(),
)

@Dao
interface ChangeDao {
    @Insert
    suspend fun insert(score: ChangeScore): Long

    /** Both orders count as the same pair (C↔G = G↔C). Newest first. */
    @Query("SELECT * FROM change_scores WHERE (chordA = :a AND chordB = :b) OR (chordA = :b AND chordB = :a) ORDER BY at DESC")
    fun forPair(a: String, b: String): Flow<List<ChangeScore>>

    @Query("SELECT * FROM change_scores ORDER BY at DESC")
    fun all(): Flow<List<ChangeScore>>
}

/**
 * A song in the learner's book: ChordPro [body] plus how they like to play it. Bundled samples carry the
 * asset file name in [sample] so they are seeded once and never duplicated.
 */
@Entity(tableName = "songs", indices = [Index(value = ["sample"], unique = true)])
data class SavedSong(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val artist: String = "",
    val body: String,
    /** Semitones the sounding key is moved (to suit the voice). */
    val transpose: Int = 0,
    val capo: Int = 0,
    val bpm: Int = 72,
    /** Auto-scroll speed multiplier. */
    val speed: Float = 1f,
    val sample: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Dao
interface SongDao {
    /** The learner's own songs first (newest edit first), then the samples by title. */
    @Query("SELECT * FROM songs ORDER BY sample IS NOT NULL, CASE WHEN sample IS NULL THEN -updatedAt ELSE 0 END, title")
    fun all(): Flow<List<SavedSong>>

    @Query("SELECT * FROM songs WHERE id = :id")
    fun byId(id: Long): Flow<SavedSong?>

    @Query("SELECT * FROM songs WHERE sample = :file")
    suspend fun bySample(file: String): SavedSong?

    @Query("SELECT sample FROM songs WHERE sample IS NOT NULL")
    suspend fun sampleKeys(): List<String>

    @Insert
    suspend fun insert(song: SavedSong): Long

    @Update
    suspend fun update(song: SavedSong)

    @Delete
    suspend fun delete(song: SavedSong)
}

/** A finished course session. [rating]: 1 = khó, 2 = vừa, 3 = dễ (the learner's own feel). */
@Entity(tableName = "session_logs")
data class SessionLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val rating: Int,
    val at: Long = System.currentTimeMillis(),
)

/**
 * Something the learner achieved or declared, used by the week tests and the streak.
 * [kind] is one of the constants below; [key] / [value] depend on it.
 */
@Entity(tableName = "milestones", indices = [Index(value = ["kind", "key"])])
data class Milestone(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val key: String = "",
    val value: Int = 0,
    val at: Long = System.currentTimeMillis(),
) {
    companion object {
        /** All six strings tuned in one tuner visit. */
        const val TUNED = "tuned"
        /** [key] = chord name passed in Chord Check. */
        const val CHORD = "chord"
        /** [value] = correct answers out of 10 in the note quiz. */
        const val QUIZ = "quiz"
        /** [key] = "w<week>:<criterion index>", ticked by the learner. */
        const val SELF = "self"
        /** [key] = week number whose test was passed (or skipped). */
        const val WEEK = "week"
        /** [key] = ISO date of a rest day. */
        const val REST = "rest"
    }
}

@Dao
interface ProgressDao {
    @Insert
    suspend fun log(entry: SessionLog): Long

    @Query("SELECT * FROM session_logs ORDER BY at DESC")
    fun logs(): Flow<List<SessionLog>>

    @Insert
    suspend fun add(m: Milestone): Long

    @Query("SELECT * FROM milestones ORDER BY at DESC")
    fun milestones(): Flow<List<Milestone>>

    @Query("SELECT COUNT(*) FROM milestones WHERE kind = :kind AND `key` = :key")
    suspend fun count(kind: String, key: String): Int

    @Query("DELETE FROM milestones WHERE kind = :kind AND `key` = :key")
    suspend fun remove(kind: String, key: String)
}

/** One scored strumming take (see RhythmScorer). [pattern] is the stroke string, e.g. "D.DU.UDU". */
@Entity(tableName = "rhythm_scores")
data class RhythmScore(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bpm: Int,
    val pattern: String,
    val bars: Int,
    val meanAbsMs: Int,
    val meanSignedMs: Int,
    /** 0–100: share of the pattern's strokes that were played. */
    val hitPercent: Int,
    val extras: Int,
    val at: Long = System.currentTimeMillis(),
) {
    val passes: Boolean get() = meanAbsMs <= 60 && hitPercent >= 80
}

/**
 * A practice recording (m4a in filesDir/recordings) and the learner's own rubric:
 * each criterion 0 = not rated, 1 = chưa được, 2 = ổn, 3 = tốt.
 */
@Entity(tableName = "recordings")
data class Recording(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val file: String,
    val title: String,
    val durationMs: Long,
    val rhythm: Int = 0,
    val clean: Int = 0,
    val changes: Int = 0,
    val voice: Int = 0,
    val flow: Int = 0,
    val at: Long = System.currentTimeMillis(),
) {
    val ratings: List<Int> get() = listOf(rhythm, clean, changes, voice, flow)
    val rated: Boolean get() = ratings.all { it > 0 }

    fun withRating(index: Int, value: Int): Recording = when (index) {
        0 -> copy(rhythm = value)
        1 -> copy(clean = value)
        2 -> copy(changes = value)
        3 -> copy(voice = value)
        else -> copy(flow = value)
    }

    companion object {
        val CRITERIA = listOf(
            "Nhịp đều" to "Tay phải không dừng, không nhanh dần",
            "Âm sạch" to "Không rè, không dây câm",
            "Đổi hợp âm kịp" to "Hợp âm mới vang đúng phách 1",
            "Giọng hợp tông" to "Không gồng nốt cao, không hụt nốt trầm",
            "Liền mạch" to "Từ intro tới outro không ngắt",
        )
        val LEVELS = listOf("Chưa", "Ổn", "Tốt")
    }
}

@Dao
interface PracticeDao {
    @Insert
    suspend fun addRhythm(s: RhythmScore): Long

    @Query("SELECT * FROM rhythm_scores ORDER BY at DESC")
    fun rhythm(): Flow<List<RhythmScore>>

    @Insert
    suspend fun addRecording(r: Recording): Long

    @Update
    suspend fun updateRecording(r: Recording)

    @Delete
    suspend fun deleteRecording(r: Recording)

    @Query("SELECT * FROM recordings ORDER BY at DESC")
    fun recordings(): Flow<List<Recording>>
}

@Database(
    entities = [ChangeScore::class, SavedSong::class, SessionLog::class, Milestone::class, RhythmScore::class, Recording::class],
    version = 4,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4)],
)
abstract class AppDb : RoomDatabase() {
    abstract fun changes(): ChangeDao
    abstract fun songs(): SongDao
    abstract fun progress(): ProgressDao
    abstract fun practice(): PracticeDao

    companion object {
        /** Database file name; each platform builds the database itself (see Platform.db). */
        const val FILE = "guitar.db"
    }
}
