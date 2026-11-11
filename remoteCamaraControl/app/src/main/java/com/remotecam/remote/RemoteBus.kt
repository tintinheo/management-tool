package com.remotecam.remote

import android.os.SystemClock
import com.remotecam.camera.ControlRequest
import com.remotecam.camera.EngineState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogEntry(val time: String, val text: String)

/** A command plus the uptime at which the signal arrived, so the camera side can report latency. */
data class TimedCommand(val command: RemoteCommand, val receivedAtMs: Long)

/** Spike-only switches, kept in memory so they can be flipped while testing with the band. */
object SpikeSettings {
    /** After a pause, report PLAYING again so the band always sends "pause" (the button that works). */
    val keepPlaying = MutableStateFlow(true)

    /** Route the band's volume buttons to this session (remote playback) instead of the phone volume. */
    val remoteVolume = MutableStateFlow(false)

    /** Play inaudible audio so Android ranks this session as the active media app. */
    val silentAudio = MutableStateFlow(true)
}

/** Process-wide link between the MediaSession service and the camera screen. */
object RemoteBus {
    private const val MAX_LOG = 300
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val _log = MutableStateFlow<List<LogEntry>>(emptyList())
    val log: StateFlow<List<LogEntry>> = _log.asStateFlow()

    private val _commands = MutableSharedFlow<TimedCommand>(extraBufferCapacity = 8)
    val commands: SharedFlow<TimedCommand> = _commands.asSharedFlow()

    /** Text shown on the band as the "song title". */
    private val _bandStatus = MutableStateFlow("RemoteCam · Sẵn sàng")
    val bandStatus: StateFlow<String> = _bandStatus.asStateFlow()

    private val _sessionRunning = MutableStateFlow(false)
    val sessionRunning: StateFlow<Boolean> = _sessionRunning.asStateFlow()

    private val debouncer = CommandDebouncer()

    fun log(text: String) {
        val entry = LogEntry(timeFormat.format(Date()), text)
        _log.update { (listOf(entry) + it).take(MAX_LOG) }
    }

    fun clearLog() = _log.update { emptyList() }

    fun setBandStatus(text: String) {
        _bandStatus.value = text
    }

    fun setSessionRunning(running: Boolean) {
        _sessionRunning.value = running
    }

    /** Called from the MediaSession callbacks. [from] is the controller package, when Android tells us. */
    fun dispatch(signal: SessionSignal, from: String?) {
        val now = SystemClock.uptimeMillis()
        val command = CommandMapper.forSignal(signal)
        val source = from?.let { " ← $it" } ?: ""
        if (command == null) {
            log("${signal.label}$source → (bỏ qua)")
            return
        }
        if (!debouncer.accept(command, now)) {
            log("${signal.label}$source → trùng, bỏ qua")
            return
        }
        emit(command, "${signal.label}$source", now)
    }

    /** Called by the LAN server. Returns false when the camera screen is not open. */
    fun dispatchCommand(command: RemoteCommand, source: String): Boolean =
        emit(command, source, SystemClock.uptimeMillis())

    private fun emit(command: RemoteCommand, source: String, now: Long): Boolean {
        if (_commands.subscriptionCount.value == 0) {
            log("$source → ${command.label} ⚠ màn hình camera đang đóng")
            return false
        }
        log("$source → ${command.label}")
        return _commands.tryEmit(TimedCommand(command, now))
    }

    private val _controls = MutableSharedFlow<ControlRequest>(extraBufferCapacity = 16)

    /** Settings, zoom, exposure and focus requests from the browser. */
    val controls: SharedFlow<ControlRequest> = _controls.asSharedFlow()

    /** Called by the LAN server. Returns false when the camera screen is not open. */
    fun dispatchControl(request: ControlRequest): Boolean {
        if (_controls.subscriptionCount.value == 0) return false
        return _controls.tryEmit(request)
    }

    private val _camera = MutableStateFlow(CameraStatus())

    /** Camera state published by the camera screen, read by the LAN server. */
    val camera: StateFlow<CameraStatus> = _camera.asStateFlow()

    fun setCamera(status: CameraStatus) {
        _camera.value = status
    }

    private val _serverUrls = MutableStateFlow<List<String>>(emptyList())

    /** Addresses where the browser control page is reachable; empty when the server is off. */
    val serverUrls: StateFlow<List<String>> = _serverUrls.asStateFlow()

    fun setServerUrls(urls: List<String>) {
        _serverUrls.value = urls
    }

    private val _viewers = MutableStateFlow(0)

    /** Browsers currently watching the preview stream. */
    val viewers: StateFlow<Int> = _viewers.asStateFlow()

    fun setViewers(count: Int) {
        _viewers.value = count
    }
}

/** [active] is false while the camera screen is not visible (Android stops the camera then). */
data class CameraStatus(val active: Boolean = false, val state: EngineState = EngineState())
