package com.audiohub.data.podcast

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InputStream

data class HttpResponse(
    val code: Int,
    val body: ByteArray,
    /** Lower-cased header names. */
    val headers: Map<String, String>,
) {
    val isSuccessful get() = code in 200..299
}

class HttpException(val code: Int, url: String) : IOException("HTTP $code for $url")

/** Minimal HTTP seam so repositories can be tested without a network. */
interface HttpClient {
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResponse

    suspend fun getText(url: String): String {
        val r = get(url)
        if (!r.isSuccessful) throw HttpException(r.code, url)
        return r.body.toString(Charsets.UTF_8)
    }

    /** Streams a (possibly huge) response body without buffering it in memory. */
    suspend fun <T> stream(url: String, block: suspend (code: Int, contentLength: Long, body: InputStream) -> T): T {
        val r = get(url)
        return block(r.code, r.body.size.toLong(), r.body.inputStream())
    }
}

class OkHttpHttpClient(private val client: OkHttpClient) : HttpClient {
    override suspend fun <T> stream(
        url: String,
        block: suspend (code: Int, contentLength: Long, body: InputStream) -> T,
    ): T = withContext(Dispatchers.IO) {
        // Large media: no HTTP cache, long read timeout.
        val call = client.newBuilder().cache(null).readTimeout(2, java.util.concurrent.TimeUnit.MINUTES).build()
            .newCall(Request.Builder().url(url).build())
        call.execute().use { response ->
            response.body.byteStream().use { input -> block(response.code, response.body.contentLength(), input) }
        }
    }

    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        client.newCall(request).execute().use { response ->
            HttpResponse(
                code = response.code,
                body = response.body.bytes(),
                headers = response.headers.toMultimap().mapKeys { it.key.lowercase() }.mapValues { it.value.first() },
            )
        }
    }
}
