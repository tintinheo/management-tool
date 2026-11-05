package com.audiohub.data.podcast

import android.util.Xml
import com.audiohub.data.db.EpisodeFeedData
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

data class ParsedFeed(
    val title: String,
    val author: String,
    val description: String,
    val artworkUrl: String?,
    val websiteUrl: String?,
    /** Newest first. Only items with an audio/video enclosure. */
    val episodes: List<EpisodeFeedData>,
)

class FeedParseException(message: String) : Exception(message)

/** Streaming RSS 2.0 + iTunes/Podcasting 2.0 parser (tolerant of the usual feed quirks). */
object RssParser {

    fun parse(input: InputStream): ParsedFeed {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        p.setInput(input, null)

        var sawChannel = false
        var title = ""
        var author = ""
        var description = ""
        var summary = ""
        var rssImage: String? = null
        var itunesImage: String? = null
        var link: String? = null
        val episodes = ArrayList<EpisodeFeedData>()

        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType != XmlPullParser.START_TAG) continue
            val ns = p.namespace.orEmpty()
            when {
                p.name == "channel" && ns.isEmpty() -> sawChannel = true
                p.name == "item" && ns.isEmpty() -> parseItem(p, itunesImage)?.let(episodes::add)
                p.depth == 3 -> when {
                    ns.isEmpty() && p.name == "title" -> title = readText(p)
                    ns.isEmpty() && p.name == "description" -> description = readText(p)
                    ns.isEmpty() && p.name == "link" -> link = readText(p).ifEmpty { null }
                    ns.isEmpty() && p.name == "image" -> rssImage = readImageUrl(p)
                    isItunes(ns) && p.name == "author" -> author = readText(p)
                    isItunes(ns) && p.name == "summary" -> summary = readText(p)
                    isItunes(ns) && p.name == "image" -> itunesImage = p.getAttributeValue(null, "href")?.trim()
                }
            }
        }
        if (!sawChannel) throw FeedParseException("Not an RSS podcast feed")
        return ParsedFeed(
            title = title.ifEmpty { "Untitled podcast" },
            author = author,
            description = description.ifEmpty { summary },
            artworkUrl = itunesImage ?: rssImage,
            websiteUrl = link,
            episodes = episodes.sortedByDescending { it.pubDate },
        )
    }

    private fun parseItem(p: XmlPullParser, showImage: String?): EpisodeFeedData? {
        val itemDepth = p.depth
        var title = ""
        var guid = ""
        var description = ""
        var contentEncoded = ""
        var summary = ""
        var pubDate = ""
        var enclosureUrl: String? = null
        var enclosureType: String? = null
        var enclosureLength = 0L
        var duration = ""
        var image: String? = null
        var season: Int? = null
        var episode: Int? = null

        while (true) {
            val event = p.next()
            if (event == XmlPullParser.END_DOCUMENT) break
            if (event == XmlPullParser.END_TAG && p.depth == itemDepth) break
            if (event != XmlPullParser.START_TAG) continue
            val ns = p.namespace.orEmpty()
            when {
                ns.isEmpty() && p.name == "title" -> title = readText(p)
                ns.isEmpty() && p.name == "guid" -> guid = readText(p)
                ns.isEmpty() && p.name == "description" -> description = readText(p)
                ns.isEmpty() && p.name == "pubDate" -> pubDate = readText(p)
                ns.isEmpty() && p.name == "enclosure" && enclosureUrl == null -> {
                    enclosureUrl = p.getAttributeValue(null, "url")?.trim()
                    enclosureType = p.getAttributeValue(null, "type")?.trim()
                    enclosureLength = p.getAttributeValue(null, "length")?.trim()?.toLongOrNull() ?: 0
                }
                ns.contains("purl.org/rss/1.0/modules/content") && p.name == "encoded" -> contentEncoded = readText(p)
                isItunes(ns) && p.name == "summary" -> summary = readText(p)
                isItunes(ns) && p.name == "duration" -> duration = readText(p)
                isItunes(ns) && p.name == "image" -> image = p.getAttributeValue(null, "href")?.trim()
                isItunes(ns) && p.name == "season" -> season = readText(p).toIntOrNull()
                isItunes(ns) && p.name == "episode" -> episode = readText(p).toIntOrNull()
            }
        }
        val url = enclosureUrl?.takeIf { it.isNotEmpty() } ?: return null
        return EpisodeFeedData(
            guid = guid.ifEmpty { url },
            title = title.ifEmpty { "Untitled episode" },
            description = contentEncoded.ifBlank { description.ifBlank { summary } },
            pubDate = FeedValues.parseDate(pubDate),
            durationMs = FeedValues.parseDuration(duration),
            enclosureUrl = url,
            enclosureType = enclosureType,
            enclosureLength = enclosureLength,
            artworkUrl = image?.takeIf { it.isNotEmpty() && it != showImage },
            season = season,
            episodeNumber = episode,
        )
    }

    private fun isItunes(ns: String) = ns.contains("itunes.com/dtds", ignoreCase = true)

    /** Text content of the current element (CDATA included), skipping any nested markup. */
    private fun readText(p: XmlPullParser): String {
        val sb = StringBuilder()
        var depth = 1
        while (depth > 0) {
            when (p.next()) {
                XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> sb.append(p.text)
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> break
            }
        }
        return sb.toString().trim()
    }

    /** <image><url>…</url></image> */
    private fun readImageUrl(p: XmlPullParser): String? {
        val depth = p.depth
        var url: String? = null
        while (true) {
            val e = p.next()
            if (e == XmlPullParser.END_DOCUMENT || (e == XmlPullParser.END_TAG && p.depth == depth)) break
            if (e == XmlPullParser.START_TAG && p.name == "url") url = readText(p)
        }
        return url?.ifEmpty { null }
    }
}
