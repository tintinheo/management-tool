package com.audiohub.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * One audiobook = one folder in the audiobook folder (or one loose file in it). Listening progress
 * lives here and survives rescans because books are matched by [folderKey].
 */
@Entity(tableName = "books", indices = [Index(value = ["folderKey"], unique = true), Index("lastPlayedAt")])
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Folder name under the audiobook root, or "file:<name>" for a loose file. */
    val folderKey: String,
    val title: String,
    val author: String,
    val artworkUri: String?,
    val chapterCount: Int,
    val durationMs: Long,
    val addedAt: Long,
    /** MediaStore id of the chapter to resume (0 = start). */
    val currentFileId: Long = 0,
    /** Position inside that chapter. */
    val positionMs: Long = 0,
    /** Total listened time across chapters (for the progress bar). */
    val listenedMs: Long = 0,
    val lastPlayedAt: Long = 0,
    val finished: Boolean = false,
) {
    val progress: Float get() = if (finished) 1f else if (durationMs > 0) (listenedMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val started: Boolean get() = !finished && (listenedMs > 0 || currentFileId != 0L)
}

@Entity(
    tableName = "book_chapters",
    foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["bookId", "position"])],
)
data class BookChapterEntity(
    /** MediaStore id of the file. */
    @PrimaryKey val fileId: Long,
    val bookId: Long,
    /** 0-based play order inside the book. */
    val position: Int,
    val title: String,
    val uri: String,
    val durationMs: Long,
)

@Dao
abstract class BookDao {
    @Query("SELECT * FROM books ORDER BY title COLLATE NOCASE")
    abstract fun observeBooks(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id")
    abstract fun observeBook(id: Long): Flow<BookEntity?>

    @Query("SELECT * FROM books WHERE id = :id")
    abstract suspend fun book(id: Long): BookEntity?

    @Query("SELECT * FROM books")
    abstract suspend fun allBooks(): List<BookEntity>

    @Query("SELECT * FROM book_chapters WHERE bookId = :bookId ORDER BY position")
    abstract fun observeChapters(bookId: Long): Flow<List<BookChapterEntity>>

    @Query("SELECT * FROM book_chapters WHERE bookId = :bookId ORDER BY position")
    abstract suspend fun chapters(bookId: Long): List<BookChapterEntity>

    @Query("SELECT * FROM book_chapters WHERE fileId = :fileId")
    abstract suspend fun chapter(fileId: Long): BookChapterEntity?

    @Query("SELECT COALESCE(SUM(durationMs), 0) FROM book_chapters WHERE bookId = :bookId AND position < :position")
    abstract suspend fun durationBefore(bookId: Long, position: Int): Long

    @Insert
    abstract suspend fun insertBook(book: BookEntity): Long

    @Update
    abstract suspend fun updateBook(book: BookEntity)

    @Query("DELETE FROM books WHERE id = :id")
    abstract suspend fun deleteBook(id: Long)

    @Query("DELETE FROM book_chapters WHERE bookId = :bookId")
    abstract suspend fun deleteChapters(bookId: Long)

    /** REPLACE: a file moved from one book folder to another keeps its MediaStore id. */
    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    abstract suspend fun insertChapters(chapters: List<BookChapterEntity>)

    @Transaction
    open suspend fun replaceChapters(bookId: Long, chapters: List<BookChapterEntity>) {
        deleteChapters(bookId)
        insertChapters(chapters)
    }

    @Query(
        "UPDATE books SET currentFileId = :fileId, positionMs = :positionMs, listenedMs = :listenedMs, " +
            "lastPlayedAt = :now, finished = 0 WHERE id = :bookId",
    )
    abstract suspend fun saveProgress(bookId: Long, fileId: Long, positionMs: Long, listenedMs: Long, now: Long)

    /** Finished → listened in full, next play starts over; unfinished → back to the start. */
    @Query(
        "UPDATE books SET finished = :finished, currentFileId = 0, positionMs = 0, " +
            "listenedMs = CASE WHEN :finished THEN durationMs ELSE 0 END, lastPlayedAt = :now WHERE id = :bookId",
    )
    abstract suspend fun setFinished(bookId: Long, finished: Boolean, now: Long)
}
