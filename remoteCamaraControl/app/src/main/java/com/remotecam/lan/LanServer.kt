package com.remotecam.lan

import com.remotecam.camera.ControlRequest
import com.remotecam.remote.RemoteCommand
import com.remotecam.util.Json
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** What the server needs from the rest of the app. */
interface LanBackend {
    fun page(): ByteArray
    fun status(): String
    /** False when the camera screen on the phone is closed. */
    fun command(command: RemoteCommand): Boolean
    fun control(request: ControlRequest): Boolean
    val media: MediaLibrary?
}

/**
 * Minimal HTTP server for the local network (Wi-Fi or the phone's own hotspot; no internet needed).
 *
 * - `/`             control page
 * - `/stream.mjpg`  MJPEG preview, `?p=low|med|high`
 * - `/events`       Server-Sent Events with the status JSON whenever it changes (no polling)
 * - `/cmd`          button actions, `/control` settings and tap-to-focus
 * - `/media…`       gallery list, thumbnails and downloads
 *
 * Every request must carry the access code as `?k=`. Plain JVM code, tested with real sockets.
 */
class LanServer(
    private val token: String,
    private val streams: StreamHubs,
    private val backend: LanBackend,
    private val log: (String) -> Unit = {},
    private val onViewers: (Int) -> Unit = {},
) {
    private var server: ServerSocket? = null
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private var pool: ExecutorService? = null

    /** Binds to the first free port from [preferredPort] and returns it. */
    fun start(preferredPort: Int = 8080): Int {
        check(server == null) { "already started" }
        val socket = (preferredPort until preferredPort + 10).firstNotNullOfOrNull { port ->
            runCatching {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(port))
                }
            }.getOrNull()
        } ?: ServerSocket(0)
        val executor = Executors.newCachedThreadPool { r -> Thread(r, "remotecam-http").apply { isDaemon = true } }
        server = socket
        pool = executor
        executor.execute { acceptLoop(socket, executor) }
        return socket.localPort
    }

    fun stop() {
        runCatching { server?.close() }
        clients.forEach { runCatching { it.close() } }
        clients.clear()
        pool?.shutdownNow()
        server = null
        pool = null
    }

    private fun acceptLoop(socket: ServerSocket, executor: ExecutorService) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: IOException) {
                break
            }
            clients += client
            executor.execute {
                try {
                    handle(client)
                } catch (_: IOException) {
                    // Browser went away mid-response.
                } catch (_: InterruptedException) {
                    // Server stopping.
                } finally {
                    clients -= client
                    runCatching { client.close() }
                }
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.tcpNoDelay = true
        socket.soTimeout = 10_000
        val input = BufferedInputStream(socket.getInputStream())
        val requestLine = readLine(input) ?: return
        var contentLength = 0
        while (true) {
            val header = readLine(input) ?: return
            if (header.isEmpty()) break
            if (header.startsWith("content-length:", ignoreCase = true)) {
                contentLength = header.substringAfter(':').trim().toIntOrNull() ?: 0
            }
        }
        repeat(contentLength.coerceAtMost(64 * 1024)) { if (input.read() < 0) return }

        val method = requestLine.substringBefore(' ')
        val target = requestLine.substringAfter(' ').substringBefore(' ')
        val path = target.substringBefore('?')
        val query = parseQuery(target.substringAfter('?', ""))
        val out = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)

        if (path == "/favicon.ico") return respond(out, 404, TEXT, ByteArray(0))
        if (query["k"] != token) {
            return respond(out, 403, TEXT, "Sai mã truy cập. Mở đúng link hiện trong app RemoteCam.".toByteArray())
        }
        when {
            method == "GET" && path == "/" -> respond(out, 200, "text/html; charset=utf-8", backend.page())
            method == "GET" && path == "/status" -> respond(out, 200, JSON, backend.status().toByteArray())
            method == "GET" && path == "/events" -> {
                socket.soTimeout = 0
                events(socket, out, query["i"]?.toLongOrNull()?.coerceIn(100, 2_000) ?: 250)
            }
            method == "GET" && path == "/stream.mjpg" -> {
                socket.soTimeout = 0
                stream(socket, out, StreamProfile.from(query["p"]))
            }
            method == "POST" && path == "/cmd" -> {
                val command = RemoteCommand.fromWire(query["c"])
                when {
                    command == null -> respond(out, 400, TEXT, "Lệnh không hợp lệ".toByteArray())
                    backend.command(command) -> respond(out, 204, TEXT, ByteArray(0))
                    else -> respond(out, 409, TEXT, CAMERA_CLOSED)
                }
            }
            method == "POST" && path == "/control" -> {
                val request = ControlRequest.parse(query)
                when {
                    request == null -> respond(out, 400, TEXT, "Cài đặt không hợp lệ".toByteArray())
                    backend.control(request) -> respond(out, 204, TEXT, ByteArray(0))
                    else -> respond(out, 409, TEXT, CAMERA_CLOSED)
                }
            }
            method == "GET" && path.startsWith("/media") -> media(out, path, query)
            else -> respond(out, 404, TEXT, "Not found".toByteArray())
        }
    }

    /** Pushes the status only when it changed, plus a heartbeat so proxies and Wi-Fi sleep keep it open. */
    private fun events(socket: Socket, out: OutputStream, intervalMs: Long) {
        out.write(
            ("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\n\r\nretry: 1500\n\n").toByteArray(),
        )
        out.flush()
        var last: String? = null
        var lastSentAt = 0L
        while (!socket.isClosed) {
            val status = backend.status()
            val now = System.currentTimeMillis()
            if (status != last) {
                out.write("data: $status\n\n".toByteArray())
                out.flush()
                last = status
                lastSentAt = now
            } else if (now - lastSentAt > 15_000) {
                out.write(": ping\n\n".toByteArray())
                out.flush()
                lastSentAt = now
            }
            Thread.sleep(intervalMs)
        }
    }

    private fun stream(socket: Socket, out: OutputStream, profile: StreamProfile) {
        val who = socket.inetAddress?.hostAddress ?: "?"
        val hub = streams.hub(profile)
        hub.viewers.incrementAndGet()
        val count = streams.totalViewers()
        log("Xem preview: $who, chất lượng ${profile.wire} (đang xem: $count)")
        onViewers(count)
        try {
            out.write(
                ("HTTP/1.1 200 OK\r\n" +
                    "Content-Type: multipart/x-mixed-replace; boundary=$BOUNDARY\r\n" +
                    "Cache-Control: no-store\r\n" +
                    "Connection: close\r\n\r\n").toByteArray(),
            )
            out.flush()
            var seq = 0L
            while (!socket.isClosed) {
                val (nextSeq, jpeg) = hub.awaitNext(seq, 2_000) ?: continue
                seq = nextSeq
                out.write("--$BOUNDARY\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpeg.size}\r\n\r\n".toByteArray())
                out.write(jpeg)
                out.write(CRLF)
                out.flush()
            }
        } finally {
            hub.viewers.decrementAndGet()
            val left = streams.totalViewers()
            log("Ngừng xem: $who (đang xem: $left)")
            onViewers(left)
        }
    }

    private fun media(out: OutputStream, path: String, query: Map<String, String>) {
        val library = backend.media ?: return respond(out, 404, TEXT, "Không có thư viện".toByteArray())
        val id = query["id"]?.toLongOrNull()
        when (path) {
            "/media" -> {
                val limit = query["limit"]?.toIntOrNull()?.coerceIn(1, 200) ?: 40
                val items = library.list(limit).map {
                    mapOf(
                        "id" to it.id, "name" to it.name, "mime" to it.mime,
                        "size" to it.size, "date" to it.dateMs, "video" to it.video,
                    )
                }
                respond(out, 200, JSON, Json.write(items).toByteArray())
            }
            "/media/thumb" -> {
                val jpeg = id?.let(library::thumbnail) ?: return respond(out, 404, TEXT, ByteArray(0))
                respond(out, 200, "image/jpeg", jpeg, cacheSeconds = 3_600)
            }
            "/media/file" -> {
                val file = id?.let(library::open) ?: return respond(out, 404, TEXT, "Không tìm thấy file".toByteArray())
                file.input.use { input ->
                    val name = URLEncoder.encode(file.name, "UTF-8").replace("+", "%20")
                    val headers = StringBuilder("HTTP/1.1 200 OK\r\nContent-Type: ${file.mime}\r\n")
                    if (file.size > 0) headers.append("Content-Length: ${file.size}\r\n")
                    headers.append("Content-Disposition: attachment; filename*=UTF-8''$name\r\n")
                    headers.append("Connection: close\r\n\r\n")
                    out.write(headers.toString().toByteArray())
                    input.copyTo(out, 64 * 1024)
                    out.flush()
                }
            }
            else -> respond(out, 404, TEXT, "Not found".toByteArray())
        }
    }

    private fun respond(out: OutputStream, code: Int, type: String, body: ByteArray, cacheSeconds: Int = 0) {
        val reason = when (code) {
            200 -> "OK"
            204 -> "No Content"
            400 -> "Bad Request"
            403 -> "Forbidden"
            409 -> "Conflict"
            else -> "Not Found"
        }
        val cache = if (cacheSeconds > 0) "private, max-age=$cacheSeconds" else "no-store"
        out.write(
            ("HTTP/1.1 $code $reason\r\n" +
                "Content-Type: $type\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Cache-Control: $cache\r\n" +
                "Connection: close\r\n\r\n").toByteArray(),
        )
        out.write(body)
        out.flush()
    }

    companion object {
        private const val BOUNDARY = "remotecamframe"
        private const val TEXT = "text/plain; charset=utf-8"
        private const val JSON = "application/json; charset=utf-8"
        private val CRLF = "\r\n".toByteArray()
        private val CAMERA_CLOSED = "Màn hình camera trên điện thoại đang đóng".toByteArray()

        internal fun parseQuery(query: String): Map<String, String> =
            query.split('&').filter { it.isNotEmpty() }.associate { pair ->
                val key = pair.substringBefore('=')
                val value = pair.substringAfter('=', "")
                URLDecoder.decode(key, "UTF-8") to URLDecoder.decode(value, "UTF-8")
            }

        /** Reads one CRLF-terminated line; null at end of stream. Caps the length against junk input. */
        private fun readLine(input: InputStream): String? {
            val buffer = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) return if (buffer.size() == 0) null else buffer.toString(Charsets.UTF_8.name())
                if (b == '\n'.code) break
                if (b != '\r'.code) buffer.write(b)
                if (buffer.size() > 8 * 1024) throw IOException("header too long")
            }
            return buffer.toString(Charsets.UTF_8.name())
        }
    }
}
