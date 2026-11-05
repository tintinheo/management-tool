package com.audiohub.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** An upcoming item; [index] is its position in the player's (unshuffled) item list. */
data class QueueItem(
    val index: Int,
    val mediaId: String,
    val title: String,
    val artist: String,
    val artworkUri: Uri?,
)

data class PlayerUiState(
    val hasMedia: Boolean = false,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val mediaId: String? = null,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val artworkUri: Uri? = null,
    val durationMs: Long = 0L,
    val shuffleEnabled: Boolean = false,
    /** One of Player.REPEAT_MODE_*. */
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val hasNext: Boolean = false,
    val queueIndex: Int = 0,
    val queueSize: Int = 0,
    val speed: Float = 1f,
    /** Items that will play after the current one, in play order (respects shuffle). */
    val upNext: List<QueueItem> = emptyList(),
)

/** Transport controls used by the player UI; lets screens be previewed/tested without a player. */
interface PlayerControls {
    val currentPositionMs: Long
    fun togglePlayPause()
    fun skipNext()
    fun skipPrevious()
    fun seekTo(positionMs: Long)
    /** Relative seek, e.g. -15 s / +30 s for podcasts. */
    fun seekBy(deltaMs: Long)
    fun toggleShuffle()
    fun cycleRepeat()
    fun jumpTo(index: Int)
    fun removeAt(index: Int)
    /** Moves the item at list [from] to list [to] (player indices, see [QueueItem.index]). */
    fun move(from: Int, to: Int)
}

/**
 * App-scoped bridge between the UI and [PlaybackService] via a Media3 [MediaController].
 * Commands issued before the controller is connected are queued and replayed on connect.
 * Must be used from the main thread.
 */
class PlayerConnection(private val context: Context) : PlayerControls {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val pending = ArrayDeque<(MediaController) -> Unit>()

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish(player)
    }

    fun connect() {
        if (controllerFuture != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        controllerFuture = future
        future.addListener({
            val connected = runCatching { future.get() }.getOrNull()
            if (connected == null) {
                controllerFuture = null
                return@addListener
            }
            connected.addListener(listener)
            controller = connected
            publish(connected)
            while (pending.isNotEmpty()) pending.removeFirst().invoke(connected)
        }, ContextCompat.getMainExecutor(context))
    }

    private fun withController(block: (MediaController) -> Unit) {
        val c = controller
        if (c != null && c.isConnected) {
            block(c)
        } else {
            if (c != null) { // session died (e.g. service killed); reconnect
                controller = null
                controllerFuture = null
            }
            pending.addLast(block)
            connect()
        }
    }

    private fun publish(player: Player) {
        val md = player.mediaMetadata
        val item = player.currentMediaItem
        _state.value = PlayerUiState(
            hasMedia = item != null,
            isPlaying = player.isPlaying,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            mediaId = item?.mediaId,
            title = md.title?.toString().orEmpty(),
            artist = (md.artist ?: md.albumArtist)?.toString().orEmpty(),
            album = md.albumTitle?.toString().orEmpty(),
            artworkUri = md.artworkUri,
            durationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L,
            shuffleEnabled = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
            hasNext = player.hasNextMediaItem(),
            queueIndex = player.currentMediaItemIndex,
            queueSize = player.mediaItemCount,
            speed = player.playbackParameters.speed,
            upNext = upNextOf(player),
        )
    }

    private fun upNextOf(player: Player): List<QueueItem> {
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return emptyList()
        val result = ArrayList<QueueItem>()
        var i = timeline.getNextWindowIndex(player.currentMediaItemIndex, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
        while (i != C.INDEX_UNSET && result.size < MAX_UP_NEXT) {
            val item = player.getMediaItemAt(i)
            val md = item.mediaMetadata
            result += QueueItem(
                index = i,
                mediaId = item.mediaId,
                title = md.title?.toString().orEmpty(),
                artist = md.artist?.toString().orEmpty(),
                artworkUri = md.artworkUri,
            )
            i = timeline.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
        }
        return result
    }

    /** Current position; poll this from the UI while visible (not part of [state] to avoid churn). */
    override val currentPositionMs: Long get() = controller?.currentPosition ?: 0L

    fun playQueue(items: List<MediaItem>, startIndex: Int = 0, shuffle: Boolean = false, startPositionMs: Long = 0L) {
        if (items.isEmpty()) return
        withController { c ->
            c.shuffleModeEnabled = shuffle
            c.setMediaItems(items, startIndex.coerceIn(0, items.lastIndex), startPositionMs.coerceAtLeast(0L))
            c.prepare()
            c.play()
        }
    }

    override fun togglePlayPause() = withController { c ->
        if (c.isPlaying) {
            c.pause()
        } else {
            when (c.playbackState) {
                Player.STATE_IDLE -> c.prepare()
                Player.STATE_ENDED -> c.seekToDefaultPosition()
                else -> Unit
            }
            c.play()
        }
    }

    override fun skipNext() = withController { it.seekToNext() }

    override fun skipPrevious() = withController { it.seekToPrevious() }

    override fun seekTo(positionMs: Long) = withController { it.seekTo(positionMs) }

    override fun seekBy(deltaMs: Long) = withController { c ->
        val target = (c.currentPosition + deltaMs).coerceAtLeast(0L)
        val duration = c.duration
        c.seekTo(if (duration != C.TIME_UNSET && duration > 0) target.coerceAtMost(duration - 1_000) else target)
    }

    override fun toggleShuffle() = withController { it.shuffleModeEnabled = !it.shuffleModeEnabled }

    /** Off -> All -> One -> Off, like Apple Music. */
    override fun cycleRepeat() = withController {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    override fun jumpTo(index: Int) = withController { c ->
        if (index in 0 until c.mediaItemCount) {
            c.seekToDefaultPosition(index)
            c.play()
        }
    }

    override fun removeAt(index: Int) = withController { c ->
        if (index in 0 until c.mediaItemCount) c.removeMediaItem(index)
    }

    override fun move(from: Int, to: Int) = withController { c ->
        if (from != to && from in 0 until c.mediaItemCount && to in 0 until c.mediaItemCount) {
            c.moveMediaItem(from, to)
        }
    }

    fun playNext(items: List<MediaItem>) = withController { c ->
        if (items.isEmpty()) return@withController
        if (c.mediaItemCount == 0) {
            c.setMediaItems(items); c.prepare(); c.play()
        } else {
            c.addMediaItems(c.currentMediaItemIndex + 1, items)
        }
    }

    fun addToQueue(items: List<MediaItem>) = withController { c ->
        if (items.isEmpty()) return@withController
        if (c.mediaItemCount == 0) {
            c.setMediaItems(items); c.prepare(); c.play()
        } else {
            c.addMediaItems(items)
        }
    }

    fun playNext(item: MediaItem) = playNext(listOf(item))

    fun addToQueue(item: MediaItem) = addToQueue(listOf(item))

    fun setSpeed(speed: Float) = withController { it.setPlaybackSpeed(speed) }

    private companion object {
        const val MAX_UP_NEXT = 500
    }
}
