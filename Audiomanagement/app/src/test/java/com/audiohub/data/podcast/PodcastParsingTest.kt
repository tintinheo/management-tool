package com.audiohub.data.podcast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/** Robolectric: RssParser uses android.util.Xml and ItunesJson uses org.json. */
@RunWith(RobolectricTestRunner::class)
class PodcastParsingTest {

    @Test
    fun parsesDatesFromRealWorldFeeds() {
        fun iso(s: String) = Instant.parse(s).toEpochMilli()
        assertEquals(iso("2026-11-04T10:51:00Z"), FeedValues.parseDate("Wed, 04 Nov 2026 10:51:00 +0000"))
        assertEquals(iso("2026-11-04T10:51:00Z"), FeedValues.parseDate("Wed, 4 Nov 2026 10:51:00 GMT"))
        assertEquals(iso("2026-11-04T15:51:00Z"), FeedValues.parseDate("Wed, 04 Nov 2026 10:51:00 EST"))
        assertEquals(iso("2026-11-04T03:51:00Z"), FeedValues.parseDate("04 Nov 2026 10:51:00 +07:00"))
        assertEquals(iso("2026-11-04T10:51:00Z"), FeedValues.parseDate("Wednesday, 04 Nov 2026 10:51 +0000"))
        assertEquals(iso("2026-11-04T10:51:00Z"), FeedValues.parseDate("2026-11-04T10:51:00Z"))
        assertEquals(0L, FeedValues.parseDate("yesterday"))
        assertEquals(0L, FeedValues.parseDate(null))
    }

    @Test
    fun parsesDurations() {
        assertEquals(3_723_000L, FeedValues.parseDuration("1:02:03"))
        assertEquals(3_723_000L, FeedValues.parseDuration("62:03"))
        assertEquals(3_723_000L, FeedValues.parseDuration("3723"))
        assertEquals(3_723_500L, FeedValues.parseDuration("3723.5"))
        assertEquals(0L, FeedValues.parseDuration("abc"))
        assertEquals(0L, FeedValues.parseDuration(""))
    }

    @Test
    fun classifiesPastedLinks() {
        assertEquals(PodcastInput.Apple(1200361736), ApplePodcastLinks.classify("https://podcasts.apple.com/vn/podcast/the-daily/id1200361736"))
        assertEquals(PodcastInput.Apple(1200361736), ApplePodcastLinks.classify("https://podcasts.apple.com/us/podcast/the-daily/id1200361736?i=1000700000000"))
        assertEquals(PodcastInput.Feed("https://feeds.simplecast.com/Sl5CSM3S"), ApplePodcastLinks.classify(" feed://feeds.simplecast.com/Sl5CSM3S "))
        assertEquals(PodcastInput.Feed("https://example.com/rss"), ApplePodcastLinks.classify("example.com/rss"))
        assertEquals(PodcastInput.Feed("http://example.com/feed.xml"), ApplePodcastLinks.classify("http://example.com/feed.xml"))
        assertEquals(PodcastInput.Search("the daily"), ApplePodcastLinks.classify("the daily"))
        assertNull(ApplePodcastLinks.classify("  "))
    }

    @Test
    fun parsesItunesSearchAndCharts() {
        val search = """{"resultCount":1,"results":[{"wrapperType":"track","kind":"podcast","collectionId":1200361736,
            "artistName":"The New York Times","collectionName":"The Daily","feedUrl":"https://feeds.simplecast.com/Sl5CSM3S",
            "artworkUrl100":"https://x/100x100bb.jpg","artworkUrl600":"https://x/600x600bb.jpg","primaryGenreName":"Daily News","trackCount":2770}]}"""
        val r = ItunesJson.parseSearch(search).single()
        assertEquals(1200361736L, r.itunesId)
        assertEquals("The Daily", r.title)
        assertEquals("https://feeds.simplecast.com/Sl5CSM3S", r.feedUrl)
        assertEquals("https://x/600x600bb.jpg", r.artworkUrl)
        assertEquals(2770, r.episodeCount)

        val charts = """{"feed":{"title":"Top Shows","results":[{"artistName":"The New York Times","id":"1200361736",
            "name":"The Daily","kind":"podcasts","artworkUrl100":"https://x/100x100bb.png",
            "genres":[{"genreId":"1489","name":"News"}],"url":"https://podcasts.apple.com/us/podcast/the-daily/id1200361736"}]}}"""
        val c = ItunesJson.parseTopCharts(charts).single()
        assertEquals(1200361736L, c.itunesId)
        assertNull(c.feedUrl)
        assertEquals("https://x/600x600bb.png", c.artworkUrl)
        assertEquals("News", c.genre)
    }

