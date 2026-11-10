package com.myguitarplayer.ui.songs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.myguitarplayer.data.SavedSong
import com.myguitarplayer.data.SongRepo
import com.myguitarplayer.music.ChordPro
import com.myguitarplayer.music.Song
import com.myguitarplayer.ui.common.Card
import com.myguitarplayer.ui.common.ScreenHeader
import com.myguitarplayer.ui.theme.Palette
import kotlinx.coroutines.launch

/** Paste or edit a song. [onDone] gets the saved song id, or null when cancelled. */
@Composable
fun SongEditorScreen(repo: SongRepo, existing: SavedSong?, onDone: (Long?) -> Unit) {
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf(existing?.body.orEmpty()) }
    var title by rememberSaveable { mutableStateOf(existing?.title.orEmpty()) }
    var artist by rememberSaveable { mutableStateOf(existing?.artist.orEmpty()) }
    val preview = remember(text) { if (text.isBlank()) null else ChordPro.parse(ChordPro.toChordPro(text)) }

    SongEditorContent(
        isNew = existing == null,
        text = text, title = title, artist = artist, preview = preview,
        onText = { text = it }, onTitle = { title = it }, onArtist = { artist = it },
        onSave = { scope.launch { onDone(repo.save(text, title, artist, existing)) } },
        onCancel = { onDone(null) },
    )
}

@Composable
fun SongEditorContent(
    isNew: Boolean,
    text: String,
    title: String,
    artist: String,
    preview: Song?,
    onText: (String) -> Unit,
    onTitle: (String) -> Unit,
    onArtist: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Palette.Accent,
        unfocusedBorderColor = Palette.Hairline,
        focusedLabelColor = Palette.Accent,
        cursorColor = Palette.Accent,
    )
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader(if (isNew) "Thêm bài" else "Sửa bài", "Dán lời có hợp âm từ web hoặc tự gõ", onCancel)
        OutlinedTextField(
            value = title, onValueChange = onTitle, label = { Text("Tên bài") }, singleLine = true,
            colors = fieldColors, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = artist, onValueChange = onArtist, label = { Text("Tác giả / ca sĩ") }, singleLine = true,
            colors = fieldColors, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = text,
            onValueChange = onText,
            label = { Text("Lời và hợp âm") },
            placeholder = { Text(EXAMPLE, style = MONO, color = Palette.Muted) },
            textStyle = MONO,
            minLines = 10,
            colors = fieldColors,
            modifier = Modifier.fillMaxWidth(),
        )
        PreviewCard(preview)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f).height(52.dp)) { Text("Huỷ") }
            Button(
                onClick = onSave,
                enabled = text.isNotBlank(),
                modifier = Modifier.weight(1f).height(52.dp),
            ) { Text("Lưu bài") }
        }
        Card {
            Text("Hai cách viết đều được", style = MaterialTheme.typography.titleMedium)
            Text(
                "• Hợp âm trong ngoặc vuông, ngay trước chữ đổi hợp âm: [Am]Hôm nay [G]trời...\n" +
                    "• Hoặc dòng hợp âm nằm trên dòng lời (kiểu chép từ web) — app tự ghép theo vị trí cột.\n" +
                    "• Thêm {key: G} {tempo: 72} {capo: 2} {strum: D.DU.UDU} nếu muốn. Dòng [ĐK] hoặc {soc} để đánh dấu điệp khúc.",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.Muted,
            )
            Text("Bài bạn dán chỉ lưu trên máy này.", style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
        }
    }
}

@Composable
private fun PreviewCard(preview: Song?) {
    if (preview == null) return
    val lyricLines = preview.lines.count { it is com.myguitarplayer.music.SongLine.Lyrics }
    val chords = preview.uniqueChords
    Card {
        if (chords.isEmpty()) {
            Text("Chưa thấy hợp âm nào", style = MaterialTheme.typography.titleMedium, color = Palette.Wrong)
            Text(
                "Đặt hợp âm trong [ ] hoặc trên một dòng riêng phía trên lời.",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.Muted,
            )
        } else {
            Text(
                "Đọc được $lyricLines dòng · ${chords.size} hợp âm" + (preview.key?.let { " · tông ${it.name}" } ?: ""),
                style = MaterialTheme.typography.titleMedium,
                color = Palette.Right,
            )
            Text(chords.joinToString("  ·  "), style = MaterialTheme.typography.titleMedium, color = Palette.Accent)
        }
    }
}

private val MONO = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 20.sp)

private const val EXAMPLE = """{key: C}
[C]Bắc kim thang cà lang bí [G]rợ
Cột qua kèo là kèo qua [C]cột

— hoặc —

C                        G
Bắc kim thang cà lang bí rợ"""
