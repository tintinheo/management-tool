package com.audiohub.data.podcast

import android.net.Uri
import androidx.room.Room
import com.audiohub.data.db.AppDatabase
import com.audiohub.data.db.EpisodeEntity
import com.audiohub.data.db.EpisodeFeedData
import com.audiohub.data.db.EpisodeWithPodcast
import com.audiohub.data.db.PodcastEntity
import com.audiohub.data.settings.SettingsRepository
import com.audiohub.ui.Routes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

@RunWith(RobolectricTestRunner::class)
class Phase5Test {

    // ------------------------------------------------------------------ fakes

    private class FakeHttp : HttpClient {
        val bodies = HashMap<String, Pair<Int, ByteArray>>()
        override suspend fun get(url: String, headers: Map<String, String>): HttpResponse {
            val (code, body) = bodies[url] ?: (404 to ByteArray(0))
            return HttpResponse(code, body, emptyMap())
        }
    }

    /** Writes "MediaStore" files into a temp dir. */
    private class FakeStore(private val dir: File) : EpisodeFileStore {
        val files = HashMap<Uri, File>()
        val published = HashSet<Uri>()
        val relativeDirs = ArrayList<String>()
        override fun create(relativeDir: String, displayName: String, mimeType: String): Uri {
            relativeDirs += relativeDir
            val f = File(dir, relativeDir + displayName).apply { parentFile?.mkdirs() }
            return Uri.fromFile(f).also { files[it] = f }
        }
        override fun openOutput(uri: Uri): OutputStream = FileOutputStream(files.getValue(uri))
        override fun publish(uri: Uri) { published += uri }
        override fun delete(uri: Uri): Boolean = (files.remove(uri)?.delete() ?: false).also { published -= uri }
    }

