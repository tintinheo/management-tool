package com.myguitarplayer.audio

import com.myguitarplayer.music.Chords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class RhythmTest {
    private val sr = PluckSynth.SAMPLE_RATE
    private val clickHz = listOf(1175.0, 1760.0)

    /** Strums at [times] (seconds) with gain [gains], plus optional metronome clicks on every beat and noise. */
    private fun render(
        seconds: Double,
        times: List<Double>,
        gains: List<Double> = times.map { 1.0 },
        clicksBpm: Int? = null,
        noise: Double = 0.002,
    ): FloatArray {
        val out = FloatArray((seconds * sr).toInt())
        val chords = listOf(Chords["G"], Chords["C"], Chords["D"], Chords["Em"])
        times.forEachIndexed { i, t ->
            // A real strum crosses the six strings in ~20–40 ms.
            val s = PluckSynth.strum(chords[i % chords.size], seconds = 1.5, spreadMs = 6, seed = i)
            val start = (t * sr).toInt()
            // Re-strumming takes over the strings: what was ringing drops to ~30 % within ~20 ms.
            for (k in start until out.size) out[k] *= (0.3 + 0.7 * kotlin.math.exp(-(k - start) / (0.02 * sr))).toFloat()
            for (k in s.indices) if (start + k < out.size) out[start + k] += s[k] * gains[i].toFloat() * 0.5f
        }
        if (clicksBpm != null) {
            val beat = 60.0 / clicksBpm
            var b = 0
            while (b * beat < seconds) {
                val c = Metronome.click(if (b % 4 == 0) 1760.0 else 1175.0)
                val start = (b * beat * sr).toInt()
                for (k in c.indices) if (start + k < out.size) out[start + k] += c[k] / 32768f * 0.6f
                b++
            }
        }
        val rnd = Random(1)
        for (i in out.indices) out[i] += ((rnd.nextDouble() * 2 - 1) * noise).toFloat()
        return out
    }

    private fun detect(signal: FloatArray, ignore: List<Double> = clickHz): List<Double> {
        val d = OnsetDetector(sr, ignoreHz = ignore)
        // Feed in mic-sized chunks, like the app does.
        return signal.toList().chunked(512).flatMap { d.push(it.toFloatArray()) }.map { it.toDouble() / sr }
    }

    private fun assertMatches(truth: List<Double>, found: List<Double>, tolMs: Double) {
        assertEquals("onset count: found $found", truth.size, found.size)
        truth.zip(found).forEach { (t, f) -> assertTrue("truth $t found $f", abs(t - f) * 1000 < tolMs) }
    }

    @Test
    fun findsEveryStrumWithin15ms() {
        val truth = (0 until 16).map { 0.5 + it * 60.0 / 80 } // quarter notes at 80 BPM
        assertMatches(truth, detect(render(13.0, truth)), 15.0)
    }

    @Test
    fun findsSoftUpstrokesInDownUp() {
        // DUDU eighths at 70 BPM, up-strokes at half strength.
        val truth = (0 until 24).map { 0.4 + it * 30.0 / 70 }
        val gains = truth.indices.map { if (it % 2 == 0) 1.0 else 0.5 }
        assertMatches(truth, detect(render(11.5, truth, gains)), 15.0)
    }

    @Test
    fun keepsUpWithFastEighths() {
        // DUDU at 140 BPM (214 ms apart) over the click, soft up-strokes.
        val truth = (0 until 32).map { 0.5 + it * 30.0 / 140 + 0.01 }
        val gains = truth.indices.map { if (it % 2 == 0) 1.0 else 0.6 }
        // Fast + soft is the hardest case; 20 ms is still well inside the 60 ms scoring band.
        assertMatches(truth, detect(render(8.0, truth, gains, clicksBpm = 140)), 20.0)
    }

    @Test
    fun ignoresTheMetronomeClick() {
        val clicksOnly = detect(render(8.0, emptyList(), clicksBpm = 80))
        assertTrue("clicks were detected as strums: $clicksOnly", clicksOnly.size <= 1)
        // Without the click filter the same signal does trigger — proves the filter is what helps.
        assertTrue(detect(render(8.0, emptyList(), clicksBpm = 80), ignore = emptyList()).size >= 6)
    }

    @Test
    fun strumsOverClicksAreStillFound() {
        // Strums slightly off the clicks (a human is never exact); clicks must not add onsets.
        val truth = (0 until 12).map { 0.75 + it * 0.75 + 0.03 }
        assertMatches(truth, detect(render(10.0, truth, clicksBpm = 80)), 15.0)
    }

    @Test
    fun silenceAndNoiseGiveNothing() {
        assertTrue(detect(render(4.0, emptyList(), noise = 0.0)).isEmpty())
        assertTrue(detect(render(4.0, emptyList(), noise = 0.01)).isEmpty())
    }

    @Test
    fun scorerPlacesStrumsOnTheGrid() {
        val step = RhythmScorer.stepNanos(60) // 0.5 s eighths
        assertEquals(500_000_000L, step)
        val origin = 1_000_000_000L
        val latency = 100_000_000L
        val s = RhythmScorer(origin, step, "D.DU.UDU", latency)
        // Slot 0 played 20 ms late, slot 2 30 ms early, slot 3 on time, slot 1 (not in pattern) an extra.
        assertEquals(Hit(0, 20.0, true), s.add(origin + latency + 20_000_000))
        assertEquals(Hit(2, -30.0, true), s.add(origin + latency + 2 * step - 30_000_000))
        assertEquals(Hit(3, 0.0, true), s.add(origin + latency + 3 * step))
        assertFalse(s.add(origin + latency + step + 5_000_000)!!.expected)
        // Double detection of the same strum is ignored; onsets before the scored part too.
        assertEquals(null, s.add(origin + latency + 3 * step + 40_000_000))
        assertEquals(null, s.add(origin - step))

        val sum = s.summary(steps = 4) // slots 0..3: pattern expects 0, 2, 3
        assertEquals(3, sum.expectedSlots)
        assertEquals(1.0, sum.hitRate, 1e-9)
        assertEquals(50.0 / 3, sum.meanAbsMs, 1e-9)
        assertEquals(-10.0 / 3, sum.meanSignedMs, 1e-9)
        assertEquals(1, sum.extras)
        assertTrue(sum.passes())
        // Over a full bar, slots 5, 6, 7 were missed.
        assertEquals(0.5, s.summary(8).hitRate, 1e-9)
        assertFalse(s.summary(8).passes())
    }

    @Test
    fun calibrationTakesTheMedianLatency() {
        val beat = 750_000_000L
        val first = 10_000_000_000L
        val onsets = listOf(118, 131, 125, 400, 122, 127, 119, 124).mapIndexed { i, ms -> first + i * beat + ms * 1_000_000L }
        val lat = RhythmScorer.latencyFrom(onsets, first, beat)
        assertNotNull(lat)
        assertEquals(124.0, lat!! / 1e6, 4.0)
        assertEquals(null, RhythmScorer.latencyFrom(onsets.take(3), first, beat))
    }

    @Test
    fun endToEndDetectorPlusScorer() {
        // 70 BPM, pattern D.D.D.D. (quarters); a player 25 ms late on every strum, 2 bars.
        val bpm = 70
        val beat = 60.0 / bpm
        val start = 1.0
        val truth = (0 until 8).map { start + it * beat + 0.025 }
        val onsets = detect(render(9.0, truth, clicksBpm = bpm))
        val scorer = RhythmScorer((start * 1e9).toLong(), RhythmScorer.stepNanos(bpm), "D.D.D.D.", 0)
        onsets.forEach { scorer.add((it * 1e9).toLong()) }
        val sum = scorer.summary(16)
        assertEquals(1.0, sum.hitRate, 1e-9)
        assertEquals(25.0, sum.meanSignedMs, 12.0)
        assertTrue(sum.passes())
    }
}
