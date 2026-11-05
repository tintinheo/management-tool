package com.audiohub.data

import java.text.Normalizer

/** Accent-insensitive search keys, so "lac troi" matches "Lạc Trôi" and "dong" matches "Đông". */
object SearchText {
    private val combiningMarks = Regex("\\p{Mn}+")
    private val whitespace = Regex("\\s+")

    fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
            .replace(combiningMarks, "")
            .replace('đ', 'd')
            .replace(whitespace, " ")
            .trim()

    fun of(vararg parts: String?): String =
        parts.filterNot { it.isNullOrBlank() }.joinToString(" ") { normalize(it!!) }

    /** Every word of [query] must appear somewhere in [haystack] (already normalized). */
    fun matches(haystack: String, query: String): Boolean {
        val words = normalize(query).split(' ').filter { it.isNotEmpty() }
        return words.isNotEmpty() && words.all { haystack.contains(it) }
    }
}
