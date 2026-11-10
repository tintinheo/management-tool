package com.myguitarplayer.music

/**
 * Chord symbols as written in songs ("G", "F#m7", "Bb/D", "Csus4"): parsing, transposing with
 * key-aware spelling, and mapping to a beginner shape from [Chords].
 */
data class ChordSymbol(val root: Int, val suffix: String, val bass: Int?) {
    val isMinor: Boolean get() = (suffix.startsWith("m") && !suffix.startsWith("maj")) || suffix.startsWith("min")
    private val isDominant7: Boolean get() = "7" in suffix && "maj7" !in suffix && "M7" !in suffix

    fun transposed(semitones: Int) = ChordSymbol(
        Math.floorMod(root + semitones, 12),
        suffix,
        bass?.let { Math.floorMod(it + semitones, 12) },
    )

    fun name(flats: Boolean): String {
        val names = if (flats) FLAT_NAMES else Note.SHARP_NAMES
        return names[root] + suffix + (bass?.let { "/" + names[it] } ?: "")
    }

    /**
     * The library voicing to show for this symbol: exact when possible ("Am", "E7", "Bm7"), otherwise
     * the closest simpler chord ("Am7" → Am, "Cadd9" → C, "G/B" → G). Null when nothing fits
     * (e.g. F#m, which needs a barre).
     */
    fun shape(): ShapeMatch? {
        val r = Note.SHARP_NAMES[root]
        Chords.find(r + suffix)?.takeIf { bass == null }?.let { return ShapeMatch(it, exact = true) }
        val tries = buildList {
            if (isMinor && isDominant7) add(r + "m7")
            if (!isMinor && isDominant7) add(r + "7")
            add(if (isMinor) r + "m" else r)
        }
        return tries.firstNotNullOfOrNull { Chords.find(it) }?.let { ShapeMatch(it, exact = false) }
    }

    companion object {
        val FLAT_NAMES = listOf("C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B")

        private val PATTERN = Regex("""^([A-G])([#b]?)((?:maj|min|dim|aug|sus|add|m|M|\+|°|ø|-|\d|\(|\)|#|b)*)(?:/([A-G])([#b]?))?$""")

        private fun pc(letter: String, accidental: String): Int {
            val base = Note.SHARP_NAMES.indexOf(letter)
            return Math.floorMod(base + if (accidental == "#") 1 else if (accidental == "b") -1 else 0, 12)
        }

        fun parse(text: String): ChordSymbol? {
            val m = PATTERN.matchEntire(text.trim()) ?: return null
            val (l, a, suffix, bl, ba) = m.destructured
            return ChordSymbol(pc(l, a), suffix, if (bl.isEmpty()) null else pc(bl, ba))
        }

        /** Transposes [text] by [semitones]; anything that is not a chord comes back unchanged. */
        fun transpose(text: String, semitones: Int, flats: Boolean): String =
            parse(text)?.transposed(semitones)?.name(flats) ?: text
    }
}

data class ShapeMatch(val chord: Chord, val exact: Boolean)

/** A song key: root pitch class plus major/minor. Decides whether to spell chords with ♭ or ♯. */
data class Key(val root: Int, val minor: Boolean) {
    val prefersFlats: Boolean get() = if (minor) root in MINOR_FLATS else root in MAJOR_FLATS

    val name: String get() = (if (prefersFlats) ChordSymbol.FLAT_NAMES else Note.SHARP_NAMES)[root] + if (minor) "m" else ""

    fun transposed(semitones: Int) = Key(Math.floorMod(root + semitones, 12), minor)

    companion object {
        // F Bb Eb Ab Db major; Dm Gm Cm Fm Bbm Ebm minor. (Gb/F# and G#m stay sharp.)
        private val MAJOR_FLATS = setOf(5, 10, 3, 8, 1)
        private val MINOR_FLATS = setOf(2, 7, 0, 5, 10, 3)

        fun of(text: String?): Key? = text?.let { ChordSymbol.parse(it) }?.let { Key(it.root, it.isMinor) }
    }
}
