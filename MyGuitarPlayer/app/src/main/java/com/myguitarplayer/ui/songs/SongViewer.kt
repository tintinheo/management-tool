package com.myguitarplayer.ui.songs

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.TimerOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myguitarplayer.audio.ChordPlayer
import com.myguitarplayer.audio.Metronome
import com.myguitarplayer.data.SavedSong
import com.myguitarplayer.data.SongRepo
import com.myguitarplayer.music.CapoAdvisor
import com.myguitarplayer.music.ChordPro
import com.myguitarplayer.music.ChordSymbol
import com.myguitarplayer.music.Fingering
import com.myguitarplayer.music.Song
import com.myguitarplayer.music.SongLine
import com.myguitarplayer.ui.chords.ChordDiagram
import com.myguitarplayer.ui.common.Card
import com.myguitarplayer.ui.common.KeepScreenOn
import com.myguitarplayer.ui.common.ScreenHeader
import com.myguitarplayer.ui.theme.Palette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Everything the viewer shows besides the song itself. */
data class ViewerState(
    val transpose: Int = 0,
    val capo: Int = 0,
    val bpm: Int = 72,
    val speed: Float = 1f,
    val playing: Boolean = false,
    val click: Boolean = false,
    val chordsOnly: Boolean = false,
    val textScale: Float = 1f,
) {
    val fingering: Fingering get() = Fingering(transpose, capo)

    companion object {
        const val MIN_BPM = 40
        const val MAX_BPM = 160
        const val MIN_SPEED = 0.3f
        const val MAX_SPEED = 3f
    }
}

@Composable
fun SongViewerScreen(repo: SongRepo, id: Long, onBack: () -> Unit, onEdit: (() -> Unit)?, onDeleted: () -> Unit) {
    val saved by remember(id) { repo.byId(id) }.collectAsStateWithLifecycle(initialValue = null)
    val s = saved ?: return
    SongViewer(repo, s, onBack, onEdit, onDeleted)
}

