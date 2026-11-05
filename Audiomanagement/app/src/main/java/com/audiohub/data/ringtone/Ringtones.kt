package com.audiohub.data.ringtone

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class RingtoneTarget(val label: String, val type: Int) {
    RINGTONE("Phone ringtone", RingtoneManager.TYPE_RINGTONE),
    NOTIFICATION("Notification sound", RingtoneManager.TYPE_NOTIFICATION),
    ALARM("Alarm", RingtoneManager.TYPE_ALARM),
}

/** Pure helpers for the clip editor (unit-tested). */
object RingtoneClip {
    const val DEFAULT_LENGTH_MS = 30_000L
    const val MIN_LENGTH_MS = 1_000L
    const val MAX_LENGTH_MS = 5 * 60_000L

    /** "1:23.5" → 83 500 ms, "83" → 83 000, "1:02:03" → 3 723 000; null if invalid. */
    fun parseTime(text: String): Long? {
        val t = text.trim().replace(',', '.')
        if (t.isEmpty()) return null
        val parts = t.split(':')
        if (parts.size > 3) return null
        var seconds = 0.0
        parts.forEachIndexed { i, p ->
            val v = p.toDoubleOrNull() ?: return null
            if (v < 0 || (i > 0 && v >= 60)) return null
            seconds = seconds * 60 + v
        }
        return (seconds * 1000).toLong()
    }

    /** 83 500 → "1:23.5" (tenths shown so the start/end can be set precisely). */
    fun formatTime(ms: Long): String {
        val tenths = (ms.coerceAtLeast(0) + 50) / 100
        val m = tenths / 600
        val s = (tenths % 600) / 10
        val d = tenths % 10
        return "%d:%02d.%d".format(m, s, d)
    }

    /** Initial selection: the first 30 s (or the whole track if shorter). */
    fun defaultRange(durationMs: Long): Pair<Long, Long> = 0L to minOf(DEFAULT_LENGTH_MS, durationMs.coerceAtLeast(0))

    /** Problem with a selection, or null if it's usable. */
    fun validate(startMs: Long, endMs: Long, durationMs: Long): String? = when {
        startMs < 0 -> "Start can't be negative"
        durationMs > 0 && endMs > durationMs -> "End is after the song ends (${formatTime(durationMs)})"
        endMs - startMs < MIN_LENGTH_MS -> "Clip must be at least 1 second"
        endMs - startMs > MAX_LENGTH_MS -> "Clip can be at most 5 minutes"
        else -> null
    }

    fun fileName(title: String): String =
        title.replace(Regex("""[\\/:*?"<>|\u0000-\u001F]+"""), " ").replace(Regex("\\s+"), " ").trim().take(80).ifEmpty { "Ringtone" } + ".m4a"
}

/** Cuts a clip out of a song, saves it to /Ringtones and optionally sets it as a system sound. */
class RingtoneMaker(private val context: Context) {

    /** Encodes [startMs]..[endMs] of [source] to AAC and stores it in the shared Ringtones folder. */
    suspend fun export(source: Uri, startMs: Long, endMs: Long, title: String): Uri {
        val temp = File(context.cacheDir, "ringtone-${System.currentTimeMillis()}.m4a")
        try {
            withContext(Dispatchers.Main) { transcode(source, startMs, endMs, temp) }
            return withContext(Dispatchers.IO) { publish(temp, title) }
        } finally {
            temp.delete()
        }
    }

    @OptIn(UnstableApi::class)
    private suspend fun transcode(source: Uri, startMs: Long, endMs: Long, out: File) =
        suspendCancellableCoroutine { cont ->
            val item = MediaItem.Builder()
                .setUri(source)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startMs).setEndPositionMs(endMs).build(),
                )
                .build()
            val edited = EditedMediaItem.Builder(item).setRemoveVideo(true).build()
            val transformer = Transformer.Builder(context)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (cont.isActive) cont.resume(Unit)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        if (cont.isActive) cont.resumeWithException(exportException)
                    }
                })
                .build()
            cont.invokeOnCancellation { transformer.cancel() }
            transformer.start(edited, out.absolutePath)
        }

    private fun publish(file: File, title: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, RingtoneClip.fileName(title))
            put(MediaStore.Audio.Media.TITLE, title)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_RINGTONES + "/")
            put(MediaStore.Audio.Media.IS_RINGTONE, 1)
            put(MediaStore.Audio.Media.IS_NOTIFICATION, 1)
            put(MediaStore.Audio.Media.IS_ALARM, 1)
            put(MediaStore.Audio.Media.IS_MUSIC, 0)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IOException("Couldn't create the ringtone file")
        try {
            resolver.openOutputStream(uri, "w")?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: throw IOException("Couldn't write the ringtone file")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    /** Changing system sounds needs the "Modify system settings" special permission. */
    fun canSetSystemSounds(): Boolean = Settings.System.canWrite(context)

    fun systemSettingsPermissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, "package:${context.packageName}".toUri())

    fun setAsDefault(uri: Uri, target: RingtoneTarget) {
        RingtoneManager.setActualDefaultRingtoneUri(context, target.type, uri)
    }
}
