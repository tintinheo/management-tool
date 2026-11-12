package com.audiohub.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class QueueOrderTest {

    @Test
    fun pinAfterPutsSongsRightAfterTheCurrentOne() {
        // Shuffled order 4,1,0,3,2 with 1 playing: 2 and 0 should come next, in that order.
        assertEquals(listOf(4, 1, 2, 0, 3), QueueOrder.pinAfter(listOf(4, 1, 0, 3, 2), current = 1, pinned = listOf(2, 0)))
        // Current last.
        assertEquals(listOf(0, 1, 2), QueueOrder.pinAfter(listOf(0, 2, 1), current = 1, pinned = listOf(2)))
    }

    @Test
    fun insertBlockKeepsTheBlockTogether() {
        assertEquals(listOf(0, 3, 1, 4, 2), QueueOrder.insertBlock(listOf(0, 3, 2), listOf(1, 4), at = 2))
        assertEquals(listOf(1, 4, 0, 3, 2), QueueOrder.insertBlock(listOf(0, 3, 2), listOf(1, 4), at = -5))
        assertEquals(listOf(0, 3, 2, 1, 4), QueueOrder.insertBlock(listOf(0, 3, 2), listOf(1, 4), at = 99))
    }

    @Test
    fun permutationCheck() {
        assertTrue(QueueOrder.isPermutation(listOf(2, 0, 1), 3))
        assertFalse(QueueOrder.isPermutation(listOf(2, 0, 0), 3))
        assertFalse(QueueOrder.isPermutation(listOf(0, 1), 3))
        assertFalse(QueueOrder.isPermutation(listOf(0, 1, 3), 3))
    }

    /** Applies (from, to) moves the way Player.moveMediaItem does. */
    private fun apply(n: Int, moves: List<Pair<Int, Int>>): List<Int> {
        val list = (0 until n).toMutableList()
        moves.forEach { (from, to) -> list.add(to, list.removeAt(from)) }
        return list
    }

    @Test
    fun planMovesReachesAnyOrder() {
        val random = Random(7)
        repeat(300) {
            val n = random.nextInt(0, 40)
            val desired = (0 until n).shuffled(random)
            assertEquals(desired, apply(n, QueueOrder.planMoves(desired)))
        }
    }

    @Test
    fun draggingABlockCostsOneMovePerSong() {
        // 3 songs (10, 11, 12) dragged from the middle of 100 to near the top.
        val order = (0 until 100).toMutableList()
        val block = listOf(10, 11, 12)
        order.removeAll(block)
        val desired = QueueOrder.insertBlock(order, block, at = 2)
        val moves = QueueOrder.planMoves(desired)
        assertEquals(3, moves.size)
        assertEquals(desired, apply(100, moves))

        // …and down again.
        val down = QueueOrder.insertBlock((0 until 100).filterNot { it in block }, block, at = 90)
        assertEquals(3, QueueOrder.planMoves(down).size)
        assertTrue(QueueOrder.planMoves((0 until 50).toList()).isEmpty())
    }
}
