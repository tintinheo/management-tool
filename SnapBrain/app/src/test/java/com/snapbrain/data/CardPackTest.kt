package com.snapbrain.data

import com.snapbrain.data.content.CardParser
import com.snapbrain.data.content.Topic
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Content rules from the style guide: catches a bad card before it reaches the phone. */
class CardPackTest {

    private val cards = CardParser.parse(File("src/main/assets/cards.json").readText(Charsets.UTF_8))

    private fun words(s: String) = s.trim().split(Regex("\\s+")).size

    @Test
    fun everyTopicHasAtLeastTenCards() {
        val byTopic = cards.groupingBy { it.topic }.eachCount()
        Topic.entries.forEach { assertTrue("$it has ${byTopic[it]}", (byTopic[it] ?: 0) >= 10) }
    }

    @Test
    fun idsAreUnique() {
        assertEquals(cards.size, cards.map { it.id }.toSet().size)
    }

    @Test
    fun cardsFollowTheStyleGuide() {
        val problems = cards.flatMap { c ->
            buildList {
                if (words(c.hook) > 12) add("${c.id}: hook ${words(c.hook)} words")
                if (c.body.size != 3) add("${c.id}: body has ${c.body.size} lines")
                if (words(c.tf.explain) > 15) add("${c.id}: explain ${words(c.tf.explain)} words")
                if (c.mcq.options.size != 3) add("${c.id}: mcq needs 3 options")
                if (c.mcq.correct !in c.mcq.options.indices) add("${c.id}: mcq.correct out of range")
                if (c.visual.items.isEmpty()) add("${c.id}: empty visual")
                if (c.visual.type.name == "COMPARE" && c.visual.items.size != 2) add("${c.id}: compare needs 2 items")
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun trueFalseAnswersAreBalanced() {
        // If almost every statement were true, "always swipe right" would win.
        val trueShare = cards.count { it.tf.answer }.toDouble() / cards.size
        assertTrue("true share $trueShare", trueShare in 0.4..0.6)
    }
}
