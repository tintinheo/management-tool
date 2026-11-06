package com.snapbrain.data.content

import android.content.Context

/** The bundled card pack (assets/cards.json). Loaded once, kept in memory (a few hundred KB at most). */
class CardRepository(private val load: () -> String) {

    constructor(context: Context) : this({
        context.assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
    })

    val all: List<Card> by lazy { CardParser.parse(load()) }

    private val byId: Map<String, Card> by lazy { all.associateBy { it.id } }

    operator fun get(id: String): Card? = byId[id]

    companion object {
        const val ASSET = "cards.json"
    }
}
