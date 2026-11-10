package com.ytsync.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ytsync.data.Settings
import com.ytsync.data.UploadItem
import com.ytsync.data.UploadState
import com.ytsync.youtube.VideoMetadata
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal val Bg = Color(0xFF101010)
internal val Card = Color(0xFF1A1A1A)
internal val Line = Color(0xFF2A2A2A)
internal val Ink = Color(0xFFEDEDED)
internal val Muted = Color(0xFF8C8C8C)
internal val Accent = Color(0xFFE53935)
internal val Ok = Color(0xFF66BB6A)

data class HomeState(
    val settings: Settings = Settings(),
    val items: List<UploadItem> = emptyList(),
    val hasVideoPermission: Boolean = false,
    val hasNotificationPermission: Boolean = false,
    val batteryUnrestricted: Boolean = false,
    val isXiaomi: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
)

interface HomeActions {
    fun connect()
    fun requestVideoPermission()
    fun requestNotificationPermission()
    fun fixBattery()
    fun openAutostart()
    fun setEnabled(on: Boolean)
    fun setWifiOnly(on: Boolean)
    fun setChargingOnly(on: Boolean)
    fun setAgeRestrict(on: Boolean)
    fun setTitleTemplate(text: String)
    fun scanNow()
    fun backfill(days: Int?)
    fun retry(item: UploadItem)
    fun retryAllFailed()
    fun skip(item: UploadItem)
    fun openStudio(item: UploadItem)
    fun dismissMessage()
}

@Composable
fun YtTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = Card, onSurface = Ink, onBackground = Ink),
    content = content,
)

