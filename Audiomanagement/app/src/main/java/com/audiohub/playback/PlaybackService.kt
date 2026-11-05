package com.audiohub.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.audiohub.MainActivity
import com.audiohub.appContainer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Owns the single ExoPlayer used for both music and podcasts. Media3 takes care of the
 * media-style notification, lock screen controls, headset/Bluetooth buttons and audio focus.
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var episodeTracker: EpisodeTracker? = null
    private var podcastSpeed = 1f

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true) // pause when headphones are unplugged
            .setWakeMode(C.WAKE_MODE_NETWORK) // keeps CPU + Wi-Fi awake for streamed podcasts too
            .build()

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        player.addListener(PlayCounter())

        val podcasts = appContainer.podcastRepository
        episodeTracker = EpisodeTracker(
            player = player,
            mainScope = mainScope,
            ioScope = serviceScope,
            persist = podcasts::savePosition,
            markPlayed = {
                podcasts.setPlayed(it, true)
                appContainer.episodeDownloader.onPlayed(it)
            },
        ).also(player::addListener)

        // Podcasts play at the user's podcast speed; music always at 1×.
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = applySpeed()
        })
        mainScope.launch {
            appContainer.settings.settings.map { it.podcastSpeed }.distinctUntilChanged().collect {
                podcastSpeed = it
                applySpeed()
            }
        }

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setCallback(SessionCallback)
            .build()
    }

    private fun applySpeed() {
        val player = mediaSession?.player ?: return
        val target = if (isPodcastMediaId(player.currentMediaItem?.mediaId)) podcastSpeed else 1f
        if (player.playbackParameters.speed != target) player.setPlaybackSpeed(target)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /**
     * Counts one play per item transition (incl. repeat-one loops) once audio actually starts,
     * so pause/resume doesn't double count and a queue loaded while paused isn't counted early.
     */
    private inner class PlayCounter : Player.Listener {
        private var transition = 0
        private var counted = -1

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            transition++
            maybeCount()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) maybeCount()
        }

        private fun maybeCount() {
            val player = mediaSession?.player ?: return
            if (counted == transition || !player.playWhenReady) return
            val trackId = trackIdOf(player.currentMediaItem?.mediaId) ?: return
            counted = transition
            serviceScope.launch { runCatching { appContainer.statsRepository.recordPlay(trackId) } }
        }
    }

    override fun onDestroy() {
        episodeTracker?.flush()
        mainScope.cancel()
        // serviceScope is left to finish the final position write; it holds no player references.
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    private object SessionCallback : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(mediaItems.mapTo(ArrayList()) { it.resolveForPlayback() })
    }
}
