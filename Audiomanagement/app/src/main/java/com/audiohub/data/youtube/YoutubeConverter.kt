package com.audiohub.data.youtube

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.provider.MediaStore
import com.audiohub.system.DiagnosticsLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.MediaFormat as NpFormat
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext

data class VideoInfo(
    val url: String,
    val title: String,
    val uploader: String,
    val durationMs: Long,
    val thumbnailUrl: String?,
    val audio: AudioCandidate,
)

/** Progress of the single conversion the app runs at a time (survives leaving the screen). */
sealed interface ConvertState {
    data object Idle : ConvertState
    data class Downloading(val title: String, val progress: Float) : ConvertState
    data class Saving(val title: String) : ConvertState
    data class Done(val title: String, val uri: Uri, val fileName: String) : ConvertState
    data class Failed(val message: String) : ConvertState
}

/**
 * YouTube → audio file, for personal use. Uses NewPipeExtractor to find the stream, downloads
 * the best audio-only stream in ranged chunks, trims by remuxing (no re-encode, so no quality
 * loss) and saves to Music/Youtube. YouTube changes often; extractor updates fix breakage.
 */
class YoutubeConverter(
    private val context: Context,
    base: OkHttpClient,
) {
    /** Separate client: no app User-Agent interceptor and no HTTP cache (YouTube needs browser-like headers). */
    private val http: OkHttpClient = base.newBuilder()
        .apply { interceptors().clear() }
        .cache(null)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val r = chain.request()
            chain.proceed(if (r.header("User-Agent") == null) r.newBuilder().header("User-Agent", BROWSER_UA).build() else r)
        }
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val _state = MutableStateFlow<ConvertState>(ConvertState.Idle)
    val state: StateFlow<ConvertState> = _state.asStateFlow()

    private val initialized by lazy { NewPipe.init(OkHttpNewPipeDownloader(http)) }

    suspend fun fetchInfo(link: String): VideoInfo = withContext(Dispatchers.IO) {
        val url = YoutubeLinks.canonical(link) ?: throw IllegalArgumentException("That isn't a YouTube video link")
        initialized
        val info = StreamInfo.getInfo(ServiceList.YouTube, url)
        val candidates = info.audioStreams.mapNotNull { s ->
            if (!s.isUrl) return@mapNotNull null
            val container = when (s.format) {
                NpFormat.WEBMA_OPUS -> AudioContainer.WEBM_OPUS
                NpFormat.M4A -> AudioContainer.M4A_AAC
                else -> return@mapNotNull null
            }
            val kbps = (if (s.averageBitrate > 0) s.averageBitrate else s.bitrate / 1000).coerceAtLeast(1)
            AudioCandidate(
                url = s.content,
                bitrateKbps = kbps,
                container = container,
                isOriginalTrack = s.audioTrackType == null || s.audioTrackType == AudioTrackType.ORIGINAL,
            )
        }
        val best = AudioPicker.best(candidates) ?: throw IOException("No downloadable audio stream for this video")
        VideoInfo(
            url = url,
            title = info.name,
            uploader = info.uploaderName.orEmpty(),
            durationMs = info.duration * 1000,
            thumbnailUrl = info.thumbnails.maxByOrNull { it.width }?.url,
            audio = best,
        )
    }

    /** Starts converting in the background; observe [state]. [endMs] ≤ 0 means "to the end". */
    fun start(video: VideoInfo, startMs: Long, endMs: Long) {
        if (job?.isActive == true) return
        job = scope.launch {
            val temp = File(context.cacheDir, "yt-${System.nanoTime()}.${video.audio.container.name.lowercase()}")
            val trimmed = File(context.cacheDir, "yt-${System.nanoTime()}.out")
            try {
                _state.value = ConvertState.Downloading(video.title, 0f)
                download(video.audio.url, temp) { _state.value = ConvertState.Downloading(video.title, it) }
                _state.value = ConvertState.Saving(video.title)
                remux(temp, trimmed, video.audio.container, startMs, if (endMs > 0) endMs else Long.MAX_VALUE)
                val suffix = if (YoutubeTrim.isFull(startMs, endMs, video.durationMs)) "" else
                    " (${com.audiohub.data.ringtone.RingtoneClip.formatTime(startMs)}–" +
                        "${com.audiohub.data.ringtone.RingtoneClip.formatTime(if (endMs > 0) endMs else video.durationMs)})"
                val name = YoutubeTrim.fileBaseName(video.title + suffix.replace(':', '.')) + "." + video.audio.container.extension
                val uri = publish(trimmed, name, video)
                DiagnosticsLog.log("YouTube", "Saved $name (${video.audio.label})")
                _state.value = ConvertState.Done(video.title, uri, name)
            } catch (e: kotlinx.coroutines.CancellationException) {
                _state.value = ConvertState.Idle
                throw e
            } catch (e: Exception) {
                DiagnosticsLog.log("YouTube", "Conversion failed for ${video.url}", e)
                _state.value = ConvertState.Failed(e.message ?: "Conversion failed")
            } finally {
                temp.delete()
                trimmed.delete()
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    fun reset() {
        if (job?.isActive != true) _state.value = ConvertState.Idle
    }

    /** YouTube throttles single huge requests; fetch in ranged chunks like official clients. */
    private suspend fun download(url: String, out: File, onProgress: (Float) -> Unit) {
        val chunk = 4L * 1024 * 1024
        var position = 0L
        var total = -1L
        out.outputStream().buffered().use { sink ->
            while (total < 0 || position < total) {
                coroutineContext.ensureActive()
                val end = if (total > 0) minOf(position + chunk, total) - 1 else position + chunk - 1
                val request = Request.Builder().url(url).header("Range", "bytes=$position-$end").build()
                http.newCall(request).execute().use { response ->
                    if (response.code == 416 && total < 0) { total = position; return@use }
                    if (!response.isSuccessful) throw IOException("YouTube refused the download (HTTP ${response.code})")
                    response.header("Content-Range")?.substringAfter('/')?.toLongOrNull()?.let { total = it }
                    val bytes = response.body.byteStream().copyTo(sink)
                    if (bytes == 0L) total = position
                    position += bytes
                    if (response.code == 200) total = position // server ignored Range: whole file in one go
                }
                if (total > 0) onProgress((position.toFloat() / total).coerceIn(0f, 1f))
            }
        }
    }

    /** Copies [startMs]..[endMs] of the audio track into a new container without re-encoding. */
    private fun remux(input: File, output: File, container: AudioContainer, startMs: Long, endMs: Long) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(input.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("No audio track in the download")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val muxer = MediaMuxer(
                output.absolutePath,
                if (container == AudioContainer.WEBM_OPUS) MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG
                else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
            )
            try {
                val outTrack = muxer.addTrack(format)
                muxer.start()
                val startUs = startMs * 1000
                val endUs = if (endMs == Long.MAX_VALUE) Long.MAX_VALUE else endMs * 1000
                if (startUs > 0) extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val capacity = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 1 shl 20
                val buffer = ByteBuffer.allocate(capacity.coerceAtLeast(64 * 1024))
                val info = MediaCodec.BufferInfo()
                var written = 0
                while (true) {
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    val time = extractor.sampleTime
                    if (time > endUs) break
                    if (time >= startUs) {
                        info.set(0, size, time - startUs, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                        muxer.writeSampleData(outTrack, buffer, info)
                        written++
                    }
                    extractor.advance()
                }
                if (written == 0) throw IOException("Nothing to save in that time range")
                muxer.stop()
            } finally {
                muxer.release()
            }
        } finally {
            extractor.release()
        }
    }

    private fun publish(file: File, displayName: String, video: VideoInfo): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.TITLE, video.title)
            put(MediaStore.Audio.Media.ARTIST, video.uploader)
            put(MediaStore.Audio.Media.MIME_TYPE, video.audio.container.mimeType)
            put(MediaStore.Audio.Media.RELATIVE_PATH, OUTPUT_FOLDER)
            put(MediaStore.Audio.Media.IS_MUSIC, 1)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IOException("Couldn't create the file in $OUTPUT_FOLDER")
        try {
            resolver.openOutputStream(uri, "w")?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: throw IOException("Couldn't write the file")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    companion object {
        const val OUTPUT_FOLDER = "Music/Youtube/"
        /** Same desktop-browser identity NewPipe uses. */
        const val BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; rv:128.0) Gecko/20100101 Firefox/128.0"
    }
}

/** NewPipeExtractor's HTTP hook, backed by the app's OkHttp client. */
private class OkHttpNewPipeDownloader(private val client: OkHttpClient) : Downloader() {
    override fun execute(request: org.schabi.newpipe.extractor.downloader.Request): Response {
        val body = request.dataToSend()?.toRequestBody()
        val builder = Request.Builder().url(request.url()).method(request.httpMethod(), body)
        request.headers().forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { builder.addHeader(name, it) }
        }
        client.newCall(builder.build()).execute().use { r ->
            if (r.code == 429) throw ReCaptchaException("YouTube is rate-limiting this network (HTTP 429). Try again later.", request.url())
            return Response(
                r.code,
                r.message,
                r.headers.toMultimap(),
                r.body.string(),
                r.request.url.toString(),
            )
        }
    }
}
