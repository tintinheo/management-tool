package com.remotecam.lan

import com.remotecam.camera.ControlRequest
import com.remotecam.remote.RemoteCommand
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URI
import java.util.Collections

class LanServerTest {
    private val hubs = StreamHubs()
    private val commands = Collections.synchronizedList(mutableListOf<RemoteCommand>())
    private val controls = Collections.synchronizedList(mutableListOf<ControlRequest>())
    @Volatile private var cameraOpen = true
    @Volatile private var status = """{"photos":2}"""
    private val video = "fake-mp4-bytes".toByteArray()

    private val library = object : MediaLibrary {
        override fun list(limit: Int) = listOf(MediaItem(7, "RemoteCam_1.mp4", "video/mp4", video.size.toLong(), 1_000, video = true))
        override fun thumbnail(id: Long) = if (id == 7L) byteArrayOf(1, 2, 3) else null
        override fun open(id: Long) =
            if (id == 7L) MediaFile("RemoteCam 1.mp4", "video/mp4", video.size.toLong(), ByteArrayInputStream(video)) else null
    }

    private lateinit var server: LanServer
    private var port = 0

    @Before fun setUp() {
        server = LanServer(
            token = "123456",
            streams = hubs,
            backend = object : LanBackend {
                override fun page() = "<html>ok</html>".toByteArray()
                override fun status() = status
                override fun command(command: RemoteCommand) = cameraOpen && commands.add(command)
                override fun control(request: ControlRequest) = cameraOpen && controls.add(request)
                override val media = library
            },
        )
        port = server.start(preferredPort = 0)
    }

    @After fun tearDown() = server.stop()

    private fun request(path: String, method: String = "GET"): Triple<Int, String, HttpURLConnection> {
        val conn = URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection
        conn.requestMethod = method
        val code = conn.responseCode
        val body = (if (code < 400) conn.inputStream else conn.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
        conn.disconnect()
        return Triple(code, body, conn)
    }

    @Test fun rejectsRequestsWithoutTheAccessCode() {
        assertEquals(403, request("/").first)
        assertEquals(403, request("/status?k=000000").first)
        assertEquals(403, request("/cmd?c=shutter", "POST").first)
        assertEquals(403, request("/media/file?id=7").first)
    }

    @Test fun servesPageAndStatus() {
        assertEquals("<html>ok</html>", request("/?k=123456").second)
        assertEquals("""{"photos":2}""", request("/status?k=123456").second)
    }

    @Test fun commandsReachTheCameraOrReportItClosed() {
        assertEquals(204, request("/cmd?k=123456&c=shutter", "POST").first)
        assertEquals(204, request("/cmd?k=123456&c=zoom_in", "POST").first)
        assertEquals(listOf(RemoteCommand.SHUTTER, RemoteCommand.ZOOM_IN), commands.toList())
        assertEquals(400, request("/cmd?k=123456&c=selfdestruct", "POST").first)
        cameraOpen = false
        assertEquals(409, request("/cmd?k=123456&c=shutter", "POST").first)
    }

    @Test fun controlsAreValidatedBeforeReachingTheCamera() {
        assertEquals(204, request("/control?k=123456&key=mode&value=video", "POST").first)
        assertEquals(204, request("/control?k=123456&key=focus&x=0.25&y=0.75", "POST").first)
        assertEquals(400, request("/control?k=123456&key=mode&value=cinema", "POST").first)
        assertEquals(400, request("/control?k=123456&key=focus&x=2&y=0", "POST").first)
        assertEquals(400, request("/control?k=123456&key=rm&value=-rf", "POST").first)
        assertEquals(
            listOf(ControlRequest("mode", "video"), ControlRequest("focus", x = 0.25f, y = 0.75f)),
            controls.toList(),
        )
    }

    @Test fun galleryListsThumbnailsAndDownloads() {
        val (code, json) = request("/media?k=123456").let { it.first to it.second }
        assertEquals(200, code)
        assertTrue(json, json.contains("\"id\":7") && json.contains("\"video\":true"))

        assertEquals(200, request("/media/thumb?k=123456&id=7").first)
        assertEquals(404, request("/media/thumb?k=123456&id=8").first)

        val conn = URI("http://127.0.0.1:$port/media/file?k=123456&id=7").toURL().openConnection() as HttpURLConnection
        assertEquals(200, conn.responseCode)
        assertTrue(conn.getHeaderField("Content-Disposition"), conn.getHeaderField("Content-Disposition").contains("RemoteCam%201.mp4"))
        assertArrayEquals(video, conn.inputStream.use { it.readBytes() })
    }

    @Test fun eventsPushStatusOnlyWhenItChanges() {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().write("GET /events?k=123456&i=100 HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
            val input = DataInputStream(socket.getInputStream())
            assertTrue(readUntil(input, "\r\n\r\n").contains("text/event-stream"))
            readUntil(input, "\n\n") // retry hint
            assertEquals("data: {\"photos\":2}\n\n", readUntil(input, "\n\n"))
            status = """{"photos":3}"""
            assertEquals("data: {\"photos\":3}\n\n", readUntil(input, "\n\n"))
        }
    }

    @Test fun streamsEachQualityFromItsOwnHub() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().write("GET /stream.mjpg?k=123456&p=low HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
            val input = DataInputStream(socket.getInputStream())
            val headers = readUntil(input, "\r\n\r\n")
            assertTrue(headers, headers.startsWith("HTTP/1.1 200") && headers.contains("multipart/x-mixed-replace"))

            val low = hubs.hub(StreamProfile.LOW)
            val deadline = System.currentTimeMillis() + 2_000
            while (low.viewers.get() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
            assertEquals(listOf(StreamProfile.LOW), hubs.active())

            hubs.hub(StreamProfile.HIGH).publish(byteArrayOf(9, 9)) // nobody watches HIGH
            low.publish(jpeg)
            val part = readUntil(input, "\r\n\r\n")
            assertTrue(part, part.contains("Content-Length: ${jpeg.size}"))
            assertArrayEquals(jpeg, ByteArray(jpeg.size).also { input.readFully(it) })
        }
    }

    @Test fun parsesQueryStrings() {
        assertEquals(mapOf("k" to "12 34", "c" to "shutter"), LanServer.parseQuery("k=12%2034&c=shutter"))
        assertEquals(emptyMap<String, String>(), LanServer.parseQuery(""))
    }

    private fun readUntil(input: DataInputStream, end: String): String {
        val text = StringBuilder()
        while (!text.endsWith(end)) text.append(input.readUnsignedByte().toChar())
        return text.toString()
    }
}
