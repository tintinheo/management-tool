package com.myguitarplayer.audio

import com.myguitarplayer.music.Note
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 12-bin pitch-class profile ("chroma") from spectral peaks. Only local maxima close to an
 * equal-tempered pitch are counted, so broadband noise (fret buzz, room) mostly drops out.
 */
class ChromaAnalyzer(private val sampleRate: Int, val size: Int = 8192) {
    private val fft = Fft(size)
    private val window = DoubleArray(size) { 0.5 - 0.5 * cos(2 * PI * it / (size - 1)) }
    private val re = DoubleArray(size)
    private val im = DoubleArray(size)
    private val mag = DoubleArray(size / 2 + 1)
    private val lo = (70.0 * size / sampleRate).toInt()
    private val hi = (4000.0 * size / sampleRate).toInt().coerceAtMost(size / 2 - 1)

    /** Returns null when the window is too quiet to judge. */
    fun chroma(buf: FloatArray, silenceRms: Double = AudioConfig.chromaSilence): DoubleArray? {
        require(buf.size >= size)
        if (Yin.rms(buf) < silenceRms) return null
        for (i in 0 until size) {
            re[i] = buf[i] * window[i]
            im[i] = 0.0
        }
        fft.transform(re, im)
        var max = 0.0
        for (k in lo..hi) {
            mag[k] = hypot(re[k], im[k])
            if (mag[k] > max) max = mag[k]
        }
        mag[lo - 1] = hypot(re[lo - 1], im[lo - 1]); mag[hi + 1] = hypot(re[hi + 1], im[hi + 1])
        if (max <= 0) return null
        val floor = max * FLOOR
        val out = DoubleArray(12)
        for (k in lo..hi) {
            val m = mag[k]
            if (m < floor || !isPeak(k, lo - 1, hi + 1)) continue
            val a = ln(mag[k - 1] + 1e-12); val b = ln(m); val c = ln(mag[k + 1] + 1e-12)
            val den = a - 2 * b + c
            val p = if (den == 0.0) 0.0 else 0.5 * (a - c) / den
            val f = (k + p) * sampleRate / size
            val midi = Note.midiOf(f)
            val nearest = midi.roundToInt()
            // ±25 cents: keeps harmonics 1–6, 8–10, 12 (all within 14 cents of a tempered note) but drops
            // the 7th (−31 c) and 11th (−49 c), which would otherwise read as a b7 and fake a "7" chord.
            if (abs(midi - nearest) > 0.25) continue
            out[Math.floorMod(nearest, 12)] += m
        }
        val top = out.max()
        if (top <= 0) return null
        for (i in 0 until 12) out[i] /= top
        return out
    }

    /**
     * True peak = the largest bin within ±3. A plain local maximum would also accept Hann side lobes
     * (−31 dB, ~2.5 bins out), which at bass frequencies sit a semitone or two away and smear the chroma.
     */
    private fun isPeak(k: Int, min: Int, max: Int): Boolean {
        val m = mag[k]
        for (j in maxOf(min, k - 3)..minOf(max, k + 3)) {
            if (j < k && mag[j] >= m) return false
            if (j > k && mag[j] > m) return false
        }
        return true
    }

    companion object {
        const val SILENCE_RMS = 0.006

        /** Peaks below this fraction of the loudest one are ignored (−26 dB). */
        const val FLOOR = 0.05
    }
}

fun cosine(a: DoubleArray, b: DoubleArray): Double {
    var dot = 0.0; var na = 0.0; var nb = 0.0
    for (i in a.indices) {
        dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]
    }
    return if (na == 0.0 || nb == 0.0) 0.0 else dot / sqrt(na * nb)
}
