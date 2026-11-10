package com.myguitarplayer.audio

import android.media.MediaPlayer
import android.media.MediaRecorder
import com.myguitarplayer.AndroidPlatform
import com.myguitarplayer.platform.Platform
import java.io.File

/** Records the mic to an AAC .m4a file (practice recordings). */
class AudioRecorder {
    private var recorder: MediaRecorder? = null

    /** False when the mic could not be opened. */
    fun start(file: File): Boolean {
        val r = MediaRecorder((Platform.current as AndroidPlatform).context).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(44_100)
            setAudioEncodingBitRate(128_000)
            setAudioChannels(1)
            setOutputFile(file.absolutePath)
        }
        runCatching { r.prepare(); r.start() }.onFailure { r.release(); return false }
        recorder = r
        return true
    }

    /** Finishes the file; false when nothing usable was written. */
    fun stop(): Boolean {
        val r = recorder ?: return false
        val ok = runCatching { r.stop() }.isSuccess
        r.release()
        recorder = null
        return ok
    }

    companion object {
        const val EXTENSION = "m4a"
    }
}

/** Plays one recording at a time. */
class AudioPlayback {
    private var player: MediaPlayer? = null

    fun play(file: File, onDone: () -> Unit): Boolean {
        stop()
        val p = MediaPlayer()
        runCatching {
            p.setDataSource(file.absolutePath)
            p.prepare()
            p.setOnCompletionListener { onDone() }
            p.start()
        }.onFailure { p.release(); return false }
        player = p
        return true
    }

    fun stop() {
        player?.runCatching { stop(); release() }
        player = null
    }
}
