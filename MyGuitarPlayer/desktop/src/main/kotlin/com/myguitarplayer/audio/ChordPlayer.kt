package com.myguitarplayer.audio

import com.myguitarplayer.music.Chord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip

/** Plays a synthesized strum or single note on Windows; a new call stops the previous one. */
class ChordPlayer {
    private var clip: Clip? = null

    suspend fun play(chord: Chord) = play { PluckSynth.strum(chord) }

    suspend fun playNote(midi: Int) = play { PluckSynth.note(midi) }

    private suspend fun play(render: () -> FloatArray) {
        val pcm = withContext(Dispatchers.Default) { toPcm16(render()) }
        stop()
        val c = withContext(Dispatchers.IO) {
            runCatching {
                AudioSystem.getClip().apply {
                    open(FORMAT, pcm, 0, pcm.size)
                    start()
                }
            }.getOrNull()
        }
        clip = c
    }

    fun stop() {
        clip?.let { runCatching { it.stop(); it.close() } }
        clip = null
    }

    companion object {
        internal val FORMAT = AudioFormat(PluckSynth.SAMPLE_RATE.toFloat(), 16, 1, true, false)

        /** Float −1..1 → 16-bit little-endian PCM bytes. */
        internal fun toPcm16(f: FloatArray): ByteArray {
            val out = ByteArray(f.size * 2)
            for (i in f.indices) {
                val s = (f[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt()
                out[2 * i] = s.toByte()
                out[2 * i + 1] = (s shr 8).toByte()
            }
            return out
        }
    }
}
