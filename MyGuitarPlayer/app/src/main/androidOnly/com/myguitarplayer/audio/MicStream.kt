package com.myguitarplayer.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

/**
 * Microphone as a flow of overlapping analysis windows. Recording starts on collect and the
 * mic is released as soon as the collector is cancelled (i.e. the screen leaves composition).
 * Caller must hold RECORD_AUDIO.
 */
object MicStream {
    const val SAMPLE_RATE = 44_100

    private val _error = MutableStateFlow<String?>(null)
    /**
     * Why the mic could not be used (busy in another app, read error), or null. A failing mic ends the flow
     * quietly instead of throwing, so a practice screen never crashes the app; the UI shows this instead.
     */
    val error: StateFlow<String?> = _error.asStateFlow()

    fun clearError() { _error.value = null }

    /** Opens the mic with the source chosen in [AudioConfig]; null (and [error] set) when it can't be opened. */
    @SuppressLint("MissingPermission")
    private fun open(bufferBytes: Int): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        // Try the chosen source first, then fall back to the standard one before giving up.
        val sources = listOf(AudioConfig.source.id, MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC).distinct()
        for (src in sources) {
            val rec = runCatching {
                AudioRecord(src, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT, maxOf(minBuf, bufferBytes))
            }.getOrNull() ?: continue
            if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); continue }
            val started = runCatching { rec.startRecording() }.isSuccess && rec.recordingState == AudioRecord.RECORDSTATE_RECORDING
            if (!started) { rec.release(); continue }
            _error.value = null
            return rec
        }
        _error.value = "Không mở được micro. Có thể app khác (gọi điện, ghi âm, trợ lý giọng nói) đang dùng micro — tắt app đó rồi mở lại màn này."
        return null
    }

    /** Fills [buf] from [rec]; false (and [error] set) on a read error. */
    private fun readFully(rec: AudioRecord, buf: FloatArray, count: Int): Boolean {
        var read = 0
        while (read < count) {
            val n = rec.read(buf, read, count - read, AudioRecord.READ_BLOCKING)
            if (n < 0) {
                _error.value = "Micro bị ngắt (mã lỗi $n). Mở lại màn này để tiếp tục."
                return false
            }
            read += n
        }
        return true
    }

    /**
     * Raw mic audio in [hop]-sized chunks, each with a clock that converts its frames to System.nanoTime
     * (from AudioRecord's capture timestamp when the device provides one). Used for rhythm timing.
     */
    fun chunks(hop: Int = 512): Flow<MicChunk> = flow {
        val rec = open(hop * 4 * 4) ?: return@flow
        val ts = AudioTimestamp()
        var frames = 0L
        try {
            while (currentCoroutineContext().isActive) {
                val chunk = FloatArray(hop)
                if (!readFully(rec, chunk, hop)) break
                val clock = if (rec.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                    FrameClock(ts.framePosition, ts.nanoTime, SAMPLE_RATE)
                } else {
                    // Fallback: the chunk's last frame arrived just now (input latency left to calibration).
                    FrameClock(frames + hop, System.nanoTime(), SAMPLE_RATE)
                }
                emit(MicChunk(chunk, frames, clock))
                frames += hop
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
    }.flowOn(Dispatchers.IO)

    // Default source VOICE_RECOGNITION: no AGC / noise suppression on most phones, so the guitar's spectrum stays intact.
    fun windows(size: Int = 4096, hop: Int = 2048): Flow<FloatArray> = flow {
        val rec = open(size * 4 * 2) ?: return@flow
        val window = FloatArray(size)
        val chunk = FloatArray(hop)
        try {
            while (currentCoroutineContext().isActive) {
                if (!readFully(rec, chunk, hop)) break
                System.arraycopy(window, hop, window, 0, size - hop)
                System.arraycopy(chunk, 0, window, size - hop, hop)
                emit(window.copyOf())
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
    }.flowOn(Dispatchers.IO)
}

/** [samples] start at mic frame [firstFrame]; [clock] maps frames to System.nanoTime. */
class MicChunk(val samples: FloatArray, val firstFrame: Long, val clock: FrameClock)
