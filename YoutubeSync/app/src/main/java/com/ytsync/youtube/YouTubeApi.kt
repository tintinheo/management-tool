package com.ytsync.youtube

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/** An HTTP error from YouTube. [reason] is the first `error.errors[].reason`, e.g. `quotaExceeded`. */
class YouTubeException(val code: Int, val reason: String?, message: String) : IOException("HTTP $code $reason: $message") {
    /** Worth retrying later with the same session (YouTube's own list: 500/502/503/504). */
    val transient: Boolean get() = code in setOf(500, 502, 503, 504) || code == 429
    val unauthorized: Boolean get() = code == 401
    /** Daily API quota or the channel's upload limit — nothing to do until tomorrow. */
    val quota: Boolean get() = code == 403 && reason in setOf("quotaExceeded", "uploadLimitExceeded", "rateLimitExceeded", "userRateLimitExceeded")
}

sealed interface ResumeStatus {
    /** Server has bytes [0, nextByte). */
    data class Partial(val nextByte: Long) : ResumeStatus
    data class Complete(val videoId: String) : ResumeStatus
    /** 404 — the session URI expired; start a new one. */
    data object Expired : ResumeStatus
}

data class VideoCheck(val privacyStatus: String?, val ageRestricted: Boolean, val madeForKids: Boolean?)

/**
 * Minimal YouTube Data API v3 client: resumable upload + the two read calls we need.
 * Protocol: https://developers.google.com/youtube/v3/guides/using_resumable_upload_protocol
 */
