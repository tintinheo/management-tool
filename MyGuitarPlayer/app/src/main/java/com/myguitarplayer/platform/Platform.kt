package com.myguitarplayer.platform

import com.myguitarplayer.data.AppDb
import java.io.File

/**
 * What the shared code needs from the operating system. Android (MainActivity) and Windows (desktop Main.kt)
 * each install one implementation at start-up, before any screen is shown.
 */
interface Platform {
    val db: AppDb
    /** Private app folder: crash log, recordings. */
    val filesDir: File
    /** Text of a bundled file (curriculum.json, songs/xxx.cho). */
    fun readAsset(path: String): String
    /** File names (not paths) in a bundled folder. */
    fun listAssets(dir: String): List<String>
    fun prefs(name: String): Prefs
    fun copyToClipboard(label: String, text: String)
    /** Whether the mic offers an unprocessed (no AGC / noise suppression) source. */
    val supportsRawMic: Boolean
    /** Whether the learner can pick a mic source (Android audio sources); Windows uses its default input. */
    val micSourceChoice: Boolean
    /** App version, e.g. "1.0.0". */
    val appVersion: String
    /** Device and OS, e.g. "Xiaomi 2407FPN8EG · Android 15 (SDK 35)". */
    val deviceDescription: String

    companion object {
        @Volatile private var installed: Platform? = null

        val current: Platform get() = installed ?: error("Platform.install() was not called")

        fun install(p: Platform) {
            installed = p
        }
    }
}

/** Small persistent key/value settings (SharedPreferences on Android, a properties file on Windows). */
interface Prefs {
    fun getInt(key: String, default: Int): Int
    fun getFloat(key: String, default: Float): Float
    fun getString(key: String): String?
    fun edit(block: Editor.() -> Unit)

    interface Editor {
        fun putInt(key: String, value: Int)
        fun putFloat(key: String, value: Float)
        fun putString(key: String, value: String)
        fun remove(key: String)
    }
}
