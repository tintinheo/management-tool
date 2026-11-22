package com.ytsync.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

enum class UploadState {
    /** Known to the app (e.g. given a custom title in the Video tab) but not queued for upload. */
    IDLE,
    QUEUED, UPLOADING, DONE, FAILED, SKIPPED,
    /** Being recorded in the app right now (uploaded live by LiveSession, ignored by the queue worker). */
    RECORDING,
}

/** How a video got into the app. Only AUTO rows are subject to the camera-only rule of the scanner. */
enum class UploadSource {
    /** Found by the DCIM/Camera scanner (camera recordings only). */
    AUTO,
    /** Picked in the Video tab ("Tải lên"). */
    MANUAL,
    /** Sent from another app (Gallery → Send → Youtube Sync). Any video, any folder. */
    SHARE,
    /** Recorded in the app with "Quay & đồng bộ" and uploaded while recording. */
    LIVE,
}

/**
 * One video, keyed by its MediaStore id. Shared videos that aren't in MediaStore (an app's private
 * file) are copied into the app: they get a negative id and [localPath].
 */
@Entity(tableName = "uploads")
data class UploadItem(
    @PrimaryKey val mediaId: Long,
    val displayName: String,
    val sizeBytes: Long,
    val mimeType: String,
    /** Epoch millis the video was RECORDED — see [com.ytsync.media.CameraVideos.recordedAt]. Never the upload time. */
    val takenAt: Long,
    val durationMs: Long = 0,
    val state: UploadState = UploadState.QUEUED,
    val bytesSent: Long = 0,
    /** Resumable session URI from YouTube; valid ~1 week, lets an interrupted upload continue. */
    val sessionUrl: String? = null,
    val videoId: String? = null,
    /** null = not checked, true = YouTube reports ytAgeRestricted, false = it doesn't. */
    val ageRestricted: Boolean? = null,
    val error: String? = null,
    val attempts: Int = 0,
    val updatedAt: Long = System.currentTimeMillis(),
    /** Title chosen by the user for this video (group rename); wins over the auto template. */
    val titleOverride: String? = null,
    /** Title actually sent to YouTube (set when the upload session starts / after a rename). */
    val title: String? = null,
    @ColumnInfo(defaultValue = "AUTO") val source: UploadSource = UploadSource.AUTO,
    /** App-private copy of a shared video that has no MediaStore entry; deleted after upload. */
    val localPath: String? = null,
)

@Dao
interface UploadDao {
    @Query("SELECT * FROM uploads WHERE state != 'IDLE' ORDER BY takenAt DESC LIMIT 200")
    fun observeRecent(): Flow<List<UploadItem>>

    @Query("SELECT * FROM uploads")
    fun observeAll(): Flow<List<UploadItem>>

    /** Recordings the app was in the middle of when its process died. */
    @Query("SELECT * FROM uploads WHERE state = 'RECORDING'")
    suspend fun recordingRows(): List<UploadItem>

    /** Videos the user parked as IDLE that are now inside the auto-sync window get queued. */
    @Query("UPDATE uploads SET state = 'QUEUED', updatedAt = :now WHERE state = 'IDLE' AND mediaId IN (:ids)")
    suspend fun promoteIdle(ids: List<Long>, now: Long = System.currentTimeMillis())

    /** Queue by hand (Video tab / Share): marks the source so the camera-only scanner leaves them alone. */
    @Query("UPDATE uploads SET state = 'QUEUED', source = :source, error = NULL, attempts = 0, updatedAt = :now WHERE mediaId IN (:ids) AND state IN ('IDLE','SKIPPED','FAILED')")
    suspend fun queue(ids: List<Long>, source: UploadSource, now: Long = System.currentTimeMillis())

    @Query("UPDATE uploads SET titleOverride = :title, updatedAt = :now WHERE mediaId = :id")
    suspend fun setTitleOverride(id: Long, title: String?, now: Long = System.currentTimeMillis())

    @Query("UPDATE uploads SET title = :title, titleOverride = :title, updatedAt = :now WHERE mediaId = :id")
    suspend fun setRemoteTitle(id: Long, title: String, now: Long = System.currentTimeMillis())

    @Query("SELECT * FROM uploads WHERE mediaId = :id")
    suspend fun get(id: Long): UploadItem?

    @Query("SELECT mediaId FROM uploads")
    suspend fun knownIds(): List<Long>

    /** Oldest first, so a backlog lands on the channel in recording order. */
    @Query("SELECT * FROM uploads WHERE state IN ('QUEUED','UPLOADING') ORDER BY takenAt ASC LIMIT 1")
    suspend fun nextPending(): UploadItem?

    @Query("SELECT COUNT(*) FROM uploads WHERE state IN ('QUEUED','UPLOADING')")
    suspend fun pendingCount(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNew(items: List<UploadItem>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(item: UploadItem)

    @Query("UPDATE uploads SET bytesSent = :sent, updatedAt = :now WHERE mediaId = :id")
    suspend fun progress(id: Long, sent: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE uploads SET state = 'QUEUED', error = NULL, attempts = 0, updatedAt = :now WHERE mediaId = :id")
    suspend fun retry(id: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE uploads SET state = 'QUEUED', error = NULL, attempts = 0 WHERE state = 'FAILED'")
    suspend fun retryAllFailed()

    @Query("UPDATE uploads SET state = 'SKIPPED', sessionUrl = NULL, updatedAt = :now WHERE mediaId = :id AND state != 'DONE'")
    suspend fun skip(id: Long, now: Long = System.currentTimeMillis())
}

@Database(
    entities = [UploadItem::class],
    version = 3,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2), // 1.1.0: + titleOverride, title
        AutoMigration(from = 2, to = 3), // 1.3.0: + source (default AUTO), localPath
    ],
)
abstract class UploadDb : RoomDatabase() {
    abstract fun dao(): UploadDao

    companion object {
        @Volatile private var instance: UploadDb? = null

        fun get(context: Context): UploadDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, UploadDb::class.java, "uploads.db")
                .build().also { instance = it }
        }
    }
}
