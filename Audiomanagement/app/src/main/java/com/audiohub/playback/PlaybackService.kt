package com.audiohub.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.audiohub.MainActivity
import com.audiohub.appContainer
import com.audiohub.data.settings.AppSettings
import com.audiohub.widget.NowPlayingWidget
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Owns the single ExoPlayer used for music and podcasts. As a MediaLibraryService it also serves
 * the browse tree to Android Auto / Bluetooth browsers. Media3 handles the notification, lock
 * screen, headset buttons and audio focus.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class]) // pauseAtEndOfMediaItems, skipSilence, audio session id
class PlaybackService : MediaLibraryService() {

    private var session: MediaLibrarySession? = null
    private lateinit var player: ExoPlayer
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var episodeTracker: EpisodeTracker? = null
    private val effects = AudioEffects()
    private var settings: AppSettings? = null
    private lateinit var tree: LibraryTree

    // Sleep timer
    private var sleepJob: Job? = null
    private var sleepEndsAt = 0L
    private var sleepEndOfItem = false

    override fun onCreate() {
        super.onCreate()
        val container = appContainer
        tree = LibraryTree(container.musicRepository, container.playlistRepository, container.podcastRepository, container.settings)
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true) // pause when headphones are unplugged
            .setWakeMode(C.WAKE_MODE_NETWORK) // keeps CPU + Wi-Fi awake for streamed podcasts too
            .build()

