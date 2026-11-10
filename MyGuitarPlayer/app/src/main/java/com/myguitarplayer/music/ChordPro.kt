package com.myguitarplayer.music

/** A run of lyrics with the chord (if any) that starts on its first syllable. */
data class Segment(val chord: String?, val text: String)

sealed interface SongLine {
    data class Lyrics(val segments: List<Segment>) : SongLine {
        val hasChords: Boolean get() = segments.any { it.chord != null }
        val hasText: Boolean get() = segments.any { it.text.isNotBlank() }
    }

    /** "Điệp khúc", "Verse 2", "Intro"… */
    data class Section(val label: String) : SongLine

    /** A note to the player, e.g. "Lặp lại 2 lần" or "Quạt chậm". */
    data class Comment(val text: String) : SongLine

    data object Blank : SongLine
}

data class Song(
    val title: String,
    val artist: String,
    /** Key as written in the source (from {key:} or the first chord). */
    val key: Key?,
    val capo: Int,
    val tempo: Int?,
    val beatsPerBar: Int,
    /** Strum pattern from the non-standard {strum: D.DU.UDU} directive. */
    val strum: String?,
    val lines: List<SongLine>,
) {
    /** Every chord occurrence, in playing order. */
    val chords: List<String> get() = lines.filterIsInstance<SongLine.Lyrics>().flatMap { l -> l.segments.mapNotNull { it.chord } }

    val uniqueChords: List<String> get() = chords.distinct()
}

/**
 * Reads ChordPro ("[G]Twinkle twinkle [C]little [G]star") with the common directives, and converts the
 * "chords on their own line above the lyrics" layout used by most chord sites.
 */
object ChordPro {
    private val BRACKET = Regex("""\[([^\]]*)]""")

    fun parse(text: String, title: String = "", artist: String = ""): Song {
        var t = title
        var a = artist
        var key: String? = null
        var capo = 0
        var tempo: Int? = null
        var beats = 4
        var strum: String? = null
        val lines = mutableListOf<SongLine>()

        fun add(line: SongLine) {
            if (line == SongLine.Blank && (lines.isEmpty() || lines.last() == SongLine.Blank)) return
            lines += line
        }

        for (raw in text.replace("\r", "").lines()) {
            val line = raw.trimEnd()
            val trimmed = line.trim()
            when {
                trimmed.startsWith("#") -> Unit
                trimmed.isEmpty() -> add(SongLine.Blank)
                trimmed.startsWith("{") && trimmed.endsWith("}") -> {
                    val body = trimmed.substring(1, trimmed.length - 1).trim()
                    val split = body.indexOfFirst { it == ':' || it == ' ' }
                    val name = (if (split < 0) body else body.substring(0, split)).lowercase()
                    val value = if (split < 0) "" else body.substring(split + 1).trim()
                    when (name) {
                        "title", "t" -> if (t.isBlank()) t = value
                        "artist", "subtitle", "st", "composer" -> if (a.isBlank()) a = value
                        "key" -> key = value
                        "capo" -> capo = value.toIntOrNull()?.coerceIn(0, 11) ?: 0
                        "tempo" -> tempo = value.toIntOrNull()
                        "time" -> beats = value.substringBefore('/').trim().toIntOrNull()?.coerceIn(2, 12) ?: 4
                        "strum" -> strum = value.ifBlank { null }
                        "comment", "c", "ci", "comment_italic", "cb", "comment_box", "highlight" -> add(SongLine.Comment(value))
                        "soc", "start_of_chorus" -> add(SongLine.Section(value.ifBlank { "Điệp khúc" }))
                        "sov", "start_of_verse" -> add(SongLine.Section(value.ifBlank { "Lời" }))
                        "sob", "start_of_bridge" -> add(SongLine.Section(value.ifBlank { "Chuyển đoạn" }))
                        "eoc", "end_of_chorus", "eov", "end_of_verse", "eob", "end_of_bridge" -> add(SongLine.Blank)
                        else -> Unit
                    }
                }
                else -> add(parseLyrics(line))
            }
        }
        while (lines.lastOrNull() == SongLine.Blank) lines.removeAt(lines.lastIndex)

        val chords = lines.filterIsInstance<SongLine.Lyrics>().flatMap { l -> l.segments.mapNotNull { it.chord } }
        val songKey = Key.of(key) ?: chords.firstNotNullOfOrNull { Key.of(it) }
        return Song(t.ifBlank { "Bài không tên" }, a, songKey, capo, tempo, beats, strum, lines)
    }

