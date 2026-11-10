package com.myguitarplayer.audio

import com.myguitarplayer.music.Chords
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioConfigTest {
    @After
    fun restore() {
        AudioConfig.noiseRms = null
        AudioConfig.chordLevel = 1
        AudioConfig.onsetLevel = 1
    }

    @Test
    fun defaultsAreTheTestedThresholds() {
        assertEquals(0.006, AudioConfig.chromaSilence, 1e-12)
        assertEquals(0.003, AudioConfig.onsetSilence, 1e-12)
        assertEquals(0.85, AudioConfig.match, 1e-12)
        assertEquals(6.0, AudioConfig.onsetSensitivity, 1e-12)
        assertFalse(AudioConfig.calibrated)
    }

    @Test
    fun gatesFollowRoomNoiseWithinLimits() {
        AudioConfig.noiseRms = 0.001
        assertEquals(0.003, AudioConfig.chromaSilence, 1e-12)
        assertEquals(0.0016, AudioConfig.onsetSilence, 1e-12)
        AudioConfig.noiseRms = 1e-6 // dead-silent mic: gates must not drop to zero
        assertEquals(0.0015, AudioConfig.chromaSilence, 1e-12)
        assertEquals(0.001, AudioConfig.onsetSilence, 1e-12)
        AudioConfig.noiseRms = 0.5 // very loud room: capped so a strum can still open the gate
        assertEquals(0.03, AudioConfig.chromaSilence, 1e-12)
        assertEquals(0.02, AudioConfig.onsetSilence, 1e-12)
    }

    @Test
    fun strictnessChangesTheVerdict() {
        val g = Chords["G"]
        // A G smeared by a stray C#, just enough that its similarity falls between "Dễ" (0.80) and "Vừa" (0.85).
        val v = generateSequence(0.0) { it + 0.01 }.takeWhile { it < 1.5 }.map { noise ->
            val chroma = g.template.copyOf()
            chroma[1] = noise
            ChordMatcher.judge(chroma, g)
        }.first { it.score in 0.81..0.84 }
        assertTrue("best ${v.best.name}, missing ${v.missing}", v.best == g && v.missing.isEmpty())
        AudioConfig.chordLevel = 0
        assertTrue(v.correct)
        AudioConfig.chordLevel = 2
        assertFalse(v.correct)
    }
}
