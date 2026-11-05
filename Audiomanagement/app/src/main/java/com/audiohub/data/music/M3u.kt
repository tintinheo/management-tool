package com.audiohub.data.music

import com.audiohub.data.db.TrackEntity
import com.audiohub.data.storage.AudioFolders

/** Extended M3U (UTF-8 .m3u8) with paths relative to the music folder. */
object M3u {

    /** UTF-8 byte-order mark some Windows tools prepend; built at runtime to keep the source BOM-free. */
    private val BOM = 0xFEFF.toChar().toString()

    fun format(name: String, tracks: List<TrackEntity>, musicFolder: String): String = buildString {
        val root = AudioFolders.normalize(musicFolder)
        append("#EXTM3U\n")
        append("#PLAYLIST:").append(name).append('\n')
        for (t in tracks) {
            append("#EXTINF:").append(t.durationMs / 1000).append(',')
                .append(t.artist).append(" - ").append(t.title).append('\n')
            append(t.relativePath.removePrefix(root)).append(t.displayName).append('\n')
        }
    }

    data class Parsed(val name: String?, val paths: List<String>)

    fun parse(content: String): Parsed {
        var name: String? = null
        val paths = ArrayList<String>()
        for (raw in content.removePrefix(BOM).lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#PLAYLIST:", ignoreCase = true) -> name = line.substringAfter(':').trim().ifEmpty { null }
                line.startsWith("#") -> Unit
                else -> paths += line.replace('\\', '/')
            }
        }
        return Parsed(name, paths)
    }

    /**
     * Maps playlist paths to library tracks: first by path suffix (handles absolute paths and
     * paths relative to any folder), then by file name alone. Unmatched entries are dropped.
     */
    fun match(paths: List<String>, library: List<TrackEntity>): List<TrackEntity> {
        val byFullPath = library.associateBy { (it.relativePath + it.displayName).lowercase() }
        val byName = library.groupBy { it.displayName.lowercase() }
        return paths.mapNotNull { path ->
            val p = path.lowercase().removePrefix("file://").trimStart('/')
            byFullPath.entries.firstOrNull { (full, _) -> p.endsWith(full) || full.endsWith(p) }?.value
                ?: byName[p.substringAfterLast('/')]?.firstOrNull()
        }
    }
}
