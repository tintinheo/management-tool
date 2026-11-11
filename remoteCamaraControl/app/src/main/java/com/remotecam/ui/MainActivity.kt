package com.remotecam.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.remotecam.camera.CameraEngine
import com.remotecam.camera.CaptureMode
import com.remotecam.camera.ControlRequest
import com.remotecam.camera.EngineState
import com.remotecam.camera.Lens
import com.remotecam.remote.CameraStatus
import com.remotecam.remote.RemoteBus
import com.remotecam.remote.RemoteCommand
import com.remotecam.remote.RemoteSessionService
import com.remotecam.remote.SpikeSettings
import com.remotecam.remote.TimedCommand
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var engine: CameraEngine
    private var hasCameraPermission by mutableStateOf(false)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            hasCameraPermission = granted(Manifest.permission.CAMERA)
            if (hasCameraPermission) engine.start() else RemoteBus.log("✗ Không có quyền camera")
            if (!granted(Manifest.permission.RECORD_AUDIO)) RemoteBus.log("Không có quyền micro: video sẽ không có tiếng")
            if (!granted(Manifest.permission.POST_NOTIFICATIONS)) {
                RemoteBus.log("Không có quyền thông báo: session vẫn chạy nhưng không hiện thông báo")
            }
            RemoteSessionService.start(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        engine = CameraEngine(this)
        hasCameraPermission = granted(Manifest.permission.CAMERA)
        if (hasCameraPermission) engine.start()

        setContent {
            val camera by engine.state.collectAsStateWithLifecycle()
            val log by RemoteBus.log.collectAsStateWithLifecycle()
            val running by RemoteBus.sessionRunning.collectAsStateWithLifecycle()
            val keepPlaying by SpikeSettings.keepPlaying.collectAsStateWithLifecycle()
            val remoteVolume by SpikeSettings.remoteVolume.collectAsStateWithLifecycle()
            val silentAudio by SpikeSettings.silentAudio.collectAsStateWithLifecycle()
            val urls by RemoteBus.serverUrls.collectAsStateWithLifecycle()
            val viewers by RemoteBus.viewers.collectAsStateWithLifecycle()
            RemoteCamScreen(
                state = ScreenState(
                    camera = camera.toUi(),
                    hasCameraPermission = hasCameraPermission,
                    sessionRunning = running,
                    keepPlaying = keepPlaying,
                    remoteVolume = remoteVolume,
                    silentAudio = silentAudio,
                    serverUrls = urls,
                    viewers = viewers,
                    log = log,
                ),
                actions = ScreenActions(
                    onShutter = { engine.shutter(SystemClock.uptimeMillis()) },
                    onToggleSession = {
                        if (running) RemoteSessionService.stop(this) else RemoteSessionService.start(this)
                    },
                    onKeepPlaying = { SpikeSettings.keepPlaying.value = it },
                    onRemoteVolume = { SpikeSettings.remoteVolume.value = it },
                    onSilentAudio = { SpikeSettings.silentAudio.value = it },
                    onCopyLog = ::copyLog,
                    onClearLog = RemoteBus::clearLog,
                    onMode = { engine.control(ControlRequest("mode", it.wire)) },
                ),
                preview = {
                    // Must have a real size: CameraX only starts once PreviewView has a viewport.
                    AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                        PreviewView(context).apply {
                            scaleType = PreviewView.ScaleType.FIT_CENTER
                            engine.attachPreview(this)
                        }
                    })
                },
            )
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { RemoteBus.commands.collect(::onCommand) }
                launch { RemoteBus.controls.collect(engine::control) }
                launch {
                    engine.state.map { it.bandText() }.distinctUntilChanged().collect(RemoteBus::setBandStatus)
                }
                // "active" only while this screen is visible: Android does not let the camera run in
                // the background.
                try {
                    engine.state.collect { RemoteBus.setCamera(CameraStatus(active = true, state = it)) }
                } finally {
                    RemoteBus.setCamera(CameraStatus(active = false, state = engine.state.value))
                }
            }
        }

        if (savedInstanceState == null) {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS),
            )
        }
    }

    /** Rotation is handled here (manifest configChanges) so a recording survives turning the phone. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        engine.onRotationChanged(display.rotation)
    }

    override fun onDestroy() {
        engine.release()
        super.onDestroy()
    }

    private fun granted(permission: String) =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun onCommand(command: TimedCommand) {
        when (command.command) {
            RemoteCommand.SHUTTER -> engine.shutter(command.receivedAtMs)
            RemoteCommand.TIMER_SHOT -> engine.timerShot()
            RemoteCommand.SWITCH_CAMERA -> engine.control(ControlRequest("lens", "toggle"))
            RemoteCommand.ZOOM_IN -> engine.zoomBy(1.25f)
            RemoteCommand.ZOOM_OUT -> engine.zoomBy(1 / 1.25f)
        }
    }

    private fun EngineState.toUi() = CameraUiState(
        photos = photos,
        videos = videos,
        backCamera = config.lens == Lens.BACK,
        zoom = zoom,
        countdown = countdown,
        flashTick = flashTick,
        lastLatencyMs = lastLatencyMs,
        mode = config.mode,
        modes = caps.modes,
        recordingSeconds = recordingSeconds,
        detail = when (config.mode) {
            CaptureMode.VIDEO -> "${config.videoQuality.label} ${config.videoFps}fps ${config.videoProfile.label}"
            else -> listOfNotNull(config.photoFormat.label, photoSize?.megapixels).joinToString(" · ")
        },
    )

    private fun copyLog() {
        val text = RemoteBus.log.value.reversed().joinToString("\n") { "${it.time}  ${it.text}" }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("RemoteCam log", text))
        Toast.makeText(this, "Đã copy log", Toast.LENGTH_SHORT).show()
    }
}
