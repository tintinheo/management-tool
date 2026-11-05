package com.audiohub.ui.music

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.audiohub.data.db.TrackEntity
import com.audiohub.data.music.MusicRepository
import com.audiohub.playback.PlayerConnection
import com.audiohub.playback.toMediaItem
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MusicViewModel(
    private val repository: MusicRepository,
    private val player: PlayerConnection,
) : ViewModel() {

    val tracks: StateFlow<List<TrackEntity>?> =
        repository.tracks.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val scanning: StateFlow<Boolean> = repository.scanning

    private var watching = false

    /** Call once the audio permission is granted: scans now and re-scans when files change. */
    @OptIn(FlowPreview::class)
    fun onPermissionGranted() {
        if (watching) return
        watching = true
        viewModelScope.launch { repository.rescan() }
        viewModelScope.launch {
            repository.mediaStoreChanges().debounce(2_000).collect { repository.rescan() }
        }
    }

    fun rescan() {
        viewModelScope.launch { repository.rescan() }
    }

    fun play(index: Int) {
        val list = tracks.value ?: return
        player.playQueue(list.map { it.toMediaItem() }, startIndex = index)
    }

    fun shuffleAll() {
        val list = tracks.value ?: return
        if (list.isEmpty()) return
        player.playQueue(list.map { it.toMediaItem() }, startIndex = list.indices.random(), shuffle = true)
    }

    fun playNext(track: TrackEntity) = player.playNext(track.toMediaItem())

    fun addToQueue(track: TrackEntity) = player.addToQueue(track.toMediaItem())
}
