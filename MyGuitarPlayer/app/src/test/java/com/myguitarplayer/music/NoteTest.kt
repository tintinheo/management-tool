package com.myguitarplayer.music

import com.myguitarplayer.ui.tuner.TunerTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteTest {
    @Test
    fun standardTuningFrequencies() {
        assertEquals(82.41, GuitarString.E2.freq, 0.01)
        assertEquals(110.0, GuitarString.A2.freq, 0.01)
        assertEquals(329.63, GuitarString.E4.freq, 0.01)
        assertEquals("E2", GuitarString.E2.label)
        assertEquals("B3", GuitarString.B3.label)
    }

    @Test
    fun readingPicksNearestStringUnlessLocked() {
        val r = TunerReading.of(100.0) // between E2 and A2, closer to G#2/A2
        assertEquals(GuitarString.A2, r.string)
        val locked = TunerReading.of(100.0, lockedString = GuitarString.E2)
        assertEquals(GuitarString.E2, locked.string)
        assertTrue(locked.centsFromString > 300)
    }

    @Test
    fun trackerMarksStringTunedAfterHoldingPitch() {
        val t = TunerTracker()
        repeat(7) { t.push(GuitarString.G3.freq, null) }
        assertTrue(t.tuned.isEmpty())
        t.push(GuitarString.G3.freq, null)
        assertEquals(setOf(GuitarString.G3), t.tuned)
    }

    @Test
    fun strumPatternsAreOneBarOfEighths() {
        StrumPattern.entries.forEach { assertEquals(8, it.strokes.length) }
    }
}
