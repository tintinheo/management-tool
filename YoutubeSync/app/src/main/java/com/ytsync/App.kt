package com.ytsync

import android.app.Application
import com.ytsync.sync.Notifier
import com.ytsync.sync.SyncScheduler

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannels(this)
        SyncScheduler.ensure(this)
    }
}
