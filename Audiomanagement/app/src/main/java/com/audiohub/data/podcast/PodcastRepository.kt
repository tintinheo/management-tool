package com.audiohub.data.podcast

import com.audiohub.data.db.EpisodeEntity
import com.audiohub.data.db.EpisodeWithPodcast
import com.audiohub.data.db.PodcastDao
import com.audiohub.data.db.PodcastEntity
import com.audiohub.data.db.PodcastSummary
import com.audiohub.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

class PodcastRepository(
    private val dao: PodcastDao,
    private val http: HttpClient,
    private val itunes: ItunesApi,
    private val settings: SettingsRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val summaries: Flow<List<PodcastSummary>> = dao.observeSummaries()

    fun podcast(id: Long): Flow<PodcastEntity?> = dao.observe(id)
    fun episodes(podcastId: Long): Flow<List<EpisodeEntity>> = dao.observeEpisodes(podcastId)
    fun observeByFeedUrl(feedUrl: String) = dao.observeByFeedUrl(feedUrl)
    fun inProgress(limit: Int = 20) = dao.observeInProgress(limit)
    fun latest(days: Int = 30, limit: Int = 50) = dao.observeLatest(clock() - days * DAY_MS, limit)
    fun observeEpisode(id: Long) = dao.observeEpisodeWithPodcast(id)
    suspend fun episode(id: Long): EpisodeWithPodcast? = dao.episodeWithPodcast(id)
    suspend fun findByFeedUrl(feedUrl: String) = dao.byFeedUrl(feedUrl)
    suspend fun findByItunesId(itunesId: Long) = dao.byItunesId(itunesId)

    // ---- catalog

    suspend fun search(term: String) = itunes.search(term, country())
    suspend fun topCharts() = itunes.topCharts(country())
    suspend fun lookup(itunesId: Long) = itunes.lookup(itunesId, country())
    private suspend fun country() = settings.current().podcastCountry

    // ---- feeds

    /** Downloads and parses a feed without saving anything (used to preview a show). */
    suspend fun fetchFeed(feedUrl: String): ParsedFeed {
        val response = http.get(feedUrl)
        if (!response.isSuccessful) throw HttpException(response.code, feedUrl)
        return parse(response.body)
    }

    private suspend fun parse(bytes: ByteArray): ParsedFeed =
        withContext(Dispatchers.Default) { RssParser.parse(bytes.inputStream()) }

    /** Follows a show (idempotent). Pass [preview] when the feed was already fetched. */
    suspend fun subscribe(feedUrl: String, itunesId: Long? = null, preview: ParsedFeed? = null): Long {
        dao.byFeedUrl(feedUrl)?.let { return it.id }
        val feed = preview ?: fetchFeed(feedUrl)
        val now = clock()
        val id = dao.insertPodcast(
            PodcastEntity(
                feedUrl = feedUrl, itunesId = itunesId, title = feed.title, author = feed.author,
                description = feed.description, artworkUrl = feed.artworkUrl, websiteUrl = feed.websiteUrl,
                subscribedAt = now, lastRefreshedAt = now,
            ),
        )
        dao.mergeEpisodes(id, feed.episodes)
        return id
    }

    suspend fun unsubscribe(podcastId: Long) = dao.deletePodcast(podcastId)

    /** Conditional GET (ETag / Last-Modified). Returns the number of new episodes. */
    suspend fun refresh(podcastId: Long): Int {
        val podcast = dao.get(podcastId) ?: return 0
        if (isLocalFeed(podcast.feedUrl)) return 0 // folder on the phone, see LocalPodcastScanner
        val headers = buildMap {
            podcast.etag?.let { put("If-None-Match", it) }
            podcast.lastModified?.let { put("If-Modified-Since", it) }
        }
        val response = http.get(podcast.feedUrl, headers)
        val now = clock()
        if (response.code == 304) {
            dao.markRefreshed(podcastId, now)
            return 0
        }
        if (!response.isSuccessful) throw HttpException(response.code, podcast.feedUrl)
        val feed = parse(response.body)
        dao.updateFeedInfo(
            podcastId, feed.title, feed.author, feed.description, feed.artworkUrl ?: podcast.artworkUrl,
            feed.websiteUrl, now, response.headers["etag"], response.headers["last-modified"],
        )
        return dao.mergeEpisodes(podcastId, feed.episodes)
    }

    /** Refreshes every show not refreshed within [maxAgeMs]; failures are skipped. Returns new episodes. */
    suspend fun refreshAll(maxAgeMs: Long = 0): Int = coroutineScope {
        val gate = Semaphore(4)
        val due = dao.all().filter { !isLocalFeed(it.feedUrl) && clock() - it.lastRefreshedAt >= maxAgeMs }
        due.map { p -> async { gate.withPermit { runCatching { refresh(p.id) }.getOrDefault(0) } } }.awaitAll().sum()
    }

    // ---- downloads & auto-download

    val downloaded = dao.observeDownloaded()

    suspend fun setAutoDownload(podcastId: Long, enabled: Boolean) = dao.setAutoDownload(podcastId, enabled)

    /** Episodes that auto-download shows should fetch now: the newest [perShow] unplayed ones from the last week. */
    suspend fun autoDownloadCandidates(perShow: Int = 2): List<Long> =
        dao.autoDownloadPodcasts().flatMap { p ->
            dao.autoDownloadCandidates(p.id, since = clock() - 7 * DAY_MS, limit = perShow).map { it.id }
        }

    // ---- OPML

    data class OpmlImportResult(val added: Int, val alreadyFollowed: Int, val failed: List<String>)

    suspend fun importOpml(entries: List<OpmlEntry>): OpmlImportResult = coroutineScope {
        val gate = Semaphore(4)
        val known = dao.all().map { it.feedUrl }.toHashSet()
        val fresh = entries.filter { it.feedUrl !in known }
        val outcomes = fresh.map { e ->
            async { gate.withPermit { e to runCatching { subscribe(e.feedUrl) }.isSuccess } }
        }.awaitAll()
        OpmlImportResult(
            added = outcomes.count { it.second },
            alreadyFollowed = entries.size - fresh.size,
            failed = outcomes.filterNot { it.second }.map { it.first.title },
        )
    }

    suspend fun opmlEntries(): List<OpmlEntry> =
        dao.all().filterNot { isLocalFeed(it.feedUrl) }.sortedBy { it.title.lowercase() }.map { OpmlEntry(it.title, it.feedUrl) }

    // ---- listening state

    /** Saves progress; near the end (see [isNearEnd]) the episode counts as played. */
    suspend fun savePosition(episodeId: Long, positionMs: Long, durationMs: Long) {
        if (isNearEnd(positionMs, durationMs)) {
            dao.setPlayed(episodeId, true, clock())
        } else {
            dao.savePosition(episodeId, positionMs.coerceAtLeast(0), durationMs, clock())
        }
    }

    suspend fun setPlayed(episodeId: Long, played: Boolean) = dao.setPlayed(episodeId, played, clock())

    companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
        private const val PLAYED_THRESHOLD_MS = 30_000L

        /**
         * True when the remaining time is within min(30 s, 10 % of the length) — long episodes
         * count as played during the outro, short clips aren't "finished" the moment they start.
         */
        fun isNearEnd(positionMs: Long, durationMs: Long): Boolean =
            durationMs > 0 && durationMs - positionMs <= minOf(PLAYED_THRESHOLD_MS, durationMs / 10)
    }
}
