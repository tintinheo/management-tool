package com.audiohub.data.podcast

import androidx.room.Room
import com.audiohub.data.db.AppDatabase
import com.audiohub.data.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PodcastRepositoryTest {

    /** Serves canned responses and records request headers. */
    private class FakeHttp : HttpClient {
        val responses = HashMap<String, HttpResponse>()
        val requests = ArrayList<Pair<String, Map<String, String>>>()
        override suspend fun get(url: String, headers: Map<String, String>): HttpResponse {
            requests += url to headers
            return responses[url] ?: HttpResponse(404, ByteArray(0), emptyMap())
        }
    }

    private val feedUrl = "https://example.com/feed.xml"
    private lateinit var db: AppDatabase
    private lateinit var http: FakeHttp
    private lateinit var repo: PodcastRepository
    private var now = 1_000_000L

    @Before
    fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
        http = FakeHttp()
        repo = PodcastRepository(db.podcastDao(), http, ItunesApi(http), SettingsRepository(app), clock = { now })
        serve(PodcastParsingTest.SAMPLE_FEED, etag = "\"v1\"")
    }

    @After
    fun tearDown() = db.close()

    private fun serve(xml: String, etag: String? = null, code: Int = 200) {
        http.responses[feedUrl] = HttpResponse(code, xml.toByteArray(), buildMap { etag?.let { put("etag", it) } })
    }

    @Test
    fun subscribeStoresShowAndEpisodesAndIsIdempotent() = runBlocking {
        val id = repo.subscribe(feedUrl, itunesId = 42)
        assertEquals(id, repo.subscribe(feedUrl)) // second call: no duplicate
        val show = repo.podcast(id).first()!!
        assertEquals("Chuyện Cà Phê", show.title)
        assertEquals(42L, show.itunesId)
        assertEquals(listOf("Tập 2: Đà Lạt", "Tập 1"), repo.episodes(id).first().map { it.title })
        assertEquals(1, repo.summaries.first().size)
        assertEquals(2, repo.summaries.first().single().unplayedCount)
        assertEquals(id, repo.findByItunesId(42)?.id)
    }

    @Test
    fun refreshUsesConditionalGetAndKeepsListeningState() = runBlocking {
        val id = repo.subscribe(feedUrl)
        assertEquals(0, repo.refresh(id)) // first refresh stores the ETag
        val ep1 = repo.episodes(id).first().first { it.title == "Tập 1" }
        repo.savePosition(ep1.id, positionMs = 600_000, durationMs = 1_800_000)

        // Server says "not modified" for our ETag.
        serve("", code = 304)
        assertEquals(0, repo.refresh(id))
        assertEquals("\"v1\"", http.requests.last().second["If-None-Match"])

        // New episode + retitled old one.
        val updated = PodcastParsingTest.SAMPLE_FEED
            .replace("<title>Tập 1</title>", "<title>Tập 1 (remastered)</title>")
            .replace(
                Regex("<item>\\s*<title>Tập 2"),
                """<item><title>Tập 3</title><guid>ep-3</guid><pubDate>Wed, 04 Nov 2026 08:00:00 GMT</pubDate>
                   <enclosure url="https://example.com/ep3.mp3" type="audio/mpeg" length="1"/></item>
                   <item><title>Tập 2""",
            )
        serve(updated, etag = "\"v2\"")
        assertEquals(1, repo.refresh(id))
        val episodes = repo.episodes(id).first()
        assertEquals(listOf("Tập 3", "Tập 2: Đà Lạt", "Tập 1 (remastered)"), episodes.map { it.title })
        val kept = episodes.first { it.id == ep1.id }
        assertEquals(600_000L, kept.positionMs) // progress survived the refresh
        assertEquals("\"v2\"", repo.podcast(id).first()?.etag)
    }

    @Test
    fun progressNearTheEndMarksPlayedAndFeedsListsUpdate() = runBlocking {
        val id = repo.subscribe(feedUrl)
        val (ep2, ep1) = repo.episodes(id).first()
        now = ep2.pubDate + 1_000 // "today" is right after the newest episode
        repo.savePosition(ep2.id, positionMs = 60_000, durationMs = ep2.durationMs)

        val inProgress = repo.inProgress().first()
        assertEquals(listOf(ep2.id), inProgress.map { it.episode.id })
        assertEquals("Chuyện Cà Phê", inProgress.single().podcastTitle)
        assertEquals(listOf(ep1.id), repo.latest(days = 30).first().map { it.episode.id }) // unstarted only

        repo.savePosition(ep2.id, positionMs = ep2.durationMs - 10_000, durationMs = ep2.durationMs)
        val done = repo.episode(ep2.id)!!.episode
        assertTrue(done.played)
        assertEquals(0L, done.positionMs)
        assertTrue(repo.inProgress().first().isEmpty())

        repo.setPlayed(ep2.id, false)
        assertFalse(repo.episode(ep2.id)!!.episode.played)
    }

    @Test
    fun refreshAllSkipsRecentAndSurvivesFailures() = runBlocking {
        val id = repo.subscribe(feedUrl)
        val before = http.requests.size
        assertEquals(0, repo.refreshAll(maxAgeMs = 60_000)) // just subscribed → not due
        assertEquals(before, http.requests.size)

        now += 120_000
        serve("", code = 500)
        assertEquals(0, repo.refreshAll(maxAgeMs = 60_000)) // failure swallowed
        assertEquals(before + 1, http.requests.size)
        assertEquals(2, repo.episodes(id).first().size)
    }

    @Test
    fun nearEndRuleScalesWithLength() {
        assertTrue(PodcastRepository.isNearEnd(3_590_000, 3_600_000)) // 10 s left of 1 h
        assertFalse(PodcastRepository.isNearEnd(3_500_000, 3_600_000)) // 100 s left
        assertFalse(PodcastRepository.isNearEnd(1_000, 20_000)) // short clip just started
        assertTrue(PodcastRepository.isNearEnd(19_000, 20_000)) // 1 s left of 20 s
        assertFalse(PodcastRepository.isNearEnd(5_000, 0)) // unknown length
    }

    @Test
    fun unsubscribeRemovesEpisodes() = runBlocking {
        val id = repo.subscribe(feedUrl)
        val episodeId = repo.episodes(id).first().first().id
        repo.unsubscribe(id)
        assertNull(repo.findByFeedUrl(feedUrl))
        assertNull(repo.episode(episodeId))
    }
}
