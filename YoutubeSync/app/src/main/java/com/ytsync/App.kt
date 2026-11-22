package com.ytsync

import android.app.Application
import com.ytsync.live.LiveSession
import com.ytsync.sync.Notifier
import com.ytsync.sync.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannels(this)
        SyncScheduler.ensure(this)
        // A fresh process can't be mid-recording: anything still marked RECORDING was cut off by a
        // crash/kill. Its fragmented MP4 is valid up to the last fragment → publish and upload it.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { runCatching { LiveSession.recover(this@App) } }
    }
}
