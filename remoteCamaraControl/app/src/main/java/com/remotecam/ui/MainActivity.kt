package com.remotecam.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.pm.PackageManager
import android.media.MediaActionSound
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.remotecam.remote.RemoteBus
import com.remotecam.remote.RemoteCommand
import com.remotecam.remote.RemoteSessionService
import com.remotecam.remote.SpikeSettings
import com.remotecam.remote.TimedCommand
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var camera: LifecycleCameraController
    private val shutterSound = MediaActionSound()
    private var cameraState by mutableStateOf(CameraUiState())
    private var hasCameraPermission by mutableStateOf(false)
    private var cameraBound = false
    private var timerJob: Job? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            hasCameraPermission = granted(Manifest.permission.CAMERA)
            if (hasCameraPermission) bindCamera() else RemoteBus.log("✗ Không có quyền camera")
            if (!granted(Manifest.permission.POST_NOTIFICATIONS)) {
                RemoteBus.log("Không có quyền thông báo: session vẫn chạy nhưng không hiện thông báo")
            }
            RemoteSessionService.start(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        shutterSound.load(MediaActionSound.SHUTTER_CLICK)
        camera = LifecycleCameraController(this).apply {
            imageCaptureMode = ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
        }
        hasCameraPermission = granted(Manifest.permission.CAMERA)
        if (hasCameraPermission) bindCamera()

        setContent {
            val log by RemoteBus.log.collectAsStateWithLifecycle()
            val running by RemoteBus.sessionRunning.collectAsStateWithLifecycle()
            val keepPlaying by SpikeSettings.keepPlaying.collectAsStateWithLifecycle()
            val remoteVolume by SpikeSettings.remoteVolume.collectAsStateWithLifecycle()
            val silentAudio by SpikeSettings.silentAudio.collectAsStateWithLifecycle()
            RemoteCamScreen(
                state = ScreenState(
                    camera = cameraState,
                    hasCameraPermission = hasCameraPermission,
                    sessionRunning = running,
                    keepPlaying = keepPlaying,
                    remoteVolume = remoteVolume,
                    silentAudio = silentAudio,
                    log = log,
                ),
                actions = ScreenActions(
                    onShutter = { takePhoto(SystemClock.uptimeMillis()) },
                    onToggleSession = {
                        if (running) RemoteSessionService.stop(this) else RemoteSessionService.start(this)
                    },
                    onKeepPlaying = { SpikeSettings.keepPlaying.value = it },
                    onRemoteVolume = { SpikeSettings.remoteVolume.value = it },
                    onSilentAudio = { SpikeSettings.silentAudio.value = it },
                    onCopyLog = ::copyLog,
                    onClearLog = RemoteBus::clearLog,
                ),
                preview = {
                    // Must have a real size: CameraController only binds once PreviewView has a viewport,
                    // and a wrap-content PreviewView measures 0x0 before the first frame arrives.
                    AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                        PreviewView(context).apply {
                            scaleType = PreviewView.ScaleType.FIT_CENTER
                            controller = camera
                        }
                    })
                },
            )
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                RemoteBus.commands.collect(::onCommand)
            }
        }

        if (savedInstanceState == null) {
            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS))
        }
        updateBand()
    }

    override fun onDestroy() {
        shutterSound.release()
        super.onDestroy()
    }

    private fun granted(permission: String) =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun bindCamera() {
        if (cameraBound) return
        cameraBound = true
        camera.bindToLifecycle(this)
        camera.zoomState.observe(this) { cameraState = cameraState.copy(zoom = it.zoomRatio) }
        camera.initializationFuture.addListener(
            { RemoteBus.log("Camera khởi tạo xong") },
            mainExecutor,
        )
    }

    private fun onCommand(command: TimedCommand) {
        when (command.command) {
            RemoteCommand.SHUTTER -> takePhoto(command.receivedAtMs)
            RemoteCommand.TIMER_SHOT -> startTimer()
            RemoteCommand.SWITCH_CAMERA -> switchCamera()
            RemoteCommand.ZOOM_IN -> zoomBy(1.25f)
            RemoteCommand.ZOOM_OUT -> zoomBy(1 / 1.25f)
        }
    }

    private fun takePhoto(receivedAtMs: Long, retry: Boolean = true) {
        if (!hasCameraPermission) {
            RemoteBus.log("✗ Không chụp được: chưa có quyền camera")
            return
        }
        if (camera.cameraInfo == null) {
            RemoteBus.log("✗ Không chụp được: camera chưa gắn vào khung preview")
            return
        }
        val name = "RemoteCam_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/RemoteCam")
        }
        val options = ImageCapture.OutputFileOptions
            .Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            .build()
        shutterSound.play(MediaActionSound.SHUTTER_CLICK)
        cameraState = cameraState.copy(flashTick = cameraState.flashTick + 1)
        camera.takePicture(options, mainExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                val ms = SystemClock.uptimeMillis() - receivedAtMs
                cameraState = cameraState.copy(photos = cameraState.photos + 1, lastLatencyMs = ms)
                RemoteBus.log("✓ Đã lưu $name.jpg ($ms ms từ lúc nhận lệnh)")
                updateBand()
            }

            override fun onError(exception: ImageCaptureException) {
                // Happens when a shot lands while the camera is reopening after a lens switch.
                if (retry && exception.imageCaptureError == ImageCapture.ERROR_CAMERA_CLOSED) {
                    RemoteBus.log("Camera đang mở lại, chụp lại sau 0,6s")
                    lifecycleScope.launch {
                        delay(600)
                        takePhoto(receivedAtMs, retry = false)
                    }
                    return
                }
                RemoteBus.log("✗ Lỗi chụp: ${exception.message}")
            }
        })
    }

    private fun startTimer() {
        if (timerJob?.isActive == true) {
            timerJob?.cancel()
            cameraState = cameraState.copy(countdown = null)
            RemoteBus.log("Huỷ hẹn giờ")
            updateBand()
            return
        }
        timerJob = lifecycleScope.launch {
            for (second in 3 downTo 1) {
                cameraState = cameraState.copy(countdown = second)
                RemoteBus.setBandStatus("Hẹn giờ: $second")
                delay(1_000)
            }
            cameraState = cameraState.copy(countdown = null)
            takePhoto(SystemClock.uptimeMillis())
        }
    }

    private fun switchCamera() {
        val toBack = !cameraState.backCamera
        val selector = if (toBack) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
        if (!camera.hasCamera(selector)) {
            RemoteBus.log("✗ Máy không có camera ${if (toBack) "sau" else "trước"}")
            return
        }
        camera.cameraSelector = selector
        cameraState = cameraState.copy(backCamera = toBack)
        updateBand()
    }

    private fun zoomBy(factor: Float) {
        val zoom = camera.zoomState.value ?: return
        val target = (zoom.zoomRatio * factor).coerceIn(zoom.minZoomRatio, zoom.maxZoomRatio)
        camera.setZoomRatio(target)
        RemoteBus.log("Zoom %.1fx".format(target))
    }

    private fun updateBand() {
        val side = if (cameraState.backCamera) "cam sau" else "cam trước"
        RemoteBus.setBandStatus("RemoteCam · ${cameraState.photos} ảnh · $side")
    }

    private fun copyLog() {
        val text = RemoteBus.log.value.reversed().joinToString("\n") { "${it.time}  ${it.text}" }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("RemoteCam log", text))
        Toast.makeText(this, "Đã copy log", Toast.LENGTH_SHORT).show()
    }
}