@Composable
private fun SongViewer(repo: SongRepo, saved: SavedSong, onBack: () -> Unit, onEdit: (() -> Unit)?, onDeleted: () -> Unit) {
    KeepScreenOn()
    val scope = rememberCoroutineScope()
    val song = remember(saved.body, saved.title, saved.artist) { ChordPro.parse(saved.body, saved.title, saved.artist) }
    var state by remember(saved.id) { mutableStateOf(ViewerState(saved.transpose, saved.capo, saved.bpm, saved.speed)) }
    var chordShown by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val metronome = remember { Metronome() }
    val player = remember { ChordPlayer() }
    DisposableEffect(Unit) { onDispose { metronome.stop(); player.stop() } }

    // Auto-scroll: about one lyric line per two bars at the song's tempo, times the speed factor.
    val linePx = with(LocalDensity.current) { (LINE_DP * state.textScale).dp.toPx() }
    LaunchedEffect(state.playing, state.bpm, state.speed, linePx) {
        if (!state.playing) return@LaunchedEffect
        val pxPerSec = linePx * state.bpm / (60f * song.beatsPerBar * 2) * state.speed
        var last = withFrameNanos { it }
        var carry = 0f
        while (currentCoroutineContext().isActive) {
            val now = withFrameNanos { it }
            carry += pxPerSec * (now - last) / 1e9f
            last = now
            if (carry >= 1f) {
                try {
                    scroll.scrollBy(carry)
                } catch (e: CancellationException) {
                    if (!currentCoroutineContext().isActive) throw e // left the screen; a finger drag just pauses this frame
                }
                carry = 0f
            }
            if (scroll.maxValue > 0 && scroll.value >= scroll.maxValue) state = state.copy(playing = false)
        }
    }
    LaunchedEffect(state.playing && state.click, state.bpm) {
        if (state.playing && state.click) metronome.start(scope, state.bpm, song.beatsPerBar) else metronome.stop()
    }

    SongViewerContent(
        song = song,
        state = state,
        isSample = saved.sample != null || onEdit == null,
        scroll = scroll,
        onChange = { next ->
            val persist = next.transpose != state.transpose || next.capo != state.capo || next.bpm != state.bpm || next.speed != state.speed
            if (next.playing && !state.playing && scroll.value >= scroll.maxValue) scope.launch { scroll.scrollTo(0) }
            state = next
            if (persist) scope.launch { repo.updateSettings(saved.copy(transpose = next.transpose, capo = next.capo, bpm = next.bpm, speed = next.speed)) }
        },
        onChord = { chordShown = it },
        onEdit = onEdit,
        onDelete = { confirmDelete = true },
        onBack = onBack,
    )

    chordShown?.let { name ->
        ChordShapeDialog(name, onPlay = { c -> scope.launch { player.play(c) } }, onDismiss = { chordShown = null })
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Xoá bài này?") },
            text = { Text("\"${saved.title}\" sẽ bị xoá khỏi máy. Không hoàn tác được.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; scope.launch { repo.delete(saved); onDeleted() } }) {
                    Text("Xoá", color = Palette.Wrong)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Huỷ") } },
            containerColor = Palette.Surface,
        )
    }
}

private const val LINE_DP = 60f

@Composable
fun SongViewerContent(
    song: Song,
    state: ViewerState,
    isSample: Boolean,
    scroll: ScrollState,
    onChange: (ViewerState) -> Unit,
    onChord: (String) -> Unit,
    onEdit: (() -> Unit)?,
    onDelete: () -> Unit,
    onBack: () -> Unit,
) {
    val f = state.fingering
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { ScreenHeader(song.title, song.artist.ifBlank { null }, onBack) }
                if (onEdit != null) IconButton(onClick = onEdit) { Icon(Icons.Rounded.Edit, contentDescription = "Sửa bài") }
                if (!isSample) IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, contentDescription = "Xoá bài") }
            }
            KeyCard(song, state, onChange)
            ChordStrip(song, f, onChord)
            SongLines(song, state, onChord)
            Spacer(Modifier.height(96.dp))
        }
        PlayBar(state, onChange, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun KeyCard(song: Song, state: ViewerState, onChange: (ViewerState) -> Unit) {
    val f = state.fingering
    val sounding = f.soundingKey(song)?.name
    val shapeKey = f.shapeKey(song)?.name
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Stepper(
                label = "Tông",
                value = (sounding ?: "—") + if (state.transpose != 0) " (%+d)".format(state.transpose) else "",
                onMinus = { onChange(state.copy(transpose = wrapTranspose(state.transpose - 1))) },
                onPlus = { onChange(state.copy(transpose = wrapTranspose(state.transpose + 1))) },
                modifier = Modifier.weight(1f),
            )
            Stepper(
                label = "Capo",
                value = if (state.capo == 0) "Không" else "Ngăn ${state.capo}",
                onMinus = { if (state.capo > 0) onChange(state.copy(capo = state.capo - 1)) },
                onPlus = { if (state.capo < CapoAdvisor.MAX_CAPO) onChange(state.copy(capo = state.capo + 1)) },
                modifier = Modifier.weight(1f),
            )
        }
        if (state.capo > 0 && shapeKey != null) {
            Text(
                "Kẹp capo ngăn ${state.capo}, bấm theo thế $shapeKey — nghe ra tông $sounding.",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.Muted,
            )
        }
        val options = remember(song, state.transpose) { CapoAdvisor.options(song, state.transpose) }
        val best = options.first()
        val current = options.first { it.capo == state.capo }
        if (best.capo != state.capo && best.cost < current.cost - 0.2) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Dễ hơn: " + (if (best.capo == 0) "bỏ capo" else "capo ${best.capo}") +
                        " → " + best.shapes.distinct().joinToString(" "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.Accent,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onChange(state.copy(capo = best.capo)) }) { Text("Dùng") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stepper(
                label = "Nhịp",
                value = "${state.bpm} BPM",
                onMinus = { onChange(state.copy(bpm = (state.bpm - 4).coerceAtLeast(ViewerState.MIN_BPM))) },
                onPlus = { onChange(state.copy(bpm = (state.bpm + 4).coerceAtMost(ViewerState.MAX_BPM))) },
                modifier = Modifier.weight(1f),
            )
            Column(Modifier.weight(1f)) {
                Text("Chữ", style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onChange(state.copy(textScale = (state.textScale - 0.15f).coerceAtLeast(0.7f))) }) { Text("A−") }
                    TextButton(onClick = { onChange(state.copy(textScale = (state.textScale + 0.15f).coerceAtMost(1.9f))) }) { Text("A+", fontSize = 19.sp) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = state.chordsOnly,
                onClick = { onChange(state.copy(chordsOnly = !state.chordsOnly)) },
                label = { Text("Chỉ hợp âm") },
                colors = goldChip(),
            )
            song.strum?.let { Text("Quạt: " + strumArrows(it), style = MaterialTheme.typography.titleMedium, color = Palette.Text) }
        }
    }
}

