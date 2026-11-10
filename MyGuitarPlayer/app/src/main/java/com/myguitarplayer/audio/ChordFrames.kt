package com.myguitarplayer.audio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** One mic hop for chord work: loudness of the newest 2048 samples + chroma of the last 8192 (null = quiet). */
data class ChordFrame(val rms: Double, val chroma: DoubleArray?)

object ChordFrames {
    private const val HOP = 2048

    fun fromMic(): Flow<ChordFrame> = flow {
        val analyzer = ChromaAnalyzer(MicStream.SAMPLE_RATE)
        emitAll(
            MicStream.windows(analyzer.size, HOP).map { w ->
                ChordFrame(Yin.rms(w.copyOfRange(w.size - HOP, w.size)), analyzer.chroma(w))
            },
        )
    }.flowOn(Dispatchers.Default)
}
