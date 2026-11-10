package com.myguitarplayer.ui.songs

import com.myguitarplayer.ui.common.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.myguitarplayer.data.Repos
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myguitarplayer.data.SavedSong
import com.myguitarplayer.music.ChordPro
import com.myguitarplayer.music.Fingering
import com.myguitarplayer.ui.common.Card
import com.myguitarplayer.ui.common.ScreenHeader
import com.myguitarplayer.ui.theme.Palette

private const val NEW_SONG = -1L

/** Songs tab: list → viewer, plus the paste/edit screen. */
@Composable
fun SongsTab() {
    val repo = Repos.songs
    LaunchedEffect(Unit) { repo.seedSamples() }
    val songs by remember { repo.all() }.collectAsStateWithLifecycle(initialValue = emptyList())

    var openId by rememberSaveable { mutableStateOf<Long?>(null) }
    var editId by rememberSaveable { mutableStateOf<Long?>(null) }
    BackHandler(enabled = editId != null) { editId = null }
    BackHandler(enabled = editId == null && openId != null) { openId = null }

    val editing = editId
    val open = openId
    when {
        editing != null -> SongEditorScreen(
            repo = repo,
            existing = songs.firstOrNull { it.id == editing },
            onDone = { saved -> editId = null; if (saved != null) openId = saved },
        )
        open != null -> SongViewerScreen(
            repo = repo,
            id = open,
            onBack = { openId = null },
            onEdit = { editId = open },
            onDeleted = { openId = null },
        )
        else -> SongListContent(songs, onOpen = { openId = it }, onAdd = { editId = NEW_SONG })
    }
}

@Composable
fun SongListContent(songs: List<SavedSong>, onOpen: (Long) -> Unit, onAdd: () -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ScreenHeader("Bài hát", "${songs.size} bài · lời + hợp âm, chuyển tông, capo, tự cuộn")
                Button(onClick = onAdd, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Icon(Icons.Rounded.Add, null); Text("  Thêm bài (dán lời có hợp âm)")
                }
            }
        }
        val mine = songs.filter { it.sample == null }
        if (mine.isNotEmpty()) {
            item { SectionTitle("Bài của bạn") }
            items(mine, key = { it.id }) { SongRow(it, onOpen) }
        }
        val samples = songs.filter { it.sample != null }
        if (samples.isNotEmpty()) {
            item { SectionTitle("Bài mẫu & bài luyện") }
            items(samples, key = { it.id }) { SongRow(it, onOpen) }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun SectionTitle(text: String) =
    Text(text, style = MaterialTheme.typography.titleMedium, color = Palette.Muted, modifier = Modifier.padding(top = 8.dp))

@Composable
private fun SongRow(saved: SavedSong, onOpen: (Long) -> Unit) {
    val song = remember(saved.body) { ChordPro.parse(saved.body, saved.title, saved.artist) }
    val fingering = Fingering(saved.transpose, saved.capo)
    Card(Modifier.clickable { onOpen(saved.id) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(saved.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (saved.artist.isNotBlank()) {
                    Text(saved.artist, style = MaterialTheme.typography.bodyMedium, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (saved.capo > 0) Tag("Capo ${saved.capo}")
        }
        Text(
            song.uniqueChords.map { fingering.shape(it, song) }.distinct().joinToString("  ·  "),
            style = MaterialTheme.typography.titleMedium,
            color = Palette.Accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun Tag(text: String) = Text(
    text,
    style = MaterialTheme.typography.labelMedium,
    color = Palette.Accent,
    modifier = Modifier
        .background(Palette.SurfaceHigh, RoundedCornerShape(8.dp))
        .padding(horizontal = 8.dp, vertical = 4.dp),
)
