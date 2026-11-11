package com.remotecam.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import com.remotecam.R
import com.remotecam.lan.LanHost
import com.remotecam.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Owns a MediaSession that pretends to be a music player, so the Galaxy Fit 3 media controller
 * (via Galaxy Wearable on the phone) sends its play/pause/next/prev presses to us.
 */
class RemoteSessionService : Service() {

    companion object {
        private const val CHANNEL_ID = "remote_session"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.remotecam.action.STOP"

        private const val ALL_ACTIONS = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
            PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP or
            PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND

        fun start(context: Context) {
            context.startForegroundService(Intent(context, RemoteSessionService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RemoteSessionService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private val silentPlayer = SilentPlayer()
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private val lanHost = LanHost(this)
    private lateinit var session: MediaSession
    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null

    /** Volume stays mid-scale so the band can always send both "up" and "down". */
    private val volumeProvider = object : VolumeProvider(VOLUME_CONTROL_ABSOLUTE, 10, 5) {
        override fun onAdjustVolume(direction: Int) {
            when {
                direction > 0 -> handle(SessionSignal.VOLUME_UP)
                direction < 0 -> handle(SessionSignal.VOLUME_DOWN)
            }
        }

        override fun onSetVolumeTo(volume: Int) {
            when {
                volume > currentVolume -> handle(SessionSignal.VOLUME_UP)
                volume < currentVolume -> handle(SessionSignal.VOLUME_DOWN)
            }
            currentVolume = 5
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
        session = MediaSession(this, "RemoteCam").apply {
            setCallback(SessionCallback(), handler)
            setSessionActivity(openAppIntent())
            setPlaybackToLocal(audioAttributes)
            isActive = true
        }
        setPlaying(true)
        startForeground(
            NOTIFICATION_ID,
            buildNotification(RemoteBus.bandStatus.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )

        RemoteBus.bandStatus.onEach { status ->
            updateMetadata(status)
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(status))
        }.launchIn(scope)
        SpikeSettings.silentAudio.onEach(::applySilentAudio).launchIn(scope)
        SpikeSettings.remoteVolume.onEach { remote ->
            if (remote) session.setPlaybackToRemote(volumeProvider) else session.setPlaybackToLocal(audioAttributes)
            RemoteBus.log(if (remote) "Volume: gửi về session (remote)" else "Volume: âm lượng điện thoại (local)")
        }.launchIn(scope)

        runCatching { lanHost.start(scope) }
            .onFailure { RemoteBus.log("✗ Không mở được server preview: ${it.message}") }

        RemoteBus.setSessionRunning(true)
        RemoteBus.log("Session bật. Mở mục nhạc trên Fit 3 và bấm thử các nút.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        lanHost.stop()
        handler.removeCallbacksAndMessages(null)
        applySilentAudio(false)
        session.isActive = false
        session.release()
        RemoteBus.setSessionRunning(false)
        RemoteBus.log("Session tắt.")
        super.onDestroy()
    }

    private inner class SessionCallback : MediaSession.Callback() {
        override fun onPlay() = handle(SessionSignal.PLAY)
        override fun onPause() = handle(SessionSignal.PAUSE)
        override fun onSkipToNext() = handle(SessionSignal.NEXT)
        override fun onSkipToPrevious() = handle(SessionSignal.PREVIOUS)
        override fun onStop() = handle(SessionSignal.STOP)
        override fun onFastForward() = handle(SessionSignal.FAST_FORWARD)
        override fun onRewind() = handle(SessionSignal.REWIND)

        override fun onSeekTo(pos: Long) {
            RemoteBus.log("onSeekTo($pos)${callerSuffix()}")
        }

        override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
            val key = mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                ?: return super.onMediaButtonEvent(mediaButtonIntent)
            if (key.action != KeyEvent.ACTION_DOWN) return true
            RemoteBus.log("key ${KeyEvent.keyCodeToString(key.keyCode)}${callerSuffix()}")
            if (key.repeatCount > 0) return true
            // Handled here rather than in super: the framework waits for a possible double-tap on
            // PLAY_PAUSE before calling onPause, which would add latency to the shutter.
            val signal = CommandMapper.forKeyCode(key.keyCode) ?: return super.onMediaButtonEvent(mediaButtonIntent)
            handle(signal)
            return true
        }
    }

    private fun caller(): String? = runCatching { session.currentControllerInfo.packageName }.getOrNull()

    private fun callerSuffix(): String = caller()?.let { " ← $it" } ?: ""

    private fun handle(signal: SessionSignal) {
        RemoteBus.dispatch(signal, caller())
        when (signal) {
            SessionSignal.PAUSE -> {
                setPlaying(false)
                if (SpikeSettings.keepPlaying.value) handler.postDelayed({ setPlaying(true) }, 250)
            }
            SessionSignal.PLAY -> setPlaying(true)
            else -> Unit
        }
    }

    private fun setPlaying(isPlaying: Boolean) {
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(ALL_ACTIONS)
                .setState(
                    if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    0,
                    if (isPlaying) 1f else 0f,
                    SystemClock.elapsedRealtime(),
                )
                .build(),
        )
    }

    private fun updateMetadata(status: String) {
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, status)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "RemoteCam")
                .putString(MediaMetadata.METADATA_KEY_ALBUM, "Fit 3 remote")
                .build(),
        )
    }

    private fun applySilentAudio(enabled: Boolean) {
        if (enabled) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(audioAttributes)
                .setOnAudioFocusChangeListener({ change ->
                    RemoteBus.log("Audio focus: ${focusName(change)}")
                }, handler)
                .build()
            focusRequest = request
            val granted = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            silentPlayer.start(audioAttributes)
            RemoteBus.log("Âm thanh im lặng: bật (focus ${if (granted) "OK" else "bị từ chối"})")
        } else {
            silentPlayer.stop()
            focusRequest?.let(audioManager::abandonAudioFocusRequest)
            focusRequest = null
            RemoteBus.log("Âm thanh im lặng: tắt")
        }
    }

    private fun focusName(change: Int) = when (change) {
        AudioManager.AUDIOFOCUS_GAIN -> "có lại"
        AudioManager.AUDIOFOCUS_LOSS -> "mất hẳn (app khác phát nhạc)"
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "mất tạm thời"
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "bị giảm âm"
        else -> change.toString()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun buildNotification(status: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.channel_remote), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, RemoteSessionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_camera)
            .setContentTitle(status)
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_stat_camera),
                    getString(R.string.action_stop),
                    stopIntent,
                ).build(),
            )
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0))
            .build()
    }
}
