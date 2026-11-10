package com.remotecam.lan

import java.util.concurrent.atomic.AtomicInteger

/**
 * Latest preview JPEG, handed from the camera analyzer thread to any number of stream threads.
 * Each viewer waits for a sequence number newer than the one it last sent, so slow viewers skip
 * frames instead of building up a backlog.
 */
class FrameHub {
    private val lock = Object()
    private var frame: ByteArray? = null
    private var seq = 0L

    /** Open MJPEG connections. The analyzer skips encoding when nobody is watching. */
    val viewers = AtomicInteger(0)

    fun publish(jpeg: ByteArray) {
        synchronized(lock) {
            frame = jpeg
            seq++
            lock.notifyAll()
        }
    }

    /** Returns the next frame after [afterSeq], or null if none arrives within [timeoutMs]. */
    fun awaitNext(afterSeq: Long, timeoutMs: Long): Pair<Long, ByteArray>? {
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(lock) {
            while (seq == afterSeq || frame == null) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) return null
                lock.wait(remaining)
            }
            return seq to frame!!
        }
    }
}

/** Process-wide hub: the camera screen publishes, the LAN server streams. */
object LiveStream {
    val frames = FrameHub()
}
