package com.remotecam.util

/** Tiny JSON writer for maps, lists, strings, numbers and booleans. Plain JVM so tests can use it. */
object Json {
    fun write(value: Any?): String = StringBuilder().also { it.writeValue(value) }.toString()

    // Not named append(): StringBuilder.append(Any?) is a member and would silently win.
    private fun StringBuilder.writeValue(value: Any?) {
        when (value) {
            null -> append("null")
            is String -> quote(value)
            is Boolean -> append(if (value) "true" else "false")
            is Int, is Long, is Short, is Byte -> append(value.toString())
            is Float -> if (value.isFinite()) append(trim(value.toDouble())) else append("null")
            is Double -> if (value.isFinite()) append(trim(value)) else append("null")
            is Map<*, *> -> {
                append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) append(',')
                    first = false
                    quote(k.toString())
                    append(':')
                    writeValue(v)
                }
                append('}')
            }
            is Iterable<*> -> {
                append('[')
                value.forEachIndexed { i, v ->
                    if (i > 0) append(',')
                    writeValue(v)
                }
                append(']')
            }
            else -> quote(value.toString())
        }
    }

    private fun trim(d: Double): String {
        val rounded = Math.round(d * 1000) / 1000.0
        return if (rounded == Math.floor(rounded) && kotlin.math.abs(rounded) < 1e15) {
            rounded.toLong().toString()
        } else {
            rounded.toString()
        }
    }

    private fun StringBuilder.quote(s: String) {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append(String.format("\\u%04x", c.code)) else append(c)
            }
        }
        append('"')
    }
}
