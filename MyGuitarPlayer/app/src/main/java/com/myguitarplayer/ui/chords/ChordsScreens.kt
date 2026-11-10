package com.myguitarplayer.ui.chords

import com.myguitarplayer.ui.common.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.myguitarplayer.audio.ChordPlayer
import com.myguitarplayer.music.Chord
import com.myguitarplayer.music.ChordGroup
import com.myguitarplayer.music.Chords
import com.myguitarplayer.music.Note
import com.myguitarplayer.ui.changes.ChangesScreen
import com.myguitarplayer.ui.chordcheck.ChordCheckScreen
import com.myguitarplayer.ui.common.Card
import com.myguitarplayer.ui.common.ScreenHeader
import com.myguitarplayer.ui.theme.Palette
import kotlinx.coroutines.launch

private enum class ChordSub { CHECK, CHANGES }

/** Chords tab: library → chord detail → mic check / one-minute changes. */
@Composable
fun ChordsTab() {
    var detail by rememberSaveable { mutableStateOf<String?>(null) }
    var sub by rememberSaveable { mutableStateOf<ChordSub?>(null) }
    BackHandler(enabled = sub != null) { sub = null }
    BackHandler(enabled = sub == null && detail != null) { detail = null }

    val chord = detail?.let { Chords.find(it) }
    when {
        chord != null && sub == ChordSub.CHECK -> ChordCheckScreen(chord.name, onBack = { sub = null })
        chord != null && sub == ChordSub.CHANGES -> ChangesScreen(chord.name, suggestPartner(chord).name, onBack = { sub = null })
        chord != null -> ChordDetailScreen(
            chord,
            onBack = { detail = null },
            onCheck = { sub = ChordSub.CHECK },
            onChanges = { sub = ChordSub.CHANGES },
        )
        else -> ChordListScreen(onOpen = { detail = it.name })
    }
}

/** The usual neighbour in beginner progressions — a sensible default partner for change drills. */
fun suggestPartner(chord: Chord): Chord = Chords[
    when (chord.name) {
        "Em" -> "Am"; "Am" -> "C"; "C" -> "G"; "G" -> "D"; "D" -> "A"; "A" -> "E"; "E" -> "Am"
        "Dm" -> "Am"; "F" -> "C"; "Bm7", "Bm" -> "G"; "E7" -> "Am"; "A7" -> "D"; "D7" -> "G"; "G7" -> "C"; "B7" -> "Em"
        else -> "Em"
    },
]

@Composable
fun ChordListScreen(onOpen: (Chord) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.padding(top = 16.dp)) { ScreenHeader("Hợp âm", "${Chords.ALL.size} thế bấm cho người mới") }
        }
        ChordGroup.entries.forEach { group ->
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(group.title, style = MaterialTheme.typography.titleMedium, color = Palette.Muted, modifier = Modifier.padding(top = 10.dp))
            }
            items(Chords.ALL.filter { it.group == group }, key = { it.name }) { chord ->
                Card(Modifier.clickable { onOpen(chord) }) {
                    Text(chord.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.align(Alignment.CenterHorizontally))
                    ChordDiagram(chord, width = 84.dp, showFingers = false)
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) { Column(Modifier.height(16.dp)) {} }
    }
}

@Composable
fun ChordDetailScreen(chord: Chord, onBack: () -> Unit, onCheck: () -> Unit, onChanges: () -> Unit) {
    val scope = rememberCoroutineScope()
    val player = remember { ChordPlayer() }
    DisposableEffect(Unit) { onDispose { player.stop() } }
    ChordDetailContent(chord, onBack, onPlay = { scope.launch { player.play(chord) } }, onCheck, onChanges)
}

@Composable
fun ChordDetailContent(chord: Chord, onBack: () -> Unit, onPlay: () -> Unit, onCheck: () -> Unit, onChanges: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenHeader(chord.name, chord.group.title, onBack)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { ChordDiagram(chord, width = 240.dp) }
        Card {
            Text(chord.tip, style = MaterialTheme.typography.bodyLarge)
            Text(
                "Gảy từ dây ${chord.firstString} · nốt: ${chord.midiNotes.joinToString(" ") { Note.name(it) }}",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.Muted,
            )
            Text(
                "Số trên chấm là ngón tay: 1 trỏ · 2 giữa · 3 áp út · 4 út. × = không gảy dây đó, ○ = dây buông.",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.Muted,
            )
        }
        OutlinedButton(onClick = onPlay, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.GraphicEq, null); Text("  Nghe thử")
        }
        Button(onClick = onCheck, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Icon(Icons.Rounded.Mic, null); Text("  Kiểm tra bằng micro")
        }
        OutlinedButton(onClick = onChanges, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.SwapHoriz, null); Text("  Luyện chuyển ${chord.name} ↔ ${suggestPartner(chord).name}")
        }
    }
}
