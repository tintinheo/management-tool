package com.myguitarplayer.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Sample-accurate click track: clicks are written into one continuous PCM stream, so timing never
 * drifts the way timer-based clicks do. The grid is in eighth notes ("steps"); clicks fall on beats.
 */
class Metronome {
    private var track: AudioTrack? = null
    private var job: Job? = null
    private var samplesPerStep = 1

    val isRunning: Boolean get() = job?.isActive == true

    fun start(scope: CoroutineScope, bpm: Int, beatsPerBar: Int = 4, muted: Boolean = false) {
        stop()
        val step = stepSamples(bpm)
        samplesPerStep = step
        val accent = click(1760.0)
        val normal = click(1175.0)
        val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setBufferSizeInBytes(minBuf * 2)
            .build()
        track = t
        t.play()
        job = scope.launch(Dispatchers.IO) {
            val buf = ShortArray(step)
            var i = 0L
            while (isActive) {
                ClickSound.fillStep(buf, i, beatsPerBar, muted, accent, normal)
                if (t.write(buf, 0, step) < 0) break
                i++
            }
        }
    }

    /** Eighth-note step being heard right now (from the playback head, so it matches the sound). */
    fun currentStep(): Long {
        val t = track ?: return -1
        if (!isRunning) return -1
        return (t.playbackHeadPosition.toLong() and 0xffffffffL) / samplesPerStep
    }

    /**
     * When eighth-note [step] sounds (or sounded) from the speaker, as System.nanoTime. Uses the device's
     * playback timestamp when available, which includes the output latency.
     */
    fun stepTimeNanos(step: Long): Long? {
        val t = track ?: return null
        val ts = AudioTimestamp()
        val clock = if (t.getTimestamp(ts)) {
            FrameClock(ts.framePosition, ts.nanoTime, SAMPLE_RATE)
        } else {
            FrameClock(t.playbackHeadPosition.toLong() and 0xffffffffL, System.nanoTime(), SAMPLE_RATE)
        }
        return clock.nanosOf(step * samplesPerStep)
    }

    fun stop() {
        job?.cancel()
        job = null
        track?.let {
            runCatching { it.pause(); it.flush(); it.release() }
        }
        track = null
    }

    companion object {
        const val SAMPLE_RATE = ClickSound.SAMPLE_RATE

        fun stepSamples(bpm: Int): Int = ClickSound.stepSamples(bpm)

        fun click(freq: Double): ShortArray = ClickSound.click(freq)
    }
}