/** Keeps transpose in −6…+6 (the same 12 keys, the shorter way round). */
private fun wrapTranspose(t: Int): Int = Math.floorMod(t + 6, 12) - 6

/** "D.DU.UDU" → "↓ · ↓↑ · ↑↓↑" (groups per beat when the pattern is on an eighth-note grid). */
fun strumArrows(pattern: String): String = pattern.uppercase().chunked(2).joinToString("  ") { beat ->
    beat.map { when (it) { 'D' -> '↓'; 'U' -> '↑'; else -> '·' } }.joinToString("")
}

@Composable
private fun goldChip() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = Palette.Accent,
    selectedLabelColor = Palette.OnAccent,
    labelColor = Palette.Text,
)

@Composable
private fun Stepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
        Row(verticalAlignment = Alignment.CenterVertically) {
            SmallRound(Icons.Rounded.Remove, "Giảm $label", onMinus)
            Text(value, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 8.dp).widthIn(min = 44.dp))
            SmallRound(Icons.Rounded.Add, "Tăng $label", onPlus)
        }
    }
}

@Composable
private fun SmallRound(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .border(1.dp, Palette.Hairline, CircleShape)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = label, modifier = Modifier.size(18.dp)) }
}

/** The song's chords as mini diagrams, in the order they first appear. Tap for the big diagram. */
@Composable
private fun ChordStrip(song: Song, f: Fingering, onChord: (String) -> Unit) {
    val shapes = song.uniqueChords.map { f.shape(it, song) }.distinct()
    if (shapes.isEmpty()) return
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        shapes.forEach { name ->
            val match = ChordSymbol.parse(name)?.shape()
            Column(
                Modifier
                    .border(1.dp, Palette.Hairline, RoundedCornerShape(12.dp))
                    .clickable { onChord(name) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(name, style = MaterialTheme.typography.titleMedium, color = Palette.Accent)
                if (match != null) ChordDiagram(match.chord, width = 60.dp, showFingers = false)
                else Text("chặn", style = MaterialTheme.typography.labelMedium, color = Palette.Muted, modifier = Modifier.padding(top = 20.dp, bottom = 20.dp))
            }
        }
    }
}

private data class Token(val chord: String?, val word: String)

private val WORD = Regex("""\S+\s*|\s+""")

private fun tokens(line: SongLine.Lyrics): List<Token> = line.segments.flatMap { seg ->
    val words = WORD.findAll(seg.text).map { it.value }.toList()
    if (words.isEmpty()) listOf(Token(seg.chord, "")) else words.mapIndexed { i, w -> Token(if (i == 0) seg.chord else null, w) }
}

