package com.remotecam.remote

import android.view.KeyEvent

/** Raw signal that reached our MediaSession, before it is mapped to a camera action. */
enum class SessionSignal(val label: String) {
    PLAY("onPlay"),
    PAUSE("onPause"),
    NEXT("onSkipToNext"),
    PREVIOUS("onSkipToPrevious"),
    STOP("onStop"),
    FAST_FORWARD("onFastForward"),
    REWIND("onRewind"),
    VOLUME_UP("volume +"),
    VOLUME_DOWN("volume -"),
}

/** Camera action triggered by a remote signal. */
enum class RemoteCommand(val label: String, val wire: String) {
    SHUTTER("Chụp", "shutter"),
    TIMER_SHOT("Chụp hẹn giờ 3s", "timer"),
    SWITCH_CAMERA("Đổi camera", "switch"),
    ZOOM_IN("Zoom +", "zoom_in"),
    ZOOM_OUT("Zoom -", "zoom_out"),
    ;

    companion object {
        /** Name used by the browser control page. */
        fun fromWire(name: String?): RemoteCommand? = entries.firstOrNull { it.wire == name }
    }
}

object CommandMapper {

    /**
     * Play and pause both mean "shutter": depending on what state the band thinks the session is in,
     * the same physical button sends either one.
     */
    fun forSignal(signal: SessionSignal): RemoteCommand? = when (signal) {
        SessionSignal.PLAY, SessionSignal.PAUSE -> RemoteCommand.SHUTTER
        SessionSignal.NEXT, SessionSignal.FAST_FORWARD -> RemoteCommand.SWITCH_CAMERA
        SessionSignal.PREVIOUS, SessionSignal.REWIND -> RemoteCommand.TIMER_SHOT
        SessionSignal.VOLUME_UP -> RemoteCommand.ZOOM_IN
        SessionSignal.VOLUME_DOWN -> RemoteCommand.ZOOM_OUT
        SessionSignal.STOP -> null
    }

    /** Media key codes delivered through onMediaButtonEvent. Null = not a key we act on. */
    fun forKeyCode(keyCode: Int): SessionSignal? = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_PLAY -> SessionSignal.PLAY
        KeyEvent.KEYCODE_MEDIA_PAUSE -> SessionSignal.PAUSE
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> SessionSignal.PAUSE
        KeyEvent.KEYCODE_MEDIA_NEXT -> SessionSignal.NEXT
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> SessionSignal.PREVIOUS
        KeyEvent.KEYCODE_MEDIA_STOP -> SessionSignal.STOP
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> SessionSignal.FAST_FORWARD
        KeyEvent.KEYCODE_MEDIA_REWIND -> SessionSignal.REWIND
        else -> null
    }
}

/**
 * Drops a repeat of the same command inside [windowMs]. Some controllers send both a key event and a
 * transport call for one press; without this a single press would take two photos. The Fit 3 plugin
 * sends one key event per press, and deliberate rapid taps arrive about 150 ms apart, so the window is
 * kept short.
 */
class CommandDebouncer(private val windowMs: Long = 100) {
    private val lastAt = mutableMapOf<RemoteCommand, Long>()

    fun accept(command: RemoteCommand, nowMs: Long): Boolean {
        val previous = lastAt[command]
        if (previous != null && nowMs - previous < windowMs) return false
        lastAt[command] = nowMs
        return true
    }
}
