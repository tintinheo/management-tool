package com.audiohub.playback

import android.media.AudioManager
import android.media.audiofx.Equalizer

/** Device equalizer capabilities, read once for the settings screen. */
data class EqualizerInfo(
    val presets: List<String>,
    /** Centre frequency of each band in Hz. */
    val bandFrequencies: List<Int>,
    val minLevel: Int,
    val maxLevel: Int,
)

/** Applies the user's equalizer settings to the player's audio session. */
class AudioEffects {
    private var equalizer: Equalizer? = null
    private var sessionId = 0

    fun attach(audioSessionId: Int, enabled: Boolean, preset: Int, bands: List<Int>) {
        if (audioSessionId != sessionId) {
            release()
            sessionId = audioSessionId
        }
        if (audioSessionId == 0) return
        val eq = equalizer ?: runCatching { Equalizer(0, audioSessionId) }.getOrNull()?.also { equalizer = it } ?: return
        runCatching {
            eq.enabled = enabled
            if (!enabled) return
            if (preset >= 0 && preset < eq.numberOfPresets) {
                eq.usePreset(preset.toShort())
            } else {
                bands.forEachIndexed { i, level ->
                    if (i < eq.numberOfBands) eq.setBandLevel(i.toShort(), level.toShort())
                }
            }
        }
    }

    fun release() {
        runCatching { equalizer?.release() }
        equalizer = null
    }

    companion object {
        /** Reads presets/bands using a throwaway session; null if the device has no equalizer. */
        fun info(audioManager: AudioManager): EqualizerInfo? = runCatching {
            val eq = Equalizer(0, audioManager.generateAudioSessionId())
            try {
                val range = eq.bandLevelRange
                EqualizerInfo(
                    presets = (0 until eq.numberOfPresets).map { eq.getPresetName(it.toShort()) },
                    bandFrequencies = (0 until eq.numberOfBands).map { eq.getCenterFreq(it.toShort()) / 1000 },
                    minLevel = range[0].toInt(),
                    maxLevel = range[1].toInt(),
                )
            } finally {
                eq.release()
            }
        }.getOrNull()
    }
}