@Composable
fun HomeScreen(state: HomeState, actions: HomeActions, modifier: Modifier = Modifier) {
    var backfillOpen by remember { mutableStateOf(false) }
    val s = state.settings
    val ready = s.connected && state.hasVideoPermission

    LazyColumn(
        modifier = modifier.fillMaxSize().background(Bg),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 48.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text("Youtube Sync", color = Ink, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text("Video quay bằng camera → kênh YouTube của bạn", color = Muted, fontSize = 14.sp)
        }

        state.message?.let { msg ->
            item {
                Section {
                    // Selectable so the package / SHA-1 can be copied into Cloud Console.
                    SelectionContainer { Text(msg, color = Ink, fontSize = 14.sp) }
                    TextButton(onClick = actions::dismissMessage) { Text("Đóng", color = Accent) }
                }
            }
        }

        item {
            Section(title = "Thiết lập") {
                SetupRow(
                    done = s.connected,
                    title = if (s.connected) "Đã kết nối YouTube" else "Kết nối kênh YouTube",
                    detail = if (s.connected) s.channelTitle.ifBlank { "Quyền tải video lên" } else "Đăng nhập Google, cho phép tải video lên",
                    action = if (s.connected) null else "Kết nối",
                    onAction = actions::connect,
                )
                Hairline()
                SetupRow(
                    done = state.hasVideoPermission,
                    title = "Quyền đọc video",
                    detail = "Chọn \"Cho phép tất cả\" để thấy video mới",
                    action = "Cho phép",
                    onAction = actions::requestVideoPermission,
                )
                Hairline()
                SetupRow(
                    done = state.hasNotificationPermission,
                    title = "Thông báo",
                    detail = "Tiến độ tải lên và khi cần đăng nhập lại",
                    action = "Cho phép",
                    onAction = actions::requestNotificationPermission,
                )
                Hairline()
                SetupRow(
                    done = state.batteryUnrestricted,
                    title = "Chạy nền không giới hạn",
                    detail = "Để video dài tải lên khi tắt màn hình",
                    action = "Mở",
                    onAction = actions::fixBattery,
                )
                if (state.isXiaomi) {
                    Hairline()
                    SetupRow(
                        done = null,
                        title = "Tự khởi chạy (HyperOS)",
                        detail = "Bật cho Youtube Sync để chạy sau khi khởi động lại",
                        action = "Mở",
                        onAction = actions::openAutostart,
                    )
                }
            }
        }

        item {
            Section(title = "Tự động tải lên") {
                ToggleRow("Bật tự động tải lên", if (ready) "Video mới trong DCIM/Camera" else "Hoàn tất thiết lập ở trên trước", s.enabled, enabled = ready || s.enabled, onChange = actions::setEnabled)
                Hairline()
                ToggleRow("Chỉ khi có Wi‑Fi", "Không dùng dữ liệu di động", s.wifiOnly, onChange = actions::setWifiOnly)
                Hairline()
                ToggleRow("Chỉ khi đang sạc", null, s.chargingOnly, onChange = actions::setChargingOnly)
                Hairline()
                ToggleRow("Giới hạn độ tuổi 18+", "Gửi yêu cầu 18+ cho YouTube và kiểm tra lại sau khi tải", s.ageRestrict, onChange = actions::setAgeRestrict)
                Hairline()
                Text("Mỗi video: Riêng tư · Không dành cho trẻ em · Không cho nhúng", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 10.dp))
                TitleTemplateField(s.titleTemplate, actions::setTitleTemplate)
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { backfillOpen = true }, enabled = s.enabled) { Text("Thêm video cũ…", color = if (s.enabled) Ink else Muted) }
                    TextButton(onClick = actions::scanNow, enabled = s.enabled && !state.busy) { Text("Quét ngay", color = if (s.enabled) Accent else Muted) }
                }
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Hàng đợi", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                if (state.items.any { it.state == UploadState.FAILED }) {
                    TextButton(onClick = actions::retryAllFailed) { Text("Thử lại tất cả lỗi", color = Accent) }
                }
            }
        }
        if (state.items.isEmpty()) {
            item { Text("Chưa có video nào. Quay một video bằng camera để bắt đầu.", color = Muted, fontSize = 14.sp) }
        }
        items(state.items, key = { it.mediaId }) { item -> QueueRow(item, actions) }
    }

    if (backfillOpen) {
        AlertDialog(
            onDismissRequest = { backfillOpen = false },
            title = { Text("Thêm video cũ vào hàng đợi") },
            text = { Text("Tải lên cả video camera đã quay trước đây. Mỗi ngày YouTube cho tối đa khoảng 100 lượt tải lên qua API.") },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { backfillOpen = false; actions.backfill(1) }) { Text("24 giờ qua") }
                    TextButton(onClick = { backfillOpen = false; actions.backfill(7) }) { Text("7 ngày qua") }
                    TextButton(onClick = { backfillOpen = false; actions.backfill(30) }) { Text("30 ngày qua") }
                    TextButton(onClick = { backfillOpen = false; actions.backfill(null) }) { Text("Tất cả") }
                }
            },
            dismissButton = { TextButton(onClick = { backfillOpen = false }) { Text("Huỷ") } },
        )
    }
}

/** Title template with tap-to-insert date tokens and a live preview using the current time. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TitleTemplateField(
    saved: String,
    onChange: (String) -> Unit,
    tokens: List<String> = VideoMetadata.TOKENS,
    showPreview: Boolean = true,
) {
    // Local copy so typing isn't fighting the async DataStore round-trip (cursor jumps).
    var local by remember { mutableStateOf<String?>(null) }
    val text = local ?: saved
    fun set(v: String) { local = v; onChange(v) }

    OutlinedTextField(
        value = text,
        onValueChange = { set(it.take(100)) },
        singleLine = true,
        label = { Text("Mẫu tiêu đề video") },
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, unfocusedBorderColor = Line, focusedTextColor = Ink, unfocusedTextColor = Ink, focusedLabelColor = Accent, unfocusedLabelColor = Muted),
        modifier = Modifier.fillMaxWidth(),
    )
    if (showPreview) {
        val preview = VideoMetadata.title(text, previewTime, "VID_20261109_175130.mp4")
        Text("Ví dụ: $preview", color = Ink, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
    }
    Text("Ngày giờ = lúc QUAY video. Chạm để chèn: YYYYMMDD, giờ HH, phút mm, tên file…", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
        tokens.forEach { token ->
            Text(
                token, color = Ink, fontSize = 12.sp,
                modifier = Modifier.clip(RoundedCornerShape(50)).border(1.dp, Line, RoundedCornerShape(50))
                    .clickable { set((text.trimEnd() + " " + token).trimStart()) }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        Text(
            "Mặc định", color = Accent, fontSize = 12.sp,
            modifier = Modifier.clip(RoundedCornerShape(50)).border(1.dp, Accent.copy(alpha = 0.6f), RoundedCornerShape(50))
                .clickable { set(VideoMetadata.DEFAULT_TEMPLATE) }
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** Fixed sample time (09/11/2026 17:51:30 local) so the preview matches the README example. */
private val previewTime: Long = java.time.LocalDateTime.of(2026, 11, 9, 17, 51, 30)
    .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

