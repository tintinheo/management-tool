package com.audiohub.data.podcast

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

data class OpmlEntry(val title: String, val feedUrl: String)

/** OPML 1.0/2.0 subscription lists (the format every podcast app imports/exports). */
object Opml {

    /** Every outline with an xmlUrl, at any nesting depth; duplicates removed. */
    fun parse(input: InputStream): List<OpmlEntry> {
        val p = Xml.newPullParser()
        p.setInput(input, null)
        val result = LinkedHashMap<String, OpmlEntry>()
        var sawOpml = false
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType != XmlPullParser.START_TAG) continue
            when (p.name.lowercase()) {
                "opml" -> sawOpml = true
                "outline" -> {
                    val url = attr(p, "xmlUrl") ?: continue
                    val normalized = FeedValues.normalizeFeedUrl(url)
                    val title = attr(p, "text") ?: attr(p, "title") ?: normalized
                    result.putIfAbsent(normalized, OpmlEntry(title, normalized))
                }
            }
        }
        if (!sawOpml) throw FeedParseException("Not an OPML file")
        return result.values.toList()
    }

    private fun attr(p: XmlPullParser, name: String): String? =
        (0 until p.attributeCount)
            .firstOrNull { p.getAttributeName(it).equals(name, ignoreCase = true) }
            ?.let { p.getAttributeValue(it)?.trim() }
            ?.takeIf { it.isNotEmpty() }

    fun format(entries: List<OpmlEntry>, title: String = "My Audio Hub subscriptions"): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<opml version=\"2.0\">\n")
        append("  <head><title>").append(escape(title)).append("</title></head>\n")
        append("  <body>\n")
        for (e in entries) {
            append("    <outline type=\"rss\" text=\"").append(escape(e.title))
                .append("\" title=\"").append(escape(e.title))
                .append("\" xmlUrl=\"").append(escape(e.feedUrl)).append("\"/>\n")
        }
        append("  </body>\n")
        append("</opml>\n")
    }

    private fun escape(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")
}
