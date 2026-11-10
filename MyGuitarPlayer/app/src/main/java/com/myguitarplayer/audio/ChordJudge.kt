package com.myguitarplayer.audio

import com.myguitarplayer.music.Chord
import com.myguitarplayer.music.Chords
import com.myguitarplayer.music.Note

/** How well one chroma frame fits the chord the learner is trying to play. */
data class ChordVerdict(
    val target: Chord,
    /** Cosine match with the target's template, 0…1. */
    val score: Double,
    /** Best-matching chord in the library (may be the target). */
    val best: Chord,
    val bestScore: Double,
    /** Chord tones that are (almost) absent — usually a muted or un-pressed string. */
    val missing: List<String>,
    /** Much energy on non-chord notes — buzzing, a wrong fret, or an open string that should be muted. */
    val noisy: Boolean,
    val chroma: DoubleArray,
) {
    val correct: Boolean get() = score >= AudioConfig.match && missing.isEmpty() && (best == target || bestScore - score < 0.02)
}

object ChordMatcher {
    fun judge(chroma: DoubleArray, target: Chord, library: List<Chord> = Chords.ALL): ChordVerdict {
        val score = cosine(chroma, target.template)
        val best = library.maxBy { cosine(chroma, it.template) }
        // Relative to what the template expects: harmonics of other strings put a little energy on most notes.
        val obsTop = chroma.max().coerceAtLeast(1e-9)
        val missing = target.pitchClasses
            .filter { chroma[it] / obsTop < minOf(0.4 * target.template[it], 0.15) }
            .sorted().map { Note.name(it) }
        val total = chroma.sum()
        val outside = (0 until 12).filter { it !in target.pitchClasses }.sumOf { chroma[it] }
        // Harmonics of chord tones land on non-chord classes too, so only flag clearly excessive energy.
        val noisy = total > 0 && outside / total > 0.38
        return ChordVerdict(target, score, best, cosine(chroma, best.template), missing, noisy, chroma)
    }
}

/**
 * Splits the mic stream into strums (attack = sudden loudness rise) and judges each strum on the
 * frames just after its attack, so the decaying tail or the next strum don't blur the result.
 */
class StrumJudge(var target: Chord) {
    data class State(val verdict: ChordVerdict?, val strums: Int, val correctStreak: Int, val listening: Boolean)

    private var prevRms = 0.0
    private var framesSinceOnset = 99
    private var quietFrames = 0
    private var sum = DoubleArray(12)
    private var verdict: ChordVerdict? = null
    private var judgedThisStrum = false
    private var strums = 0
    private var streak = 0

    fun reset() {
        prevRms = 0.0; framesSinceOnset = 99; quietFrames = 0; sum = DoubleArray(12)
        verdict = null; judgedThisStrum = false; strums = 0; streak = 0
    }

    fun push(rms: Double, chroma: DoubleArray?): State {
        val onset = rms > AudioConfig.chromaSilence * 1.5 && rms > prevRms * 1.4 && framesSinceOnset >= 4
        prevRms = rms
        if (onset) {
            finishStrum()
            framesSinceOnset = 0
            sum = DoubleArray(12)
            judgedThisStrum = true
            strums++
        } else {
            framesSinceOnset++
        }
        if (chroma == null) {
            quietFrames++
            if (quietFrames == 3) finishStrum()
        } else {
            quietFrames = 0
            // Window frames 1–6 after the attack hold the full chord; later frames are only the decay.
            if (judgedThisStrum && framesSinceOnset in 1..6) {
                for (i in 0 until 12) sum[i] += chroma[i]
                val top = sum.max()
                if (top > 0) verdict = ChordMatcher.judge(DoubleArray(12) { sum[it] / top }, target)
            }
        }
        return State(verdict, strums, streak, listening = chroma != null)
    }

    private fun finishStrum() {
        if (!judgedThisStrum) return
        judgedThisStrum = false
        val v = verdict ?: return
        streak = if (v.correct) streak + 1 else 0
    }
}

/**
 * "One-minute changes": counts confirmed switches between two chords. A chord counts once it is
 * heard in [STABLE] consecutive frames, so a sloppy in-between strum is not a change.
 */
class ChangeCounter(val a: Chord, val b: Chord) {
    var changes = 0
        private set
    var current: Chord? = null
        private set
    private var candidate: Chord? = null
    private var run = 0

    fun push(chroma: DoubleArray?): Chord? {
        val label = chroma?.let {
            val sa = cosine(it, a.template)
            val sb = cosine(it, b.template)
            when {
                maxOf(sa, sb) < AudioConfig.match - 0.10 || kotlin.math.abs(sa - sb) < 0.02 -> null
                sa > sb -> a
                else -> b
            }
        }
        if (label == null) {
            run = 0; candidate = null
            return current
        }
        if (label == candidate) run++ else { candidate = label; run = 1 }
        if (run == STABLE && label != current) {
            if (current != null) changes++
            current = label
        }
        return current
    }

    companion object {
        const val STABLE = 3
    }
}
