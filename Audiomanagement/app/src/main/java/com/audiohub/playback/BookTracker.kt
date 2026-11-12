package com.audiohub.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Remembers where the listener is in an audiobook: every 10 s while playing, on pause, on seek and
 * on every chapter change (so the next chapter is saved even before it's paused). When the last
 * chapter plays to its end the book is marked finished. Runs on the player's (main) thread.
 */
internal class BookTracker(
    private val player: Player,
    private val mainScope: CoroutineScope,
    private val ioScope: CoroutineScope,
    private val persist: suspend (fileId: Long, positionMs: Long) -> Unit,
    private val playedToEnd: suspend (fileId: Long) -> Unit,
    private val intervalMs: Long = 10_000,
) : Player.Listener {

    private var lastFileId: Long? = null
    private var ticker: Job? = null

    /** Writes run one at a time, in order: a late position save must never undo "finished". */
    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        ioScope.launch { for (write in writes) runCatching { write() } }
    }

    private fun write(block: suspend () -> Unit) {
        writes.trySend(block)
    }

    private fun saveNow() {
        // At the very end the book is marked finished instead.
        if (player.playbackState == Player.STATE_ENDED) return
        val id = bookFileIdOf(player.currentMediaItem?.mediaId) ?: return
        lastFileId = id
        val position = player.currentPosition
        write { persist(id, position) }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val previous = lastFileId
        if (previous != null && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
            write { playedToEnd(previous) }
        }
        lastFileId = null
        saveNow()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        saveNow()
        ticker?.cancel()
        if (isPlaying && isBookMediaId(player.currentMediaItem?.mediaId)) {
            ticker = mainScope.launch {
                while (isActive) {
                    delay(intervalMs)
                    saveNow()
                }
            }
        }
    }

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK) saveNow()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED) {
            lastFileId?.let { id -> write { playedToEnd(id) } }
        }
    }

    fun flush() {
        ticker?.cancel()
        saveNow()
    }
}
