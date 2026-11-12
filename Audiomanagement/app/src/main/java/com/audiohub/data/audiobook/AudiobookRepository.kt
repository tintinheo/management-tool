package com.audiohub.data.audiobook

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.audiohub.data.db.BookChapterEntity
import com.audiohub.data.db.BookDao
import com.audiohub.data.db.BookEntity
import com.audiohub.data.music.MusicScanner
import com.audiohub.data.settings.SettingsRepository
import com.audiohub.data.storage.AudioFolders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** An audio file found under the audiobook folder. */
data class BookFile(
    val mediaId: Long,
    val uri: String,
    val title: String?,
    val artist: String?,
    val displayName: String,
    val relativePath: String,
    val durationMs: Long,
    val albumId: Long,
    val dateAddedSec: Long,
)

/** A book as found on disk, before it's merged with saved progress. */
data class ScannedBook(
    val folderKey: String,
    val title: String,
    val author: String,
    val artworkUri: String?,
    val addedAt: Long,
    val chapters: List<ScannedChapter>,
) {
    val durationMs: Long get() = chapters.sumOf { it.durationMs }
}

data class ScannedChapter(val fileId: Long, val title: String, val uri: String, val durationMs: Long)

/** Where to start playing a book. */
data class BookResume(val chapterIndex: Int, val positionMs: Long)

/**
 * Audiobooks from the audiobook folder (default /Audiobook/): each subfolder is a book, its audio
 * files (also in nested folders, e.g. CD1/CD2) are the chapters in natural filename order. A file
 * placed directly in the root folder is a one-chapter book.
 */
