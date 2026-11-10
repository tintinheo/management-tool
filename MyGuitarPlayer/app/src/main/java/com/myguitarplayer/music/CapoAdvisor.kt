package com.myguitarplayer.music

/**
 * What to finger for a song. [transpose] moves the sounding key (to suit the voice); [capo] raises the
 * guitar so easier shapes give that same sound. Shapes = original chords + transpose − capo.
 */
data class Fingering(val transpose: Int, val capo: Int) {
    val shapeShift: Int get() = transpose - capo

    fun soundingKey(song: Song): Key? = song.key?.transposed(transpose)
    fun shapeKey(song: Song): Key? = song.key?.transposed(shapeShift)

    /** Chord as the learner should finger it, spelled for the shape key. */
    fun shape(chord: String, song: Song): String =
        ChordSymbol.transpose(chord, shapeShift, shapeKey(song)?.prefersFlats ?: false)
}

object CapoAdvisor {
    const val MAX_CAPO = 7

    /** Small per-fret penalty: high capos sound thin and crowd the fretboard, so prefer the lower one. */
    private const val CAPO_COST = 0.05

    /** Difficulty of one chord for a beginner: 0 = open basic chord … 4 = needs a barre / unknown. */
    fun cost(chord: String): Double {
        val sym = ChordSymbol.parse(chord) ?: return 0.0 // not a chord (e.g. "N.C.")
        val match = sym.shape() ?: return 4.0
        val base = when (match.chord.name) {
            "F" -> 1.5
            "Bm" -> 3.0
            else -> when (match.chord.group) {
                ChordGroup.BASIC -> 0.0
                ChordGroup.MORE, ChordGroup.SEVENTH -> 1.0
            }
        }
        return base + if (match.exact) 0.0 else 0.5
    }

    data class Option(val capo: Int, val shapeKey: Key?, val cost: Double, val shapes: List<String>)

    /** Every capo 0…[MAX_CAPO] for this song at [transpose], easiest first (ties go to the lower capo). */
    fun options(song: Song, transpose: Int): List<Option> {
        val counts = song.chords.groupingBy { it }.eachCount()
        val total = counts.values.sum().coerceAtLeast(1)
        return (0..MAX_CAPO).map { capo ->
            val f = Fingering(transpose, capo)
            val shapes = song.uniqueChords.map { f.shape(it, song) }
            val cost = counts.entries.sumOf { (c, n) -> cost(f.shape(c, song)) * n } / total
            Option(capo, f.shapeKey(song), cost + capo * CAPO_COST, shapes)
        }.sortedBy { it.cost }
    }

    fun best(song: Song, transpose: Int): Option = options(song, transpose).first()
}