    @Test
    fun parsesRssFeed() {
        val feed = RssParser.parse(SAMPLE_FEED.byteInputStream())
        assertEquals("Chuyện Cà Phê", feed.title)
        assertEquals("AudioHub Tests", feed.author)
        assertEquals("https://example.com/show.jpg", feed.artworkUrl)
        assertEquals("https://example.com", feed.websiteUrl)
        assertTrue(feed.description.contains("Một podcast"))

        // Item without enclosure is skipped; newest first.
        assertEquals(listOf("Tập 2: Đà Lạt", "Tập 1"), feed.episodes.map { it.title })
        val ep2 = feed.episodes[0]
        assertEquals("ep-2", ep2.guid)
        assertEquals(2_730_000L, ep2.durationMs)
        assertEquals("https://example.com/ep2.mp3", ep2.enclosureUrl)
        assertEquals("audio/mpeg", ep2.enclosureType)
        assertEquals(12345L, ep2.enclosureLength)
        assertEquals("<p>Show notes <b>đầy đủ</b></p>", ep2.description) // content:encoded wins
        assertEquals("https://example.com/ep2.jpg", ep2.artworkUrl)
        assertEquals(2, ep2.episodeNumber)
        assertEquals(1, ep2.season)
        assertEquals(Instant.parse("2026-11-03T08:00:00Z").toEpochMilli(), ep2.pubDate)

        val ep1 = feed.episodes[1]
        assertEquals("https://example.com/ep1.mp3", ep1.guid) // no <guid>: falls back to enclosure URL
        assertEquals("Plain description", ep1.description)
        assertNull(ep1.artworkUrl) // same as show art → not duplicated
    }

    @Test(expected = FeedParseException::class)
    fun rejectsNonFeeds() {
        RssParser.parse("<html><body>Not a feed</body></html>".byteInputStream())
    }

    companion object {
        val SAMPLE_FEED = """<?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd" xmlns:content="http://purl.org/rss/1.0/modules/content/">
              <channel>
                <title>Chuyện Cà Phê</title>
                <link>https://example.com</link>
                <description><![CDATA[Một podcast về <i>cà phê</i>]]></description>
                <itunes:author>AudioHub Tests</itunes:author>
                <itunes:image href="https://example.com/show.jpg"/>
                <image><url>https://example.com/rss.jpg</url><title>x</title></image>
                <item>
                  <title>Tập 1</title>
                  <description>Plain description</description>
                  <pubDate>Mon, 02 Nov 2026 08:00:00 +0000</pubDate>
                  <enclosure url="https://example.com/ep1.mp3" type="audio/mpeg" length="999"/>
                  <itunes:duration>30:00</itunes:duration>
                  <itunes:image href="https://example.com/show.jpg"/>
                </item>
                <item>
                  <title>Tập 2: Đà Lạt</title>
                  <guid isPermaLink="false">ep-2</guid>
                  <description>Short</description>
                  <content:encoded><![CDATA[<p>Show notes <b>đầy đủ</b></p>]]></content:encoded>
                  <pubDate>Tue, 03 Nov 2026 08:00:00 GMT</pubDate>
                  <enclosure url="https://example.com/ep2.mp3" type="audio/mpeg" length="12345"/>
                  <itunes:duration>45:30</itunes:duration>
                  <itunes:image href="https://example.com/ep2.jpg"/>
                  <itunes:episode>2</itunes:episode>
                  <itunes:season>1</itunes:season>
                </item>
                <item>
                  <title>Trailer without audio</title>
                  <pubDate>Sun, 01 Nov 2026 08:00:00 GMT</pubDate>
                </item>
              </channel>
            </rss>""".trimIndent()
    }
}
