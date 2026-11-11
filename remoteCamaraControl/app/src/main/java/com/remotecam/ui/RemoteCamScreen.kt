package com.remotecam.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remotecam.camera.CaptureMode
import com.remotecam.camera.EngineState
import com.remotecam.remote.LogEntry

private val Ink = Color(0xFF0E0E0E)
private val Panel = Color(0xFF161616)
private val Ivory = Color(0xFFF4F1EA)
private val Muted = Color(0xFF8A877F)
private val Hairline = Color(0xFF2A2A2A)
private val Gold = Color(0xFFC9A14A)
private val Live = Color(0xFF5BBF73)

private val Rec = Color(0xFFE5484D)

data class CameraUiState(
    val photos: Int = 0,
    val videos: Int = 0,
    val backCamera: Boolean = true,
    val zoom: Float = 1f,
    val countdown: Int? = null,
    val flashTick: Int = 0,
    val lastLatencyMs: Long? = null,
    val mode: CaptureMode = CaptureMode.PHOTO,
    val modes: List<CaptureMode> = listOf(CaptureMode.PHOTO, CaptureMode.VIDEO),
    val recordingSeconds: Long? = null,
    /** e.g. "RAW + JPEG · 50.3MP" or "4K 30fps HLG 10-bit". */
    val detail: String = "",
)

data class ScreenState(
    val camera: CameraUiState = CameraUiState(),
    val hasCameraPermission: Boolean = true,
    val sessionRunning: Boolean = false,
    val keepPlaying: Boolean = true,
    val remoteVolume: Boolean = false,
    val silentAudio: Boolean = true,
    val serverUrls: List<String> = emptyList(),
    val viewers: Int = 0,
    val log: List<LogEntry> = emptyList(),
)

class ScreenActions(
    val onShutter: () -> Unit = {},
    val onToggleSession: () -> Unit = {},
    val onKeepPlaying: (Boolean) -> Unit = {},
    val onRemoteVolume: (Boolean) -> Unit = {},
    val onSilentAudio: (Boolean) -> Unit = {},
    val onCopyLog: () -> Unit = {},
    val onClearLog: () -> Unit = {},
    val onMode: (CaptureMode) -> Unit = {},
)

