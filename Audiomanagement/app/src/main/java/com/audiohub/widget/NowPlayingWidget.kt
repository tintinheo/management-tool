package com.audiohub.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionSendBroadcast
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.audiohub.MainActivity
import com.audiohub.R
import com.audiohub.appContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Home-screen "now playing" card: title, artist, shuffle / previous / play-pause / next. */
class NowPlayingWidget : GlanceAppWidget() {

    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val prefs = currentState<Preferences>()
            Content(
                title = prefs[TITLE] ?: "My Audio Hub",
                artist = prefs[ARTIST] ?: "Nothing playing",
                playing = prefs[PLAYING] ?: false,
                shuffle = prefs[SHUFFLE] ?: false,
                context = context,
            )
        }
    }

    @Composable
    private fun Content(title: String, artist: String, playing: Boolean, shuffle: Boolean, context: Context) {
        val ink = ColorProvider(Color(0xFFF3F0EA))
        val muted = ColorProvider(Color(0xFF9A948B))
        Box(
            GlanceModifier
                .fillMaxSize()
                .background(ColorProvider(Color(0xFF141413)))
                .cornerRadius(24.dp)
                .clickable(actionStartActivity<MainActivity>())
                .padding(horizontal = 18.dp, vertical = 14.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(GlanceModifier.defaultWeight()) {
                    Text(title, maxLines = 1, style = TextStyle(color = ink, fontSize = 16.sp, fontFamily = FontFamily.Serif))
                    Text(artist, maxLines = 1, style = TextStyle(color = muted, fontSize = 12.sp, fontWeight = FontWeight.Medium))
                }
                Spacer(GlanceModifier.width(6.dp))
                ControlButton(
                    if (shuffle) R.drawable.ic_widget_shuffle_on else R.drawable.ic_widget_shuffle,
                    WidgetActionReceiver.ACTION_SHUFFLE, context, 34,
                )
                ControlButton(R.drawable.ic_widget_previous, WidgetActionReceiver.ACTION_PREVIOUS, context, 36)
                ControlButton(
                    if (playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
                    WidgetActionReceiver.ACTION_PLAY_PAUSE, context, 44,
                )
                ControlButton(R.drawable.ic_widget_next, WidgetActionReceiver.ACTION_NEXT, context, 36)
            }
        }
    }

    @Composable
    private fun ControlButton(icon: Int, action: String, context: Context, size: Int) {
        Image(
            provider = ImageProvider(icon),
            contentDescription = action.substringAfterLast('.'),
            modifier = GlanceModifier
                .size(size.dp)
                .padding(6.dp)
                .clickable(actionSendBroadcast(Intent(context, WidgetActionReceiver::class.java).setAction(action))),
        )
    }

    companion object {
        private val TITLE = stringPreferencesKey("title")
        private val ARTIST = stringPreferencesKey("artist")
        private val PLAYING = booleanPreferencesKey("playing")
        private val SHUFFLE = booleanPreferencesKey("shuffle")

        /** Pushes the current track to every placed widget (no-op if none). */
        suspend fun update(context: Context, title: String, artist: String, playing: Boolean, shuffle: Boolean = false) {
            val manager = GlanceAppWidgetManager(context)
            val widget = NowPlayingWidget()
            manager.getGlanceIds(NowPlayingWidget::class.java).forEach { id ->
                updateAppWidgetState(context, id) { prefs ->
                    prefs[TITLE] = title.ifEmpty { "My Audio Hub" }
                    prefs[ARTIST] = artist
                    prefs[PLAYING] = playing
                    prefs[SHUFFLE] = shuffle
                }
                widget.update(context, id)
            }
        }
    }
}

class NowPlayingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NowPlayingWidget()
}

/** Widget buttons → the app's player connection (binds to the playback service if needed). */
class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = context.appContainer
        val player = container.playerConnection
        when (intent.action) {
            ACTION_PLAY_PAUSE -> player.togglePlayPause()
            ACTION_NEXT -> player.skipNext()
            ACTION_PREVIOUS -> player.skipPrevious()
            ACTION_SHUFFLE -> player.toggleShuffle()
            ACTION_PLAY_COLLECTION -> {
                // Loading tracks touches the database: keep the receiver alive until they're queued.
                val pending = goAsync()
                val collection = intent.data?.let(WidgetCollections::parse)
                CoroutineScope(Dispatchers.Main).launch {
                    try {
                        val items = collection?.let { WidgetCollections.tracks(container, it) }.orEmpty()
                        if (items.isNotEmpty()) player.playQueue(items, startIndex = items.indices.random(), shuffle = true)
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_PLAY_PAUSE = "com.audiohub.widget.PLAY_PAUSE"
        const val ACTION_NEXT = "com.audiohub.widget.NEXT"
        const val ACTION_PREVIOUS = "com.audiohub.widget.PREVIOUS"
        const val ACTION_SHUFFLE = "com.audiohub.widget.SHUFFLE"
        const val ACTION_PLAY_COLLECTION = "com.audiohub.widget.PLAY_COLLECTION"
    }
}
