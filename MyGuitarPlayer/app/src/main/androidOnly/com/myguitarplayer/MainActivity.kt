package com.myguitarplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.myguitarplayer.audio.AudioConfig
import com.myguitarplayer.platform.Platform
import com.myguitarplayer.ui.theme.GuitarTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Platform.install(AndroidPlatform.get(applicationContext))
        CrashLog.install()
        AudioConfig.load()
        setContent { GuitarTheme { App() } }
    }
}
