package com.remotecam.lan

import com.remotecam.camera.CameraConfig
import com.remotecam.camera.EngineState
import com.remotecam.camera.FlashSetting
import com.remotecam.camera.PhotoRes
import com.remotecam.camera.VideoProfile
import com.remotecam.camera.Wire
import com.remotecam.remote.CameraStatus
import com.remotecam.util.Json

/** JSON pushed to the browser over /events. Field names are what remote.html reads. */
object StatusJson {
    fun encode(status: CameraStatus, viewers: Int): String {
        val s = status.state
        return Json.write(
            mapOf(
                "active" to status.active,
                "ready" to s.ready,
                "busy" to s.busy,
                "viewers" to viewers,
                "photos" to s.photos,
                "videos" to s.videos,
                "latencyMs" to s.lastLatencyMs,
                "countdown" to s.countdown,
                "recording" to s.recordingSeconds,
                "processing" to s.processing,
                "message" to s.message,
                "messageSeq" to s.messageSeq,
                "config" to s.config.toMap(),
                "wanted" to s.wanted.toMap(),
                "zoom" to mapOf("value" to s.zoom, "min" to s.zoomMin, "max" to s.zoomMax),
                "ev" to mapOf("value" to s.ev, "min" to s.evMin, "max" to s.evMax, "step" to s.evStep),
                "info" to mapOf(
                    "photoSize" to s.photoSize?.let { "$it (${it.megapixels})" },
                    "videoSize" to s.videoSize?.toString(),
                    "maxPhotoSize" to s.caps.maxPhotoSize?.let { "$it (${it.megapixels})" },
                    "previewFromScreen" to s.previewFromScreen,
                ),
                "caps" to caps(s),
            ),
        )
    }

    private fun caps(s: EngineState): Map<String, Any?> {
        val c = s.caps
        return mapOf(
            "modes" to c.modes.options(),
            "photoFormats" to c.photoFormats.options(),
            "photoRes" to (if (c.maxResAvailable) PhotoRes.entries else listOf(PhotoRes.STANDARD)).options(),
            "flash" to (if (c.flash) FlashSetting.entries else listOf(FlashSetting.OFF)).options(),
            "timer" to CameraConfig.TIMER_CHOICES,
            "videoProfiles" to c.videoProfiles.options(),
            "videoQualities" to c.qualitiesFor(s.wanted.videoProfile)
                .ifEmpty { c.qualitiesFor(VideoProfile.SDR) }.options(),
            "fps" to if (c.fps60) listOf(30, 60) else listOf(30),
            "notes" to c.notes,
        )
    }

    private fun List<Wire>.options() = map { mapOf("id" to it.wire, "label" to it.label) }
}
