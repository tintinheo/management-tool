package com.audiohub

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.audiohub.ui.AppRoot
import com.audiohub.ui.Routes
import com.audiohub.ui.theme.AudioHubTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleLink(intent)
        setContent {
            AudioHubTheme {
                AppRoot()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    override fun onStart() {
        super.onStart()
        appContainer.playerConnection.connect()
    }

    /** podcasts.apple.com / feed links opened or shared to the app → show page. */
    private fun handleLink(intent: Intent?) {
        val text = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> return
        }
        val route = Routes.forSharedText(text)
        if (route != null) {
            appContainer.pendingRoute.value = route
        } else {
            Toast.makeText(this, "No podcast link found", Toast.LENGTH_SHORT).show()
        }
    }
}
