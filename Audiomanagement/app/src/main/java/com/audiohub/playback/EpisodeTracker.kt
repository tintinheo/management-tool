package com.audiohub.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Persists listening progress of saved podcast episodes: every 10 s while playing, on pause,
 * on seek, and when moving to another item (an automatic advance means it was finished).
 * Runs on the player's (main) thread; [persist]/[markPlayed] are launched on [ioScope].
 */
internal class EpisodeTracker(
    private val player: Player,
    private val mainScope: CoroutineScope,
    private val ioScope: CoroutineScope,
    private val persist: suspend (episodeId: Long, positionMs: Long, durationMs: Long) -> Unit,
    private val markPlayed: suspend (episodeId: Long) -> Unit,
    private val intervalMs: Long = 10_000,
) : Player.Listener {

    private data class Snapshot(val episodeId: Long, val positionMs: Long, val durationMs: Long)

    private var last: Snapshot? = null
    private var ticker: Job? = null

    private fun snapshot(): Snapshot? {
        val id = episodeIdOf(player.currentMediaItem?.mediaId) ?: return null
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        return Snapshot(id, player.currentPosition, duration)
    }

    private fun save(s: Snapshot) {
        ioScope.launch { runCatching { persist(s.episodeId, s.positionMs, s.durationMs) } }
    }

    private fun saveNow() {
        snapshot()?.let { last = it; save(it) }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val previous = last
        if (previous != null && previous.episodeId != episodeIdOf(mediaItem?.mediaId)) {
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                ioScope.launch { runCatching { markPlayed(previous.episodeId) } }
            } else {
                save(previous)
            }
        }
        last = snapshot()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        saveNow()
        ticker?.cancel()
        if (isPlaying) {
            ticker = mainScope.launch {
                while (isActive) {
                    delay(intervalMs)
                    saveNow()
                }
            }
        }
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK) saveNow()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED) {
            last?.let { s -> ioScope.launch { runCatching { markPlayed(s.episodeId) } } }
        }
    }

    /** Call before the player is released. */
    fun flush() {
        ticker?.cancel()
        saveNow()
    }
}
