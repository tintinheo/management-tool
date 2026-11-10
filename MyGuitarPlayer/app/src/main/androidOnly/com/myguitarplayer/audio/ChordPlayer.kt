package com.myguitarplayer.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.myguitarplayer.music.Chord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Plays a synthesized strum or single note; a new call stops the previous one. */
class ChordPlayer {
    private var track: AudioTrack? = null

    suspend fun play(chord: Chord) = play { PluckSynth.strum(chord) }

    suspend fun playNote(midi: Int) = play { PluckSynth.note(midi) }

    private suspend fun play(render: () -> FloatArray) {
        val pcm = withContext(Dispatchers.Default) {
            render().let { f -> ShortArray(f.size) { (f[it] * Short.MAX_VALUE).toInt().toShort() } }
        }
        stop()
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(PluckSynth.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        t.write(pcm, 0, pcm.size)
        t.play()
        track = t
    }

    fun stop() {
        track?.let { runCatching { it.stop(); it.release() } }
        track = null
    }
}
