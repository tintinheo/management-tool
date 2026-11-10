package com.myguitarplayer.data

import com.myguitarplayer.audio.AudioRecorder
import com.myguitarplayer.platform.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Rhythm takes, recordings (files + rubric) and the one-time audio latency calibration. */
class PracticeRepo(private val dao: PracticeDao) {
    private val prefs by lazy { Platform.current.prefs("practice") }

    fun rhythm() = dao.rhythm()
    fun recordings() = dao.recordings()

    suspend fun saveRhythm(s: RhythmScore) = withContext(Dispatchers.IO) { dao.addRhythm(s) }

    /** Speaker → mic → detector delay in ms, measured by strumming along to clicks; null until calibrated. */
    var latencyMs: Int?
        get() = prefs.getInt(KEY_LATENCY, -1).takeIf { it >= 0 }
        set(value) {
            prefs.edit { if (value == null) remove(KEY_LATENCY) else putInt(KEY_LATENCY, value) }
        }

    val recordingsDir: File get() = File(Platform.current.filesDir, "recordings").also { it.mkdirs() }

    fun newRecordingFile(): File = File(recordingsDir, "rec_${System.currentTimeMillis()}.${AudioRecorder.EXTENSION}")

    fun fileOf(r: Recording): File = File(recordingsDir, r.file)

    suspend fun addRecording(r: Recording) = withContext(Dispatchers.IO) { dao.addRecording(r) }

    suspend fun updateRecording(r: Recording) = withContext(Dispatchers.IO) { dao.updateRecording(r) }

    suspend fun deleteRecording(r: Recording) = withContext(Dispatchers.IO) {
        fileOf(r).delete()
        dao.deleteRecording(r)
    }

    companion object {
        private const val KEY_LATENCY = "latency_ms"
    }
}
