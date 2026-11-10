package com.myguitarplayer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myguitarplayer.audio.MicStream
import com.myguitarplayer.music.Chords
import com.myguitarplayer.platform.Platform
import com.myguitarplayer.ui.changes.ChangesScreen
import com.myguitarplayer.ui.chordcheck.ChordCheckScreen
import com.myguitarplayer.ui.chords.ChordsTab
import com.myguitarplayer.ui.chords.suggestPartner
import com.myguitarplayer.ui.common.BackHandler
import com.myguitarplayer.ui.metronome.MetronomeScreen
import com.myguitarplayer.ui.notes.NotesScreen
import com.myguitarplayer.ui.progress.ProgressTab
import com.myguitarplayer.ui.record.RecordScreen
import com.myguitarplayer.ui.rhythm.RhythmScreen
import com.myguitarplayer.ui.setup.MicSetupScreen
import com.myguitarplayer.ui.songs.SongsTab
import com.myguitarplayer.ui.theme.Palette
import com.myguitarplayer.ui.today.TodayTab
import com.myguitarplayer.ui.tools.Tool
import com.myguitarplayer.ui.tools.ToolsScreen
import com.myguitarplayer.ui.tuner.TunerScreen

enum class Tab(val label: String, val icon: ImageVector) {
    TODAY("Hôm nay", Icons.Rounded.Today),
    TOOLS("Công cụ", Icons.Rounded.Build),
    CHORDS("Hợp âm", Icons.Rounded.GridView),
    SONGS("Bài hát", Icons.Rounded.LibraryMusic),
    PROGRESS("Tiến độ", Icons.AutoMirrored.Rounded.ShowChart),
}

/** Wider than this (tablet, desktop window) the tabs move to a side rail and screens get a reading width. */
private val WIDE = 720.dp
private val CONTENT_MAX = 760.dp

/** The whole app, shared by Android (MainActivity) and Windows (desktop Main.kt). */
@Composable
fun App(initialTab: Tab = Tab.TODAY) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    var tool by rememberSaveable { mutableStateOf<Tool?>(null) }
    var toolChord by rememberSaveable { mutableStateOf("Em") }

    var crash by remember { mutableStateOf(CrashLog.read()) }
    crash?.let { report ->
        AlertDialog(
            onDismissRequest = { CrashLog.clear(); crash = null },
            title = { Text("Lần trước app bị lỗi") },
            text = { Text("Xin lỗi bạn! Bấm \"Copy\" rồi dán gửi cho người làm app để sửa. Dữ liệu tập của bạn vẫn còn nguyên.") },
            confirmButton = {
                TextButton(onClick = {
                    Platform.current.copyToClipboard("MyGuitarPlayer crash", report)
                    CrashLog.clear(); crash = null
                }) { Text("Copy") }
            },
            dismissButton = { TextButton(onClick = { CrashLog.clear(); crash = null }) { Text("Bỏ qua") } },
            containerColor = Palette.Surface,
        )
    }

    val micError by MicStream.error.collectAsStateWithLifecycle()
    // A mic error belongs to the screen that hit it; leaving that screen dismisses it.
    LaunchedEffect(tab, tool) { MicStream.clearError() }

    BackHandler(enabled = tool != null) { tool = null }
    BackHandler(enabled = tool == null && tab != Tab.TODAY) { tab = Tab.TODAY }

    val select: (Tab) -> Unit = { tab = it; tool = null }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= WIDE
        Scaffold(
            containerColor = Palette.Ink,
            bottomBar = { if (!wide) BottomTabs(tab, select) },
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                if (wide) SideTabs(tab, select)
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    Box(
                        Modifier
                            .align(Alignment.TopCenter)
                            .then(if (wide) Modifier.widthIn(max = CONTENT_MAX) else Modifier)
                            .fillMaxSize(),
                    ) {
                        val openTool: (Tool, String) -> Unit = { t, chord -> tab = Tab.TOOLS; tool = t; toolChord = chord }
                        when (tab) {
                            Tab.TODAY -> TodayTab(onMicSetup = { openTool(Tool.MIC_SETUP, toolChord) })
                            Tab.TOOLS -> when (tool) {
                                Tool.MIC_SETUP -> MicSetupScreen(onBack = { tool = null })
                                Tool.TUNER -> TunerScreen(onBack = { tool = null })
                                Tool.METRONOME -> MetronomeScreen(onBack = { tool = null })
                                Tool.NOTES -> NotesScreen(onBack = { tool = null })
                                Tool.RHYTHM -> RhythmScreen(onBack = { tool = null })
                                Tool.RECORD -> RecordScreen(onBack = { tool = null })
                                Tool.CHORD_CHECK -> ChordCheckScreen(toolChord, onBack = { tool = null })
                                Tool.CHANGES -> {
                                    val a = Chords[toolChord]
                                    ChangesScreen(a.name, suggestPartner(a).name, onBack = { tool = null })
                                }
                                null -> ToolsScreen(onOpen = { openTool(it, toolChord) })
                            }
                            Tab.CHORDS -> ChordsTab()
                            Tab.SONGS -> SongsTab()
                            Tab.PROGRESS -> ProgressTab()
                        }
                    }
                    micError?.let { msg -> MicErrorBanner(msg, Modifier.align(Alignment.BottomCenter)) }
                }
            }
        }
    }
}

@Composable
private fun BottomTabs(tab: Tab, onSelect: (Tab) -> Unit) {
    NavigationBar(containerColor = Palette.Surface) {
        Tab.entries.forEach { t ->
            NavigationBarItem(
                selected = t == tab,
                onClick = { onSelect(t) },
                icon = { Icon(t.icon, contentDescription = null) },
                label = { Text(t.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Palette.OnAccent,
                    indicatorColor = Palette.Accent,
                    selectedTextColor = Palette.Accent,
                    unselectedIconColor = Palette.Muted,
                    unselectedTextColor = Palette.Muted,
                ),
            )
        }
    }
}

@Composable
private fun SideTabs(tab: Tab, onSelect: (Tab) -> Unit) {
    NavigationRail(containerColor = Palette.Surface, modifier = Modifier.fillMaxHeight()) {
        Tab.entries.forEach { t ->
            NavigationRailItem(
                selected = t == tab,
                onClick = { onSelect(t) },
                icon = { Icon(t.icon, contentDescription = null) },
                label = { Text(t.label) },
                modifier = Modifier.padding(vertical = 4.dp),
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = Palette.OnAccent,
                    indicatorColor = Palette.Accent,
                    selectedTextColor = Palette.Accent,
                    unselectedIconColor = Palette.Muted,
                    unselectedTextColor = Palette.Muted,
                ),
            )
        }
    }
}

@Composable
private fun MicErrorBanner(msg: String, modifier: Modifier) {
    Row(
        modifier
            .padding(12.dp)
            .background(Palette.SurfaceHigh, RoundedCornerShape(12.dp))
            .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(msg, style = MaterialTheme.typography.bodyMedium, color = Palette.Wrong, modifier = Modifier.weight(1f))
        TextButton(onClick = MicStream::clearError) { Text("Đóng") }
    }
}
