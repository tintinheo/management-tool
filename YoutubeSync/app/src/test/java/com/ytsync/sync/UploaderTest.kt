package com.ytsync.sync

import com.ytsync.data.Settings
import com.ytsync.data.UploadDao
import com.ytsync.data.UploadItem
import com.ytsync.data.UploadState
import com.ytsync.youtube.YouTubeApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class UploaderTest {
    private lateinit var server: MockWebServer
    private lateinit var api: YouTubeApi
    private val dao = FakeDao()
    private val files = FakeFiles()
    private val tokens = FakeTokens()
    private var settings = Settings(enabled = true, connected = true)

    /** 1 KiB chunks keep the test small; real uploads use 8 MiB. */
    private val chunk = 1024L

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        api = YouTubeApi(OkHttpClient(), server.url("/").toString().trimEnd('/'), chunkSize = chunk)
    }

    @After fun tearDown() = server.close()

    private fun uploader(retryDelays: List<Long> = emptyList(), with: YouTubeApi = api) =
        Uploader(dao, with, tokens, files, { settings }, retryDelays)

    @Test fun defaultStreamsWholeFileInOnePut() = runTest {
        val single = YouTubeApi(OkHttpClient(), server.url("/").toString().trimEnd('/'))
        dao.put(video(20, 5000))
        server.enqueue(session())
        server.enqueue(created("one"))
        server.enqueue(check(age = true))

        assertEquals(QueueOutcome.DRAINED, uploader(with = single).runQueue())
        server.takeRequest()
        val put = server.takeRequest()
        assertEquals("bytes 0-4999/5000", put.headers["Content-Range"])
        assertEquals(5000L, put.bodySize)
        assertEquals(UploadState.DONE, dao.get(20)!!.state)
    }

    @Test fun groupRenameTitleIsUsedAndAutoTitleUsesRecordingTime() = runTest {
        settings = settings.copy(titleTemplate = "{YYYYMMDD} Auto")
        dao.put(video(30, 100).copy(titleOverride = "20261109 Đà Lạt 01"))
        dao.put(video(31, 100))
        repeat(2) {
            server.enqueue(session()); server.enqueue(created("t$it")); server.enqueue(check(age = true))
        }
        assertEquals(QueueOutcome.DRAINED, uploader().runQueue())

        assertTrue(server.takeRequest().body!!.utf8().contains("\"title\":\"20261109 Đà Lạt 01\""))
        repeat(2) { server.takeRequest() }
        // takenAt = 31_000 ms (1970) — proves the RECORDING time feeds {YYYYMMDD}, not the upload time.
        val auto = server.takeRequest().body!!.utf8()
        val expected = com.ytsync.youtube.VideoMetadata.title("{YYYYMMDD} Auto", 31_000L)
        assertTrue(expected.startsWith("19"))
        assertTrue(auto, auto.contains("\"title\":\"$expected\""))
        assertEquals("20261109 Đà Lạt 01", dao.get(30)!!.title)
        assertTrue(dao.get(31)!!.title!!.endsWith(" Auto"))
    }

    @Test fun updateTitleKeepsDescriptionTagsAndCategory() {
        server.enqueue(MockResponse.Builder().code(200).body(
            """{"items":[{"id":"v1","snippet":{"title":"old","description":"giữ nguyên","tags":["a","b"],"categoryId":"22","channelId":"UC1","thumbnails":{}}}]}""",
        ).build())
        server.enqueue(MockResponse.Builder().code(200).body("""{"id":"v1"}""").build())
        api.updateTitle("tok", "v1", "20261109 Mới")

        assertTrue(server.takeRequest().url.toString().contains("part=snippet&id=v1"))
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        val body = put.body!!.utf8()
        assertTrue(body, body.contains("\"title\":\"20261109 Mới\""))
        assertTrue(body.contains("\"description\":\"giữ nguyên\""))
        assertTrue(body.contains("\"tags\":[\"a\",\"b\"]"))
        assertTrue(body.contains("\"categoryId\":\"22\""))
        assertTrue(body.contains("\"id\":\"v1\""))
        assertTrue(!body.contains("channelId")) // read-only fields are not sent back
    }

    @Test fun networkBlipResumesInSameRun() = runTest {
        dao.put(video(21, 3000))
        server.enqueue(session())
        server.enqueue(partial(1023))
        server.enqueue(MockResponse.Builder().code(503).build())
        // quick retry: status query → server has 0..1023 → continue
        server.enqueue(partial(1023))
        server.enqueue(partial(2047))
        server.enqueue(created("v21"))
        server.enqueue(check(age = true))

        assertEquals(QueueOutcome.DRAINED, uploader(retryDelays = listOf(1)).runQueue())
        repeat(3) { server.takeRequest() }
        assertEquals("bytes */3000", server.takeRequest().headers["Content-Range"])
        assertEquals("bytes 1024-2047/3000", server.takeRequest().headers["Content-Range"])
        assertEquals(UploadState.DONE, dao.get(21)!!.state)
    }

    private fun video(id: Long, size: Int) = UploadItem(id, "VID_$id.mp4", size.toLong(), "video/mp4", takenAt = 1_000L * id).also {
        files.data[id] = ByteArray(size) { i -> (i % 251).toByte() }
    }

    private fun session() = MockResponse.Builder().code(200).addHeader("Location", server.url("/session/1").toString()).build()
    private fun partial(lastByte: Long) = MockResponse.Builder().code(308).addHeader("Range", "bytes=0-$lastByte").build()
    private fun created(id: String) = MockResponse.Builder().code(201).body("""{"id":"$id"}""").build()
    private fun check(age: Boolean) = MockResponse.Builder().code(200).body(
        """{"items":[{"status":{"privacyStatus":"private","madeForKids":false},"contentDetails":{"contentRating":${if (age) """{"ytRating":"ytAgeRestricted"}""" else "{}"}}}]}""",
    ).build()

    @Test fun uploadsInChunksAsPrivate18Plus() = runTest {
        dao.put(video(1, 2500))
        server.enqueue(session())
        server.enqueue(partial(1023))
        server.enqueue(partial(2047))
        server.enqueue(created("abc"))
        server.enqueue(check(age = true))

        assertEquals(QueueOutcome.DRAINED, uploader().runQueue())

        val init = server.takeRequest()
        assertEquals("POST", init.method)
        assertTrue(init.url.toString().contains("uploadType=resumable"))
        assertTrue(init.url.queryParameter("part")!!.contains("contentDetails"))
        assertEquals("2500", init.headers["X-Upload-Content-Length"])
        val meta = init.body!!.utf8()
        assertTrue(meta.contains("\"privacyStatus\":\"private\""))
        assertTrue(meta.contains("\"selfDeclaredMadeForKids\":false"))
        assertTrue(meta.contains("ytAgeRestricted"))
        assertEquals("Bearer tok1", init.headers["Authorization"])

        assertEquals("bytes 0-1023/2500", server.takeRequest().headers["Content-Range"])
        assertEquals("bytes 1024-2047/2500", server.takeRequest().headers["Content-Range"])
        val last = server.takeRequest()
        assertEquals("bytes 2048-2499/2500", last.headers["Content-Range"])
        assertEquals(452L, last.bodySize)

        val done = dao.get(1)!!
        assertEquals(UploadState.DONE, done.state)
        assertEquals("abc", done.videoId)
        assertEquals(true, done.ageRestricted)
        assertNull(done.sessionUrl)
    }

    @Test fun serverErrorKeepsSessionThenResumesFromServerOffset() = runTest {
        dao.put(video(2, 3000))
        server.enqueue(session())
        server.enqueue(partial(1023))
        server.enqueue(MockResponse.Builder().code(503).build())

        assertEquals(QueueOutcome.RETRY, uploader().runQueue())
        val interrupted = dao.get(2)!!
        assertEquals(UploadState.UPLOADING, interrupted.state)
        assertNotNull(interrupted.sessionUrl)
        assertEquals(1024, interrupted.bytesSent)

        // Next run: ask the server, it only has 0..999 → continue from 1000 (re-seeks the file).
        server.enqueue(partial(999))
        server.enqueue(partial(2023))
        server.enqueue(created("v2"))
        server.enqueue(check(age = false))
        assertEquals(QueueOutcome.DRAINED, uploader().runQueue())

        repeat(3) { server.takeRequest() }
        assertEquals("bytes */3000", server.takeRequest().headers["Content-Range"])
        val resumed = server.takeRequest()
        assertEquals("bytes 1000-2023/3000", resumed.headers["Content-Range"])
        assertEquals((1000 % 251).toByte(), resumed.body!![0])
        assertEquals("bytes 2024-2999/3000", server.takeRequest().headers["Content-Range"])
        assertEquals(UploadState.DONE, dao.get(2)!!.state)
        assertEquals(false, dao.get(2)!!.ageRestricted)
    }

    @Test fun rejectedRatingFallsBackToPlainPrivateUpload() = runTest {
        dao.put(video(3, 100))
        server.enqueue(MockResponse.Builder().code(400).body("""{"error":{"message":"bad","errors":[{"reason":"invalidContentDetails"}]}}""").build())
        server.enqueue(session())
        server.enqueue(created("v3"))
        server.enqueue(check(age = false))

        assertEquals(QueueOutcome.DRAINED, uploader().runQueue())
        server.takeRequest()
        val retry = server.takeRequest()
        assertTrue(!retry.url.queryParameter("part")!!.contains("contentDetails"))
        assertTrue(retry.body!!.utf8().contains("\"privacyStatus\":\"private\""))
        assertEquals(UploadState.DONE, dao.get(3)!!.state)
    }

    @Test fun quotaStopsTheRunWithoutFailingTheVideo() = runTest {
        dao.put(video(4, 100))
        server.enqueue(MockResponse.Builder().code(403).body("""{"error":{"message":"quota","errors":[{"reason":"quotaExceeded"}]}}""").build())
        assertEquals(QueueOutcome.QUOTA, uploader().runQueue())
        assertEquals(UploadState.QUEUED, dao.get(4)!!.state)
    }

    @Test fun expiredTokenIsRefreshedOnce() = runTest {
        dao.put(video(5, 100))
        server.enqueue(MockResponse.Builder().code(401).body("""{"error":{"message":"auth"}}""").build())
        server.enqueue(session())
        server.enqueue(created("v5"))
        server.enqueue(check(age = true))
        assertEquals(QueueOutcome.DRAINED, uploader().runQueue())
        assertEquals(listOf("tok1"), tokens.invalidated)
        assertEquals("Bearer tok1", server.takeRequest().headers["Authorization"])
        assertEquals("Bearer tok2", server.takeRequest().headers["Authorization"])
    }

    @Test fun deletedVideoIsSkippedAndNextOneUploads() = runTest {
        dao.put(video(6, 100))
        dao.put(video(7, 100))
        files.data.remove(6)
        server.enqueue(session())
        server.enqueue(created("v7"))
        server.enqueue(check(age = true))
        assertEquals(QueueOutcome.DRAINED, uploader().runQueue())
        assertEquals(UploadState.SKIPPED, dao.get(6)!!.state)
        assertEquals(UploadState.DONE, dao.get(7)!!.state)
    }

    @Test fun permanentErrorFailsOnlyThatVideo() = runTest {
        dao.put(video(8, 100))
        dao.put(video(9, 100))
        server.enqueue(MockResponse.Builder().code(400).body("""{"error":{"message":"bad title","errors":[{"reason":"invalidTitle"}]}}""").build())
        server.enqueue(MockResponse.Builder().code(400).body("""{"error":{"message":"bad title"}}""").build())
        server.enqueue(session())
        server.enqueue(created("v9"))
        server.enqueue(check(age = true))
        assertEquals(QueueOutcome.DRAINED, uploader().runQueue())
        assertEquals(UploadState.FAILED, dao.get(8)!!.state)
        assertEquals(UploadState.DONE, dao.get(9)!!.state)
    }

    @Test fun noTokenAsksForUser() = runTest {
        dao.put(video(10, 100))
        tokens.available = false
        assertEquals(QueueOutcome.NEEDS_AUTH, uploader().runQueue())
        assertEquals(UploadState.QUEUED, dao.get(10)!!.state)
    }
}

