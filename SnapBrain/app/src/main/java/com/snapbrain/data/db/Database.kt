package com.snapbrain.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Learning state of one card. Cards without a row have never been seen. */
@Entity(tableName = "card_progress")
data class CardProgress(
    @PrimaryKey val cardId: String,
    val box: Int,
    val dueDay: Long,
    val seen: Int,
    val correct: Int,
    val wrong: Int,
    val lastAnsweredAt: Long,
    val lastPointDay: Long?,
)

/** Point ledger: + for correct answers, − for tickets. Balance = sum. */
@Entity(tableName = "point_event")
data class PointEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val delta: Int,
    val reason: String,
)

/** One day with at least one answered card (or a repaired day). Drives the streak. */
@Entity(tableName = "study_day")
data class StudyDay(
    @PrimaryKey val day: Long,
    val answers: Int,
    val correct: Int,
    val repaired: Boolean = false,
)

/** Every time the guard covered a blocked app, and what the user chose. */
@Entity(tableName = "intercept_event")
data class InterceptEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val day: Long,
    val pkg: String,
    val outcome: String,
) {
    companion object {
        const val LEARNED = "learned"
        const val SKIPPED = "skipped"
        const val CLOSED = "closed"   // left the app instead of scrolling
        const val TICKET = "ticket"
    }
}

data class OutcomeCount(val outcome: String, val n: Int)

@Dao
interface StudyDao {
    @Query("SELECT * FROM card_progress")
    suspend fun allProgress(): List<CardProgress>

    @Query("SELECT * FROM card_progress")
    fun observeProgress(): Flow<List<CardProgress>>

    @Query("SELECT * FROM card_progress WHERE cardId = :id")
    suspend fun progress(id: String): CardProgress?

    @Upsert
    suspend fun upsertProgress(p: CardProgress)

    @Insert
    suspend fun insertPoint(e: PointEvent)

    @Query("SELECT COALESCE(SUM(delta), 0) FROM point_event")
    fun observeBalance(): Flow<Int>

    @Query("SELECT COALESCE(SUM(delta), 0) FROM point_event")
    suspend fun balance(): Int

    @Query("SELECT * FROM study_day WHERE day = :day")
    suspend fun studyDay(day: Long): StudyDay?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putStudyDay(d: StudyDay)

    @Query("SELECT day FROM study_day")
    fun observeStudyDays(): Flow<List<Long>>

    @Query("SELECT day FROM study_day")
    suspend fun studyDays(): List<Long>

    @Insert
    suspend fun insertIntercept(e: InterceptEvent)

    @Query("SELECT outcome, COUNT(*) AS n FROM intercept_event WHERE day = :day GROUP BY outcome")
    fun observeOutcomes(day: Long): Flow<List<OutcomeCount>>

    @Query("SELECT outcome, COUNT(*) AS n FROM intercept_event GROUP BY outcome")
    fun observeAllOutcomes(): Flow<List<OutcomeCount>>
}

@Database(
    entities = [CardProgress::class, PointEvent::class, StudyDay::class, InterceptEvent::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun studyDao(): StudyDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "snapbrain.db").build()
    }
}