class AudiobookRepository(
    private val context: Context,
    private val dao: BookDao,
    private val settings: SettingsRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val scanLock = Mutex()

    val books: Flow<List<BookEntity>> = dao.observeBooks()
    fun book(id: Long): Flow<BookEntity?> = dao.observeBook(id)
    fun chapters(bookId: Long): Flow<List<BookChapterEntity>> = dao.observeChapters(bookId)
    suspend fun chapterList(bookId: Long) = dao.chapters(bookId)
    suspend fun bookOnce(id: Long) = dao.book(id)

    suspend fun scan() = withContext(Dispatchers.IO) {
        val root = AudioFolders.normalize(settings.current().audiobookFolder)
        scanLock.withLock { sync(index(query(root), root)) }
    }

    private fun query(root: String): List<BookFile> {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.RELATIVE_PATH, MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DATE_ADDED,
        )
        val selection = "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Audio.Media.IS_PENDING} = 0"
        val out = ArrayList<BookFile>()
        context.contentResolver.query(collection, projection, selection, arrayOf("$root%"), null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                out += BookFile(
                    mediaId = id,
                    uri = ContentUris.withAppendedId(collection, id).toString(),
                    title = c.getString(1)?.takeUnless { it.isBlank() || it == MediaStore.UNKNOWN_STRING },
                    artist = c.getString(2)?.takeUnless { it.isBlank() || it == MediaStore.UNKNOWN_STRING },
                    displayName = c.getString(3).orEmpty(),
                    relativePath = c.getString(4).orEmpty(),
                    durationMs = c.getLong(5),
                    albumId = c.getLong(6),
                    dateAddedSec = c.getLong(7),
                )
            }
        }
        return out
    }

    /** Merges what's on disk with saved books; progress is kept for books (and chapters) still present. */
    internal suspend fun sync(scanned: List<ScannedBook>) {
        val existing = dao.allBooks().associateBy { it.folderKey }
        val keep = HashSet<String>()
        for (s in scanned) {
            keep += s.folderKey
            val old = existing[s.folderKey]
            val id = if (old == null) {
                dao.insertBook(
                    BookEntity(
                        folderKey = s.folderKey, title = s.title, author = s.author, artworkUri = s.artworkUri,
                        chapterCount = s.chapters.size, durationMs = s.durationMs, addedAt = s.addedAt,
                    ),
                )
            } else {
                old.id
            }
            val chapters = s.chapters.mapIndexed { i, c -> BookChapterEntity(c.fileId, id, i, c.title, c.uri, c.durationMs) }
            if (old == null || dao.chapters(id) != chapters) dao.replaceChapters(id, chapters)
            if (old != null) {
                val current = chapters.firstOrNull { it.fileId == old.currentFileId }
                val updated = old.copy(
                    title = s.title, author = s.author, artworkUri = s.artworkUri,
                    chapterCount = chapters.size, durationMs = s.durationMs,
                ).let {
                    when {
                        it.finished -> it.copy(listenedMs = s.durationMs)
                        current == null -> it.copy(currentFileId = 0, positionMs = 0, listenedMs = 0) // chapter gone: start over
                        else -> it.copy(listenedMs = dao.durationBefore(id, current.position) + it.positionMs)
                    }
                }
                if (updated != old) dao.updateBook(updated)
            }
        }
        existing.values.filter { it.folderKey !in keep }.forEach { dao.deleteBook(it.id) }
    }

    /** Saves where the listener is; [fileId] identifies the chapter. */
    suspend fun savePosition(fileId: Long, positionMs: Long) {
        val chapter = dao.chapter(fileId) ?: return
        val listened = dao.durationBefore(chapter.bookId, chapter.position) + positionMs.coerceAtLeast(0)
        dao.saveProgress(chapter.bookId, fileId, positionMs.coerceAtLeast(0), listened, clock())
    }

    /**
     * A chapter played to its end. Only the last one matters (book finished); for the others the
     * tracker already saves the next chapter as the new position.
     */
    suspend fun onPlayedToEnd(fileId: Long) {
        val chapter = dao.chapter(fileId) ?: return
        if (chapter.position == dao.chapters(chapter.bookId).lastIndex) dao.setFinished(chapter.bookId, true, clock())
    }

    /** Media items for [book] plus where to start. */
    suspend fun playable(bookId: Long, fromChapter: Int? = null): Pair<List<androidx.media3.common.MediaItem>, BookResume>? {
        val book = dao.book(bookId) ?: return null
        val chapters = dao.chapters(bookId)
        if (chapters.isEmpty()) return null
        val resume = if (fromChapter != null) BookResume(fromChapter.coerceIn(0, chapters.lastIndex), 0) else resumeOf(book, chapters)
        return chapters.map { com.audiohub.playback.bookChapterItem(it, book) } to resume
    }

    suspend fun setFinished(bookId: Long, finished: Boolean) = dao.setFinished(bookId, finished, clock())

    companion object {
        const val LOOSE_PREFIX = "file:"

        /** Where to resume [book]: saved chapter/position, or the start if finished / not started. */
        fun resumeOf(book: BookEntity, chapters: List<BookChapterEntity>): BookResume {
            if (book.finished) return BookResume(0, 0)
            val index = chapters.indexOfFirst { it.fileId == book.currentFileId }
            if (index < 0) return BookResume(0, 0)
            val ch = chapters[index]
            // Within the last few seconds of a chapter → start the next one.
            return if (ch.durationMs > 0 && book.positionMs >= ch.durationMs - 3_000 && index < chapters.lastIndex) {
                BookResume(index + 1, 0)
            } else {
                BookResume(index, book.positionMs)
            }
        }

        /** Groups files into books (pure; visible for tests). */
        fun index(files: List<BookFile>, root: String): List<ScannedBook> {
            val normalizedRoot = AudioFolders.normalize(root)
            return files
                .filter { it.relativePath.startsWith(normalizedRoot) }
                .groupBy { f ->
                    val folder = f.relativePath.removePrefix(normalizedRoot).trim('/').substringBefore('/')
                    if (folder.isEmpty()) LOOSE_PREFIX + f.displayName else folder
                }
                .map { (key, group) -> book(key, group) }
                .sortedWith(compareBy(NaturalOrder) { it.title })
        }

        private fun book(key: String, files: List<BookFile>): ScannedBook {
            val ordered = files.sortedWith(compareBy(NaturalOrder) { it.relativePath + it.displayName })
            val fileTitles = ordered.map { it.displayName.substringBeforeLast('.') }
            // Many books tag every file with the book's name; then the file names tell chapters apart.
            val tagTitles = ordered.map { it.title }
            val useTags = tagTitles.none { it == null } && tagTitles.toSet().size == tagTitles.size
            val loose = key.startsWith(LOOSE_PREFIX)
            val title = if (loose) ordered.first().title ?: fileTitles.first() else key
            val author = ordered.mapNotNull { it.artist }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key.orEmpty()
            val art = ordered.firstOrNull { it.albumId > 0 }?.albumId?.let { MusicScanner.albumArtUri(it).toString() }
            return ScannedBook(
                folderKey = key,
                title = title,
                author = author,
                artworkUri = art,
                addedAt = ordered.minOf { it.dateAddedSec } * 1000,
                chapters = ordered.mapIndexed { i, f ->
                    ScannedChapter(f.mediaId, if (useTags) tagTitles[i]!! else fileTitles[i], f.uri, f.durationMs)
                },
            )
        }
    }
}

/** "Chapter 2" < "Chapter 10"; case-insensitive. */
object NaturalOrder : Comparator<String> {
    private val chunk = Regex("\\d+|\\D+")

    override fun compare(a: String, b: String): Int {
        val x = chunk.findAll(a.lowercase()).map { it.value }.toList()
        val y = chunk.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(x.size, y.size)) {
            val p = x[i]
            val q = y[i]
            val c = if (p[0].isDigit() && q[0].isDigit()) {
                val byLength = p.trimStart('0').length.compareTo(q.trimStart('0').length)
                if (byLength != 0) byLength else p.trimStart('0').compareTo(q.trimStart('0'))
            } else {
                p.compareTo(q)
            }
            if (c != 0) return c
        }
        return x.size.compareTo(y.size)
    }
}
