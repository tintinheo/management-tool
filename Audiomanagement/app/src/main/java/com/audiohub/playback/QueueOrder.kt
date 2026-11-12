package com.audiohub.playback

/**
 * Pure helpers for reordering the play queue. Items are identified by their player index (position
 * in the unshuffled list); an "order" is the sequence in which they will play.
 */
object QueueOrder {

    /** [order] with [pinned] (in that order) moved to right after [current]. */
    fun pinAfter(order: List<Int>, current: Int, pinned: List<Int>): List<Int> {
        val pinnedSet = pinned.toHashSet()
        val rest = order.filterNot { it in pinnedSet }
        val at = rest.indexOf(current)
        if (at < 0) return pinned + rest
        return rest.subList(0, at + 1) + pinned + rest.subList(at + 1, rest.size)
    }

    /**
     * Moves [block] (kept in its current relative order) so it starts where [anchor] ends up in
     * [orderWithoutBlock]. Used for multi-select drag: the block is dropped at the dragged row.
     */
    fun insertBlock(orderWithoutBlock: List<Int>, block: List<Int>, at: Int): List<Int> {
        val i = at.coerceIn(0, orderWithoutBlock.size)
        return orderWithoutBlock.subList(0, i) + block + orderWithoutBlock.subList(i, orderWithoutBlock.size)
    }

    /** True if [order] is a permutation of 0 until [size]. */
    fun isPermutation(order: List<Int>, size: Int): Boolean {
        if (order.size != size) return false
        val seen = BooleanArray(size)
        for (i in order) {
            if (i !in 0 until size || seen[i]) return false
            seen[i] = true
        }
        return true
    }

    /**
     * Moves (from, to) — Player.moveMediaItem semantics, `to` is the index after the move — that
     * turn the list 0 until n into [desired]. Items on the longest increasing run stay put, so a
     * block of k songs dragged anywhere costs exactly k moves (each move rebuilds the timeline).
     */
    fun planMoves(desired: List<Int>): List<Pair<Int, Int>> {
        val n = desired.size
        val keep = longestIncreasing(desired)
        val current = ArrayList<Int>((0 until n).toList())
        val moves = ArrayList<Pair<Int, Int>>()
        for (i in 0 until n) {
            val id = desired[i]
            if (id in keep) continue
            val from = current.indexOf(id)
            current.removeAt(from)
            val to = if (i == 0) 0 else current.indexOf(desired[i - 1]) + 1
            current.add(to, id)
            if (from != to) moves += from to to
        }
        return moves
    }

    /** Values of one longest strictly increasing subsequence (O(n log n)). */
    private fun longestIncreasing(values: List<Int>): Set<Int> {
        if (values.isEmpty()) return emptySet()
        val tailIdx = IntArray(values.size)
        val prev = IntArray(values.size) { -1 }
        var len = 0
        for (i in values.indices) {
            var lo = 0
            var hi = len
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (values[tailIdx[mid]] < values[i]) lo = mid + 1 else hi = mid
            }
            if (lo > 0) prev[i] = tailIdx[lo - 1]
            tailIdx[lo] = i
            if (lo == len) len++
        }
        val out = HashSet<Int>()
        var k = tailIdx[len - 1]
        while (k >= 0) {
            out += values[k]
            k = prev[k]
        }
        return out
    }
}
