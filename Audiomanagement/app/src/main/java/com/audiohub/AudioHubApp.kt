package com.audiohub

import android.app.Application
import android.content.Context
import com.audiohub.work.DownloadScheduler
import com.audiohub.work.Notifications

class AudioHubApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.createChannels(this)
        // WorkManager may be unavailable in some test environments; the app works without it.
        runCatching { DownloadScheduler.schedulePeriodicRefresh(this) }
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as AudioHubApp).container
