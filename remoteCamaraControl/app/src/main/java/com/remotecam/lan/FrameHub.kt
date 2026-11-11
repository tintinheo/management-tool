package com.remotecam.lan

import java.util.concurrent.atomic.AtomicInteger

/**
 * Latest preview JPEG, handed from the camera thread to any number of stream threads.
 * Each viewer waits for a sequence number newer than the one it last sent, so slow viewers skip
 * frames instead of building up a backlog.
 */
class FrameHub {
    private val lock = Object()
    private var frame: ByteArray? = null
    private var seq = 0L

    /** Open MJPEG connections. Nothing is encoded for a hub nobody is watching. */
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

/**
 * Preview qualities the browser can ask for. A slow PC (2 GB RAM, weak CPU) picks LOW: small frames
 * are cheap to download and, more importantly, cheap to decode.
 */
enum class StreamProfile(val wire: String, val maxEdge: Int, val quality: Int, val intervalMs: Long) {
    LOW("low", 640, 50, 125), // ~8 fps
    MEDIUM("med", 960, 60, 66), // ~15 fps
    HIGH("high", 1280, 72, 40), // ~25 fps
    ;

    companion object {
        fun from(wire: String?): StreamProfile = entries.firstOrNull { it.wire == wire } ?: MEDIUM
    }
}

/** One hub per profile; the camera side only encodes the profiles someone is watching. */
class StreamHubs {
    private val hubs = StreamProfile.entries.associateWith { FrameHub() }

    fun hub(profile: StreamProfile): FrameHub = hubs.getValue(profile)

    fun active(): List<StreamProfile> = hubs.filterValues { it.viewers.get() > 0 }.keys.toList()

    fun totalViewers(): Int = hubs.values.sumOf { it.viewers.get() }
}

/** Process-wide hubs: the camera publishes, the LAN server streams. */
object LiveStream {
    val hubs = StreamHubs()
}
