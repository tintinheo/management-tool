package com.myguitarplayer.audio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine

/**
 * Windows microphone (default recording device, via javax.sound) as a flow of analysis windows — same API as
 * the Android MicStream. Recording starts on collect and the mic is released when the collector is cancelled.
 */
object MicStream {
    const val SAMPLE_RATE = 44_100

    /** 16-bit mono little-endian: what every Windows capture device offers through javax.sound. */
    internal val FORMAT = AudioFormat(SAMPLE_RATE.toFloat(), 16, 1, true, false)

    private val _error = MutableStateFlow<String?>(null)
    /** Why the mic could not be used, or null. A failing mic ends the flow quietly; the UI shows this instead. */
    val error: StateFlow<String?> = _error.asStateFlow()

    fun clearError() { _error.value = null }

    private fun open(bufferFrames: Int): TargetDataLine? {
        val line = runCatching {
            val info = DataLine.Info(TargetDataLine::class.java, FORMAT)
            (AudioSystem.getLine(info) as TargetDataLine).apply {
                open(FORMAT, bufferFrames * FORMAT.frameSize)
                start()
            }
        }.getOrNull()
        if (line == null) {
            _error.value = "Không mở được micro. Kiểm tra: có micro cắm vào máy, và Cài đặt › Quyền riêng tư › Micro › " +
                "\"Cho phép ứng dụng máy tính truy cập micro\" đang bật. Sau đó mở lại màn này."
            return null
        }
        _error.value = null
        return line
    }

    /** Fills [out] with [count] samples from [line]; false (and [error] set) when the line stops delivering. */
    private fun readFully(line: TargetDataLine, bytes: ByteArray, out: FloatArray, count: Int): Boolean {
        val need = count * 2
        var read = 0
        while (read < need) {
            val n = line.read(bytes, read, need - read)
            if (n <= 0) {
                if (!line.isOpen) {
                    _error.value = "Micro bị ngắt. Mở lại màn này để tiếp tục."
                    return false
                }
                continue
            }
            read += n
        }
        for (i in 0 until count) {
            val s = (bytes[2 * i].toInt() and 0xff) or (bytes[2 * i + 1].toInt() shl 8)
            out[i] = s.toShort() / 32768f
        }
        return true
    }

    /**
     * Raw mic audio in [hop]-sized chunks with a clock that converts frames to System.nanoTime. javax.sound has
     * no capture timestamp, so the newest frame (what we've read plus what's waiting in the buffer) is taken
     * as "now"; the remaining input latency is covered by the strum-along calibration.
     */
    fun chunks(hop: Int = 512): Flow<MicChunk> = flow {
        val line = open(hop * 4) ?: return@flow
        val bytes = ByteArray(hop * 2)
        var frames = 0L
        try {
            while (currentCoroutineContext().isActive) {
                val chunk = FloatArray(hop)
                if (!readFully(line, bytes, chunk, hop)) break
                val waiting = line.available() / FORMAT.frameSize
                val clock = FrameClock(frames + hop + waiting, System.nanoTime(), SAMPLE_RATE)
                emit(MicChunk(chunk, frames, clock))
                frames += hop
            }
        } finally {
            runCatching { line.stop() }
            line.close()
        }
    }.flowOn(Dispatchers.IO)

    fun windows(size: Int = 4096, hop: Int = 2048): Flow<FloatArray> = flow {
        val line = open(size * 2) ?: return@flow
        val window = FloatArray(size)
        val chunk = FloatArray(hop)
        val bytes = ByteArray(hop * 2)
        try {
            while (currentCoroutineContext().isActive) {
                if (!readFully(line, bytes, chunk, hop)) break
                System.arraycopy(window, hop, window, 0, size - hop)
                System.arraycopy(chunk, 0, window, size - hop, hop)
                emit(window.copyOf())
            }
        } finally {
            runCatching { line.stop() }
            line.close()
        }
    }.flowOn(Dispatchers.IO)
}

/** [samples] start at mic frame [firstFrame]; [clock] maps frames to System.nanoTime. */
class MicChunk(val samples: FloatArray, val firstFrame: Long, val clock: FrameClock)
