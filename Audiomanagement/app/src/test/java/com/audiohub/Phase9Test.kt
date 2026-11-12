package com.audiohub

import androidx.room.Room
import com.audiohub.data.audiobook.AudiobookRepository
import com.audiohub.data.audiobook.BookFile
import com.audiohub.data.audiobook.NaturalOrder
import com.audiohub.data.db.AppDatabase
import com.audiohub.data.settings.SettingsRepository
import com.audiohub.playback.bookFileIdOf
import com.audiohub.playback.isBookMediaId
import com.audiohub.playback.isPodcastMediaId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Audiobooks: folder → book grouping, chapter order, progress and resume. */
@RunWith(RobolectricTestRunner::class)
class Phase9Test {

    private lateinit var db: AppDatabase
    private lateinit var repo: AudiobookRepository
    private var now = 1_000L
    private val root = "Audiobook/"

    @Before
    fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
        repo = AudiobookRepository(app, db.bookDao(), SettingsRepository(app)) { now }
    }

    @After
    fun tearDown() = db.close()

    private var nextId = 1L
    private fun f(path: String, name: String, title: String? = null, artist: String? = "Nguyễn Nhật Ánh", ms: Long = 60_000) =
        BookFile(nextId++, "content://media/external/audio/media/$nextId", title, artist, name, path, ms, albumId = 0, dateAddedSec = 100)

    @Test
    fun naturalOrderSortsNumbersByValue() {
        val names = listOf("Chương 10.mp3", "Chương 2.mp3", "chương 1.mp3", "Chương 02b.mp3")
        assertEquals(listOf("chương 1.mp3", "Chương 2.mp3", "Chương 02b.mp3", "Chương 10.mp3"), names.sortedWith(NaturalOrder))
    }

    @Test
    fun foldersBecomeBooksAndFilesChaptersInOrder() {
        val files = listOf(
            f("Audiobook/Mắt Biếc/", "Chương 10.mp3", title = "Mắt Biếc"),
            f("Audiobook/Mắt Biếc/", "Chương 2.mp3", title = "Mắt Biếc"),
            f("Audiobook/Mắt Biếc/", "Chương 1.mp3", title = "Mắt Biếc"),
            // Nested CD folders belong to the same book, in path order.
            f("Audiobook/Sapiens/CD2/", "01.mp3", title = "Part 3", artist = "Yuval Noah Harari"),
            f("Audiobook/Sapiens/CD1/", "02.mp3", title = "Part 2", artist = "Yuval Noah Harari"),
            f("Audiobook/Sapiens/CD1/", "01.mp3", title = "Part 1", artist = "Yuval Noah Harari"),
            f("Audiobook/", "Short Story.m4b", title = "A Short Story", artist = null),
            f("Music/AudioHub/", "song.mp3"), // outside the folder
        )
        val books = AudiobookRepository.index(files, root)
        assertEquals(listOf("A Short Story", "Mắt Biếc", "Sapiens"), books.map { it.title })

        val matBiec = books.first { it.folderKey == "Mắt Biếc" }
        // Every file is tagged with the book title → chapter names come from the file names.
        assertEquals(listOf("Chương 1", "Chương 2", "Chương 10"), matBiec.chapters.map { it.title })
        assertEquals("Nguyễn Nhật Ánh", matBiec.author)
        assertEquals(180_000, matBiec.durationMs)

        val sapiens = books.first { it.folderKey == "Sapiens" }
        assertEquals(listOf("Part 1", "Part 2", "Part 3"), sapiens.chapters.map { it.title }) // distinct tags are used

        val loose = books.first { it.folderKey.startsWith(AudiobookRepository.LOOSE_PREFIX) }
        assertEquals(1, loose.chapters.size)
        assertEquals("", loose.author)
    }

    @Test
    fun progressIsSavedResumedFinishedAndSurvivesRescan() = runBlocking {
        val files = (1..3).map { f("Audiobook/Mắt Biếc/", "Chương $it.mp3", ms = 600_000) }
        repo.sync(AudiobookRepository.index(files, root))
        val book = db.bookDao().allBooks().single()
        val chapters = db.bookDao().chapters(book.id)
        assertFalse(book.started)

        now = 5_000
        repo.savePosition(chapters[1].fileId, 120_000)
        var saved = db.bookDao().book(book.id)!!
        assertEquals(720_000, saved.listenedMs) // chapter 1 (10 min) + 2 min
        assertEquals(0.4f, saved.progress, 0.001f)
        assertEquals(AudiobookRepository.resumeOf(saved, chapters).chapterIndex, 1)
        assertEquals(120_000, AudiobookRepository.resumeOf(saved, chapters).positionMs)

        // A new file appears: progress is kept, totals updated.
        repo.sync(AudiobookRepository.index(files + f("Audiobook/Mắt Biếc/", "Chương 4.mp3", ms = 600_000), root))
        saved = db.bookDao().book(book.id)!!
        assertEquals(4, saved.chapterCount)
        assertEquals(chapters[1].fileId, saved.currentFileId)
        assertEquals(720_000, saved.listenedMs)

        // Ending a middle chapter doesn't finish the book; ending the last one does.
        val all = db.bookDao().chapters(book.id)
        repo.onPlayedToEnd(all[1].fileId)
        assertFalse(db.bookDao().book(book.id)!!.finished)
        repo.onPlayedToEnd(all[3].fileId)
        saved = db.bookDao().book(book.id)!!
        assertTrue(saved.finished)
        assertEquals(1f, saved.progress)
        assertEquals(0, AudiobookRepository.resumeOf(saved, all).chapterIndex) // listen again from the start

        // Playing again clears "finished".
        repo.savePosition(all[0].fileId, 1_000)
        assertFalse(db.bookDao().book(book.id)!!.finished)
    }

    @Test
    fun resumeNearChapterEndMovesToNextAndRemovedFoldersDisappear() = runBlocking {
        val files = (1..2).map { f("Audiobook/Book/", "$it.mp3", ms = 600_000) }
        repo.sync(AudiobookRepository.index(files, root))
        val book = db.bookDao().allBooks().single()
        val chapters = db.bookDao().chapters(book.id)
        repo.savePosition(chapters[0].fileId, 599_000)
        val resume = AudiobookRepository.resumeOf(db.bookDao().book(book.id)!!, chapters)
        assertEquals(1, resume.chapterIndex)
        assertEquals(0, resume.positionMs)

        repo.sync(emptyList())
        assertTrue(repo.books.first().isEmpty())
    }

    @Test
    fun chapterMediaIdsAreRecognised() {
        assertEquals(42L, bookFileIdOf("bookfile:42"))
        assertTrue(isBookMediaId("bookfile:42"))
        assertFalse(isPodcastMediaId("bookfile:42"))
        assertEquals(null, bookFileIdOf("track:42"))
    }
}
