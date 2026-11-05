package com.audiohub.ui.podcasts

import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.draw.clip
import com.audiohub.data.podcast.DownloadState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.RemoveDone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.audiohub.ui.components.Artwork

fun artworkUri(url: String?): Uri? = url?.let(Uri::parse)

/** "2 days ago" / "3 Nov 2026" style date for episode lists. */
fun episodeDate(pubDate: Long, now: Long = System.currentTimeMillis()): String =
    if (pubDate <= 0) "" else DateUtils.getRelativeTimeSpanString(pubDate, now, DateUtils.DAY_IN_MILLIS, DateUtils.FORMAT_ABBREV_MONTH).toString()

/** "45 min", "1 hr 5 min", "12 min left". */
fun minutesLabel(ms: Long, suffix: String = ""): String {
    if (ms <= 0) return ""
    val totalMin = ((ms + 59_999) / 60_000).toInt()
    val h = totalMin / 60
    val m = totalMin % 60
    val base = when {
        h == 0 -> "$m min"
        m == 0 -> "$h hr"
        else -> "$h hr $m min"
    }
    return base + suffix
}

/** Plain-text preview of HTML show notes. */
fun notesPreview(html: String): String =
    AnnotatedString.fromHtml(html).text.replace(Regex("\\s+"), " ").trim()

@Composable
fun EpisodeRow(
    episode: EpisodeUi,
    actions: PodcastActions,
    isCurrent: Boolean,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    showArtwork: Boolean = true,
    showShowTitle: Boolean = false,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var notesOpen by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().clickable { notesOpen = true }.padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            if (showArtwork) {
                Artwork(artworkUri(episode.artworkUrl), Modifier.size(56.dp), placeholder = Icons.Rounded.Podcasts)
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    listOfNotNull(
                        episode.showTitle.takeIf { showShowTitle },
                        episodeDate(episode.pubDate).ifEmpty { null },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    episode.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val preview = remember(episode.description) { notesPreview(episode.description) }
                if (preview.isNotEmpty()) {
                    Text(
                        preview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            // Minimal play control: outlined circle + remaining time, no filled pill.
            Row(
                Modifier
                    .clip(CircleShape)
                    .clickable { actions.play(episode) }
                    .padding(end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(28.dp)
                        .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (isCurrent && isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (isCurrent && isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(16.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        episode.played -> "Played"
                        episode.inProgress -> minutesLabel(episode.durationMs - episode.positionMs, " left")
                        else -> minutesLabel(episode.durationMs).ifEmpty { "Play" }
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (episode.inProgress && episode.durationMs > 0) {
                LinearProgressIndicator(
                    progress = { (episode.positionMs.toFloat() / episode.durationMs).coerceIn(0f, 1f) },
                    modifier = Modifier.width(48.dp).height(2.dp),
                    trackColor = MaterialTheme.colorScheme.outlineVariant,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
            }
            Spacer(Modifier.weight(1f))
            if (episode.episodeId != null && !episode.isLocalFile) {
                DownloadControl(episode, actions)
            }
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Play next") },
                        leadingIcon = { Icon(Icons.Rounded.PlayArrow, null) },
                        onClick = { menuOpen = false; actions.playNext(episode) },
                    )
                    DropdownMenuItem(
                        text = { Text("Add to queue") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.QueueMusic, null) },
                        onClick = { menuOpen = false; actions.addToQueue(episode) },
                    )
                    if (episode.episodeId != null) {
                        DropdownMenuItem(
                            text = { Text(if (episode.played) "Mark as unplayed" else "Mark as played") },
                            leadingIcon = { Icon(if (episode.played) Icons.Rounded.RemoveDone else Icons.Rounded.Done, null) },
                            onClick = { menuOpen = false; actions.setPlayed(episode, !episode.played) },
                        )
                    }
                    if (episode.isDownloaded) {
                        DropdownMenuItem(
                            text = { Text("Remove download") },
                            leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) },
                            onClick = { menuOpen = false; actions.removeDownload(episode) },
                        )
                    }
                }
            }
        }
    }
    if (notesOpen) {
        EpisodeNotesDialog(episode, onPlay = { notesOpen = false; actions.play(episode) }, onDismiss = { notesOpen = false })
    }
}

/** Download ↓ / progress ring (tap to cancel) / downloaded ✓ / failed (tap to retry). */
@Composable
private fun DownloadControl(episode: EpisodeUi, actions: PodcastActions) {
    val progress = LocalDownloadProgress.current[episode.episodeId]
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    when {
        episode.isDownloaded -> Icon(
            Icons.Rounded.DownloadDone,
            contentDescription = "Downloaded",
            tint = muted,
            modifier = Modifier.padding(12.dp).size(20.dp),
        )
        episode.downloadState == DownloadState.QUEUED || episode.downloadState == DownloadState.DOWNLOADING ->
            IconButton(onClick = { actions.cancelDownload(episode) }) {
                Box(contentAlignment = Alignment.Center) {
                    if (progress != null && progress > 0f) {
                        CircularProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            trackColor = MaterialTheme.colorScheme.outlineVariant,
                            gapSize = 0.dp,
                        )
                    } else {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                    Icon(Icons.Rounded.Close, contentDescription = "Cancel download", modifier = Modifier.size(10.dp))
                }
            }
        episode.downloadState == DownloadState.FAILED ->
            IconButton(onClick = { actions.download(episode) }) {
                Icon(Icons.Rounded.ErrorOutline, contentDescription = "Download failed — retry", tint = MaterialTheme.colorScheme.primary)
            }
        else -> IconButton(onClick = { actions.download(episode) }) {
            Icon(Icons.Rounded.Download, contentDescription = "Download", tint = muted)
        }
    }
}

@Composable
fun EpisodeNotesDialog(episode: EpisodeUi, onPlay: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(episode.title, maxLines = 3, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    listOf(episodeDate(episode.pubDate), minutesLabel(episode.durationMs)).filter { it.isNotEmpty() }.joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(remember(episode.description) { AnnotatedString.fromHtml(episode.description) })
            }
        },
        confirmButton = { TextButton(onClick = onPlay) { Text("Play") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
fun ShowTile(title: String, artworkUrl: String?, badge: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.clickable(onClick = onClick).padding(6.dp)) {
        Box {
            Artwork(
                artworkUri(artworkUrl),
                Modifier.fillMaxWidth().aspectRatio(1f),
                cornerRadius = 4.dp,
                placeholder = Icons.Rounded.Podcasts,
            )
            if (badge > 0) {
                // Unplayed-episode count, like Apple Podcasts.
                Text(
                    if (badge > 99) "99+" else badge.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
        }
        Text(
            title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
fun SearchResultRow(
    title: String,
    author: String,
    detail: String?,
    artworkUrl: String?,
    rank: Int?,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (rank != null) {
            Text(
                rank.toString(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(28.dp),
            )
        }
        Artwork(artworkUri(artworkUrl), Modifier.size(64.dp), placeholder = Icons.Rounded.Podcasts)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                author,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!detail.isNullOrEmpty()) {
                Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
