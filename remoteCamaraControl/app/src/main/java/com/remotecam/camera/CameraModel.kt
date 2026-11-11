package com.remotecam.camera

import java.util.Locale
import kotlin.math.ln

/** Plain-Kotlin description of what the camera should do and what it can do; no Android types. */

interface Wire {
    val wire: String
    val label: String
}

inline fun <reified T> wireOf(value: String?): T? where T : Enum<T>, T : Wire =
    enumValues<T>().firstOrNull { it.wire == value }

enum class CaptureMode(override val wire: String, override val label: String, val extension: Boolean) : Wire {
    PHOTO("photo", "Ảnh", false),
    PORTRAIT("portrait", "Chân dung", true),
    NIGHT("night", "Đêm", true),
    HDR("hdr", "HDR", true),
    VIDEO("video", "Video", false),
}

enum class Lens(override val wire: String, override val label: String) : Wire {
    BACK("back", "sau"),
    FRONT("front", "trước"),
}

enum class PhotoFormat(override val wire: String, override val label: String) : Wire {
    JPEG("jpeg", "JPEG"),
    ULTRA_HDR("ultrahdr", "Ultra HDR"),
    RAW("raw", "RAW (DNG)"),
    RAW_JPEG("raw_jpeg", "RAW + JPEG"),
}

enum class PhotoRes(override val wire: String, override val label: String) : Wire {
    STANDARD("std", "Chuẩn"),
    MAX("max", "Tối đa"),
}

enum class FlashSetting(override val wire: String, override val label: String) : Wire {
    OFF("off", "Tắt"),
    AUTO("auto", "Tự động"),
    ON("on", "Bật"),
}

enum class VideoQuality(override val wire: String, override val label: String) : Wire {
    FHD("fhd", "1080p"),
    UHD("uhd", "4K"),
}

enum class VideoProfile(override val wire: String, override val label: String) : Wire {
    SDR("sdr", "Chuẩn"),
    HLG("hlg", "HLG 10-bit"),
    LOG("log", "Log (phẳng)"),
}

data class Dim(val width: Int, val height: Int) {
    val area: Long get() = width.toLong() * height
    val megapixels: String get() = String.format(Locale.US, "%.1fMP", area / 1_000_000.0)
    override fun toString() = "${width}×$height"
}

