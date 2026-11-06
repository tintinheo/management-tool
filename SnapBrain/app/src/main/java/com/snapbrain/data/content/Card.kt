package com.snapbrain.data.content

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One micro-lesson: a hook, three short body lines, a visual, a true/false quiz and an A/B/C quiz. */
@Serializable
data class Card(
    val id: String,
    val topic: Topic,
    val hook: String,
    val body: List<String>,
    val visual: Visual,
    val tf: TrueFalse,
    val mcq: Mcq,
)

@Serializable
enum class Topic(val label: String, val emoji: String) {
    @SerialName("office") OFFICE("Mẹo công sở", "📊"),
    @SerialName("english") ENGLISH("Business English", "💬"),
    @SerialName("ai") AI("Viết prompt AI", "🤖"),
    @SerialName("psychology") PSYCHOLOGY("Tâm lý học", "🧠"),
    @SerialName("finance") FINANCE("Tài chính cá nhân", "💰"),
    @SerialName("science") SCIENCE("Khoa học & lịch sử", "🔭"),
}

/**
 * How the body is illustrated.
 * keys: keycaps ("Ctrl", "E"). emoji: one big emoji. quote: a sentence in large type.
 * compare: items[0] = "don't", items[1] = "do".
 */
@Serializable
data class Visual(val type: VisualType, val items: List<String>)

@Serializable
enum class VisualType {
    @SerialName("keys") KEYS,
    @SerialName("emoji") EMOJI,
    @SerialName("quote") QUOTE,
    @SerialName("compare") COMPARE,
}

@Serializable
data class TrueFalse(val statement: String, val answer: Boolean, val explain: String)

@Serializable
data class Mcq(val q: String, val options: List<String>, val correct: Int)

object CardParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): List<Card> = json.decodeFromString(text)
}
