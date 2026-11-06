package com.audiohub

import android.app.SearchManager
import android.content.Intent
import android.provider.MediaStore
import androidx.lifecycle.lifecycleScope
import com.audiohub.data.music.LibrarySearch
import com.audiohub.playback.toMediaItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.audiohub.ui.AppRoot
import com.audiohub.ui.Routes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.audiohub.ui.theme.AppFonts
import com.audiohub.ui.theme.ArtMotion
import com.audiohub.ui.theme.AudioHubTheme
import com.audiohub.ui.theme.UiStyle

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleLink(intent)
        setContent {
            val settings by appContainer.settings.settings.collectAsStateWithLifecycle(initialValue = null)
            val s = settings
            // Fonts are resolved once per change (asset listing is cached per process).
            val heading = remember(s?.headingFont) { s?.headingFont?.let { AppFonts.resolve(assets, it) } }
            val body = remember(s?.bodyFont) { s?.bodyFont?.let { AppFonts.resolve(assets, it) } }
            AudioHubTheme(
                style = UiStyle.fromId(s?.uiStyle.orEmpty()),
                headingFont = heading,
                bodyFont = body,
                artMotion = s?.artMotion?.let(ArtMotion::fromId),
            ) {
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

    /** Voice search from Assistant: play the best library matches. */
    private fun playFromSearch(query: String) {
        lifecycleScope.launch {
            val tracks = appContainer.musicRepository.tracks.first()
            val r = LibrarySearch.search(tracks, query)
            val songs = when {
                query.isBlank() -> tracks.shuffled()
                r.songs.isNotEmpty() -> r.songs
                else -> r.artists.firstOrNull()?.let { a -> tracks.filter { it.artist == a.name } } ?: emptyList()
            }
            if (songs.isEmpty()) {
                Toast.makeText(this@MainActivity, "Nothing in your library matches \"$query\"", Toast.LENGTH_SHORT).show()
            } else {
                appContainer.playerConnection.playQueue(songs.map { it.toMediaItem() })
            }
        }
    }

    /** podcasts.apple.com / feed links opened or shared to the app → show page. */
    private fun handleLink(intent: Intent?) {
        if (intent?.action == MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) {
            playFromSearch(intent.getStringExtra(SearchManager.QUERY).orEmpty())
            return
        }
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
