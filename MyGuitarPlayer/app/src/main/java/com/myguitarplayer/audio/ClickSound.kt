package com.myguitarplayer.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/** The metronome's grid and click waveform, shared by the Android and Windows metronomes. */
object ClickSound {
    const val SAMPLE_RATE = 44_100

    /** Samples per eighth-note step at [bpm]. */
    fun stepSamples(bpm: Int): Int = SAMPLE_RATE * 60 / bpm / 2

    /**
     * 35 ms decaying sine "tick" with a 4 ms raised-cosine attack and a 5 ms fade-out. The soft edges keep
     * its energy in a narrow band around [freq], which the rhythm checker filters out — a hard edge would
     * splash across the spectrum and look like a strum to the mic.
     */
    fun click(freq: Double): ShortArray {
        val n = SAMPLE_RATE * 35 / 1000
        val attack = SAMPLE_RATE * 4 / 1000
        val fade = SAMPLE_RATE * 5 / 1000
        return ShortArray(n) { k ->
            val t = k.toDouble() / SAMPLE_RATE
            val head = if (k < attack) 0.5 - 0.5 * cos(PI * k / attack) else 1.0
            val tail = minOf(1.0, (n - k).toDouble() / fade)
            (sin(2 * PI * freq * t) * exp(-t * 90) * head * tail * 0.8 * Short.MAX_VALUE).toInt().toShort()
        }
    }

    /** One step of the click track: a click on every beat (even step), accented on the bar's first beat. */
    fun fillStep(buf: ShortArray, step: Long, beatsPerBar: Int, muted: Boolean, accent: ShortArray, normal: ShortArray) {
        buf.fill(0)
        if (step % 2 == 0L && !muted) {
            val src = if ((step / 2) % beatsPerBar == 0L) accent else normal
            System.arraycopy(src, 0, buf, 0, minOf(src.size, buf.size))
        }
    }
}