/** What the user asked for. Kept as asked even when the current lens cannot do it. */
data class CameraConfig(
    val mode: CaptureMode = CaptureMode.PHOTO,
    val lens: Lens = Lens.BACK,
    val photoFormat: PhotoFormat = PhotoFormat.JPEG,
    val photoRes: PhotoRes = PhotoRes.STANDARD,
    val flash: FlashSetting = FlashSetting.OFF,
    val timerSeconds: Int = 3,
    val videoQuality: VideoQuality = VideoQuality.FHD,
    val videoFps: Int = 30,
    val videoProfile: VideoProfile = VideoProfile.SDR,
    val audio: Boolean = true,
) {
    /** Returns the config with one setting changed, or null if the key or value is not recognised. */
    fun with(key: String, value: String): CameraConfig? = when (key) {
        "mode" -> wireOf<CaptureMode>(value)?.let { copy(mode = it) }
        "lens" -> (if (value == "toggle") lens.other() else wireOf<Lens>(value))?.let { copy(lens = it) }
        "photoFormat" -> wireOf<PhotoFormat>(value)?.let { copy(photoFormat = it) }
        "photoRes" -> wireOf<PhotoRes>(value)?.let { copy(photoRes = it) }
        "flash" -> wireOf<FlashSetting>(value)?.let { copy(flash = it) }
        "timer" -> value.toIntOrNull()?.takeIf { it in TIMER_CHOICES }?.let { copy(timerSeconds = it) }
        "videoQuality" -> wireOf<VideoQuality>(value)?.let { copy(videoQuality = it) }
        "fps" -> value.toIntOrNull()?.takeIf { it == 30 || it == 60 }?.let { copy(videoFps = it) }
        "videoProfile" -> wireOf<VideoProfile>(value)?.let { copy(videoProfile = it) }
        "audio" -> when (value) {
            "on" -> copy(audio = true)
            "off" -> copy(audio = false)
            else -> null
        }
        else -> null
    }

    /** True when going from this config to [other] means the camera has to be rebound. */
    fun needsRebind(other: CameraConfig): Boolean =
        copy(flash = other.flash, timerSeconds = other.timerSeconds, audio = other.audio) != other

    /**
     * What will actually be used on this lens: anything the hardware cannot do falls back to the
     * nearest safe setting, so binding never asks for an impossible combination.
     */
    fun normalized(caps: CameraCaps): CameraConfig {
        var c = this
        if (c.mode !in caps.modes) c = c.copy(mode = CaptureMode.PHOTO)
        // Extensions (portrait/night/HDR) only produce processed JPEGs.
        if (c.mode.extension || c.photoFormat !in caps.photoFormats) c = c.copy(photoFormat = PhotoFormat.JPEG)
        if (c.mode.extension || (c.photoRes == PhotoRes.MAX && !caps.maxResAvailable)) {
            c = c.copy(photoRes = PhotoRes.STANDARD)
        }
        if (c.videoProfile !in caps.videoProfiles) c = c.copy(videoProfile = VideoProfile.SDR)
        if (c.videoQuality !in caps.qualitiesFor(c.videoProfile)) {
            c = c.copy(videoQuality = caps.qualitiesFor(c.videoProfile).lastOrNull() ?: VideoQuality.FHD)
        }
        if (c.videoFps == 60 && !caps.fps60) c = c.copy(videoFps = 30)
        if (c.flash != FlashSetting.OFF && !caps.flash) c = c.copy(flash = FlashSetting.OFF)
        return c
    }

    fun toMap(): Map<String, String> = mapOf(
        "mode" to mode.wire,
        "lens" to lens.wire,
        "photoFormat" to photoFormat.wire,
        "photoRes" to photoRes.wire,
        "flash" to flash.wire,
        "timer" to timerSeconds.toString(),
        "videoQuality" to videoQuality.wire,
        "fps" to videoFps.toString(),
        "videoProfile" to videoProfile.wire,
        "audio" to if (audio) "on" else "off",
    )

    companion object {
        val TIMER_CHOICES = listOf(0, 3, 10)

        fun fromMap(map: Map<String, String>): CameraConfig =
            map.entries.fold(CameraConfig()) { config, (k, v) -> config.with(k, v) ?: config }
    }
}

private fun Lens.other() = if (this == Lens.BACK) Lens.FRONT else Lens.BACK

/** What one lens can do, probed from the device at runtime. */
data class CameraCaps(
    val modes: List<CaptureMode> = listOf(CaptureMode.PHOTO, CaptureMode.VIDEO),
    val photoFormats: List<PhotoFormat> = listOf(PhotoFormat.JPEG),
    val standardPhotoSize: Dim? = null,
    val maxPhotoSize: Dim? = null,
    val videoQualities: Map<VideoProfile, List<VideoQuality>> = mapOf(VideoProfile.SDR to listOf(VideoQuality.FHD)),
    /** The phone's CamcorderProfile lacks 4K but the encoder can do it, so record with codec capabilities. */
    val uhdNeedsCodecSource: Boolean = false,
    val fps60: Boolean = false,
    val flash: Boolean = false,
    val notes: List<String> = emptyList(),
) {
    val videoProfiles: List<VideoProfile> get() = videoQualities.filterValues { it.isNotEmpty() }.keys.toList()

    val maxResAvailable: Boolean
        get() = maxPhotoSize != null && (standardPhotoSize == null || maxPhotoSize.area > standardPhotoSize.area)

    fun qualitiesFor(profile: VideoProfile): List<VideoQuality> = videoQualities[profile].orEmpty()
}

