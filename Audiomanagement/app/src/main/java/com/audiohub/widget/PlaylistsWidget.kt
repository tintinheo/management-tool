package com.audiohub.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionSendBroadcast
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.media3.common.MediaItem
import com.audiohub.AppContainer
import com.audiohub.R
import com.audiohub.appContainer
import com.audiohub.data.storage.AudioFolders
import com.audiohub.playback.toMediaItem
import kotlinx.coroutines.flow.first

/** Something the widget can shuffle-play. Encoded in the click Intent's data URI (extras don't make PendingIntents unique). */
sealed interface WidgetCollection {
    data object All : WidgetCollection
    data class Playlist(val id: Long) : WidgetCollection
    data class Folder(val path: String) : WidgetCollection
}

object WidgetCollections {
    private const val SCHEME = "myaudiohub"

    fun uri(c: WidgetCollection): Uri = when (c) {
        WidgetCollection.All -> Uri.parse("$SCHEME://all")
        is WidgetCollection.Playlist -> Uri.parse("$SCHEME://playlist/${c.id}")
        is WidgetCollection.Folder -> Uri.Builder().scheme(SCHEME).authority("folder").appendQueryParameter("path", c.path).build()
    }

    fun parse(uri: Uri): WidgetCollection? = when (uri.authority) {
        "all" -> WidgetCollection.All
        "playlist" -> uri.lastPathSegment?.toLongOrNull()?.let { WidgetCollection.Playlist(it) }
        "folder" -> uri.getQueryParameter("path")?.let { WidgetCollection.Folder(it) }
        else -> null
    }

    suspend fun tracks(container: AppContainer, c: WidgetCollection): List<MediaItem> = when (c) {
        WidgetCollection.All -> container.musicRepository.tracks.first()
        is WidgetCollection.Playlist -> container.playlistRepository.tracks(c.id).first().map { it.track }
        is WidgetCollection.Folder -> container.musicRepository.folderTracks(c.path).first()
    }.map { it.toMediaItem() }
}

/** Home-screen list of playlists and music folders; tap one to shuffle it. */
class PlaylistsWidget : GlanceAppWidget() {

    private data class Entry(val title: String, val subtitle: String, val collection: WidgetCollection, val isFolder: Boolean)

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val container = context.appContainer
        val root = AudioFolders.normalize(container.settings.current().musicFolder)
        val entries = runCatching {
            container.musicRepository.folders.first().filter { it.path != root }.map {
                Entry(AudioFolders.displayName(it.path, root), "${it.trackCount} songs", WidgetCollection.Folder(it.path), true)
            } + container.playlistRepository.summaries.first().map {
                Entry(it.name, "${it.trackCount} songs", WidgetCollection.Playlist(it.id), false)
            }
        }.getOrDefault(emptyList())
        provideContent { Content(context, entries) }
    }

    @Composable
    private fun Content(context: Context, entries: List<Entry>) {
        val ink = ColorProvider(Color(0xFFF3F0EA))
        val muted = ColorProvider(Color(0xFF9A948B))
        val gold = ColorProvider(Color(0xFFC9A96E))
        fun play(c: WidgetCollection) = actionSendBroadcast(
            Intent(context, WidgetActionReceiver::class.java)
                .setAction(WidgetActionReceiver.ACTION_PLAY_COLLECTION)
                .setData(WidgetCollections.uri(c)),
        )
        Column(
            GlanceModifier.fillMaxSize().background(ColorProvider(Color(0xFF141413))).cornerRadius(24.dp)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Playlists",
                    style = TextStyle(color = ink, fontSize = 18.sp, fontFamily = FontFamily.Serif),
                    modifier = GlanceModifier.defaultWeight(),
                )
                Row(
                    GlanceModifier.cornerRadius(16.dp).background(gold).padding(horizontal = 12.dp, vertical = 6.dp)
                        .clickable(play(WidgetCollection.All)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(ImageProvider(R.drawable.ic_widget_shuffle_dark), contentDescription = null, modifier = GlanceModifier.size(16.dp))
                    Spacer(GlanceModifier.width(6.dp))
                    Text("Shuffle all", style = TextStyle(color = ColorProvider(Color(0xFF141413)), fontSize = 12.sp, fontWeight = FontWeight.Medium))
                }
            }
            Spacer(GlanceModifier.height(10.dp))
            if (entries.isEmpty()) {
                Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Add folders to Music/AudioHub or create a playlist", style = TextStyle(color = muted, fontSize = 12.sp))
                }
            } else {
                LazyColumn(GlanceModifier.fillMaxSize()) {
                    items(entries) { e ->
                        Row(
                            GlanceModifier.fillMaxWidth().padding(vertical = 7.dp).clickable(play(e.collection)),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Image(
                                ImageProvider(if (e.isFolder) R.drawable.ic_widget_folder else R.drawable.ic_widget_playlist),
                                contentDescription = null,
                                modifier = GlanceModifier.size(20.dp),
                            )
                            Spacer(GlanceModifier.width(12.dp))
                            Column(GlanceModifier.defaultWeight()) {
                                Text(e.title, maxLines = 1, style = TextStyle(color = ink, fontSize = 14.sp))
                                Text(e.subtitle, maxLines = 1, style = TextStyle(color = muted, fontSize = 11.sp))
                            }
                            Image(ImageProvider(R.drawable.ic_widget_shuffle), contentDescription = "Shuffle ${e.title}", modifier = GlanceModifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }

    companion object {
        suspend fun refresh(context: Context) {
            runCatching { PlaylistsWidget().updateAll(context) }
        }
    }
}

class PlaylistsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PlaylistsWidget()
}
