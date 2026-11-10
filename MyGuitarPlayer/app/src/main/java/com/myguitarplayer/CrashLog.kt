package com.myguitarplayer

import com.myguitarplayer.audio.AudioConfig
import com.myguitarplayer.platform.Platform
import java.io.File
import java.time.LocalDateTime

/**
 * Keeps the last crash on the device (no network, no analytics) so it can be copied and sent by hand.
 * The app shows it once on the next launch.
 */
object CrashLog {
    private const val FILE = "last_crash.txt"

    /** Call after Platform.install(). */
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            save(thread, e)
            previous?.uncaughtException(thread, e)
        }
    }

    /** Writes [e] as the last crash. */
    fun save(thread: Thread, e: Throwable) {
        runCatching { file().writeText(describe() + "\nThread: ${thread.name}\n\n" + e.stackTraceToString()) }
    }

    fun read(): String? = file().takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }

    fun clear() {
        file().delete()
    }

    /** App version, device and audio settings: what's needed to reproduce a device-specific problem. */
    fun describe(): String {
        val p = Platform.current
        return buildString {
            appendLine("MyGuitarPlayer ${p.appVersion} · ${LocalDateTime.now().withNano(0)}")
            appendLine(p.deviceDescription)
            append("Micro: ${AudioConfig.source.name} · ồn ${AudioConfig.noiseRms?.let { "%.4f".format(it) } ?: "chưa đo"}")
            append(" · hợp âm ${AudioConfig.chordLevel} · nhịp ${AudioConfig.onsetLevel}")
        }
    }

    private fun file() = File(Platform.current.filesDir, FILE)
}
