package com.myguitarplayer.audio

import java.io.File
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.DataLine
import javax.sound.sampled.LineEvent
import javax.sound.sampled.TargetDataLine

/** Records the default Windows mic to a 16-bit mono .wav file (practice recordings). */
class AudioRecorder {
    private var line: TargetDataLine? = null
    private var writer: Thread? = null
    @Volatile private var failed = false

    /** False when the mic could not be opened. */
    fun start(file: File): Boolean {
        val l = runCatching {
            (AudioSystem.getLine(DataLine.Info(TargetDataLine::class.java, MicStream.FORMAT)) as TargetDataLine).apply {
                open(MicStream.FORMAT)
                start()
            }
        }.getOrNull() ?: return false
        line = l
        failed = false
        // AudioSystem.write streams until the line is closed, then fixes up the WAV header.
        writer = Thread({
            runCatching { AudioSystem.write(AudioInputStream(l), AudioFileFormat.Type.WAVE, file) }.onFailure { failed = true }
        }, "recorder").apply { isDaemon = true; start() }
        return true
    }

    /** Finishes the file; false when nothing usable was written. */
    fun stop(): Boolean {
        val l = line ?: return false
        runCatching { l.stop(); l.close() }
        writer?.join(3000)
        line = null
        writer = null
        return !failed
    }

    companion object {
        const val EXTENSION = "wav"
    }
}

/** Plays one recording at a time. */
class AudioPlayback {
    private var clip: Clip? = null

    fun play(file: File, onDone: () -> Unit): Boolean {
        stop()
        val c = runCatching {
            AudioSystem.getClip().apply {
                AudioSystem.getAudioInputStream(file).use { open(it) }
            }
        }.getOrNull() ?: return false
        c.addLineListener { e ->
            // STOP also fires on our own stop(); only report a natural end.
            if (e.type == LineEvent.Type.STOP && clip === c && c.framePosition >= c.frameLength) onDone()
        }
        clip = c
        c.start()
        return true
    }

    fun stop() {
        val c = clip
        clip = null
        c?.let { runCatching { it.stop(); it.close() } }
    }
}
