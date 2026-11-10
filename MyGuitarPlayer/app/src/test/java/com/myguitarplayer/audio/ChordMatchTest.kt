package com.myguitarplayer.audio

import com.myguitarplayer.music.Chords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

class ChordMatchTest {
    private val sr = PluckSynth.SAMPLE_RATE
    private val analyzer = ChromaAnalyzer(sr)

    private fun window(signal: FloatArray, startSec: Double, noise: Double = 0.0): FloatArray {
        val rnd = Random(11)
        val s = (startSec * sr).toInt()
        return FloatArray(analyzer.size) { signal[s + it] + (noise * (rnd.nextDouble() * 2 - 1)).toFloat() }
    }

    @Test
    fun fftFindsSinePeak() {
        val n = 1024
        val re = DoubleArray(n) { sin(2 * PI * 64 * it / n) }
        val im = DoubleArray(n)
        Fft(n).transform(re, im)
        val mags = DoubleArray(n / 2) { kotlin.math.hypot(re[it], im[it]) }
        assertEquals(64, mags.indices.maxBy { mags[it] })
        assertEquals(n / 2.0, mags[64], 1e-6)
    }

    @Test
    fun everyLibraryChordIsRecognisedFromASynthesisedStrum() {
        val misses = Chords.ALL.mapNotNull { chord ->
            val chroma = analyzer.chroma(window(PluckSynth.strum(chord), 0.15))
            assertNotNull(chord.name, chroma)
            val v = ChordMatcher.judge(chroma!!, chord)
            if (v.correct) null else "${chord.name}: score=${"%.3f".format(v.score)} best=${v.best}(${"%.3f".format(v.bestScore)}) missing=${v.missing}"
        }
        assertTrue(misses.joinToString("\n"), misses.isEmpty())
    }

    @Test
    fun recognisedWithBackgroundNoise() {
        listOf("Em", "Am", "C", "G", "D").forEach { name ->
            val chord = Chords[name]
            val v = ChordMatcher.judge(analyzer.chroma(window(PluckSynth.strum(chord), 0.2, noise = 0.03))!!, chord)
            assertTrue("$name score ${v.score} best ${v.best}", v.correct)
        }
    }

    @Test
    fun wrongChordIsRejectedAndNamed() {
        val chroma = analyzer.chroma(window(PluckSynth.strum(Chords["C"]), 0.15))!!
        val v = ChordMatcher.judge(chroma, Chords["Am"])
        assertFalse(v.correct)
        assertEquals("C", v.best.name)
    }

    @Test
    fun missingStringIsReported() {
        // Am with the index finger lifted: string 2 rings open B instead of C → C is missing.
        val sloppy = com.myguitarplayer.music.Chord(
            "Am?", intArrayOf(-1, 0, 2, 2, 0, 0), IntArray(6), com.myguitarplayer.music.ChordGroup.BASIC, "",
        )
        val v = ChordMatcher.judge(analyzer.chroma(window(PluckSynth.strum(sloppy), 0.15))!!, Chords["Am"])
        assertTrue("missing=${v.missing}", "C" in v.missing)
        assertFalse(v.correct)
        // A clean Am reports nothing missing.
        val clean = ChordMatcher.judge(analyzer.chroma(window(PluckSynth.strum(Chords["Am"]), 0.15))!!, Chords["Am"])
        assertTrue("clean missing=${clean.missing}", clean.missing.isEmpty())
    }

    /** Builds a stream of 2048-sample hops through the analyzer, like the mic does. */
    private fun frames(signal: FloatArray): List<Pair<Double, DoubleArray?>> {
        val hop = 2048
        val out = mutableListOf<Pair<Double, DoubleArray?>>()
        var end = analyzer.size
        while (end <= signal.size) {
            val w = signal.copyOfRange(end - analyzer.size, end)
            out += Yin.rms(w.copyOfRange(analyzer.size - hop, analyzer.size)) to analyzer.chroma(w)
            end += hop
        }
        return out
    }

    private fun sequence(vararg names: String, each: Double = 1.0): FloatArray {
        val parts = names.map { PluckSynth.strum(Chords[it], seconds = each) }
        val lead = FloatArray(sr / 2)
        return (listOf(lead) + parts).reduce { acc, p -> acc + p }
    }

    @Test
    fun strumJudgeCountsStrumsAndStreak() {
        val judge = StrumJudge(Chords["Em"])
        var state: StrumJudge.State? = null
        frames(sequence("Em", "Em", "Em", "Am")).forEach { (rms, c) -> state = judge.push(rms, c) }
        val s = state!!
        assertEquals(4, s.strums)
        // Three correct Em strums; the Am strum is the live verdict and not yet counted.
        assertEquals(3, s.correctStreak)
        assertFalse(s.verdict!!.correct)
    }

    @Test
    fun changeCounterCountsSwitches() {
        val counter = ChangeCounter(Chords["C"], Chords["G"])
        frames(sequence("C", "G", "C", "G", "C")).forEach { (_, c) -> counter.push(c) }
        assertEquals(4, counter.changes)
    }

    @Test
    fun synthIsInTune() {
        val yin = Yin(sr, 4096)
        val single = com.myguitarplayer.music.Chord("A", intArrayOf(-1, 0, -1, -1, -1, -1), IntArray(6), com.myguitarplayer.music.ChordGroup.BASIC, "")
        val f = yin.detect(window(PluckSynth.strum(single), 0.2).copyOf(4096))!!.freq
        assertTrue("freq $f", abs(f - 110.0) < 1.0)
    }
}
