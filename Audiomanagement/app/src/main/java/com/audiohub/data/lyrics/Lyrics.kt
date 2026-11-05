package com.audiohub.data.lyrics

data class LyricLine(val timeMs: Long, val text: String)

sealed interface Lyrics {
    data class Synced(val lines: List<LyricLine>) : Lyrics {
        /** Index of the line being sung at [positionMs], or -1 before the first line. */
        fun indexAt(positionMs: Long): Int {
            var lo = 0
            var hi = lines.lastIndex
            var found = -1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (lines[mid].timeMs <= positionMs) {
                    found = mid; lo = mid + 1
                } else {
                    hi = mid - 1
                }
            }
            return found
        }
    }

    data class Plain(val text: String) : Lyrics
}

/** Parses LRC ("[01:23.45]line") text; anything without timestamps is returned as plain lyrics. */
object LrcParser {
    private val timeTag = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val offsetTag = Regex("""\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)
    private val idTag = Regex("""^\[[a-zA-Z]+:.*]$""")

    fun parse(raw: String): Lyrics? {
        val text = raw.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (text.isEmpty()) return null
        val offset = offsetTag.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

        val synced = ArrayList<LyricLine>()
        for (line in text.lines()) {
            var rest = line.trim()
            val times = ArrayList<Long>()
            while (true) {
                val m = timeTag.matchAt(rest, 0) ?: break
                val (min, sec, frac) = m.destructured
                val fracMs = if (frac.isEmpty()) 0 else frac.padEnd(3, '0').take(3).toInt()
                times += min.toLong() * 60_000 + sec.toLong() * 1_000 + fracMs
                rest = rest.substring(m.range.last + 1)
            }
            times.forEach { synced += LyricLine((it - offset).coerceAtLeast(0), rest.trim()) }
        }
        if (synced.size >= 2) return Lyrics.Synced(synced.sortedBy { it.timeMs })

        val plain = text.lines().filterNot { idTag.matches(it.trim()) }.joinToString("\n").trim()
        return if (plain.isEmpty()) null else Lyrics.Plain(plain)
    }
}