@Composable
private fun SongLines(song: Song, state: ViewerState, onChord: (String) -> Unit) {
    val f = state.fingering
    val scale = state.textScale
    val lyricSize = (19 * scale).sp
    val chordSize = (17 * scale).sp
    Column(verticalArrangement = Arrangement.spacedBy((6 * scale).dp)) {
        song.lines.forEach { line ->
            when (line) {
                SongLine.Blank -> Spacer(Modifier.height((10 * scale).dp))
                is SongLine.Section -> Text(
                    line.label.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = Palette.Accent,
                    modifier = Modifier.padding(top = 6.dp),
                )
                is SongLine.Comment -> Text(
                    line.text,
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Italic,
                    color = Palette.Muted,
                )
                is SongLine.Lyrics -> when {
                    state.chordsOnly -> if (line.hasChords) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                            line.segments.mapNotNull { it.chord }.forEach { c ->
                                val shape = f.shape(c, song)
                                Text(
                                    shape,
                                    fontSize = (34 * scale).sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Palette.Accent,
                                    modifier = Modifier.clickable { onChord(shape) },
                                )
                            }
                        }
                    }
                    !line.hasChords -> Text(line.segments.joinToString("") { it.text }, fontSize = lyricSize, lineHeight = lyricSize * 1.3f)
                    else -> FlowRow {
                        tokens(line).forEach { t ->
                            Column {
                                val shape = t.chord?.let { f.shape(it, song) }
                                Text(
                                    shape ?: " ",
                                    fontSize = chordSize,
                                    fontWeight = FontWeight.Bold,
                                    color = Palette.Accent,
                                    modifier = Modifier
                                        .padding(end = 6.dp)
                                        .then(if (shape != null) Modifier.clickable { onChord(shape) } else Modifier),
                                )
                                Text(t.word, fontSize = lyricSize)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayBar(state: ViewerState, onChange: (ViewerState) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .navigationBarsPadding()
            .padding(12.dp)
            .background(Palette.SurfaceHigh, RoundedCornerShape(28.dp))
            .border(1.dp, Palette.Hairline, RoundedCornerShape(28.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FilledIconButton(
            onClick = { onChange(state.copy(playing = !state.playing)) },
            modifier = Modifier.size(48.dp),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = Palette.Accent, contentColor = Palette.OnAccent),
        ) {
            Icon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = if (state.playing) "Dừng cuộn" else "Tự cuộn")
        }
        Text("Cuộn", style = MaterialTheme.typography.labelMedium, color = Palette.Muted, modifier = Modifier.padding(start = 4.dp))
        SmallRound(Icons.Rounded.Remove, "Cuộn chậm hơn") {
            onChange(state.copy(speed = ((state.speed - 0.1f) * 10).let { Math.round(it) / 10f }.coerceAtLeast(ViewerState.MIN_SPEED)))
        }
        Text("%.1f×".format(state.speed), style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(44.dp))
        SmallRound(Icons.Rounded.Add, "Cuộn nhanh hơn") {
            onChange(state.copy(speed = ((state.speed + 0.1f) * 10).let { Math.round(it) / 10f }.coerceAtMost(ViewerState.MAX_SPEED)))
        }
        IconButton(onClick = { onChange(state.copy(click = !state.click)) }) {
            Icon(
                if (state.click) Icons.Rounded.Timer else Icons.Rounded.TimerOff,
                contentDescription = if (state.click) "Tắt tiếng nhịp" else "Bật tiếng nhịp khi cuộn",
                tint = if (state.click) Palette.Accent else Palette.Muted,
            )
        }
    }
}

/** Big diagram for a chord tapped in a song; names the simpler stand-in when the exact shape isn't in the library. */
@Composable
fun ChordShapeDialog(name: String, onPlay: (com.myguitarplayer.music.Chord) -> Unit, onDismiss: () -> Unit) {
    val match = ChordSymbol.parse(name)?.shape()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(name, style = MaterialTheme.typography.headlineMedium) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                if (match != null) {
                    ChordDiagram(match.chord, width = 200.dp)
                    if (!match.exact) {
                        Text(
                            "Chưa có thế $name trong thư viện — tạm bấm ${match.chord.name}, nghe vẫn hợp.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Palette.Muted,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                } else {
                    Text(
                        "$name cần bấm chặn. Thử tăng/giảm capo — app sẽ gợi ý vị trí có thế bấm dễ hơn.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        },
        confirmButton = {
            if (match != null) TextButton(onClick = { onPlay(match.chord) }) { Text("Nghe thử") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Đóng") } },
        containerColor = Palette.Surface,
    )
}
