package com.snapbrain.domain

import com.snapbrain.data.content.Card
import com.snapbrain.data.content.Topic
import com.snapbrain.data.db.CardProgress
import kotlin.random.Random

/**
 * Picks what to show next: new cards (topics mixed) with due reviews woven in — one review after
 * every [FRESH_PER_REVIEW] new cards, so a new day doesn't open with a wall of yesterday's
 * questions — then the rest. Cards in [exclude] (already in the user's current session) are skipped.
 */
object FeedPlanner {
    const val FRESH_PER_REVIEW = 2

    fun plan(
        cards: List<Card>,
        progress: Map<String, CardProgress>,
        today: Long,
        favorites: Set<Topic>,
        limit: Int,
        random: Random = Random.Default,
        exclude: Set<String> = emptySet(),
    ): List<Card> {
        val pool = (if (favorites.isEmpty()) cards else cards.filter { it.topic in favorites }.ifEmpty { cards })
            .filter { it.id !in exclude }
        val due = pool.filter { progress[it.id]?.let { p -> p.dueDay <= today } == true }
            .sortedWith(compareBy({ progress.getValue(it.id).dueDay }, { progress.getValue(it.id).box }))
        val fresh = interleave(pool.filter { it.id !in progress }, random)
        val rest = pool.filter { progress[it.id]?.let { p -> p.dueDay > today } == true }
            .sortedBy { progress.getValue(it.id).dueDay }
        return (weave(fresh, due) + rest).take(limit)
    }

    /** [FRESH_PER_REVIEW] fresh cards, then one review, repeated; leftovers of either list go at the end. */
    private fun weave(fresh: List<Card>, due: List<Card>): List<Card> {
        val out = ArrayList<Card>(fresh.size + due.size)
        var f = 0
        var d = 0
        while (f < fresh.size || d < due.size) {
            repeat(FRESH_PER_REVIEW) { if (f < fresh.size) out += fresh[f++] }
            if (d < due.size) out += due[d++]
        }
        return out
    }

    /** For the streak saver: weakest seen cards first; topped up with unseen cards. */
    fun review(cards: List<Card>, progress: Map<String, CardProgress>, limit: Int, random: Random = Random.Default): List<Card> {
        val seen = cards.filter { it.id in progress }.shuffled(random).sortedBy { progress.getValue(it.id).box }
        val fresh = cards.filter { it.id !in progress }.shuffled(random)
        return (seen + fresh).take(limit)
    }

    /** Round-robin across topics so two cards of the same topic rarely sit next to each other. */
    fun interleave(cards: List<Card>, random: Random): List<Card> {
        val queues = cards.groupBy { it.topic }.values.map { it.shuffled(random).toMutableList() }.shuffled(random)
        val out = ArrayList<Card>(cards.size)
        while (out.size < cards.size) {
            for (q in queues) if (q.isNotEmpty()) out += q.removeAt(0)
        }
        return out
    }
}
