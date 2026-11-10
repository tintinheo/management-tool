package com.myguitarplayer.audio

import com.myguitarplayer.music.Chord
import com.myguitarplayer.music.Note
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** Karplus-Strong plucked strings: a believable guitar strum without bundling any samples. */
object PluckSynth {
    const val SAMPLE_RATE = 44_100

    /** Down-strum of [chord]: strings sound low→high, [spreadMs] apart. */
    fun strum(chord: Chord, seconds: Double = 2.0, spreadMs: Int = 18, seed: Int = 3): FloatArray =
        notes(chord.midiNotes, seconds, spreadMs, seed)

    /** One plucked string at [midi] (used by the fretboard: tap a position, hear its note). */
    fun note(midi: Int, seconds: Double = 1.6): FloatArray = notes(listOf(midi), seconds, 0, 5)

    private fun notes(midis: List<Int>, seconds: Double, spreadMs: Int, seed: Int): FloatArray {
        val out = FloatArray((seconds * SAMPLE_RATE).toInt())
        val rnd = Random(seed)
        midis.forEachIndexed { i, midi ->
            pluckInto(out, Note.freqOf(midi.toDouble()), i * spreadMs * SAMPLE_RATE / 1000, rnd)
        }
        val peak = out.maxOf { abs(it) }
        if (peak > 0) for (i in out.indices) out[i] = out[i] / peak * 0.8f
        // Short fade-out so playback doesn't click.
        val fade = SAMPLE_RATE / 20
        for (i in 0 until fade) out[out.size - 1 - i] *= i.toFloat() / fade
        return out
    }

    private fun pluckInto(out: FloatArray, freq: Double, start: Int, rnd: Random) {
        // The two-point average adds half a sample of delay.
        val n = (SAMPLE_RATE / freq - 0.5).roundToInt().coerceAtLeast(2)
        // Excitation with a guitar-like 1/h harmonic spectrum and random phases. Pure white noise would
        // give every harmonic a random level, so the same chord would sound (and analyse) different each time.
        val line = FloatArray(n)
        for (h in 1..minOf(40, n / 2 - 1)) {
            val amp = 1.0 / h
            val phase = rnd.nextDouble() * 2 * PI
            for (i in 0 until n) line[i] += (amp * sin(2 * PI * h * i / n + phase)).toFloat()
        }
        var idx = 0
        for (t in start until out.size) {
            val cur = line[idx]
            val next = line[(idx + 1) % n]
            out[t] += cur
            line[idx] = 0.996f * 0.5f * (cur + next)
            idx = (idx + 1) % n
        }
    }
}
