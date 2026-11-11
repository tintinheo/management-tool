package com.remotecam.camera

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.core.ImageCapture
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.video.Quality
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapabilities

/**
 * Asks the phone what a lens really offers to third-party apps. Manufacturer features of the stock
 * camera app (Xiaomi Log, Leica looks, 50 MP mode, …) are only usable here if the phone exposes them
 * through Camera2 / CameraX, so every option is checked instead of assumed.
 */
@OptIn(ExperimentalCamera2Interop::class)
object CameraProbe {

    fun selector(lens: Lens): CameraSelector =
        if (lens == Lens.BACK) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA

    fun extensionMode(mode: CaptureMode): Int? = when (mode) {
        CaptureMode.PORTRAIT -> ExtensionMode.BOKEH
        CaptureMode.NIGHT -> ExtensionMode.NIGHT
        CaptureMode.HDR -> ExtensionMode.HDR
        else -> null
    }

    fun probe(info: CameraInfo, lens: Lens, extensions: ExtensionsManager?): CameraCaps {
        val notes = mutableListOf<String>()
        val c2 = Camera2CameraInfo.from(info)

        // Modes: extensions are the only way third-party apps get the vendor's portrait/night/HDR.
        val modes = mutableListOf(CaptureMode.PHOTO)
        for (mode in listOf(CaptureMode.PORTRAIT, CaptureMode.NIGHT, CaptureMode.HDR)) {
            val ext = extensionMode(mode) ?: continue
            val available = runCatching { extensions?.isExtensionAvailable(selector(lens), ext) == true }.getOrDefault(false)
            if (available) modes += mode else notes += "${mode.label}: Xiaomi chưa mở cho app ngoài trên camera ${lens.label}"
        }
        modes += CaptureMode.VIDEO

        // Photo formats.
        val outputFormats = runCatching { ImageCapture.getImageCaptureCapabilities(info).supportedOutputFormats }
            .getOrDefault(emptySet())
        val formats = mutableListOf(PhotoFormat.JPEG)
        if (ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR in outputFormats) formats += PhotoFormat.ULTRA_HDR
        if (ImageCapture.OUTPUT_FORMAT_RAW in outputFormats) formats += PhotoFormat.RAW
        if (ImageCapture.OUTPUT_FORMAT_RAW_JPEG in outputFormats) formats += PhotoFormat.RAW_JPEG
        if (PhotoFormat.RAW !in formats) notes += "RAW: camera ${lens.label} không mở RAW cho app ngoài"

        // Resolutions: normal JPEG sizes vs. "high resolution" sizes that cost frame rate.
        val map = c2.getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val standard = map?.getOutputSizes(ImageFormat.JPEG)?.largest()
        val high = map?.getHighResolutionOutputSizes(ImageFormat.JPEG)?.largest()
        val maxRes = c2.getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
            ?.getOutputSizes(ImageFormat.JPEG)?.largest()
        val best = listOfNotNull(standard, high, maxRes).maxByOrNull { it.width.toLong() * it.height }

        // Video.
        val camcorder = runCatching { Recorder.getVideoCapabilities(info) }.getOrNull()
        val codec = runCatching {
            Recorder.getVideoCapabilities(info, Recorder.VIDEO_CAPABILITIES_SOURCE_CODEC_CAPABILITIES)
        }.getOrNull()
        fun qualities(range: DynamicRange): List<VideoQuality> {
            val found = camcorder.qualities(range) + codec.qualities(range)
            return listOfNotNull(
                VideoQuality.FHD.takeIf { Quality.FHD in found },
                VideoQuality.UHD.takeIf { Quality.UHD in found },
            )
        }
        val sdr = qualities(DynamicRange.SDR)
        val hlg = qualities(DynamicRange.HLG_10_BIT)
        val toneModes = c2.getCameraCharacteristic(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)
        val curve = toneModes?.contains(CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE) == true
        if (hlg.isEmpty()) notes += "HLG 10-bit: camera ${lens.label} không hỗ trợ"
        if (!curve) notes += "Log (phẳng): camera ${lens.label} không cho chỉnh đường cong tone"
        notes += "Xiaomi Log gốc chỉ có trong app Camera của Xiaomi; app ngoài dùng HLG hoặc Log (phẳng)"

        val uhdNeedsCodec = Quality.UHD !in camcorder.qualities(DynamicRange.SDR) && Quality.UHD in codec.qualities(DynamicRange.SDR)
        val fps60 = info.supportedFrameRateRanges.any { it.upper >= 60 }

        return CameraCaps(
            modes = modes,
            photoFormats = formats,
            standardPhotoSize = standard?.dim(),
            maxPhotoSize = best?.dim(),
            videoQualities = mapOf(
                VideoProfile.SDR to sdr.ifEmpty { listOf(VideoQuality.FHD) },
                VideoProfile.HLG to hlg,
                VideoProfile.LOG to if (curve) sdr else emptyList(),
            ),
            uhdNeedsCodecSource = uhdNeedsCodec,
            fps60 = fps60,
            flash = info.hasFlashUnit(),
            notes = notes,
        )
    }

    private fun VideoCapabilities?.qualities(range: DynamicRange): List<Quality> =
        this?.let { caps -> runCatching { caps.getSupportedQualities(range) }.getOrNull() }.orEmpty()

    private fun Array<Size>.largest(): Size? = maxByOrNull { it.width.toLong() * it.height }

    fun Size.dim() = Dim(width, height)
}
