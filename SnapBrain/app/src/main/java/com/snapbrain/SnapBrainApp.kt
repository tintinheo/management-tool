package com.snapbrain

import android.app.Application
import android.content.Context
import com.snapbrain.ai.AiKeyStore
import com.snapbrain.ai.AiStudio
import com.snapbrain.ai.CardGenerator
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.snapbrain.data.AppClock
import com.snapbrain.data.SettingsStore
import com.snapbrain.data.StudyRepository
import com.snapbrain.data.content.CardRepository
import com.snapbrain.data.db.AppDatabase
import com.snapbrain.haptics.HapticsKit
import com.snapbrain.work.Reminders

class SnapBrainApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        Reminders.ensureChannels(this)
        Reminders.schedule(this)
    }
}

/** Hand-wired singletons (the app is small enough not to need a DI framework). */
class AppContainer(context: Context) {
    val clock = AppClock()
    val settings = SettingsStore(context.getSharedPreferences("settings", Context.MODE_PRIVATE))
    val database: AppDatabase by lazy { AppDatabase.create(context) }
    val cards = CardRepository(context)
    val study: StudyRepository by lazy { StudyRepository(database.studyDao(), cards, clock, settings) }
    val haptics = HapticsKit(context)
    val aiKey: AiKeyStore by lazy { AiKeyStore(File(context.noBackupFilesDir, "gemini_key")) }
    val ai: AiStudio by lazy {
        AiStudio(cards, aiKey, CardGenerator(), CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
    }
}

val Context.appContainer: AppContainer get() = (applicationContext as SnapBrainApp).container
