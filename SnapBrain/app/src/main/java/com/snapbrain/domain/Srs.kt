package com.snapbrain.domain

/**
 * Leitner spaced repetition. Box 0 = "chưa thuộc" (answered wrong), box 5 = mastered.
 * A correct answer moves the card up one box; a wrong answer drops it to box 0, due tomorrow.
 * A new card answered right the first time is already known, so it skips to box 2 (due in 3 days)
 * instead of coming straight back tomorrow.
 */
object Srs {
    /** Days until the next review once a card lands in box i. */
    val INTERVALS = intArrayOf(1, 1, 3, 7, 21, 60)
    const val MAX_BOX = 5
    private const val KNOWN_ON_SIGHT_BOX = 2

    data class Step(val box: Int, val dueDay: Long)

    fun next(currentBox: Int?, correct: Boolean, today: Long): Step {
        val box = when {
            !correct -> 0
            currentBox == null -> KNOWN_ON_SIGHT_BOX
            else -> (currentBox + 1).coerceAtMost(MAX_BOX)
        }
        return Step(box, today + INTERVALS[box])
    }
}
