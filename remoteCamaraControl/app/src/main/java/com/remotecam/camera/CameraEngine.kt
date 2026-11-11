package com.remotecam.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.TonemapCurve
import android.media.MediaActionSound
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.DynamicRange
import androidx.camera.core.ExtendableBuilder
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.lifecycle.lifecycleScope
import com.google.common.util.concurrent.ListenableFuture
import com.remotecam.lan.FrameEncoder
import com.remotecam.lan.JpegStreamer
import com.remotecam.lan.LiveStream
import com.remotecam.remote.RemoteBus
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Owns CameraX for the camera screen: probes what each lens can do, binds the use cases for the
 * selected mode (photo / portrait / night / HDR / video), takes photos, records video and feeds the
 * browser preview. Everything runs on the main thread except frame encoding.
 */
@OptIn(ExperimentalCamera2Interop::class)
class CameraEngine(private val activity: ComponentActivity) {

    private val context: Context = activity.applicationContext
    private val prefs = context.getSharedPreferences("camera", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(EngineState(wanted = loadConfig()))
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private var provider: ProcessCameraProvider? = null
    private var extensions: ExtensionsManager? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var analysis: ImageAnalysis? = null
    private var streamer: JpegStreamer? = null
    private var previewView: PreviewView? = null
    private var recording: Recording? = null
    private var timerJob: Job? = null
    private var screenGrabJob: Job? = null
    private var rotation: Int = activity.display.rotation
    private val capsCache = mutableMapOf<Lens, CameraCaps>()

    private val frameExecutor = Executors.newSingleThreadExecutor()
    private val encoder = FrameEncoder(LiveStream.hubs)
    private val sound = MediaActionSound().apply {
        load(MediaActionSound.SHUTTER_CLICK)
        load(MediaActionSound.START_VIDEO_RECORDING)
        load(MediaActionSound.STOP_VIDEO_RECORDING)
    }

    // ---- lifecycle ------------------------------------------------------------------------------

    fun start() {
        if (provider != null) return
        activity.lifecycleScope.launch {
            val p = runCatching { ProcessCameraProvider.awaitInstance(context) }
                .onFailure { say("✗ Không mở được camera: ${it.message}") }
                .getOrNull() ?: return@launch
            provider = p
            extensions = runCatching { ExtensionsManager.getInstanceAsync(context, p).await() }
                .onFailure { RemoteBus.log("Extensions không khả dụng: ${it.message}") }
                .getOrNull()
            rebind()
        }
    }

    fun attachPreview(view: PreviewView) {
        previewView = view
        preview?.setSurfaceProvider(view.surfaceProvider)
    }

    fun onRotationChanged(newRotation: Int) {
        if (newRotation == rotation) return
        rotation = newRotation
        imageCapture?.targetRotation = newRotation
        videoCapture?.targetRotation = newRotation
        analysis?.targetRotation = newRotation
    }

    fun release() {
        recording?.stop()
        timerJob?.cancel()
        screenGrabJob?.cancel()
        provider?.unbindAll()
        frameExecutor.shutdown()
        sound.release()
    }

    // ---- remote actions -------------------------------------------------------------------------

    fun shutter(receivedAtMs: Long) {
        if (timerJob?.isActive == true) return cancelTimer()
        if (_state.value.config.mode == CaptureMode.VIDEO) toggleRecording() else takePhoto(receivedAtMs)
    }

    /** Fit 3 "previous" button and the browser timer button. Uses the timer setting, 3 s if it is off. */
    fun timerShot() {
        if (timerJob?.isActive == true) return cancelTimer()
        if (recording != null) return toggleRecording()
        val seconds = _state.value.wanted.timerSeconds.takeIf { it > 0 } ?: 3
        timerJob = activity.lifecycleScope.launch {
            for (s in seconds downTo 1) {
                _state.update { it.copy(countdown = s) }
                delay(1_000)
            }
            _state.update { it.copy(countdown = null) }
            shutter(SystemClock.uptimeMillis())
        }
    }

    fun zoomBy(factor: Float) {
        val s = _state.value
        setZoom(s.zoom * factor)
    }

    fun control(request: ControlRequest) {
        when (request.key) {
            "zoom" -> setZoom(request.value.toFloatOrNull() ?: return)
            "ev" -> setExposure(request.value.toIntOrNull() ?: return)
            "focus" -> focus(request.x, request.y)
            else -> changeSetting(request.key, request.value)
        }
    }

    // ---- settings -------------------------------------------------------------------------------

    private fun changeSetting(key: String, value: String) {
        val current = _state.value.wanted
        val next = current.with(key, value) ?: return say("Cài đặt không hợp lệ: $key=$value")
        if (next == current) return
        if (recording != null && current.needsRebind(next)) return say("Đang quay video: dừng quay rồi mới đổi được")
        saveConfig(next)
        _state.update { it.copy(wanted = next) }
        if (current.needsRebind(next)) {
            activity.lifecycleScope.launch { rebind() }
        } else {
            applyLightSettings(next.normalized(capsFor(next.lens)))
        }
    }

    /** Settings that do not need the camera to be rebound. */
    private fun applyLightSettings(cfg: CameraConfig) {
        imageCapture?.flashMode = cfg.flash.toCameraX()
        if (cfg.mode == CaptureMode.VIDEO) camera?.cameraControl?.enableTorch(cfg.flash == FlashSetting.ON)
        _state.update { it.copy(config = cfg) }
    }

    private fun setZoom(ratio: Float) {
        val s = _state.value
        val target = ratio.coerceIn(s.zoomMin, s.zoomMax)
        camera?.cameraControl?.setZoomRatio(target)
    }

    private fun setExposure(index: Int) {
        val cam = camera ?: return
        val range = cam.cameraInfo.exposureState.exposureCompensationRange
        val target = index.coerceIn(range.lower, range.upper)
        cam.cameraControl.setExposureCompensationIndex(target)
        _state.update { it.copy(ev = target) }
    }

    /** [x], [y] are 0..1 on the upright browser preview. */
    private fun focus(x: Float, y: Float) {
        val cam = camera ?: return
        val point = if (analysis != null) {
            val (bx, by) = Metering.toBuffer(x, y, streamer?.rotationDegrees ?: 0)
            SurfaceOrientedMeteringPointFactory(1f, 1f, analysis!!).createPoint(bx, by)
        } else {
            val view = previewView ?: return
            view.meteringPointFactory.createPoint(x * view.width, y * view.height)
        }
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(5, TimeUnit.SECONDS)
            .build()
        cam.cameraControl.startFocusAndMetering(action)
    }

    // ---- binding --------------------------------------------------------------------------------

    private fun capsFor(lens: Lens): CameraCaps {
        capsCache[lens]?.let { return it }
        val p = provider ?: return CameraCaps()
        val selector = CameraProbe.selector(lens)
        if (!p.hasCamera(selector)) return CameraCaps(notes = listOf("Máy không có camera ${lens.label}"))
        val caps = runCatching { CameraProbe.probe(p.getCameraInfo(selector), lens, extensions) }
            .onFailure { RemoteBus.log("✗ Không đọc được khả năng camera: ${it.message}") }
            .getOrDefault(CameraCaps())
        capsCache[lens] = caps
        RemoteBus.log(
            "Camera ${lens.label}: chế độ ${caps.modes.joinToString { it.label }} · " +
                "ảnh ${caps.photoFormats.joinToString { it.label }} · tối đa ${caps.maxPhotoSize?.megapixels ?: "?"} · " +
                "video ${caps.videoProfiles.joinToString { p -> "${p.label} ${caps.qualitiesFor(p).joinToString("/") { it.label }}" }}",
        )
        return caps
    }

    private fun rebind() {
        val p = provider ?: return
        if (recording != null) return
        val wanted = _state.value.wanted
        val caps = capsFor(wanted.lens)
        val cfg = wanted.normalized(caps)
        _state.update { it.copy(busy = true, caps = caps) }

        var bound = bind(p, cfg, caps, withAnalysis = true)
        if (!bound) bound = bind(p, cfg, caps, withAnalysis = false)
        var used = cfg
        if (!bound) {
            used = CameraConfig(lens = cfg.lens)
            say("Tổ hợp ${cfg.mode.label} này máy không chạy được, quay về chế độ Ảnh")
            bound = bind(p, used, caps, withAnalysis = true) || bind(p, used, caps, withAnalysis = false)
        }
        if (!bound) {
            _state.update { it.copy(busy = false, ready = false) }
            return say("✗ Không mở được camera")
        }

        val cam = camera!!
        cam.cameraInfo.zoomState.removeObservers(activity)
        cam.cameraInfo.zoomState.observe(activity) { z ->
            _state.update { it.copy(zoom = z.zoomRatio, zoomMin = z.minZoomRatio, zoomMax = z.maxZoomRatio) }
        }
        val exposure = cam.cameraInfo.exposureState
        if (used.mode == CaptureMode.VIDEO && used.flash == FlashSetting.ON) cam.cameraControl.enableTorch(true)
        _state.update {
            it.copy(
                ready = true,
                busy = false,
                config = used,
                ev = exposure.exposureCompensationIndex,
                evMin = exposure.exposureCompensationRange.lower,
                evMax = exposure.exposureCompensationRange.upper,
                evStep = exposure.exposureCompensationStep.toFloat(),
                photoSize = imageCapture?.resolutionInfo?.resolution?.dim(),
                videoSize = videoCapture?.resolutionInfo?.resolution?.dim(),
                previewFromScreen = analysis == null,
            )
        }
        updateScreenGrab()
        val info = imageCapture?.resolutionInfo?.resolution?.let { " · ảnh ${it.dim()} (${it.dim().megapixels})" }
            ?: videoCapture?.resolutionInfo?.resolution?.let { " · video ${it.dim()}" }.orEmpty()
        RemoteBus.log("Camera ${used.lens.label}: ${used.mode.label}$info${if (analysis == null) " · preview web lấy từ màn hình" else ""}")
    }

    private fun bind(p: ProcessCameraProvider, cfg: CameraConfig, caps: CameraCaps, withAnalysis: Boolean): Boolean {
        p.unbindAll()
        camera = null
        imageCapture = null
        videoCapture = null
        analysis = null
        streamer = null

        val base = CameraProbe.selector(cfg.lens)
        val extMode = CameraProbe.extensionMode(cfg.mode)
        val ext = extensions
        val selector = if (extMode != null && ext != null) {
            @Suppress("DEPRECATION")
            ext.getExtensionEnabledCameraSelector(base, extMode)
        } else {
            base
        }
        val analysisAllowed = withAnalysis &&
            (extMode == null || ext?.isImageAnalysisSupported(base, extMode) == true)

        val video = cfg.mode == CaptureMode.VIDEO
        val previewBuilder = Preview.Builder().setResolutionSelector(aspect(if (video) AspectRatio.RATIO_16_9 else AspectRatio.RATIO_4_3))
        if (video && cfg.videoFps == 60) previewBuilder.setTargetFrameRate(Range(60, 60))
        if (video && cfg.videoProfile == VideoProfile.LOG) applyLogCurve(previewBuilder)
        val newPreview = previewBuilder.build()
        previewView?.let { newPreview.setSurfaceProvider(it.surfaceProvider) }
        val useCases = mutableListOf<UseCase>(newPreview)

        var newImageCapture: ImageCapture? = null
        var newVideoCapture: VideoCapture<Recorder>? = null
        if (video) {
            newVideoCapture = buildVideoCapture(cfg, caps).also { useCases += it }
        } else {
            newImageCapture = buildImageCapture(cfg).also { useCases += it }
        }

        var newAnalysis: ImageAnalysis? = null
        var newStreamer: JpegStreamer? = null
        if (analysisAllowed) {
            newStreamer = JpegStreamer(encoder)
            newAnalysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
                        )
                        .build(),
                )
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetRotation(rotation)
                .build()
                .also { it.setAnalyzer(frameExecutor, newStreamer) }
            useCases += newAnalysis
        }

