package com.myguitarplayer.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Finds strum attacks with spectral flux: every [hop] samples, the log-magnitude spectrum of the last
 * [size] samples is compared with the previous one; a sharp rise across many frequencies is an onset.
 *
 * - Bins near [ignoreHz] (the metronome's click tones and their 2nd/3rd harmonics) are left out, so the click
 *   coming out of the phone's own speaker is not counted as a strum.
 * - A peak must beat an adaptive threshold (recent median + [sensitivity]·MAD + floor), be a local maximum,
 *   and come at least [minGapMs] after the previous onset.
 *
 * Feed audio in any chunk sizes with [push]; it returns absolute sample positions of the onsets found.
 * Onsets are reported with a two-hop delay (needed to confirm the peak).
 */
class OnsetDetector(
    private val sampleRate: Int,
    private val size: Int = 1024,
    private val hop: Int = 256,
    ignoreHz: List<Double> = emptyList(),
    private val sensitivity: Double = AudioConfig.onsetSensitivity,
    private val minGapMs: Double = 90.0,
    private val silenceRms: Double = AudioConfig.onsetSilence,
) {
    private val fft = Fft(size)
    private val window = DoubleArray(size) { 0.5 - 0.5 * cos(2 * PI * it / size) }
    private val re = DoubleArray(size)
    private val im = DoubleArray(size)
    private val buf = FloatArray(size)
    private var filled = 0
    private var write = 0
    private var sinceHop = 0
    private var consumed = 0L

    private val bins = size / 2
    // Below 70 Hz is rumble / handling noise; above 8 kHz adds little for strums.
    private val keep: BooleanArray = BooleanArray(bins) { b -> (b.toDouble() * sampleRate / size) in 70.0..8000.0 }
    private val clickBand: BooleanArray = BooleanArray(bins) { b ->
        val hz = b.toDouble() * sampleRate / size
        ignoreHz.flatMap { listOf(it, it * 2, it * 3) }.any { kotlin.math.abs(hz - it) < 4.5 * sampleRate / size }
    }
    private var prevLin = DoubleArray(bins)
    // Log spectra of the last two frames: SuperFlux compares against the frame two hops back.
    private var prevMag = DoubleArray(bins)
    private var prevMag2 = DoubleArray(bins)

    // Flux history for the adaptive threshold and the 3-point peak test.
    private val history = ArrayDeque<Double>()
    private var f1 = 0.0 // flux one hop ago
    private var f2 = 0.0 // flux two hops ago
    private var r1 = 0.0 // rms one hop ago
    private var lastOnset = Long.MIN_VALUE / 2
    private var lastPeak = 0.0
    private val historyLen = (0.4 * sampleRate / hop).toInt()
    private val minGap = (minGapMs / 1000 * sampleRate).toLong()

    /** Sample position the most recent flux frame is centred on. */
    private fun frameCentre(): Long = consumed - size / 2

    fun push(chunk: FloatArray): List<Long> {
        val out = mutableListOf<Long>()
        for (x in chunk) {
            // Ring buffer: [write] is the oldest sample once full.
            buf[write] = x
            write = (write + 1) % size
            if (filled < size) filled++
            consumed++
            if (filled == size && ++sinceHop >= hop) {
                sinceHop = 0
                analyse()?.let { out += it }
            }
        }
        return out
    }

    private fun analyse(): Long? {
        var energy = 0.0
        for (i in 0 until size) {
            val v = buf[(write + i) % size].toDouble()
            energy += v * v
            re[i] = v * window[i]
            im[i] = 0.0
        }
        val rms = sqrt(energy / size)
        fft.transform(re, im)
        var flux = 0.0
        var riseAll = 0.0
        var riseClick = 0.0
        val mag = DoubleArray(bins)
        val lin = DoubleArray(bins)
        for (b in 0 until bins) {
            if (!keep[b]) continue
            lin[b] = sqrt(re[b] * re[b] + im[b] * im[b])
            mag[b] = ln(1 + COMPRESSION * lin[b])
            val rise = lin[b] - prevLin[b]
            if (rise > 0) {
                riseAll += rise
                if (clickBand[b]) riseClick += rise
            }
        }
        for (b in 1 until bins - 1) {
            if (!keep[b] || clickBand[b]) continue
            // SuperFlux (Böck & Widmer 2013): compare with the loudest neighbouring bin two frames back, so noise
            // flicker and the slow ripple of ringing strings don't count as rises — only real attacks do.
            val ref = maxOf(prevMag2[b - 1], prevMag2[b], prevMag2[b + 1])
            val d = mag[b] - ref
            if (d > 0) flux += d
        }
        prevMag2 = prevMag
        prevMag = mag
        prevLin = lin
        // A rise concentrated in the click's own tones is the metronome leaking into the mic, not a strum.
        if (riseAll > 0 && riseClick / riseAll > CLICK_SHARE) flux = 0.0

        // Is the frame one hop ago a peak above the threshold?
        // Robust threshold: median + k·MAD of the last 0.4 s. A previous strum's peak in the window is an
        // outlier and barely moves it, so fast patterns (eighths at 160 BPM) still work.
        val sorted = history.sorted()
        val median = if (sorted.isEmpty()) 0.0 else sorted[sorted.size / 2]
        val mad = if (sorted.isEmpty()) 0.0 else sorted.map { kotlin.math.abs(it - median) }.sorted()[sorted.size / 2]
        val threshold = median + sensitivity * 1.4826 * mad + FLOOR
        val warm = history.size >= WARMUP
        val candidatePos = frameCentre() - hop
        // Ring-out guard: right after a strum the strings keep producing small flux bumps; ignore bumps far
        // below that strum's peak until it has faded (~150 ms time constant).
        val sinceLast = (candidatePos - lastOnset).toDouble() / sampleRate
        val ringOut = lastPeak * RING_OUT * exp(-sinceLast / 0.15)
        // The silence gate only blocks candidates: the history keeps the real noise-floor flux, so the
        // adaptive threshold sits above the noise instead of at zero.
        val candidate = warm && r1 >= silenceRms && f1 > maxOf(threshold, ringOut) && f1 >= f2 && f1 > flux
        history.addLast(f1)
        if (history.size > historyLen) history.removeFirst()
        f2 = f1
        f1 = flux
        r1 = rms
        // The attack starts where the rise does: if the frame before the peak already had most of it, use that.
        val attackPos = if (f2 > 0.5 * f1) candidatePos - hop else candidatePos
        if (candidate && attackPos - lastOnset >= minGap) {
            lastOnset = attackPos
            lastPeak = f1
            return attackPos
        }
        return null
    }

    companion object {
        /** Minimum flux for an onset, in summed log-magnitude units over the kept bins. */
        const val FLOOR = 4.0
        const val COMPRESSION = 100.0
        const val CLICK_SHARE = 0.35
        /** Hops of history needed before the threshold means anything (~50 ms). */
        const val WARMUP = 8
        /** Share of the last strum's flux peak a new onset must reach while the strum rings out. */
        const val RING_OUT = 0.3
    }
}