    private val app = RuntimeEnvironment.getApplication()
    private lateinit var db: AppDatabase
    private lateinit var http: FakeHttp
    private lateinit var store: FakeStore
    private lateinit var settings: SettingsRepository
    private lateinit var downloader: EpisodeDownloader
    private lateinit var repo: PodcastRepository
    private var now = 1_800_000_000_000L

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
        http = FakeHttp()
        store = FakeStore(File(app.cacheDir, "store-${System.nanoTime()}"))
        settings = SettingsRepository(app)
        settings.setPodcastFolder("Podcasts/AudioHub/")
        settings.setDeletePlayedDownloads(true)
        downloader = EpisodeDownloader(db.podcastDao(), http, store, settings, DownloadProgress())
        repo = PodcastRepository(db.podcastDao(), http, ItunesApi(http), settings, clock = { now })
    }

    @After
    fun tearDown() = db.close()

    private suspend fun insertShow(feedUrl: String = "https://example.com/feed", autoDownload: Boolean = false): Long =
        db.podcastDao().insertPodcast(
            PodcastEntity(
                feedUrl = feedUrl, itunesId = null, title = "Chuyện: Cà/Phê?", author = "", description = "",
                artworkUrl = null, websiteUrl = null, subscribedAt = 0, autoDownload = autoDownload,
            ),
        )

    private suspend fun insertEpisode(podcastId: Long, guid: String, url: String, pubDate: Long = now, played: Boolean = false): Long {
        db.podcastDao().mergeEpisodes(
            podcastId,
            listOf(EpisodeFeedData(guid, "Tập $guid: Đà Lạt", "", pubDate, 60_000, url, "audio/mpeg", 0, null, null, null)),
        )
        val id = db.podcastDao().observeEpisodes(podcastId).first().first { it.guid == guid }.id
        if (played) db.podcastDao().setPlayed(id, true, now)
        return id
    }

    // ------------------------------------------------------------------ OPML

    @Test
    fun opmlRoundTripAndForeignFiles() {
        val text = Opml.format(listOf(OpmlEntry("Tom & Jerry's <Show>", "https://a.com/feed"), OpmlEntry("Cà phê", "https://b.com/rss")))
        assertEquals(
            listOf(OpmlEntry("Tom & Jerry's <Show>", "https://a.com/feed"), OpmlEntry("Cà phê", "https://b.com/rss")),
            Opml.parse(text.byteInputStream()),
        )

        // Nested categories, different attribute casing, duplicates, feed:// scheme.
        val foreign = """<?xml version="1.0"?><opml version="1.0"><head/><body>
            <outline text="News"><outline text="The Daily" XMLURL="https://feeds.simplecast.com/Sl5CSM3S"/></outline>
            <outline title="Dup" xmlUrl="https://feeds.simplecast.com/Sl5CSM3S"/>
            <outline text="Other" xmlUrl="feed://example.org/rss"/>
            <outline text="Just a folder"/></body></opml>"""
        assertEquals(
            listOf(OpmlEntry("The Daily", "https://feeds.simplecast.com/Sl5CSM3S"), OpmlEntry("Other", "https://example.org/rss")),
            Opml.parse(foreign.byteInputStream()),
        )
    }

    @Test(expected = FeedParseException::class)
    fun opmlRejectsOtherXml() {
        Opml.parse("<rss><channel/></rss>".byteInputStream())
    }

    // ------------------------------------------------------------------ shared links

    @Test
    fun sharedTextBecomesShowRoute() {
        assertEquals(
            Routes.show(itunesId = 1200361736),
            Routes.forSharedText("Nghe thử: The Daily https://podcasts.apple.com/vn/podcast/the-daily/id1200361736?i=1000700000000."),
        )
        assertEquals(Routes.show(feedUrl = "https://example.org/rss"), Routes.forSharedText("feed://example.org/rss"))
        assertNull(Routes.forSharedText("https://podcasts.apple.com/vn/browse")) // Apple page without a show id
        assertNull(Routes.forSharedText("no link here"))
        assertNull(Routes.forSharedText(null))
    }

    // ------------------------------------------------------------------ downloads

    @Test
    fun fileNamesAreSafeAndTyped() {
        fun item(title: String, type: String?, url: String) = EpisodeWithPodcast(
            EpisodeEntity(
                podcastId = 1, guid = "g", title = title, description = "", pubDate = 1_762_243_200_000, durationMs = 0,
                enclosureUrl = url, enclosureType = type, enclosureLength = 0, artworkUrl = null, season = null, episodeNumber = null,
            ),
            "Show", null,
        )
        assertEquals("2025-11-04 Tập 1 Đà Lạt why.mp3" to "audio/mpeg", EpisodeDownloader.fileNameFor(item("Tập 1: Đà Lạt / why?", "audio/mpeg", "https://x/a")))
        assertEquals("audio/mp4", EpisodeDownloader.fileNameFor(item("a", "audio/x-m4a", "https://x/a")).second)
        assertTrue(EpisodeDownloader.fileNameFor(item("a", null, "https://x/ep.m4a?token=1")).first.endsWith(".m4a"))
        assertEquals("Podcast", EpisodeDownloader.safeName("///", 50))
    }

    @Test
    fun downloadWritesFileIntoShowFolderAndCanBeDeleted() = runBlocking {
        val show = insertShow()
        val bytes = ByteArray(300_000) { (it % 251).toByte() }
        http.bodies["https://example.com/1.mp3"] = 200 to bytes
        val id = insertEpisode(show, "1", "https://example.com/1.mp3")

        val reported = ArrayList<Float>()
        assertTrue(downloader.download(id) { reported += it })

        val ep = db.podcastDao().getEpisode(id)!!
        assertEquals(DownloadState.DONE, ep.downloadState)
        val uri = Uri.parse(ep.localUri)
        assertArrayEquals(bytes, store.files.getValue(uri).readBytes())
        assertTrue(uri in store.published)
        assertEquals("Podcasts/AudioHub/Chuyện Cà Phê/", store.relativeDirs.single())
        assertEquals(1f, reported.last(), 0.001f)
        assertEquals(listOf(id), repo.downloaded.first().map { it.episode.id })
        assertTrue(downloader.progress.progress.value.isEmpty())

        downloader.delete(id)
        assertNull(db.podcastDao().getEpisode(id)!!.localUri)
        assertTrue(store.files.isEmpty())
    }

    @Test
    fun failedDownloadLeavesNoFile() = runBlocking {
        val show = insertShow()
        val id = insertEpisode(show, "1", "https://example.com/missing.mp3") // 404
        assertFalse(downloader.download(id))
        assertEquals(DownloadState.FAILED, db.podcastDao().getEpisode(id)!!.downloadState)
        assertTrue(store.files.isEmpty())
    }

    @Test
    fun playedDownloadsAreRemovedOnlyWhenEnabled() = runBlocking {
        val show = insertShow()
        http.bodies["https://example.com/1.mp3"] = 200 to ByteArray(10)
        val id = insertEpisode(show, "1", "https://example.com/1.mp3")
        downloader.download(id)

        settings.setDeletePlayedDownloads(false)
        downloader.onPlayed(id)
        assertTrue(db.podcastDao().getEpisode(id)!!.localUri != null)

        settings.setDeletePlayedDownloads(true)
        downloader.onPlayed(id)
        assertNull(db.podcastDao().getEpisode(id)!!.localUri)
    }

    @Test
    fun autoDownloadPicksNewestUnplayedOfOptedInShows() = runBlocking {
        val auto = insertShow("https://a.com/feed", autoDownload = true)
        val manual = insertShow("https://b.com/feed")
        val day = 24L * 60 * 60 * 1000
        val newest = insertEpisode(auto, "n", "https://a/1", pubDate = now - day)
        val second = insertEpisode(auto, "s", "https://a/2", pubDate = now - 2 * day)
        insertEpisode(auto, "third", "https://a/3", pubDate = now - 3 * day)
        insertEpisode(auto, "old", "https://a/old", pubDate = now - 30 * day)
        insertEpisode(auto, "played", "https://a/p", pubDate = now, played = true)
        insertEpisode(manual, "m", "https://b/1", pubDate = now)

        assertEquals(listOf(newest, second), repo.autoDownloadCandidates(perShow = 2))
    }

    // ------------------------------------------------------------------ OPML import

    @Test
    fun opmlImportFollowsNewFeedsAndReportsFailures() = runBlocking {
        http.bodies["https://ok.com/feed"] = 200 to PodcastParsingTest.SAMPLE_FEED.toByteArray()
        insertShow("https://already.com/feed")
        val result = repo.importOpml(
            listOf(
                OpmlEntry("OK", "https://ok.com/feed"),
                OpmlEntry("Broken", "https://broken.com/feed"),
                OpmlEntry("Already", "https://already.com/feed"),
            ),
        )
        assertEquals(1, result.added)
        assertEquals(1, result.alreadyFollowed)
        assertEquals(listOf("Broken"), result.failed)
        assertEquals(listOf("https://already.com/feed", "https://ok.com/feed"), repo.opmlEntries().map { it.feedUrl }.sorted())
    }

    // ------------------------------------------------------------------ local folder

    private fun local(id: Long, path: String, name: String) = LocalAudio(
        mediaId = id, uri = "content://media/external/audio/media/$id", title = null, displayName = name,
        relativePath = path, durationMs = 1_000_000, dateAddedSec = id, mimeType = "audio/mpeg",
    )

    @Test
    fun localFilesBecomeShowsAndStayInSync() = runBlocking {
        val scanner = LocalPodcastScanner(app, db.podcastDao(), settings, clock = { now })
        val root = "Podcasts/AudioHub/"
        // A file this app downloaded must not be adopted as a local show.
        val show = insertShow()
        val downloadedId = insertEpisode(show, "d", "https://example.com/d.mp3")
        db.podcastDao().setDownload(downloadedId, "content://media/external/audio/media/99", DownloadState.DONE)

        val files = listOf(
            local(1, "Podcasts/AudioHub/Lectures/", "01 Intro.mp3"),
            local(2, "Podcasts/AudioHub/Lectures/Week 2/", "02 Deep dive.mp3"),
            local(3, "Podcasts/AudioHub/", "loose.mp3"),
            local(99, "Podcasts/AudioHub/Chuyện Cà Phê/", "downloaded.mp3"),
        )
        assertEquals(setOf("Lectures", "Other", "Chuyện Cà Phê"), LocalPodcastScanner.group(files, root).keys)

        scanner.sync(files, root)
        val localShows = db.podcastDao().localPodcasts().associateBy { it.title }
        assertEquals(setOf("Lectures", "Other"), localShows.keys)
        val lectures = db.podcastDao().observeEpisodes(localShows.getValue("Lectures").id).first()
        assertEquals(setOf("01 Intro", "02 Deep dive"), lectures.map { it.title }.toSet())
        assertTrue(lectures.all { it.localUri == it.enclosureUrl && it.downloadState == DownloadState.DONE })

        // Progress survives a rescan; removed files/folders disappear.
        val intro = lectures.first { it.title == "01 Intro" }
        repo.savePosition(intro.id, 120_000, 1_000_000)
        scanner.sync(listOf(files[0]), root)
        val after = db.podcastDao().localPodcasts()
        assertEquals(listOf("Lectures"), after.map { it.title })
        val kept = db.podcastDao().observeEpisodes(after.single().id).first().single()
        assertEquals(120_000L, kept.positionMs)

        // Local shows are never refreshed over HTTP or deleted by the downloader.
        assertEquals(0, repo.refresh(after.single().id))
        downloader.delete(kept.id)
        assertEquals(kept.localUri, db.podcastDao().getEpisode(kept.id)!!.localUri)
    }
}
