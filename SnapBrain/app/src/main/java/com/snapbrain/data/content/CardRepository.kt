package com.snapbrain.data.content

import android.content.Context
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * All cards: the bundled pack (assets/cards.json) plus cards generated with AI on this phone
 * (kept in [aiFile]). Both are small, so everything stays in memory.
 */
class CardRepository(private val load: () -> String, private val aiFile: File? = null) {

    constructor(context: Context) : this(
        load = { context.assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() } },
        aiFile = File(context.filesDir, AI_FILE),
    )

    private class Index(val all: List<Card>) {
        val byId: Map<String, Card> = all.associateBy { it.id }
    }

    private val bundled: List<Card> by lazy { CardParser.parse(load()) }

    private val _ai: MutableStateFlow<List<Card>> by lazy {
        val saved = aiFile?.takeIf { it.exists() }?.let { f -> runCatching { CardParser.parse(f.readText()) }.getOrNull() }
        MutableStateFlow(saved.orEmpty())
    }

    /** Cards generated with AI, newest last. */
    val aiCards: StateFlow<List<Card>> get() = _ai.asStateFlow()

    @Volatile private var index: Index? = null

    private fun index(): Index = index ?: synchronized(this) {
        index ?: Index(bundled + _ai.value).also { index = it }
    }

    val all: List<Card> get() = index().all

    operator fun get(id: String): Card? = index().byId[id]

    /** Adds generated cards; ids already in use are skipped. Returns the cards actually added. */
    @Synchronized
    fun addAi(cards: List<Card>): List<Card> {
        val taken = index().byId.keys
        val fresh = cards.filter { it.id !in taken }.map { it.copy(ai = true) }
        if (fresh.isNotEmpty()) save(_ai.value + fresh)
        return fresh
    }

    @Synchronized
    fun removeAi(topic: Topic? = null) {
        save(if (topic == null) emptyList() else _ai.value.filter { it.topic != topic })
    }

    private fun save(cards: List<Card>) {
        aiFile?.let { f ->
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(CardParser.encode(cards))
            if (!tmp.renameTo(f)) {
                f.writeText(tmp.readText())
                tmp.delete()
            }
        }
        _ai.value = cards
        index = null
    }

    companion object {
        const val ASSET = "cards.json"
        const val AI_FILE = "ai_cards.json"
    }
}
