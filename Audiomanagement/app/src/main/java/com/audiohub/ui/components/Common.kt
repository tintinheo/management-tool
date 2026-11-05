package com.audiohub.ui.components

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import com.audiohub.ui.theme.SectionLabelStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/** Square artwork with a placeholder icon that shows while loading or when there's no art. */
@Composable
fun Artwork(
    uri: Uri?,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 4.dp,
    placeholder: ImageVector = Icons.Rounded.MusicNote,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            placeholder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
        )
        if (uri != null) {
            AsyncImage(
                model = uri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

@Composable
fun ScreenHeader(title: String, modifier: Modifier = Modifier, subtitle: String? = null) {
    Column(modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 12.dp)) {
        Text(title, style = MaterialTheme.typography.headlineLarge)
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** Quiet uppercase section heading, e.g. "RECENTLY ADDED". */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = SectionLabelStyle,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 8.dp),
    )
}

/** Hairline divider, inset to align with row text. */
@Composable
fun Hairline(modifier: Modifier = Modifier, start: Dp = 20.dp) {
    HorizontalDivider(modifier.padding(start = start), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(36.dp),
        )
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        action?.invoke()
    }
}

/** "1 song", "12 songs". */
fun plural(count: Int, noun: String): String = if (count == 1) "1 $noun" else "$count ${noun}s"

/** 0:42, 3:07, 1:02:33 */
fun formatDuration(ms: Long): String {
    val totalSec = (ms.coerceAtLeast(0L) / 1000)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
