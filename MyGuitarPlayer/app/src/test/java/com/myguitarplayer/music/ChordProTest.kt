package com.myguitarplayer.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChordProTest {
    @Test
    fun parsesChordSymbols() {
        assertEquals(ChordSymbol(7, "", null), ChordSymbol.parse("G"))
        assertEquals(ChordSymbol(6, "m7", null), ChordSymbol.parse("F#m7"))
        assertEquals(ChordSymbol(10, "", 2), ChordSymbol.parse("Bb/D"))
        assertEquals(ChordSymbol(0, "sus4", null), ChordSymbol.parse("Csus4"))
        assertTrue(ChordSymbol.parse("Am")!!.isMinor)
        assertFalse(ChordSymbol.parse("Cmaj7")!!.isMinor)
        listOf("Chorus", "ĐK", "x2", "Hello", "", "H7").forEach { assertNull(it, ChordSymbol.parse(it)) }
    }

    @Test
    fun transposesWithKeySpelling() {
        // G +3 → Bb (flat key), not A#
        val bb = Key.of("G")!!.transposed(3)
        assertEquals("Bb", bb.name)
        assertEquals("Bb", ChordSymbol.transpose("G", 3, bb.prefersFlats))
        assertEquals("Eb", ChordSymbol.transpose("C", 3, bb.prefersFlats))
        assertEquals("F", ChordSymbol.transpose("D", 3, bb.prefersFlats))
        // Sharp keys stay sharp
        assertEquals("F#m", ChordSymbol.transpose("Em", 2, Key.of("D")!!.prefersFlats))
        assertEquals("A/C#", ChordSymbol.transpose("G/B", 2, false))
        assertEquals("N.C.", ChordSymbol.transpose("N.C.", 2, false))
        assertEquals("Dm", Key.of("Am")!!.transposed(5).name)
    }

    @Test
    fun shapesFallBackToSimplerChords() {
        assertEquals("Am" to true, ChordSymbol.parse("Am")!!.shape()!!.let { it.chord.name to it.exact })
        assertEquals("Am" to false, ChordSymbol.parse("Am7")!!.shape()!!.let { it.chord.name to it.exact })
        assertEquals("C" to false, ChordSymbol.parse("Cadd9")!!.shape()!!.let { it.chord.name to it.exact })
        assertEquals("G" to false, ChordSymbol.parse("G/B")!!.shape()!!.let { it.chord.name to it.exact })
        assertEquals("E7", ChordSymbol.parse("E7")!!.shape()!!.chord.name)
        assertNull(ChordSymbol.parse("F#m")!!.shape())
    }

    @Test
    fun parsesChordProWithDirectives() {
        val song = ChordPro.parse(
            """
            {title: Test Song}
            {artist: Someone}
            {key: G}
            {tempo: 80}
            {time: 3/4}
            {strum: D.DU.UDU}
            # a comment line

            {soc}
            [G]Twinkle, twinkle, [C]little [G]star
            {eoc}
            {c: Lặp lại 2 lần}
            [Verse 2]
            Up a[D]bove
            """.trimIndent(),
        )
        assertEquals("Test Song", song.title)
        assertEquals("Someone", song.artist)
        assertEquals(Key(7, false), song.key)
        assertEquals(80, song.tempo)
        assertEquals(3, song.beatsPerBar)
        assertEquals("D.DU.UDU", song.strum)
        assertEquals(listOf("G", "C", "G", "D"), song.chords)
        assertEquals(listOf("G", "C", "D"), song.uniqueChords)
        assertEquals(SongLine.Section("Điệp khúc"), song.lines[0])
        val first = song.lines[1] as SongLine.Lyrics
        assertEquals(listOf(Segment("G", "Twinkle, twinkle, "), Segment("C", "little "), Segment("G", "star")), first.segments)
        assertTrue(song.lines.contains(SongLine.Comment("Lặp lại 2 lần")))
        assertTrue(song.lines.contains(SongLine.Section("Verse 2")))
        val last = song.lines.last() as SongLine.Lyrics
        assertEquals(listOf(Segment(null, "Up a"), Segment("D", "bove")), last.segments)
    }

    @Test
    fun keyFallsBackToFirstChord() {
        assertEquals(Key(9, true), ChordPro.parse("[Am]Hôm nay [G]trời").key)
        assertNull(ChordPro.parse("chỉ có lời").key)
    }

    @Test
    fun convertsChordsOverLyrics() {
        val pasted = """
            Intro: | Am | G |
            Am            F
            Hôm nay trời nắng
            C         G
            Em đi đâu đó
            Am  G  C
            """.trimIndent()
        val cho = ChordPro.toChordPro(pasted)
        assertEquals(
            """
            Intro: | Am | G |
            [Am]Hôm nay trời n[F]ắng
            [C]Em đi đâu [G]đó
            [Am] [G] [C]
            """.trimIndent(),
            cho,
        )
        assertFalse(ChordPro.isChordLine("Em đi đâu đó"))
        assertTrue(ChordPro.isChordLine("| Am | G7 | x2"))
    }

    @Test
    fun convertsParenthesisChordsAndKeepsChordPro() {
        assertEquals("[Am]Hôm nay [G]trời (x2)", ChordPro.toChordPro("(Am)Hôm nay (G)trời (x2)"))
        val cho = "{title: A}\n[G]La la"
        assertEquals(cho, ChordPro.toChordPro(cho))
    }

    @Test
    fun capoAdvisorFindsOpenShapes() {
        // A song in Bb: capo 3 lets you play G-family shapes.
        val song = ChordPro.parse("[Bb]One [Eb]two [F]three [Gm]four")
        val best = CapoAdvisor.best(song, transpose = 0)
        assertEquals(3, best.capo)
        assertEquals("G", best.shapeKey!!.name)
        assertEquals(listOf("G", "C", "D", "Em"), best.shapes)
        // A song already in G needs no capo.
        assertEquals(0, CapoAdvisor.best(ChordPro.parse("[G]a [C]b [D]c [Em]d"), 0).capo)
    }

    @Test
    fun fingeringCombinesTransposeAndCapo() {
        val song = ChordPro.parse("[G]a [D]b")
        val f = Fingering(transpose = 2, capo = 2) // sound in A, play G shapes
        assertEquals("A", f.soundingKey(song)!!.name)
        assertEquals("G", f.shape("G", song))
        assertEquals("D", f.shape("D", song))
        assertEquals("Bb", Fingering(3, 0).shape("G", song))
    }

    @Test
    fun bundledSamplesParseAndUseBeginnerChords() {
        val dir = listOf("src/main/assets/songs", "app/src/main/assets/songs").map(::File).first { it.isDirectory }
        val files = dir.listFiles { f -> f.name.endsWith(".cho") }!!.sortedBy { it.name }
        assertTrue(files.size >= 6)
        for (file in files) {
            val song = ChordPro.parse(file.readText())
            assertNotNull(file.name, song.key)
            assertNotNull(file.name, song.tempo)
            assertTrue(file.name, song.title != "Bài không tên")
            assertTrue(file.name, song.chords.size >= 4)
            for (c in song.uniqueChords) {
                val match = ChordSymbol.parse(c)?.shape()
                assertTrue("${file.name}: $c must be an exact library chord", match?.exact == true)
            }
            assertEquals(file.name, 0, CapoAdvisor.best(song, 0).capo)
        }
    }
}
