package com.audiohub.data.youtube

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Hits the real YouTube (network!). Skipped unless LIVE_YOUTUBE=1, so the normal suite stays offline.
 * Uses "Big Buck Bunny" (Blender Foundation, CC-BY).
 */
@RunWith(RobolectricTestRunner::class)
class YoutubeLiveCheck {
    @Test
    fun extractorFindsAndServesBestAudio() = runBlocking {
        assumeTrue(System.getenv("LIVE_YOUTUBE") == "1")
        val converter = YoutubeConverter(RuntimeEnvironment.getApplication(), OkHttpClient())
        val info = converter.fetchInfo("https://youtu.be/aqz-KE-bpKQ")
        println("LIVE: '${info.title}' by ${info.uploader}, ${info.durationMs / 1000}s, best=${info.audio.label}")
        assertTrue(info.durationMs > 60_000)

        val response = OkHttpClient().newCall(
            Request.Builder().url(info.audio.url)
                .header("Range", "bytes=0-65535")
                .header("User-Agent", YoutubeConverter.BROWSER_UA)
                .build(),
        ).execute()
        response.use {
            println("LIVE: stream HTTP ${it.code}, ${it.body.bytes().size} bytes, Content-Range=${it.header("Content-Range")}")
            assertTrue("stream should be downloadable (got ${it.code})", it.code == 206 || it.code == 200)
        }
    }
}
