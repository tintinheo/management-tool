package com.snapbrain.ui

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** On-device text-to-speech (Vietnamese if the phone has the voice). Created lazily, shut down by owner. */
class Speaker(context: Context) {
    private var ready = false
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) ready = true
    }

    fun speak(text: String) {
        if (!ready) return
        tts.language = Locale.forLanguageTag("vi-VN")
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "card")
    }

    fun stop() {
        if (ready) tts.stop()
    }

    fun shutdown() = tts.shutdown()
}
