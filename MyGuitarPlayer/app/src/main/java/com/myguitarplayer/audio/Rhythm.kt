package com.myguitarplayer.audio

import kotlin.math.abs
import kotlin.math.roundToLong

/** Maps an audio device's frame counter to System.nanoTime: frame [frame] happened at [nanos]. */
data class FrameClock(val frame: Long, val nanos: Long, val sampleRate: Int) {
    fun nanosOf(f: Long): Long = nanos + ((f - frame) * 1_000_000_000.0 / sampleRate).roundToLong()
}

/** One detected strum placed on the eighth-note grid. [offsetMs] < 0 = early, > 0 = late. */
data class Hit(val step: Long, val offsetMs: Double, val expected: Boolean)

data class RhythmSummary(
    /** Mean |offset| of strums on the pattern's slots, ms. The main "how even" number. */
    val meanAbsMs: Double,
    /** Mean signed offset: negative = you rush, positive = you drag. */
    val meanSignedMs: Double,
    /** Share of the pattern's strokes that were played. */
    val hitRate: Double,
    /** Strums on slots the pattern leaves empty. */
    val extras: Int,
    val expectedSlots: Int,
    val hits: Int,
) {
    /** Week-3 standard: within 60 ms on average and at least 80 % of strokes played. */
    fun passes(maxMs: Int = 60): Boolean = expectedSlots > 0 && meanAbsMs <= maxMs && hitRate >= 0.8
}

/**
 * Scores strums against the metronome. Times are System.nanoTime values: [originNanos] is when step 0
 * of the scored part sounds, [stepNanos] is one eighth note, [latencyNanos] (from calibration) is
 * subtracted from every onset. [strokes] is the pattern, e.g. "D.DU.UDU" ('.' = no strum expected).
 */
class RhythmScorer(
    private val originNanos: Long,
    private val stepNanos: Long,
    private val strokes: String,
    private val latencyNanos: Long,
) {
    private val hitsBySlot = linkedMapOf<Long, Hit>()
    private var extras = 0

    val hits: List<Hit> get() = hitsBySlot.values.toList()

    fun isExpected(step: Long): Boolean = strokes[Math.floorMod(step, strokes.length.toLong()).toInt()] != '.'

    /** Places one onset; returns the hit, or null if it falls before the scored part or repeats a slot. */
    fun add(onsetNanos: Long): Hit? {
        val t = onsetNanos - latencyNanos - originNanos
        val step = Math.floorDiv(t + stepNanos / 2, stepNanos)
        if (step < 0) return null
        val offset = (t - step * stepNanos) / 1e6
        if (step in hitsBySlot) return null // a second detection of the same strum
        val hit = Hit(step, offset, isExpected(step))
        hitsBySlot[step] = hit
        if (!hit.expected) extras++
        return hit
    }

    /** Summary over steps 0 until [steps] (the part that has been played). */
    fun summary(steps: Long): RhythmSummary {
        val onPattern = hitsBySlot.values.filter { it.expected && it.step < steps }
        val expected = (0 until steps).count { isExpected(it) }
        return RhythmSummary(
            meanAbsMs = if (onPattern.isEmpty()) 0.0 else onPattern.map { abs(it.offsetMs) }.average(),
            meanSignedMs = if (onPattern.isEmpty()) 0.0 else onPattern.map { it.offsetMs }.average(),
            hitRate = if (expected == 0) 0.0 else onPattern.size.toDouble() / expected,
            extras = extras,
            expectedSlots = expected,
            hits = onPattern.size,
        )
    }

    companion object {
        fun stepNanos(bpm: Int): Long = 30_000_000_000L / bpm

        /**
         * Calibration: the learner strums exactly on each click. Each onset's offset from the nearest
         * click (any latency in speaker + mic + detection) is collected; the median is the latency.
         * Null when there are too few onsets to trust.
         */
        fun latencyFrom(onsets: List<Long>, firstClickNanos: Long, beatNanos: Long): Long? {
            val offsets = onsets.map { o ->
                val k = Math.floorDiv(o - firstClickNanos + beatNanos / 4, beatNanos) // bias: latency is positive
                o - (firstClickNanos + k * beatNanos)
            }.filter { it > -beatNanos / 4 && it < beatNanos * 3 / 4 }.sorted()
            if (offsets.size < 6) return null
            return offsets[offsets.size / 2]
        }
    }
}
