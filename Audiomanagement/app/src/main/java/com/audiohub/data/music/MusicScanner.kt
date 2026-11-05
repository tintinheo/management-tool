package com.audiohub.data.music

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.audiohub.data.SearchText
import com.audiohub.data.db.TrackEntity
import com.audiohub.data.storage.AudioFolders
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/** Reads audio files under the fixed music folder from MediaStore. Requires READ_MEDIA_AUDIO. */
class MusicScanner(private val context: Context) {

    private val collection: Uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

    /**
     * Returns every audio file under [relativeFolder]. Files copied in over USB that MediaStore
     * hasn't indexed yet are pushed to the system scanner first.
     */
    suspend fun scan(relativeFolder: String): List<TrackEntity> {
        val folder = AudioFolders.normalize(relativeFolder)
        val indexed = query(folder)
        val known = indexed.mapTo(HashSet()) { it.relativePath + it.displayName }
        val unindexed = AudioFolders.absolute(folder)
            .walkTopDown()
            .filter { AudioFolders.isAudioFile(it) }
            .filterNot { relativePathOf(it) + it.name in known }
            .map { it.absolutePath }
            .toList()
        if (unindexed.isEmpty()) return indexed
        requestSystemScan(unindexed)
        return query(folder)
    }

    private fun relativePathOf(file: File): String {
        val root = AudioFolders.absolute("").absolutePath.trimEnd('/')
        val parent = file.parentFile?.absolutePath ?: return ""
        return AudioFolders.normalize(parent.removePrefix(root))
    }

    private suspend fun requestSystemScan(paths: List<String>) {
        withTimeoutOrNull(60_000) {
            suspendCancellableCoroutine { cont ->
                val remaining = AtomicInteger(paths.size)
                MediaScannerConnection.scanFile(context, paths.toTypedArray(), null) { _, _ ->
                    if (remaining.decrementAndGet() == 0 && cont.isActive) cont.resume(Unit)
                }
            }
        }
    }

    private fun query(folder: String): List<TrackEntity> {
        val hasGenre = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        val projection = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ALBUM_ARTIST)
            add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.ALBUM_ID)
            add(MediaStore.Audio.Media.TRACK)
            add(MediaStore.Audio.Media.YEAR)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.DATE_ADDED)
            add(MediaStore.Audio.Media.RELATIVE_PATH)
            add(MediaStore.Audio.Media.DISPLAY_NAME)
            if (hasGenre) add(MediaStore.Audio.Media.GENRE)
        }.toTypedArray()

        val selection = "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ? ESCAPE '\\' AND " +
            "${MediaStore.Audio.Media.IS_PENDING} = 0"
        val args = arrayOf("${escapeLike(folder)}%")

        val result = ArrayList<TrackEntity>()
        context.contentResolver.query(collection, projection, selection, args, null)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumArtistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ARTIST)
            val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val trackCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val yearCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
            val durationCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val dateAddedCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val pathCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val genreCol = if (hasGenre) c.getColumnIndexOrThrow(MediaStore.Audio.Media.GENRE) else -1

            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val displayName = c.getString(nameCol).orEmpty()
                // TRACK packs disc and track number as disc * 1000 + track.
                val packedTrack = c.getInt(trackCol)
                result += TrackEntity(
                    id = id,
                    uri = ContentUris.withAppendedId(collection, id).toString(),
                    title = c.getString(titleCol).cleanTag() ?: displayName.substringBeforeLast('.'),
                    artist = c.getString(artistCol).cleanTag() ?: UNKNOWN_ARTIST,
                    albumArtist = c.getString(albumArtistCol).cleanTag(),
                    album = c.getString(albumCol).cleanTag() ?: UNKNOWN_ALBUM,
                    albumId = c.getLong(albumIdCol),
                    trackNumber = packedTrack % 1000,
                    discNumber = packedTrack / 1000,
                    year = c.getInt(yearCol),
                    genre = if (genreCol >= 0) c.getString(genreCol).cleanTag() else null,
                    durationMs = c.getLong(durationCol),
                    dateAddedSec = c.getLong(dateAddedCol),
                    relativePath = c.getString(pathCol).orEmpty(),
                    displayName = displayName,
                ).let { it.copy(searchText = SearchText.of(it.title, it.artist, it.album, it.albumArtist)) }
            }
        }
        return result
    }

    /** Emits whenever MediaStore audio content changes (files added/removed/edited). */
    fun changes(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        context.contentResolver.registerContentObserver(collection, true, observer)
        awaitClose { context.contentResolver.unregisterContentObserver(observer) }
    }

    private fun escapeLike(value: String) =
        value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private fun String?.cleanTag(): String? =
        this?.trim()?.takeUnless { it.isEmpty() || it == MediaStore.UNKNOWN_STRING }

    companion object {
        const val UNKNOWN_ARTIST = "Unknown artist"
        const val UNKNOWN_ALBUM = "Unknown album"

        private val ALBUM_ART_BASE: Uri = Uri.parse("content://media/external/audio/albumart")

        fun albumArtUri(albumId: Long): Uri = ContentUris.withAppendedId(ALBUM_ART_BASE, albumId)
    }
}
