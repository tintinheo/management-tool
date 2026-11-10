package com.myguitarplayer.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/**
 * Sample-accurate click track on the Windows default output (javax.sound): clicks are written into one
 * continuous PCM stream, so timing never drifts. Same API as the Android Metronome.
 */
class Metronome {
    private var line: SourceDataLine? = null
    private var job: Job? = null
    private var samplesPerStep = 1

    val isRunning: Boolean get() = job?.isActive == true

    fun start(scope: CoroutineScope, bpm: Int, beatsPerBar: Int = 4, muted: Boolean = false) {
        stop()
        val step = stepSamples(bpm)
        samplesPerStep = step
        val accent = click(1760.0)
        val normal = click(1175.0)
        val l = runCatching {
            AudioSystem.getSourceDataLine(FORMAT).apply {
                // ~60 ms buffer: small enough that the click follows the screen, big enough not to stutter.
                open(FORMAT, SAMPLE_RATE * 60 / 1000 * FORMAT.frameSize)
                start()
            }
        }.getOrNull() ?: return
        line = l
        job = scope.launch(Dispatchers.IO) {
            val buf = ShortArray(step)
            val bytes = ByteArray(step * 2)
            var i = 0L
            while (isActive) {
                ClickSound.fillStep(buf, i, beatsPerBar, muted, accent, normal)
                for (k in 0 until step) {
                    val s = buf[k].toInt()
                    bytes[2 * k] = s.toByte()
                    bytes[2 * k + 1] = (s shr 8).toByte()
                }
                if (!l.isOpen) break
                l.write(bytes, 0, bytes.size)
                i++
            }
        }
    }

    /** Eighth-note step being heard right now (from the playback position, so it matches the sound). */
    fun currentStep(): Long {
        val l = line ?: return -1
        if (!isRunning) return -1
        return l.longFramePosition / samplesPerStep
    }

    /** When eighth-note [step] sounds (or sounded) from the speaker, as System.nanoTime. */
    fun stepTimeNanos(step: Long): Long? {
        val l = line ?: return null
        return FrameClock(l.longFramePosition, System.nanoTime(), SAMPLE_RATE).nanosOf(step * samplesPerStep)
    }

    fun stop() {
        job?.cancel()
        job = null
        line?.let { runCatching { it.stop(); it.flush(); it.close() } }
        line = null
    }

    companion object {
        const val SAMPLE_RATE = ClickSound.SAMPLE_RATE
        private val FORMAT = AudioFormat(SAMPLE_RATE.toFloat(), 16, 1, true, false)

        fun stepSamples(bpm: Int): Int = ClickSound.stepSamples(bpm)

        fun click(freq: Double): ShortArray = ClickSound.click(freq)
    }
}
