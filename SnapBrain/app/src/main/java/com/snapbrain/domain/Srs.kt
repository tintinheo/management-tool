package com.snapbrain.domain

/**
 * Leitner spaced repetition. Box 0 = "chưa thuộc" (answered wrong), box 5 = mastered.
 * A correct answer moves the card up one box; a wrong answer drops it to box 0, due tomorrow.
 */
object Srs {
    /** Days until the next review once a card lands in box i. */
    val INTERVALS = intArrayOf(1, 1, 3, 7, 21, 60)
    const val MAX_BOX = 5

    data class Step(val box: Int, val dueDay: Long)

    fun next(currentBox: Int?, correct: Boolean, today: Long): Step {
        val box = if (correct) ((currentBox ?: 0) + 1).coerceAtMost(MAX_BOX) else 0
        return Step(box, today + INTERVALS[box])
    }
}