        player.addListener(PlayCounter())
        val podcasts = container.podcastRepository
        episodeTracker = EpisodeTracker(
            player = player,
            mainScope = mainScope,
            ioScope = serviceScope,
            persist = podcasts::savePosition,
            markPlayed = {
                podcasts.setPlayed(it, true)
                container.episodeDownloader.onPlayed(it)
            },
        ).also(player::addListener)

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = applyPerItemSettings()
            override fun onAudioSessionIdChanged(audioSessionId: Int) = applyEqualizer()
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // "End of track" sleep timer fired.
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && sleepEndOfItem) {
                    setSleepTimer(0)
                }
            }
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(Player.EVENT_MEDIA_METADATA_CHANGED, Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                    updateWidget()
                }
            }
        })

        mainScope.launch {
            container.settings.settings.distinctUntilChanged().collect {
                settings = it
                applyPerItemSettings()
                applyEqualizer()
            }
        }

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(openApp)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    /** Podcasts get the podcast speed and optional silence skipping; music plays untouched. */
    private fun applyPerItemSettings() {
        val s = settings ?: return
        val podcast = isPodcastMediaId(player.currentMediaItem?.mediaId)
        val speed = if (podcast) s.podcastSpeed else 1f
        if (player.playbackParameters.speed != speed) player.setPlaybackSpeed(speed)
        val skip = podcast && s.skipSilence
        if (player.skipSilenceEnabled != skip) player.skipSilenceEnabled = skip
    }

    private fun applyEqualizer() {
        val s = settings ?: return
        effects.attach(player.audioSessionId, s.eqEnabled, s.eqPreset, s.eqBands)
    }

    // ------------------------------------------------------------------ sleep timer

    /** [durationMs] > 0: pause after that long (fading out); [SLEEP_END_OF_ITEM]: at end of current item; 0: cancel. */
    private fun setSleepTimer(durationMs: Long) {
        sleepJob?.cancel()
        sleepJob = null
        player.volume = 1f
        player.pauseAtEndOfMediaItems = false
        sleepEndOfItem = false
        sleepEndsAt = 0
        when {
            durationMs == SLEEP_END_OF_ITEM -> {
                sleepEndOfItem = true
                player.pauseAtEndOfMediaItems = true
            }
            durationMs > 0 -> {
                sleepEndsAt = System.currentTimeMillis() + durationMs
                sleepJob = mainScope.launch {
                    val fade = minOf(FADE_MS, durationMs)
                    delay(durationMs - fade)
                    val steps = 20
                    repeat(steps) { i ->
                        player.volume = 1f - (i + 1).toFloat() / steps
                        delay(fade / steps)
                    }
                    player.pause()
                    player.volume = 1f
                    sleepEndsAt = 0
                    publishSleepState()
                }
            }
        }
        publishSleepState()
    }

    private fun publishSleepState() {
        session?.setSessionExtras(bundleOf(EXTRA_SLEEP_ENDS_AT to sleepEndsAt, EXTRA_SLEEP_END_OF_ITEM to sleepEndOfItem))
    }

    // ------------------------------------------------------------------ widget

    private var lastWidget: Triple<String, String, Boolean>? = null

    private fun updateWidget() {
        val md = player.mediaMetadata
        val state = Triple(md.title?.toString().orEmpty(), (md.artist ?: md.albumArtist)?.toString().orEmpty(), player.isPlaying)
        if (state == lastWidget) return
        lastWidget = state
        serviceScope.launch { runCatching { NowPlayingWidget.update(this@PlaybackService, state.first, state.second, state.third) } }
    }

    // ------------------------------------------------------------------ play counts

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
            if (counted == transition || !player.playWhenReady) return
            val trackId = trackIdOf(player.currentMediaItem?.mediaId) ?: return
            counted = transition
            serviceScope.launch { runCatching { appContainer.statsRepository.recordPlay(trackId) } }
        }
    }

    // ------------------------------------------------------------------ session callback

    private fun <T> future(block: suspend () -> T): ListenableFuture<T> {
        val f = SettableFuture.create<T>()
        mainScope.launch {
            try {
                f.set(block())
            } catch (e: Throwable) {
                f.setException(e)
            }
        }
        return f
    }

    /** App items carry their URI; browser items carry only an id that we resolve from the database. */
    private suspend fun resolve(item: MediaItem): MediaItem {
        val direct = item.resolveForPlayback()
        if (direct.localConfiguration != null) return direct
        return tree.playable(item.mediaId) ?: direct
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(SessionCommand(ACTION_SLEEP, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == ACTION_SLEEP) {
                setSleepTimer(args.getLong(ARG_DURATION_MS, 0))
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(LibraryResult.ofItem(tree.root(), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
            val all = tree.children(parentId)
            val from = (page * pageSize).coerceIn(0, all.size)
            val to = (from + pageSize).coerceIn(from, all.size)
            LibraryResult.ofItemList(ImmutableList.copyOf(all.subList(from, to)), params)
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = future {
            tree.item(mediaId)?.let { LibraryResult.ofItem(it, null) } ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = future { mediaItems.mapTo(ArrayList()) { resolve(it) } }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = future {
            // "Hey Google, play <something> on My Audio Hub" arrives as one item carrying a search query.
            val query = mediaItems.singleOrNull()?.requestMetadata?.searchQuery
            if (query != null) {
                val results = tree.search(query)
                return@future MediaSession.MediaItemsWithStartPosition(results, 0, C.TIME_UNSET)
            }
            val resolved = mediaItems.map { resolve(it) }
            var position = startPositionMs
            // A single episode picked in a car/browser resumes where it was left.
            if (resolved.size == 1 && position == C.TIME_UNSET) {
                tree.resumePosition(resolved[0].mediaId)?.let { position = it }
            }
            MediaSession.MediaItemsWithStartPosition(resolved, startIndex, position)
        }
    }

    override fun onDestroy() {
        episodeTracker?.flush()
        sleepJob?.cancel()
        effects.release()
        mainScope.cancel()
        // serviceScope is left to finish the final position write; it holds no player references.
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_SLEEP = "com.audiohub.SLEEP_TIMER"
        const val ARG_DURATION_MS = "durationMs"
        const val SLEEP_END_OF_ITEM = -1L
        const val EXTRA_SLEEP_ENDS_AT = "sleepEndsAt"
        const val EXTRA_SLEEP_END_OF_ITEM = "sleepEndOfItem"
        private const val FADE_MS = 10_000L
    }
}
