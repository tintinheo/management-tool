package com.audiohub.data.storage

import android.os.Environment
import java.io.File

/**
 * The fixed folders the app reads from / writes to, expressed as MediaStore RELATIVE_PATH values
 * (relative to /storage/emulated/0, always ending with "/").
 */
object AudioFolders {
    const val DEFAULT_MUSIC = "Music/AudioHub/"
    const val DEFAULT_PODCAST = "Podcasts/AudioHub/"
    /** Each subfolder is one audiobook; its files are the chapters. */
    const val DEFAULT_AUDIOBOOK = "Audiobook/"

    val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav", "wma", "alac", "aiff", "aif")

    fun normalize(relativePath: String): String {
        val trimmed = relativePath.trim().replace('\\', '/').trim('/')
        return if (trimmed.isEmpty()) "" else "$trimmed/"
    }

    fun absolute(relativePath: String): File =
        File(Environment.getExternalStorageDirectory(), normalize(relativePath))

    /** Best effort: shared Music/ and Podcasts/ dirs are writable by apps on Android 11+. */
    fun ensureExists(relativePath: String): Boolean {
        val dir = absolute(relativePath)
        return dir.isDirectory || runCatching { dir.mkdirs() }.getOrDefault(false)
    }

    /** "Music/AudioHub/Rock/80s/" under root "Music/AudioHub/" → "Rock › 80s". */
    fun displayName(path: String, root: String): String =
        normalize(path).removePrefix(normalize(root)).trim('/').split('/')
            .filter { it.isNotBlank() }.joinToString(" › ").ifEmpty { "Music" }

    fun isAudioFile(file: File): Boolean = file.isFile && file.extension.lowercase() in AUDIO_EXTENSIONS
}
