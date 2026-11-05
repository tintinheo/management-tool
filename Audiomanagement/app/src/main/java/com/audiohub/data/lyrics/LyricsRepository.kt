package com.audiohub.data.lyrics

import android.content.Context
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileInputStream

class LyricsRepository(private val context: Context) {

    private object None

    private val cache = LruCache<String, Any>(64)

    /** Lyrics embedded in the audio file at [uri], or null if there are none / it can't be read. */
    suspend fun load(uri: Uri): Lyrics? {
        val key = uri.toString()
        cache.get(key)?.let { return it as? Lyrics }
        val lyrics = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                        EmbeddedLyrics.read(FileChannelSource(ch))
                    }
                }
            }.getOrNull()?.let(LrcParser::parse)
        }
        cache.put(key, lyrics ?: None)
        return lyrics
    }
}
