package com.snapbrain.domain

import com.snapbrain.data.content.Card
import com.snapbrain.data.content.Topic
import com.snapbrain.data.db.CardProgress
import kotlin.random.Random

/** Picks what to show next: reviews that are due, then new cards (topics mixed), then the rest. */
object FeedPlanner {

    fun plan(
        cards: List<Card>,
        progress: Map<String, CardProgress>,
        today: Long,
        favorites: Set<Topic>,
        limit: Int,
        random: Random = Random.Default,
    ): List<Card> {
        val pool = if (favorites.isEmpty()) cards else cards.filter { it.topic in favorites }.ifEmpty { cards }
        val due = pool.filter { progress[it.id]?.let { p -> p.dueDay <= today } == true }
            .sortedWith(compareBy({ progress.getValue(it.id).dueDay }, { progress.getValue(it.id).box }))
        val fresh = interleave(pool.filter { it.id !in progress }, random)
        val rest = pool.filter { progress[it.id]?.let { p -> p.dueDay > today } == true }
            .sortedBy { progress.getValue(it.id).dueDay }
        return (due + fresh + rest).take(limit)
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
