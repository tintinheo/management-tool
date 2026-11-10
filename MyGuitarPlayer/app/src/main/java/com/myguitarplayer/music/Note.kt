package com.myguitarplayer.music

import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

/** Equal-temperament note math, A4 = 440 Hz = MIDI 69. */
object Note {
    val SHARP_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    fun midiOf(freq: Double): Double = 69 + 12 * log2(freq / 440.0)
    fun freqOf(midi: Double): Double = 440.0 * 2.0.pow((midi - 69) / 12)
    fun name(midi: Int): String = SHARP_NAMES[Math.floorMod(midi, 12)]
    fun octave(midi: Int): Int = Math.floorDiv(midi, 12) - 1
    fun label(midi: Int): String = name(midi) + octave(midi)
}

/** Standard tuning, string 6 (low E) to string 1 (high E). */
enum class GuitarString(val number: Int, val midi: Int) {
    E2(6, 40), A2(5, 45), D3(4, 50), G3(3, 55), B3(2, 59), E4(1, 64);

    val label: String get() = Note.label(midi)
    val freq: Double get() = Note.freqOf(midi.toDouble())
}

/** What the tuner shows for one detected frequency. */
data class TunerReading(
    val freq: Double,
    val noteMidi: Int,
    /** Closest open string in standard tuning. */
    val string: GuitarString,
    /** Cents off that string's target (negative = flat, tighten the string). */
    val centsFromString: Double,
    /** Cents off the nearest note of any name. */
    val centsFromNote: Double,
) {
    val noteName: String get() = Note.name(noteMidi)
    val inTune: Boolean get() = abs(centsFromString) <= IN_TUNE_CENTS

    companion object {
        const val IN_TUNE_CENTS = 5.0

        fun of(freq: Double, lockedString: GuitarString? = null): TunerReading {
            val m = Note.midiOf(freq)
            val nearest = m.roundToInt()
            val string = lockedString ?: GuitarString.entries.minBy { abs(it.midi - m) }
            return TunerReading(freq, nearest, string, (m - string.midi) * 100, (m - nearest) * 100)
        }
    }
}
