package com.remotecam.remote

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * Loops one second of digital silence. Android ranks media sessions by recent real playback, so a
 * session that is "playing" without any audio can lose to the last music app the user opened.
 */
class SilentPlayer {
    private var track: AudioTrack? = null

    val isPlaying: Boolean get() = track?.playState == AudioTrack.PLAYSTATE_PLAYING

    fun start(attributes: AudioAttributes) {
        if (track != null) return
        val sampleRate = 16_000
        val frames = sampleRate // one second
        val buffer = ShortArray(frames)
        track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(frames * 2)
            .build()
            .apply {
                write(buffer, 0, buffer.size)
                setLoopPoints(0, frames, -1)
                play()
            }
    }

    fun stop() {
        track?.runCatching {
            stop()
            release()
        }
        track = null
    }
}