@Composable
internal fun Section(title: String? = null, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Card)
            .border(1.dp, Line, RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        if (title != null) Text(title.uppercase(), color = Muted, fontSize = 11.sp, letterSpacing = 1.2.sp, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
        content()
    }
}

@Composable
internal fun Hairline() = HorizontalDivider(color = Line, thickness = 1.dp)

@Composable
private fun SetupRow(done: Boolean?, title: String, detail: String, action: String?, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(10.dp).clip(CircleShape).background(
                when (done) { true -> Ok; false -> Accent; null -> Muted },
            ),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Ink, fontSize = 15.sp)
            Text(detail, color = Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (done != true && action != null) {
            TextButton(onClick = onAction) { Text(action, color = Accent) }
        }
    }
}

@Composable
private fun ToggleRow(title: String, detail: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) Ink else Muted, fontSize = 15.sp)
            if (detail != null) Text(detail, color = Muted, fontSize = 12.sp)
        }
        Switch(
            checked = checked, onCheckedChange = onChange, enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = Accent, checkedThumbColor = Color.White, uncheckedTrackColor = Line, uncheckedBorderColor = Line, uncheckedThumbColor = Muted),
        )
    }
}

private val dateFmt = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())

internal fun mb(bytes: Long) = if (bytes >= 1_000_000_000) String.format(Locale.US, "%.1f GB", bytes / 1e9) else String.format(Locale.US, "%.0f MB", bytes / 1e6)

@Composable
private fun QueueRow(item: UploadItem, actions: HomeActions) {
    Section {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.title ?: item.titleOverride ?: item.displayName, color = Ink, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Quay ${dateFmt.format(Date(item.takenAt))} · ${mb(item.sizeBytes)}", color = Muted, fontSize = 12.sp)
            }
            StateChip(item)
        }
        if (item.state == UploadState.UPLOADING || (item.state == UploadState.QUEUED && item.bytesSent > 0)) {
            LinearProgressIndicator(
                progress = { if (item.sizeBytes > 0) (item.bytesSent.toFloat() / item.sizeBytes).coerceIn(0f, 1f) else 0f },
                color = Accent, trackColor = Line,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
        }
        item.error?.takeIf { item.state != UploadState.DONE }?.let {
            Text(it, color = Muted, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 4.dp))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            when (item.state) {
                UploadState.DONE -> {
                    if (item.ageRestricted != true) Text("18+ chưa xác nhận — đặt trong Studio", color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f).align(Alignment.CenterVertically))
                    TextButton(onClick = { actions.openStudio(item) }) { Text("Mở Studio", color = Accent) }
                }
                UploadState.FAILED, UploadState.SKIPPED -> TextButton(onClick = { actions.retry(item) }) { Text("Thử lại", color = Accent) }
                UploadState.QUEUED -> TextButton(onClick = { actions.skip(item) }) { Text("Bỏ qua", color = Muted) }
                UploadState.UPLOADING, UploadState.IDLE -> {}
            }
        }
    }
}

@Composable
private fun StateChip(item: UploadItem) {
    val (label, color) = when (item.state) {
        UploadState.QUEUED, UploadState.IDLE -> "Chờ" to Muted
        UploadState.UPLOADING -> "${if (item.sizeBytes > 0) item.bytesSent * 100 / item.sizeBytes else 0}%" to Accent
        UploadState.DONE -> (if (item.ageRestricted == true) "Riêng tư · 18+" else "Riêng tư") to Ok
        UploadState.FAILED -> "Lỗi" to Accent
        UploadState.SKIPPED -> "Bỏ qua" to Muted
    }
    Text(
        label, color = color, fontSize = 12.sp,
        modifier = Modifier.border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 3.dp),
    )
}
