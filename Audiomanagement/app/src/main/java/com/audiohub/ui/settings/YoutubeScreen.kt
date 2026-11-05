package com.audiohub.ui.settings

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.audiohub.appContainer
import com.audiohub.data.youtube.ConvertState
import com.audiohub.data.youtube.VideoInfo
import com.audiohub.data.youtube.YoutubeConverter
import com.audiohub.data.youtube.YoutubeLinks
import com.audiohub.data.youtube.YoutubeTrim
import com.audiohub.ui.components.Artwork
import com.audiohub.ui.components.ScreenHeader
import com.audiohub.ui.components.SectionLabel
import com.audiohub.ui.components.formatDuration
import com.audiohub.ui.library.DetailTopBar
import com.audiohub.ui.library.LocalTrackActions
import com.audiohub.ui.podcasts.artworkUri
import com.audiohub.ui.ringtone.TimeField
import kotlinx.coroutines.launch

@Composable
fun YoutubeScreen(initialLink: String = "") {
    val context = LocalContext.current
    val container = context.appContainer
    val converter = container.youtubeConverter
    val scope = rememberCoroutineScope()
    val convertState by converter.state.collectAsStateWithLifecycle()

    var link by rememberSaveable { mutableStateOf(initialLink) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var video by remember { mutableStateOf<VideoInfo?>(null) }
    var start by remember { mutableLongStateOf(0L) }
    var end by remember { mutableLongStateOf(0L) }

    fun fetch() {
        loading = true
        error = null
        video = null
        converter.reset()
        scope.launch {
            runCatching { converter.fetchInfo(link) }
                .onSuccess { v -> video = v; start = 0; end = v.durationMs }
                .onFailure { e -> error = e.message ?: "Couldn't read that video" }
            loading = false
        }
    }

    YoutubeContent(
        link = link,
        onLinkChange = { link = it; error = null },
        onPaste = { clipboardText(context)?.let { link = it } },
        onFetch = ::fetch,
        loading = loading,
        error = error,
        video = video,
        startMs = start,
        endMs = end,
        onRangeChange = { s, e -> start = s; end = e },
        convertState = convertState,
        onConvert = { video?.let { v -> converter.start(v, start, if (end >= v.durationMs) 0 else end) } },
        onCancel = converter::cancel,
        onPlay = { done ->
            container.playerConnection.playQueue(
                listOf(
                    MediaItem.Builder().setMediaId("youtube:${done.uri}").setUri(done.uri)
                        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(done.uri).build())
                        .setMediaMetadata(MediaMetadata.Builder().setTitle(done.title).setArtist("YouTube").build())
                        .build(),
                ),
            )
        },
        onAnother = {
            converter.reset()
            video = null
            link = ""
        },
    )
}

