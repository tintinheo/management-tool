package com.remotecam.lan

import com.remotecam.remote.RemoteCommand
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Minimal HTTP server for the local network: the control page, an MJPEG preview stream, a status
 * endpoint and a command endpoint. Every request must carry the access code as `?k=` so other people
 * on the same Wi-Fi cannot watch the camera.
 *
 * Plain JVM code (no Android APIs) so it can be tested with real sockets in unit tests.
 */
class LanServer(
    private val token: String,
    private val frames: FrameHub,
    private val page: () -> ByteArray,
    private val status: () -> String,
    private val onCommand: (RemoteCommand) -> Boolean,
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
                    // Browser went away mid-response; nothing to do.
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
        val out = BufferedOutputStream(socket.getOutputStream())

        if (path == "/favicon.ico") return respond(out, 404, "text/plain", ByteArray(0))
        if (query["k"] != token) {
            return respond(out, 403, TEXT, "Sai mã truy cập. Mở đúng link hiện trong app RemoteCam.".toByteArray())
        }
        when {
            method == "GET" && path == "/" -> respond(out, 200, "text/html; charset=utf-8", page())
            method == "GET" && path == "/status" -> respond(out, 200, "application/json", status().toByteArray())
            method == "POST" && path == "/cmd" -> {
                val command = RemoteCommand.fromWire(query["c"])
                when {
                    command == null -> respond(out, 400, TEXT, "Lệnh không hợp lệ".toByteArray())
                    onCommand(command) -> respond(out, 204, TEXT, ByteArray(0))
                    else -> respond(out, 409, TEXT, "Màn hình camera trên điện thoại đang đóng".toByteArray())
                }
            }
            method == "GET" && path == "/stream.mjpg" -> {
                socket.soTimeout = 0
                stream(socket, out)
            }
            else -> respond(out, 404, TEXT, "Not found".toByteArray())
        }
    }

    private fun stream(socket: Socket, out: OutputStream) {
        val who = socket.inetAddress?.hostAddress ?: "?"
        val count = frames.viewers.incrementAndGet()
        log("Xem preview: $who (đang xem: $count)")
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
                val (nextSeq, jpeg) = frames.awaitNext(seq, 2_000) ?: continue
                seq = nextSeq
                out.write("--$BOUNDARY\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpeg.size}\r\n\r\n".toByteArray())
                out.write(jpeg)
                out.write(CRLF)
                out.flush()
            }
        } finally {
            val left = frames.viewers.decrementAndGet()
            log("Ngừng xem: $who (đang xem: $left)")
            onViewers(left)
        }
    }

    private fun respond(out: OutputStream, code: Int, type: String, body: ByteArray) {
        val reason = when (code) {
            200 -> "OK"
            204 -> "No Content"
            400 -> "Bad Request"
            403 -> "Forbidden"
            409 -> "Conflict"
            else -> "Not Found"
        }
        out.write(
            ("HTTP/1.1 $code $reason\r\n" +
                "Content-Type: $type\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: close\r\n\r\n").toByteArray(),
        )
        out.write(body)
        out.flush()
    }

    companion object {
        private const val BOUNDARY = "remotecamframe"
        private const val TEXT = "text/plain; charset=utf-8"
        private val CRLF = "\r\n".toByteArray()

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
