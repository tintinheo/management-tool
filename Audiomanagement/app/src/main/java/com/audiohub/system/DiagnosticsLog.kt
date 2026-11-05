package com.audiohub.system

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Small on-device log (crashes, player errors, session connections, audio routing) that the user
 * can share from Settings → Diagnostics — the phone isn't connected for adb, so this is how
 * problems reach us. Capped at [MAX_BYTES]; the oldest half is dropped when full.
 *
 * Writes happen on a single background thread so logging never blocks the UI / player thread;
 * crashes are written synchronously so they survive the process dying.
 */
object DiagnosticsLog {
    private const val MAX_BYTES = 256 * 1024L
    private const val TAG = "MyAudioHub"

    @Volatile
    private var file: File? = null
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "diagnostics-log").apply { isDaemon = true } }
    private val time = ThreadLocal.withInitial { SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US) }

    fun init(context: Context) {
        if (file != null) return
        file = File(context.filesDir, "diagnostics.log")
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
        log("App", "Start · v$version · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Synchronous: the process is about to die, a queued write would be lost.
            append(format("CRASH", "Uncaught exception on ${thread.name}", error))
            previous?.uncaughtException(thread, error)
        }
    }

    fun log(area: String, message: String, error: Throwable? = null) {
        Log.i("$TAG/$area", message, error)
        if (file == null) return
        val line = format(area, message, error)
        runCatching { writer.execute { append(line) } }
    }

    private fun format(area: String, message: String, error: Throwable?) = buildString {
        append(time.get()!!.format(Date())).append(" [").append(area).append("] ").append(message).append('\n')
        if (error != null) append(error.stackTraceToString().lineSequence().take(25).joinToString("\n")).append('\n')
    }

    private fun append(line: String) {
        val f = file ?: return
        synchronized(this) {
            runCatching {
                if (f.length() > MAX_BYTES) {
                    val text = f.readText()
                    f.writeText(text.substring(text.length / 2).substringAfter('\n'))
                }
                f.appendText(line)
            }
        }
    }

    /** Everything logged so far (waits briefly for queued writes so the view is complete). */
    fun read(): String {
        runCatching { writer.submit {}.get(500, TimeUnit.MILLISECONDS) }
        return synchronized(this) { file?.takeIf { it.exists() }?.readText().orEmpty() }
    }

    fun clear() {
        runCatching { writer.submit {}.get(500, TimeUnit.MILLISECONDS) }
        synchronized(this) { file?.writeText("") }
    }
}