private fun clipboardText(context: Context): String? =
    context.getSystemService(ClipboardManager::class.java)?.primaryClip?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)?.coerceToText(context)?.toString()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YoutubeContent(
    link: String,
    onLinkChange: (String) -> Unit,
    onPaste: () -> Unit,
    onFetch: () -> Unit,
    loading: Boolean,
    error: String?,
    video: VideoInfo?,
    startMs: Long,
    endMs: Long,
    onRangeChange: (Long, Long) -> Unit,
    convertState: ConvertState,
    onConvert: () -> Unit,
    onCancel: () -> Unit,
    onPlay: (ConvertState.Done) -> Unit,
    onAnother: () -> Unit,
) {
    val actions = LocalTrackActions.current
    val busy = convertState is ConvertState.Downloading || convertState is ConvertState.Saving
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        DetailTopBar("", onBack = actions::back)
        ScreenHeader("YouTube to audio", subtitle = "Best available quality · saved to /${YoutubeConverter.OUTPUT_FOLDER}")

        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = link,
                onValueChange = onLinkChange,
                label = { Text("YouTube link") },
                placeholder = { Text("https://youtu.be/…") },
                singleLine = true,
                isError = link.isNotBlank() && YoutubeLinks.videoId(link) == null,
                trailingIcon = { IconButton(onClick = onPaste) { Icon(Icons.Rounded.ContentPaste, contentDescription = "Paste") } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (YoutubeLinks.videoId(link) != null) onFetch() }),
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onFetch,
                enabled = !loading && !busy && YoutubeLinks.videoId(link) != null,
                modifier = Modifier.padding(start = 6.dp),
            ) { Text("Get") }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp))
        if (error != null) {
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
        }

        if (video != null) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(artworkUri(video.thumbnailUrl), Modifier.size(width = 112.dp, height = 63.dp), cornerRadius = 8.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(video.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${video.uploader} · ${formatDuration(video.durationMs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${video.audio.label} · original, not re-encoded",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            SectionLabel("Part to keep")
            val duration = video.durationMs.coerceAtLeast(1)
            RangeSlider(
                value = startMs.toFloat()..endMs.coerceIn(startMs, duration).toFloat(),
                onValueChange = { r -> onRangeChange(r.start.toLong(), r.endInclusive.toLong()) },
                valueRange = 0f..duration.toFloat(),
                enabled = !busy,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.onSurface,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant,
                ),
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TimeField("Start", startMs, Modifier.weight(1f)) { onRangeChange(it, endMs) }
                TimeField("End", endMs, Modifier.weight(1f)) { onRangeChange(startMs, it) }
            }
            val rangeError = YoutubeTrim.validate(startMs, endMs, video.durationMs)
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onRangeChange(0, video.durationMs) }, enabled = !busy) {
                    Text("Whole video", style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    rangeError ?: "Length ${formatDuration(endMs - startMs)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (rangeError == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }

            Spacer(Modifier.height(12.dp))
            when (convertState) {
                is ConvertState.Downloading, is ConvertState.Saving -> Column(Modifier.padding(horizontal = 20.dp)) {
                    val progress = (convertState as? ConvertState.Downloading)?.progress
                    Text(
                        if (progress != null) "Downloading ${(progress * 100).toInt()}%" else "Saving…",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (progress != null) {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), drawStopIndicator = {})
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    TextButton(onClick = onCancel, modifier = Modifier.padding(top = 4.dp)) { Text("Cancel") }
                }
                is ConvertState.Done -> Column(Modifier.padding(horizontal = 20.dp)) {
                    Text("Saved", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "/${YoutubeConverter.OUTPUT_FOLDER}${convertState.fileName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { onPlay(convertState) },
                            shape = MaterialTheme.shapes.medium,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.onSurface,
                                contentColor = MaterialTheme.colorScheme.surface,
                            ),
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Play")
                        }
                        OutlinedButton(
                            onClick = onAnother,
                            shape = MaterialTheme.shapes.medium,
                            border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outline),
                            modifier = Modifier.weight(1f),
                        ) { Text("Another", color = MaterialTheme.colorScheme.onSurface) }
                    }
                }
                else -> Column(Modifier.padding(horizontal = 20.dp)) {
                    if (convertState is ConvertState.Failed) {
                        Text(convertState.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                    }
                    Button(
                        onClick = onConvert,
                        enabled = rangeError == null,
                        shape = MaterialTheme.shapes.medium,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.onSurface,
                            contentColor = MaterialTheme.colorScheme.surface,
                        ),
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) { Text(if (convertState is ConvertState.Failed) "Try again" else "Convert & save") }
                }
            }
        } else if (!loading) {
            Text(
                "Paste a YouTube or YouTube Music link (or share one to My Audio Hub), then tap Get.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp),
            )
        }

        Text(
            "For personal use with content you have the right to keep. Downloading may break YouTube's Terms of Service; " +
                "the extractor may stop working when YouTube changes and needs an app update.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 24.dp),
        )
    }
}
