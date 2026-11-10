package com.myguitarplayer.audio

import kotlin.math.sqrt

data class Pitch(val freq: Double, val confidence: Double)

/**
 * YIN monophonic pitch detector (de Cheveigné & Kawahara, 2002).
 * Searches lags for 60–1000 Hz, which covers every guitar string up to the 12th fret and beyond.
 */
class Yin(
    private val sampleRate: Int,
    bufferSize: Int,
    private val threshold: Double = 0.15,
    minFreq: Double = 60.0,
    maxFreq: Double = 1000.0,
) {
    private val half = bufferSize / 2
    private val d = DoubleArray(half)
    private val minTau = (sampleRate / maxFreq).toInt().coerceAtLeast(2)
    private val maxTau = (sampleRate / minFreq).toInt().coerceAtMost(half - 2)

    /** Returns null for silence or noise without a clear period. */
    fun detect(buf: FloatArray, silenceRms: Double = 0.004): Pitch? {
        require(buf.size >= half * 2) { "buffer too small" }
        if (rms(buf) < silenceRms) return null

        // 1. Difference function, only up to maxTau.
        for (tau in 1..maxTau) {
            var s = 0.0
            for (i in 0 until half) {
                val diff = buf[i] - buf[i + tau]
                s += diff * diff
            }
            d[tau] = s
        }
        // 2. Cumulative mean normalised difference.
        d[0] = 1.0
        var running = 0.0
        for (tau in 1..maxTau) {
            running += d[tau]
            d[tau] = if (running == 0.0) 1.0 else d[tau] * tau / running
        }
        // 3. First dip under the threshold, then walk down to its local minimum.
        var tau = minTau
        var found = -1
        while (tau < maxTau) {
            if (d[tau] < threshold) {
                while (tau + 1 < maxTau && d[tau + 1] < d[tau]) tau++
                found = tau
                break
            }
            tau++
        }
        if (found < 0) return null
        // 4. Parabolic interpolation for sub-sample accuracy.
        val better = if (found in 1 until maxTau) {
            val s0 = d[found - 1]; val s1 = d[found]; val s2 = d[found + 1]
            val denom = 2 * (2 * s1 - s2 - s0)
            if (denom == 0.0) found.toDouble() else found + (s2 - s0) / denom
        } else found.toDouble()
        return Pitch(sampleRate / better, (1 - d[found]).coerceIn(0.0, 1.0))
    }

    companion object {
        fun rms(buf: FloatArray): Double {
            var s = 0.0
            for (x in buf) s += x * x
            return sqrt(s / buf.size)
        }
    }
}