@Composable
fun RemoteCamScreen(
    state: ScreenState,
    actions: ScreenActions,
    preview: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .safeDrawingPadding(),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1.15f)
                .background(Color.Black),
        ) {
            if (state.hasCameraPermission) {
                preview()
            } else {
                Text(
                    "Chưa có quyền camera",
                    color = Muted,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            FlashOverlay(state.camera.flashTick)
            SessionBadge(state.sessionRunning, Modifier.align(Alignment.TopStart).padding(12.dp))
            Text(
                buildString {
                    if (state.camera.detail.isNotEmpty()) append("${state.camera.detail} · ")
                    append(if (state.camera.mode == CaptureMode.VIDEO) "${state.camera.videos} video · " else "${state.camera.photos} ảnh · ")
                    append(if (state.camera.backCamera) "sau" else "trước")
                    append(" · %.1fx".format(state.camera.zoom))
                    state.camera.lastLatencyMs?.let { append(" · ${it} ms") }
                },
                color = Ivory,
                fontSize = 13.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .background(Color(0x99000000), RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
            state.camera.countdown?.let {
                Text(
                    it.toString(),
                    color = Ivory,
                    fontSize = 96.sp,
                    fontWeight = FontWeight.Light,
                    fontFamily = FontFamily.Serif,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            state.camera.recordingSeconds?.let {
                Text(
                    "● REC ${EngineState.clock(it)}",
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 48.dp)
                        .background(Rec, RoundedCornerShape(50))
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            Column(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier
                        .background(Color(0x99000000), RoundedCornerShape(50))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    state.camera.modes.forEach { mode ->
                        Text(
                            mode.label,
                            color = if (mode == state.camera.mode) Gold else Ivory,
                            fontSize = 13.sp,
                            fontWeight = if (mode == state.camera.mode) FontWeight.Medium else FontWeight.Normal,
                            modifier = Modifier.clickable { actions.onMode(mode) },
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                ShutterButton(
                    video = state.camera.mode == CaptureMode.VIDEO,
                    recording = state.camera.recordingSeconds != null,
                    onClick = actions.onShutter,
                )
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Panel)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Toggle("Giữ PLAYING", state.keepPlaying, actions.onKeepPlaying)
                Toggle("Volume → app", state.remoteVolume, actions.onRemoteVolume)
                Toggle("Âm thanh im lặng", state.silentAudio, actions.onSilentAudio)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(if (state.sessionRunning) "Tắt session" else "Bật session", accent = true, onClick = actions.onToggleSession)
                TextButton("Copy log", onClick = actions.onCopyLog)
                TextButton("Xoá log", onClick = actions.onClearLog)
            }
            Spacer(Modifier.height(10.dp))
            ViewerLinks(state.sessionRunning, state.serverUrls, state.viewers)
            Spacer(Modifier.height(6.dp))
            Text(
                "Fit 3: mục Nhạc → Play/Pause = chụp / bắt đầu-dừng quay · Next = đổi camera · Prev = hẹn giờ · Volume = zoom",
                color = Muted,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = Hairline)
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                items(state.log) { entry ->
                    Row(Modifier.padding(vertical = 3.dp)) {
                        Text(entry.time, color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.width(8.dp))
                        Text(entry.text, color = Ivory, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

@Composable
private fun ViewerLinks(running: Boolean, urls: List<String>, viewers: Int) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Hairline, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            if (viewers > 0) "Xem trên tablet / PC · $viewers đang xem" else "Xem trên tablet / PC",
            color = Muted,
            fontSize = 12.sp,
        )
        when {
            !running -> Text("Bật session để mở trang xem", color = Muted, fontSize = 13.sp)
            urls.isEmpty() -> Text(
                "Không cần internet: bật Hotspot trên điện thoại, cho tablet/PC kết nối vào, địa chỉ sẽ hiện ở đây",
                color = Ivory,
                fontSize = 13.sp,
                lineHeight = 17.sp,
            )
            else -> SelectionContainer {
                Column {
                    urls.forEach { Text(it, color = Gold, fontSize = 14.sp, fontFamily = FontFamily.Monospace) }
                }
            }
        }
    }
}

@Composable
private fun FlashOverlay(tick: Int) {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(tick) {
        if (tick > 0) {
            alpha.snapTo(0.7f)
            alpha.animateTo(0f, tween(250))
        }
    }
    Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = alpha.value)))
}

@Composable
private fun SessionBadge(running: Boolean, modifier: Modifier) {
    Row(
        modifier
            .background(Color(0x99000000), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(if (running) Live else Muted, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(if (running) "Đang nghe Fit 3" else "Session tắt", color = Ivory, fontSize = 13.sp)
    }
}

@Composable
private fun ShutterButton(video: Boolean, recording: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(68.dp)
            .border(3.dp, Ivory, CircleShape)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when {
            recording -> Box(Modifier.size(26.dp).background(Rec, RoundedCornerShape(5.dp)))
            video -> Box(Modifier.size(54.dp).background(Rec, CircleShape))
            else -> Box(Modifier.size(54.dp).background(Ivory, CircleShape))
        }
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Text(
        label,
        color = if (checked) Ink else Muted,
        fontSize = 12.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (checked) Gold else Color.Transparent)
            .border(1.dp, if (checked) Gold else Hairline, RoundedCornerShape(50))
            .clickable { onChange(!checked) }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
private fun TextButton(label: String, accent: Boolean = false, onClick: () -> Unit) {
    Text(
        label,
        color = if (accent) Gold else Ivory,
        fontSize = 13.sp,
        fontWeight = if (accent) FontWeight.Medium else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, if (accent) Gold else Hairline, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}
