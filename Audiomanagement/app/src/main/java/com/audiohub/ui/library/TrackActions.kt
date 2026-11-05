package com.audiohub.ui.library

import androidx.compose.runtime.staticCompositionLocalOf
import com.audiohub.data.db.TrackEntity

/** Everything a track list can ask for. Screens depend on this, not on ViewModels/NavController. */
interface TrackActions {
    fun play(tracks: List<TrackEntity>, index: Int)
    fun shuffle(tracks: List<TrackEntity>)
    fun playNext(tracks: List<TrackEntity>)
    fun addToQueue(tracks: List<TrackEntity>)
    fun addToPlaylist(tracks: List<TrackEntity>)
    fun toggleFavorite(track: TrackEntity)
    fun navigate(route: String)
    fun back()

    /** No-op implementation for previews and screenshot tests. */
    object None : TrackActions {
        override fun play(tracks: List<TrackEntity>, index: Int) = Unit
        override fun shuffle(tracks: List<TrackEntity>) = Unit
        override fun playNext(tracks: List<TrackEntity>) = Unit
        override fun addToQueue(tracks: List<TrackEntity>) = Unit
        override fun addToPlaylist(tracks: List<TrackEntity>) = Unit
        override fun toggleFavorite(track: TrackEntity) = Unit
        override fun navigate(route: String) = Unit
        override fun back() = Unit
    }
}

/** Per-screen display state shared by every track list. */
data class TrackListState(
    val favoriteIds: Set<Long> = emptySet(),
    val currentMediaId: String? = null,
)

val LocalTrackActions = staticCompositionLocalOf<TrackActions> { TrackActions.None }
