package com.myguitarplayer.audio

import com.myguitarplayer.music.GuitarString
import com.myguitarplayer.music.Note
import com.myguitarplayer.music.TunerReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

class YinTest {
    private val sr = MicStream.SAMPLE_RATE
    private val yin = Yin(sr, 4096)

    /** Guitar-like tone: fundamental + decaying harmonics (2nd/3rd louder than the fundamental on low strings). */
    private fun pluck(freq: Double, noise: Double = 0.0, seed: Int = 1): FloatArray {
        val rnd = Random(seed)
        val amps = doubleArrayOf(0.6, 1.0, 0.7, 0.4, 0.25, 0.15)
        return FloatArray(4096) { i ->
            val t = i.toDouble() / sr
            var s = 0.0
            for (h in amps.indices) s += amps[h] * sin(2 * PI * freq * (h + 1) * t + h)
            (s * 0.15 * exp(-t * 2) + noise * (rnd.nextDouble() * 2 - 1)).toFloat()
        }
    }

    @Test
    fun detectsEveryOpenStringWithin2Cents() {
        GuitarString.entries.forEach { s ->
            val p = yin.detect(pluck(s.freq))
            assertNotNull(s.name, p)
            val cents = (Note.midiOf(p!!.freq) - s.midi) * 100
            assertTrue("${s.name}: $cents cents", abs(cents) < 2)
        }
    }

    @Test
    fun detectsSlightlyFlatStringAndReportsIt() {
        val flat = Note.freqOf(GuitarString.A2.midi - 0.2) // 20 cents flat
        val r = TunerReading.of(yin.detect(pluck(flat))!!.freq)
        assertEquals(GuitarString.A2, r.string)
        assertEquals(-20.0, r.centsFromString, 2.0)
        assertTrue(!r.inTune)
    }

    @Test
    fun survivesModerateNoise() {
        val p = yin.detect(pluck(GuitarString.D3.freq, noise = 0.02))!!
        assertEquals(GuitarString.D3.freq, p.freq, 1.0)
    }

    @Test
    fun silenceGivesNoPitch() {
        assertNull(yin.detect(FloatArray(4096)))
    }

    @Test
    fun whiteNoiseGivesNoConfidentPitch() {
        val rnd = Random(7)
        val noise = FloatArray(4096) { (rnd.nextDouble() * 0.2 - 0.1).toFloat() }
        val p = yin.detect(noise)
        assertTrue("noise detected as $p", p == null || p.confidence < 0.8)
    }
}