        return try {
            camera = p.bindToLifecycle(activity, selector, *useCases.toTypedArray())
            preview = newPreview
            imageCapture = newImageCapture
            videoCapture = newVideoCapture
            analysis = newAnalysis
            streamer = newStreamer
            true
        } catch (e: Exception) {
            RemoteBus.log("Không ghép được ${cfg.mode.label}${if (withAnalysis) " kèm preview web" else ""}: ${e.message}")
            p.unbindAll()
            false
        }
    }

    private fun buildImageCapture(cfg: CameraConfig): ImageCapture {
        val raw = cfg.photoFormat == PhotoFormat.RAW || cfg.photoFormat == PhotoFormat.RAW_JPEG
        val builder = ImageCapture.Builder()
            .setCaptureMode(
                if (cfg.photoRes == PhotoRes.MAX || raw) ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
                else ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY,
            )
            .setFlashMode(cfg.flash.toCameraX())
            .setTargetRotation(rotation)
        when (cfg.photoFormat) {
            PhotoFormat.ULTRA_HDR -> builder.setOutputFormat(ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR)
            PhotoFormat.RAW -> builder.setOutputFormat(ImageCapture.OUTPUT_FORMAT_RAW)
            PhotoFormat.RAW_JPEG -> builder.setOutputFormat(ImageCapture.OUTPUT_FORMAT_RAW_JPEG)
            PhotoFormat.JPEG -> Unit
        }
        val resolution = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
        if (cfg.photoRes == PhotoRes.MAX) {
            // Lets CameraX pick the "high resolution" sizes (e.g. full 50 MP) that cost frame rate.
            resolution.setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE)
        }
        builder.setResolutionSelector(resolution.build())
        return builder.build()
    }

    private fun buildVideoCapture(cfg: CameraConfig, caps: CameraCaps): VideoCapture<Recorder> {
        val quality = if (cfg.videoQuality == VideoQuality.UHD) Quality.UHD else Quality.FHD
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(quality, FallbackStrategy.lowerQualityOrHigherThan(quality)))
            .setAspectRatio(AspectRatio.RATIO_16_9)
            .apply {
                if (cfg.videoQuality == VideoQuality.UHD && caps.uhdNeedsCodecSource) {
                    setVideoCapabilitiesSource(Recorder.VIDEO_CAPABILITIES_SOURCE_CODEC_CAPABILITIES)
                }
            }
            .build()
        val builder = VideoCapture.Builder(recorder).setTargetRotation(rotation)
        when (cfg.videoProfile) {
            VideoProfile.HLG -> builder.setDynamicRange(DynamicRange.HLG_10_BIT)
            VideoProfile.LOG -> applyLogCurve(builder)
            VideoProfile.SDR -> Unit
        }
        if (cfg.videoFps == 60) builder.setTargetFrameRate(Range(60, 60))
        return builder.build()
    }

    /** Flat, log-like tone curve on the whole session; the preview looks flat too, like a real Log mode. */
    private fun <T> applyLogCurve(builder: ExtendableBuilder<T>) {
        val points = LogCurve.points()
        Camera2Interop.Extender(builder)
            .setCaptureRequestOption(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)
            .setCaptureRequestOption(CaptureRequest.TONEMAP_CURVE, TonemapCurve(points, points, points))
    }

    private fun aspect(ratio: Int) = ResolutionSelector.Builder()
        .setAspectRatioStrategy(
            if (ratio == AspectRatio.RATIO_16_9) AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
            else AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY,
        )
        .build()

    /**
     * When ImageAnalysis cannot be bound (some extension or 4K/HDR combinations), copy the on-screen
     * preview for the browser instead, at a lower rate.
     */
    private fun updateScreenGrab() {
        screenGrabJob?.cancel()
        if (analysis != null) return
        screenGrabJob = activity.lifecycleScope.launch {
            while (isActive) {
                val now = SystemClock.uptimeMillis()
                val due = encoder.dueProfiles(now)
                val bitmap = if (due.isNotEmpty()) previewView?.bitmap else null
                if (bitmap != null) {
                    frameExecutor.execute {
                        encoder.encode(bitmap, due, now)
                        bitmap.recycle()
                    }
                }
                delay(100)
            }
        }
    }

    // ---- photo ----------------------------------------------------------------------------------

    private fun takePhoto(receivedAtMs: Long, retry: Boolean = true) {
        val capture = imageCapture ?: return say("Camera chưa sẵn sàng để chụp")
        val cfg = _state.value.config
        val name = "RemoteCam_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        fun options(mime: String) = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/RemoteCam")
            },
        ).build()

        sound.play(MediaActionSound.SHUTTER_CLICK)
        _state.update { it.copy(flashTick = it.flashTick + 1, processing = cfg.mode.extension) }
        if (cfg.mode.extension) RemoteBus.log("Đang xử lý ảnh ${cfg.mode.label}…")

        val callback = object : ImageCapture.OnImageSavedCallback {
            var pending = if (cfg.photoFormat == PhotoFormat.RAW_JPEG) 2 else 1

            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                val kind = if (output.imageFormat == android.graphics.ImageFormat.RAW_SENSOR) "DNG" else "JPEG"
                RemoteBus.log("✓ Đã lưu $name ($kind)")
                if (--pending == 0) {
                    val ms = SystemClock.uptimeMillis() - receivedAtMs
                    _state.update { it.copy(photos = it.photos + 1, lastLatencyMs = ms, processing = false) }
                    RemoteBus.log("Chụp xong sau $ms ms")
                }
            }

            override fun onError(exception: ImageCaptureException) {
                _state.update { it.copy(processing = false) }
                // Happens when a shot lands while the camera is reopening after a lens or mode switch.
                if (retry && exception.imageCaptureError == ImageCapture.ERROR_CAMERA_CLOSED) {
                    RemoteBus.log("Camera đang mở lại, chụp lại sau 0,6s")
                    activity.lifecycleScope.launch {
                        delay(600)
                        takePhoto(receivedAtMs, retry = false)
                    }
                    return
                }
                say("✗ Lỗi chụp: ${exception.message}")
            }
        }
        val executor = activity.mainExecutor
        when (cfg.photoFormat) {
            PhotoFormat.RAW_JPEG -> capture.takePicture(options(DNG), options("image/jpeg"), executor, callback)
            PhotoFormat.RAW -> capture.takePicture(options(DNG), executor, callback)
            else -> capture.takePicture(options("image/jpeg"), executor, callback)
        }
    }

    // ---- video ----------------------------------------------------------------------------------

    @SuppressLint("MissingPermission") // checked just before enabling audio
    private fun toggleRecording() {
        recording?.let {
            it.stop()
            sound.play(MediaActionSound.STOP_VIDEO_RECORDING)
            return
        }
        val capture = videoCapture ?: return say("Camera chưa sẵn sàng để quay")
        val cfg = _state.value.config
        val name = "RemoteCam_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val output = MediaStoreOutputOptions.Builder(context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/RemoteCam")
                },
            )
            .build()
        var pending = capture.output.prepareRecording(context, output)
        val micAllowed = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (cfg.audio && micAllowed) pending = pending.withAudioEnabled()
        if (cfg.audio && !micAllowed) say("Chưa có quyền micro: video sẽ không có tiếng")
        recording = pending.start(activity.mainExecutor, ::onRecordEvent)
        sound.play(MediaActionSound.START_VIDEO_RECORDING)
        RemoteBus.log("● Bắt đầu quay ${cfg.videoQuality.label} ${cfg.videoFps}fps ${cfg.videoProfile.label}")
    }

    private fun onRecordEvent(event: VideoRecordEvent) {
        when (event) {
            is VideoRecordEvent.Start -> _state.update { it.copy(recordingSeconds = 0) }
            is VideoRecordEvent.Status -> {
                val seconds = TimeUnit.NANOSECONDS.toSeconds(event.recordingStats.recordedDurationNanos)
                if (seconds != _state.value.recordingSeconds) _state.update { it.copy(recordingSeconds = seconds) }
            }
            is VideoRecordEvent.Finalize -> {
                recording = null
                val saved = event.outputResults.outputUri != Uri.EMPTY
                _state.update { it.copy(recordingSeconds = null, videos = it.videos + if (saved) 1 else 0) }
                if (event.hasError()) {
                    say("${if (saved) "Video đã lưu nhưng có lỗi" else "✗ Lỗi quay"}: mã ${event.error} ${event.cause?.message.orEmpty()}")
                } else {
                    RemoteBus.log("✓ Đã lưu video (${EngineState.clock(TimeUnit.NANOSECONDS.toSeconds(event.recordingStats.recordedDurationNanos))})")
                }
            }
            else -> Unit
        }
    }

    // ---- helpers --------------------------------------------------------------------------------

    private fun cancelTimer() {
        timerJob?.cancel()
        _state.update { it.copy(countdown = null) }
        RemoteBus.log("Huỷ hẹn giờ")
    }

    private fun say(text: String) {
        RemoteBus.log(text)
        _state.update { it.copy(message = text, messageSeq = it.messageSeq + 1) }
    }

    private fun loadConfig(): CameraConfig =
        CameraConfig.fromMap(prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap())

    private fun saveConfig(config: CameraConfig) {
        prefs.edit().apply { config.toMap().forEach { (k, v) -> putString(k, v) } }.apply()
    }

    private fun FlashSetting.toCameraX() = when (this) {
        FlashSetting.OFF -> ImageCapture.FLASH_MODE_OFF
        FlashSetting.AUTO -> ImageCapture.FLASH_MODE_AUTO
        FlashSetting.ON -> ImageCapture.FLASH_MODE_ON
    }

    private fun Size.dim() = Dim(width, height)

    private companion object {
        const val DNG = "image/x-adobe-dng"
    }
}

/** Awaits a Guava future without pulling in kotlinx-coroutines-guava. */
private suspend fun <T> ListenableFuture<T>.await(): T = suspendCancellableCoroutine { cont ->
    addListener({
        try {
            cont.resume(get())
        } catch (e: Exception) {
            cont.resumeWithException(e.cause ?: e)
        }
    }, Runnable::run)
    cont.invokeOnCancellation { cancel(false) }
}