private class FakeTokens : TokenSource {
    var available = true
    private var n = 1
    val invalidated = mutableListOf<String>()
    override suspend fun token(): String? = if (available) "tok$n" else null
    override suspend fun invalidate(token: String) { invalidated += token; n++ }
}

private class FakeFiles : VideoFiles {
    val data = mutableMapOf<Long, ByteArray>()
    override fun size(mediaId: Long) = data[mediaId]?.size?.toLong()
    override fun open(mediaId: Long, offset: Long): InputStream =
        ByteArrayInputStream(data.getValue(mediaId)).also { it.skip(offset) }
}

private class FakeDao : UploadDao {
    private val rows = linkedMapOf<Long, UploadItem>()
    private val flow = MutableStateFlow<List<UploadItem>>(emptyList())
    override fun observeRecent(): Flow<List<UploadItem>> = flow
    override suspend fun get(id: Long) = rows[id]
    override suspend fun knownIds() = rows.keys.toList()
    override suspend fun nextPending() = rows.values.filter { it.state == UploadState.QUEUED || it.state == UploadState.UPLOADING }.minByOrNull { it.takenAt }
    override suspend fun pendingCount() = rows.values.count { it.state == UploadState.QUEUED || it.state == UploadState.UPLOADING }
    override suspend fun insertNew(items: List<UploadItem>) = items.forEach { rows.putIfAbsent(it.mediaId, it) }
    override suspend fun put(item: UploadItem) { rows[item.mediaId] = item }
    override suspend fun progress(id: Long, sent: Long, now: Long) { rows[id]?.let { rows[id] = it.copy(bytesSent = sent) } }
    override suspend fun retry(id: Long, now: Long) { rows[id]?.let { rows[id] = it.copy(state = UploadState.QUEUED, error = null) } }
    override suspend fun retryAllFailed() = rows.replaceAll { _, v -> if (v.state == UploadState.FAILED) v.copy(state = UploadState.QUEUED) else v }
    override suspend fun skip(id: Long, now: Long) { rows[id]?.let { rows[id] = it.copy(state = UploadState.SKIPPED) } }
    override fun observeAll(): Flow<List<UploadItem>> = flow
    override suspend fun promoteIdle(ids: List<Long>, now: Long) = ids.forEach { id -> rows[id]?.takeIf { it.state == UploadState.IDLE }?.let { rows[id] = it.copy(state = UploadState.QUEUED) } }
    override suspend fun queue(ids: List<Long>, now: Long) = ids.forEach { id -> rows[id]?.let { rows[id] = it.copy(state = UploadState.QUEUED) } }
    override suspend fun setTitleOverride(id: Long, title: String?, now: Long) { rows[id]?.let { rows[id] = it.copy(titleOverride = title) } }
    override suspend fun setRemoteTitle(id: Long, title: String, now: Long) { rows[id]?.let { rows[id] = it.copy(title = title, titleOverride = title) } }
}
