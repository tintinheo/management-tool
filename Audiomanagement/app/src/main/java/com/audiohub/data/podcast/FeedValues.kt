package com.audiohub.data.podcast

import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale

/** Lenient parsers for the messy values real-world podcast feeds contain. */
object FeedValues {

    private val zoneAbbreviations = mapOf(
        "GMT" to "+0000", "UT" to "+0000", "UTC" to "+0000", "Z" to "+0000",
        "EST" to "-0500", "EDT" to "-0400", "CST" to "-0600", "CDT" to "-0500",
        "MST" to "-0700", "MDT" to "-0600", "PST" to "-0800", "PDT" to "-0700",
        "BST" to "+0100", "CET" to "+0100", "CEST" to "+0200", "AEST" to "+1000", "AEDT" to "+1100",
        "ICT" to "+0700",
    )

    private fun formatter(pattern: String): DateTimeFormatter =
        DateTimeFormatterBuilder().parseCaseInsensitive().parseLenient().appendPattern(pattern).toFormatter(Locale.ENGLISH)

    private val zoned = listOf("d MMM yyyy HH:mm:ss Z", "d MMM yyyy HH:mm Z", "d MMM yy HH:mm:ss Z", "d MMMM yyyy HH:mm:ss Z")
        .map(::formatter)
    private val local = listOf("d MMM yyyy HH:mm:ss", "d MMM yyyy HH:mm", "yyyy-MM-dd HH:mm:ss").map(::formatter)
    private val dayName = Regex("^[A-Za-z]{2,9},?\\s+")
    private val trailingZone = Regex("\\s([A-Za-z]{1,4})$")
    private val whitespace = Regex("\\s+")

    /** RFC 822/1123 (with common deviations) or ISO 8601 → epoch millis; 0 if unparseable. */
    fun parseDate(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0
        val trimmed = raw.trim()
        runCatching { return OffsetDateTime.parse(trimmed).toInstant().toEpochMilli() }

        var s = trimmed.replace(whitespace, " ").replace(dayName, "")
        trailingZone.find(s)?.let { m ->
            zoneAbbreviations[m.groupValues[1].uppercase()]?.let { s = s.substring(0, m.range.first) + " " + it }
        }
        // "+00:00" → "+0000" for the Z pattern.
        s = s.replace(Regex("([+-]\\d{2}):(\\d{2})$"), "$1$2")
        for (f in zoned) runCatching { return ZonedDateTime.parse(s, f).toInstant().toEpochMilli() }
        for (f in local) runCatching { return LocalDateTime.parse(s, f).toInstant(ZoneOffset.UTC).toEpochMilli() }
        return 0
    }

    /** itunes:duration: "3723", "62:03", "1:02:03", "3723.5" → millis; 0 if unknown. */
    fun parseDuration(raw: String?): Long {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return 0
        val parts = s.split(':')
        if (parts.size == 1) {
            val seconds = s.toDoubleOrNull() ?: return 0
            return (seconds * 1000).toLong()
        }
        var seconds = 0.0
        for (p in parts) seconds = seconds * 60 + (p.trim().toDoubleOrNull() ?: return 0)
        return (seconds * 1000).toLong()
    }

    /** Some feeds use feed://, pcast:// or itpc:// schemes, or omit the scheme. */
    fun normalizeFeedUrl(raw: String): String {
        val s = raw.trim()
        return when {
            s.startsWith("feed://", true) -> "https://" + s.substring(7)
            s.startsWith("feed:", true) -> s.substring(5).let { if (it.startsWith("http")) it else "https:$it" }
            s.startsWith("pcast://", true) || s.startsWith("itpc://", true) -> "https://" + s.substringAfter("://")
            s.startsWith("http://", true) || s.startsWith("https://", true) -> s
            else -> "https://$s"
        }
    }
}

/** What the user pasted into "Add podcast": an Apple Podcasts link, an RSS URL, or search words. */
sealed interface PodcastInput {
    data class Apple(val itunesId: Long) : PodcastInput
    data class Feed(val url: String) : PodcastInput
    data class Search(val term: String) : PodcastInput
}

object ApplePodcastLinks {
    private val idInPath = Regex("""/id(\d{4,})""")
    private val idParam = Regex("""[?&]id=(\d{4,})""")

    fun itunesIdOf(url: String): Long? {
        val lower = url.lowercase()
        if (!lower.contains("apple.com") && !lower.contains("itunes.com")) return null
        return (idInPath.find(url) ?: idParam.find(url))?.groupValues?.get(1)?.toLongOrNull()
    }

    fun classify(raw: String): PodcastInput? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        itunesIdOf(s)?.let { return PodcastInput.Apple(it) }
        val looksLikeUrl = Regex("^(https?|feed|pcast|itpc)://", RegexOption.IGNORE_CASE).containsMatchIn(s) ||
            (s.contains('.') && !s.contains(' ') && s.contains('/'))
        return if (looksLikeUrl) PodcastInput.Feed(FeedValues.normalizeFeedUrl(s)) else PodcastInput.Search(s)
    }
}
