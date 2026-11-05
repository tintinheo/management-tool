package com.audiohub

import android.app.Application
import android.content.Context
import com.audiohub.widget.PlaylistsWidget
import com.audiohub.work.DownloadScheduler
import com.audiohub.work.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

class AudioHubApp : Application() {
    lateinit var container: AppContainer
        private set

    /** Process-wide background work that isn't tied to a screen. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @OptIn(FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        com.audiohub.system.DiagnosticsLog.init(this)
        container = AppContainer(this)
        Notifications.createChannels(this)
        // Off the launch path (it touches WorkManager's database). WorkManager may be unavailable in
        // some test environments; the app works without it.
        appScope.launch { runCatching { DownloadScheduler.schedulePeriodicRefresh(this@AudioHubApp) } }

        // Keep the Playlists widget in sync with playlists and music folders.
        appScope.launch {
            combine(container.playlistRepository.summaries, container.musicRepository.folders) { p, f -> p to f }
                .distinctUntilChanged()
                .drop(1) // the widget renders the current data itself when placed
                .debounce(1_000)
                .catch { /* database unavailable (e.g. during tests); the widget just keeps its last state */ }
                .collect { PlaylistsWidget.refresh(this@AudioHubApp) }
        }
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as AudioHubApp).container