    private fun parseLyrics(line: String): SongLine {
        val matches = BRACKET.findAll(line).toList()
        if (matches.isEmpty()) return SongLine.Lyrics(listOf(Segment(null, line)))
        // "[Chorus]" / "[ĐK]" alone on a line is a section label, not a chord.
        if (matches.size == 1 && line.trim() == matches[0].value && ChordSymbol.parse(matches[0].groupValues[1]) == null) {
            return SongLine.Section(matches[0].groupValues[1].trim())
        }
        val segments = mutableListOf<Segment>()
        if (matches[0].range.first > 0) segments += Segment(null, line.substring(0, matches[0].range.first))
        matches.forEachIndexed { i, m ->
            val end = if (i + 1 < matches.size) matches[i + 1].range.first else line.length
            segments += Segment(m.groupValues[1].trim(), line.substring(m.range.last + 1, end))
        }
        return SongLine.Lyrics(segments)
    }

    // ---- Import: "chords over lyrics" → ChordPro ----

    private val FILLER = setOf("|", "||", "-", "/", "%", "x2", "x3", "x4", "(x2)", "(x3)", "(x4)", "*")

    private fun chordToken(token: String): String? = ChordSymbol.parse(token.trim('|', '(', ')', ','))?.let { token.trim('|', '(', ')', ',') }

    /** A line made only of chord names (plus bar lines / repeat marks). */
    fun isChordLine(line: String): Boolean {
        val tokens = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return tokens.any { chordToken(it) != null } && tokens.all { chordToken(it) != null || it.lowercase() in FILLER }
    }

    /** True when the text already has [Chord] markers. */
    fun looksLikeChordPro(text: String): Boolean =
        BRACKET.findAll(text).any { ChordSymbol.parse(it.groupValues[1]) != null } || text.lines().any { it.trim().startsWith("{") }

    private val PAREN_CHORD = Regex("""\(([A-G][^()\s]*)\)""")

    /**
     * Normalises pasted text to ChordPro. ChordPro input is kept as is; "(Am)" inline chords become "[Am]";
     * a chord line is merged into the lyric line under it by column.
     */
    fun toChordPro(text: String): String {
        val src = text.replace("\r", "").replace("\t", "    ")
        if (looksLikeChordPro(src)) return src.trim()
        val inline = PAREN_CHORD.replace(src) { m -> if (ChordSymbol.parse(m.groupValues[1]) != null) "[${m.groupValues[1]}]" else m.value }
        if (inline != src) return inline.trim()

        val lines = src.lines()
        val out = mutableListOf<String>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (isChordLine(line)) {
                val next = lines.getOrNull(i + 1)
                if (next != null && next.isNotBlank() && !isChordLine(next)) {
                    out += merge(line, next)
                    i += 2
                    continue
                }
                out += merge(line, "")
            } else {
                out += line.trimEnd()
            }
            i++
        }
        return out.joinToString("\n").trim()
    }

    private fun merge(chordLine: String, lyric: String): String {
        val chords = Regex("\\S+").findAll(chordLine).mapNotNull { m -> chordToken(m.value)?.let { m.range.first to it } }.toList()
        if (lyric.isEmpty()) return chords.joinToString(" ") { "[${it.second}]" }
        val sb = StringBuilder(lyric.trimEnd())
        for ((col, chord) in chords.asReversed()) {
            while (sb.length < col) sb.append(' ')
            sb.insert(col, "[$chord]")
        }
        return sb.toString()
    }
}