class YouTubeApi(
    private val http: OkHttpClient = defaultClient(),
    private val baseUrl: String = "https://www.googleapis.com",
    private val chunkSize: Long = SINGLE_REQUEST,
) {
    companion object {
        /**
         * Default: stream the whole remaining file in ONE PUT. Chunked uploads stop and wait a full
         * round-trip for a 308 after every chunk, which leaves the link idle; Google recommends a single
         * request when possible. Resuming still works — after a drop we ask the server for its offset.
         * (Tests pass a small multiple of 256 KiB to exercise the chunked path.)
         */
        const val SINGLE_REQUEST = Long.MAX_VALUE
        /** Report progress at most this often, so DB/notification updates never slow the socket. */
        private const val PROGRESS_EVERY_MS = 500L
        private const val IO_BUFFER = 256 * 1024
        private val JSON = "application/json; charset=UTF-8".toMediaType()
        private val json = Json { ignoreUnknownKeys = true }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()

        /** "bytes=0-524287" → 524288 (next byte to send). */
        fun nextByteFromRange(range: String?): Long {
            if (range.isNullOrBlank()) return 0
            val last = range.substringAfterLast('-').trim().toLongOrNull() ?: return 0
            return last + 1
        }
    }

    /** Opens an upload session; returns the session URI (Location header). */
    fun startSession(token: String, parts: String, metadata: JsonObject, size: Long, mime: String): String {
        val req = Request.Builder()
            .url("$baseUrl/upload/youtube/v3/videos?uploadType=resumable&part=$parts")
            .header("Authorization", "Bearer $token")
            .header("X-Upload-Content-Length", size.toString())
            .header("X-Upload-Content-Type", mime)
            .post(metadata.toString().toRequestBody(JSON))
            .build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw r.toError()
            return r.header("Location") ?: throw YouTubeException(r.code, null, "no Location header")
        }
    }

    /** Asks how much of the file the server already has. */
    fun queryStatus(token: String, sessionUrl: String, size: Long): ResumeStatus {
        val req = Request.Builder()
            .url(sessionUrl)
            .header("Authorization", "Bearer $token")
            .header("Content-Range", "bytes */$size")
            .put(ByteArray(0).toRequestBody(null))
            .build()
        http.newCall(req).execute().use { r ->
            return when {
                r.code == 308 -> ResumeStatus.Partial(nextByteFromRange(r.header("Range")))
                r.code == 200 || r.code == 201 -> ResumeStatus.Complete(r.videoId())
                r.code == 404 || r.code == 410 -> ResumeStatus.Expired
                else -> throw r.toError()
            }
        }
    }

    /**
     * Sends bytes from [startByte] to the end (one PUT by default, see [SINGLE_REQUEST]). [open] must
     * return a stream positioned at the given offset. [onProgress] gets bytes sent so far, throttled
     * while streaming. Returns the new video id.
     */
    fun upload(
        token: String,
        sessionUrl: String,
        size: Long,
        mime: String,
        startByte: Long,
        open: (offset: Long) -> InputStream,
        onProgress: (sent: Long) -> Unit,
        chunk: Long = chunkSize,
    ): String {
        var pos = startByte
        var stream = open(pos)
        var streamPos = pos
        try {
            while (true) {
                if (streamPos != pos) { // server accepted less than we sent: re-seek
                    stream.close(); stream = open(pos); streamPos = pos
                }
                val len = minOf(chunk, size - pos)
                val chunkStart = pos
                val body = ChunkBody(stream, len, mime.toMediaType()) { written -> onProgress(chunkStart + written) }
                val req = Request.Builder()
                    .url(sessionUrl)
                    .header("Authorization", "Bearer $token")
                    .header("Content-Range", if (len > 0) "bytes $pos-${pos + len - 1}/$size" else "bytes */$size")
                    .put(body)
                    .build()
                http.newCall(req).execute().use { r ->
                    streamPos += body.written
                    when (r.code) {
                        200, 201 -> { onProgress(size); return r.videoId() }
                        308 -> {
                            pos = nextByteFromRange(r.header("Range"))
                            onProgress(pos)
                        }
                        404, 410 -> throw YouTubeException(r.code, "sessionExpired", "upload session expired")
                        else -> throw r.toError()
                    }
                }
            }
        } finally {
            stream.close()
        }
    }

    /** Reads back privacy + age restriction so the app can show what YouTube actually applied. */
    fun checkVideo(token: String, videoId: String): VideoCheck? {
        val req = Request.Builder()
            .url("$baseUrl/youtube/v3/videos?part=status,contentDetails&id=$videoId")
            .header("Authorization", "Bearer $token")
            .get().build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw r.toError()
            val items = json.parseToJsonElement(r.body.string()).jsonObject["items"]?.jsonArray ?: return null
            val v = items.firstOrNull()?.jsonObject ?: return null
            val status = v["status"]?.jsonObject
            val rating = v["contentDetails"]?.jsonObject?.get("contentRating")?.jsonObject?.get("ytRating")?.jsonPrimitive?.content
            return VideoCheck(
                privacyStatus = status?.get("privacyStatus")?.jsonPrimitive?.content,
                ageRestricted = rating == "ytAgeRestricted",
                madeForKids = status?.get("madeForKids")?.jsonPrimitive?.content?.toBooleanStrictOrNull(),
            )
        }
    }

    /**
     * Renames an uploaded video. videos.update REPLACES the whole snippet (omitted description/tags get
     * deleted, categoryId is required), so we read the current snippet and send it back with only the
     * title changed. Needs the youtube / youtube.force-ssl scope. Costs 1 + 50 quota units.
     */
    fun updateTitle(token: String, videoId: String, title: String) {
        val get = Request.Builder()
            .url("$baseUrl/youtube/v3/videos?part=snippet&id=$videoId")
            .header("Authorization", "Bearer $token")
            .get().build()
        val snippet = http.newCall(get).execute().use { r ->
            if (!r.isSuccessful) throw r.toError()
            json.parseToJsonElement(r.body.string()).jsonObject["items"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("snippet")?.jsonObject
                ?: throw YouTubeException(404, "videoNotFound", "Video $videoId không còn trên YouTube")
        }
        val writable = setOf("description", "tags", "categoryId", "defaultLanguage")
        val newSnippet = JsonObject(
            snippet.filterKeys { it in writable } + ("title" to kotlinx.serialization.json.JsonPrimitive(title)),
        )
        val body = JsonObject(mapOf("id" to kotlinx.serialization.json.JsonPrimitive(videoId), "snippet" to newSnippet))
        val put = Request.Builder()
            .url("$baseUrl/youtube/v3/videos?part=snippet")
            .header("Authorization", "Bearer $token")
            .put(body.toString().toRequestBody(JSON))
            .build()
        http.newCall(put).execute().use { r -> if (!r.isSuccessful) throw r.toError() }
    }

    /** Title of the authorised user's channel (for the "connected as" line). */
    fun myChannelTitle(token: String): String? {
        val req = Request.Builder()
            .url("$baseUrl/youtube/v3/channels?part=snippet&mine=true")
            .header("Authorization", "Bearer $token")
            .get().build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw r.toError()
            val items = json.parseToJsonElement(r.body.string()).jsonObject["items"]?.jsonArray ?: return null
            return items.firstOrNull()?.jsonObject?.get("snippet")?.jsonObject?.get("title")?.jsonPrimitive?.content
        }
    }

    private fun Response.videoId(): String {
        val text = body.string()
        return runCatching { json.parseToJsonElement(text).jsonObject["id"]!!.jsonPrimitive.content }
            .getOrElse { throw YouTubeException(code, null, "no video id in response") }
    }

    private fun Response.toError(): YouTubeException {
        val text = runCatching { body.string() }.getOrDefault("")
        val err = runCatching { json.parseToJsonElement(text).jsonObject["error"]?.jsonObject }.getOrNull()
        val reason = runCatching { err?.get("errors")?.jsonArray?.firstOrNull()?.jsonObject?.get("reason")?.jsonPrimitive?.content }.getOrNull()
        val msg = runCatching { err?.get("message")?.jsonPrimitive?.content }.getOrNull() ?: text.take(200)
        return YouTubeException(code, reason, msg)
    }

    /** Streams exactly [length] bytes; one-shot so OkHttp never silently re-sends a half-read stream. */
    private class ChunkBody(
        private val input: InputStream,
        private val length: Long,
        private val type: MediaType,
        private val onWritten: (Long) -> Unit,
    ) : RequestBody() {
        var written = 0L
            private set

        override fun contentType() = type
        override fun contentLength() = length
        override fun isOneShot() = true

        override fun writeTo(sink: BufferedSink) {
            val buf = ByteArray(IO_BUFFER)
            var lastReport = System.currentTimeMillis()
            while (written < length) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), length - written).toInt())
                if (n < 0) throw IOException("file ended early (${written}/$length)")
                sink.write(buf, 0, n)
                written += n
                val now = System.currentTimeMillis()
                if (now - lastReport >= PROGRESS_EVERY_MS) {
                    lastReport = now
                    onWritten(written)
                }
            }
        }
    }
}
