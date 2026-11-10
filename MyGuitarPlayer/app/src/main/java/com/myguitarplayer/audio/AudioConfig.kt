package com.myguitarplayer.audio

import com.myguitarplayer.platform.Platform

/**
 * Per-phone audio tuning, set once on the "Hiệu chỉnh micro" screen. Defaults are the values the DSP was
 * built and tested with, so unit tests (which never call [load]) see exactly the original thresholds.
 *
 * Read from the audio threads, hence @Volatile; written only from the UI.
 */
object AudioConfig {
    /** [id] = Android MediaRecorder.AudioSource value (VOICE_RECOGNITION 6, UNPROCESSED 9, MIC 1); Windows ignores it. */
    enum class Source(val label: String, val detail: String, val id: Int) {
        STANDARD("Chuẩn", "Micro nhận giọng, không tự chỉnh âm lượng. Hợp với đa số máy.", 6),
        RAW("Thô", "Tín hiệu nguyên bản, không xử lý. Chính xác nhất nếu máy hỗ trợ.", 9),
        MIC("Micro thường", "Có thể bị máy tự giảm tiếng ồn — chỉ dùng nếu hai chế độ kia không nghe được.", 1),
    }

    /** Index 0..2 = Dễ / Vừa / Chặt: minimum similarity for "đúng hợp âm". */
    val MATCH_LEVELS = doubleArrayOf(0.80, 0.85, 0.90)
    /** Index 0..2 = Ít nhạy / Vừa / Nhạy: MAD multiplier of the onset threshold (lower = more sensitive). */
    val ONSET_LEVELS = doubleArrayOf(7.5, 6.0, 4.5)

    const val DEFAULT_CHROMA_SILENCE = 0.006
    const val DEFAULT_ONSET_SILENCE = 0.003

    /** Room noise RMS measured in silence; null until measured. */
    @Volatile var noiseRms: Double? = null
    @Volatile var chordLevel: Int = 1
    @Volatile var onsetLevel: Int = 1
    @Volatile var source: Source = Source.STANDARD

    val calibrated: Boolean get() = noiseRms != null

    /** Quietest window chord check / tuner chroma will judge: a few times the room noise. */
    val chromaSilence: Double
        get() = noiseRms?.let { (it * 3).coerceIn(0.0015, 0.03) } ?: DEFAULT_CHROMA_SILENCE

    /** The onset detector only needs to stay above the room hiss. */
    val onsetSilence: Double
        get() = noiseRms?.let { (it * 1.6).coerceIn(0.001, 0.02) } ?: DEFAULT_ONSET_SILENCE

    val match: Double get() = MATCH_LEVELS[chordLevel]
    val onsetSensitivity: Double get() = ONSET_LEVELS[onsetLevel]

    fun supportsRaw(): Boolean = Platform.current.supportsRawMic

    fun load() {
        val p = prefs()
        noiseRms = p.getFloat(KEY_NOISE, -1f).takeIf { it > 0 }?.toDouble()
        chordLevel = p.getInt(KEY_CHORD, 1).coerceIn(0, 2)
        onsetLevel = p.getInt(KEY_ONSET, 1).coerceIn(0, 2)
        source = Source.entries.firstOrNull { it.name == p.getString(KEY_SOURCE) } ?: Source.STANDARD
        if (source == Source.RAW && !supportsRaw()) source = Source.STANDARD
    }

    fun save() = prefs().edit {
        val n = noiseRms
        if (n == null) remove(KEY_NOISE) else putFloat(KEY_NOISE, n.toFloat())
        putInt(KEY_CHORD, chordLevel)
        putInt(KEY_ONSET, onsetLevel)
        putString(KEY_SOURCE, source.name)
    }

    fun reset() {
        noiseRms = null; chordLevel = 1; onsetLevel = 1; source = Source.STANDARD
        save()
    }

    private fun prefs() = Platform.current.prefs("audio")

    private const val KEY_NOISE = "noise_rms"
    private const val KEY_CHORD = "chord_level"
    private const val KEY_ONSET = "onset_level"
    private const val KEY_SOURCE = "source"
}