/** Everything the phone screen, the band and the browser need to show. */
data class EngineState(
    val ready: Boolean = false,
    val busy: Boolean = false,
    val wanted: CameraConfig = CameraConfig(),
    val config: CameraConfig = CameraConfig(),
    val caps: CameraCaps = CameraCaps(),
    val zoom: Float = 1f,
    val zoomMin: Float = 1f,
    val zoomMax: Float = 1f,
    val ev: Int = 0,
    val evMin: Int = 0,
    val evMax: Int = 0,
    val evStep: Float = 0f,
    val photos: Int = 0,
    val videos: Int = 0,
    val lastLatencyMs: Long? = null,
    val countdown: Int? = null,
    val recordingSeconds: Long? = null,
    val processing: Boolean = false,
    val photoSize: Dim? = null,
    val videoSize: Dim? = null,
    /** ImageAnalysis could not be bound in this mode, so the browser preview copies the screen instead. */
    val previewFromScreen: Boolean = false,
    val message: String? = null,
    /** Bumped with every message so the browser shows a repeated message again. */
    val messageSeq: Int = 0,
    val flashTick: Int = 0,
) {
    val recording: Boolean get() = recordingSeconds != null

    fun bandText(): String = when {
        recordingSeconds != null -> "● REC ${clock(recordingSeconds)}"
        countdown != null -> "Hẹn giờ: $countdown"
        processing -> "Đang xử lý ảnh…"
        config.mode == CaptureMode.VIDEO -> "RemoteCam · Video · $videos video"
        else -> "RemoteCam · ${config.mode.label} · $photos ảnh"
    }

    companion object {
        fun clock(seconds: Long): String = String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)
    }
}

/** Setting or focus request from the browser. */
data class ControlRequest(val key: String, val value: String = "", val x: Float = 0f, val y: Float = 0f) {
    companion object {
        private val SETTINGS = CameraConfig().toMap().keys

        /** Parses query parameters; null when the request cannot possibly be valid. */
        fun parse(params: Map<String, String>): ControlRequest? {
            val key = params["key"] ?: return null
            val value = params["value"].orEmpty()
            return when (key) {
                "zoom" -> value.toFloatOrNull()?.takeIf { it > 0f }?.let { ControlRequest(key, value) }
                "ev" -> value.toIntOrNull()?.let { ControlRequest(key, value) }
                "focus" -> {
                    val x = params["x"]?.toFloatOrNull()
                    val y = params["y"]?.toFloatOrNull()
                    if (x != null && y != null && x in 0f..1f && y in 0f..1f) ControlRequest(key, x = x, y = y) else null
                }
                in SETTINGS -> ControlRequest(key, value).takeIf { CameraConfig().with(key, value) != null }
                else -> null
            }
        }
    }
}

/** Maps a point on the upright browser preview back onto the sensor-oriented analysis buffer. */
object Metering {
    fun toBuffer(x: Float, y: Float, rotationDegrees: Int): Pair<Float, Float> =
        when (((rotationDegrees % 360) + 360) % 360) {
            90 -> y to 1f - x
            180 -> 1f - x to 1f - y
            270 -> 1f - y to x
            else -> x to y
        }
}

/**
 * Log-style tone curve for the "Log (flat)" video profile: lifts shadows, compresses highlights and
 * keeps 18% grey around 40%, like the manufacturers' log modes. Returned as Camera2 (in, out) pairs.
 */
object LogCurve {
    fun points(count: Int = 64, strength: Float = 10f, black: Float = 0.03f, white: Float = 0.95f): FloatArray {
        val out = FloatArray(count * 2)
        val norm = ln(1f + strength)
        for (i in 0 until count) {
            val x = i / (count - 1f)
            out[i * 2] = x
            out[i * 2 + 1] = black + (white - black) * (ln(1f + strength * x) / norm)
        }
        return out
    }
}
