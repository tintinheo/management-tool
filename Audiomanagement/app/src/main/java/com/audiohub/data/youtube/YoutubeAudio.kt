package com.audiohub.data.youtube

/** Recognises YouTube links in all their shapes and extracts the 11-character video id. */
object YoutubeLinks {
    private val id = "([A-Za-z0-9_-]{11})"
    private val patterns = listOf(
        Regex("""(?:https?://)?(?:www\.|m\.|music\.)?youtube\.com/watch\?(?:.*&)?v=$id"""),
        Regex("""(?:https?://)?(?:www\.|m\.|music\.)?youtube\.com/(?:shorts|embed|live|v)/$id"""),
        Regex("""(?:https?://)?youtu\.be/$id"""),
        Regex("""(?:https?://)?(?:www\.)?youtube-nocookie\.com/embed/$id"""),
    )

    fun videoId(text: String): String? {
        val t = text.trim()
        for (p in patterns) p.find(t)?.let { return it.groupValues[1] }
        return null
    }

    /** Canonical watch URL (what the extractor expects), or null if [text] isn't a YouTube video link. */
    fun canonical(text: String): String? = videoId(text)?.let { "https://www.youtube.com/watch?v=$it" }
}

enum class AudioContainer(val extension: String, val mimeType: String) {
    /** Opus in WebM — remuxed to an Ogg ".opus" file (no re-encoding). */
    WEBM_OPUS("opus", "audio/ogg"),
    /** AAC in MP4 — kept as ".m4a". */
    M4A_AAC("m4a", "audio/mp4"),
}

/** One downloadable audio-only stream, as offered by YouTube. */
data class AudioCandidate(
    val url: String,
    val bitrateKbps: Int,
    val container: AudioContainer,
    /** False for dubbed / descriptive tracks on multi-language videos. */
    val isOriginalTrack: Boolean = true,
) {
    val label: String
        get() = "${if (container == AudioContainer.WEBM_OPUS) "Opus" else "AAC"} · $bitrateKbps kbps"
}

/**
 * Picks the best-sounding stream. Opus is more efficient than AAC (Opus 160 ≈ AAC 256), so its
 * bitrate is weighted up; the original-language track always wins over dubbed ones.
 */
object AudioPicker {
    private fun score(c: AudioCandidate) = c.bitrateKbps * if (c.container == AudioContainer.WEBM_OPUS) 1.6 else 1.0

    fun best(candidates: List<AudioCandidate>): AudioCandidate? =
        candidates.sortedWith(
            compareByDescending<AudioCandidate> { it.isOriginalTrack }.thenByDescending { score(it) },
        ).firstOrNull()
}

/** Validation for the optional start/end trim of a video's audio. */
object YoutubeTrim {
    /** Problem with the range, or null when fine. [endMs] ≤ 0 means "to the end". */
    fun validate(startMs: Long, endMs: Long, durationMs: Long): String? = when {
        startMs < 0 -> "Start can't be negative"
        durationMs > 0 && startMs >= durationMs -> "Start is after the video ends"
        endMs > 0 && durationMs > 0 && endMs > durationMs + 500 -> "End is after the video ends"
        endMs > 0 && endMs - startMs < 1_000 -> "Clip must be at least 1 second"
        else -> null
    }

    fun isFull(startMs: Long, endMs: Long, durationMs: Long) =
        startMs <= 0 && (endMs <= 0 || (durationMs > 0 && endMs >= durationMs))

    fun fileBaseName(title: String): String =
        title.replace(Regex("""[\\/:*?"<>|\u0000-\u001F]+"""), " ").replace(Regex("\\s+"), " ").trim().trim('.').take(100)
            .ifEmpty { "YouTube audio" }
}
