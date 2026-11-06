package com.snapbrain.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.snapbrain.data.Settings
import com.snapbrain.data.content.Topic
import com.snapbrain.domain.Rules
import com.snapbrain.ui.home.Panel
import com.snapbrain.ui.theme.Palette

class SettingsActions(
    val update: ((Settings) -> Settings) -> Unit,
    val openUsageAccess: () -> Unit,
    val openOverlay: () -> Unit,
    val openAutostart: () -> Unit,
    val openPopups: () -> Unit,
    val openBattery: () -> Unit,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsContent(
    settings: Settings,
    usageOn: Boolean,
    overlayOn: Boolean,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
    appsPreview: List<AppEntry>? = null,
) {
    var showApps by remember { mutableStateOf(false) }
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Cài đặt", style = MaterialTheme.typography.headlineMedium, color = Palette.Text)

        Panel {
            SectionTitle("BỘ CHẶN")
            Text(
                if (usageOn && overlayOn) "● Đang hoạt động" else "● Đang tắt",
                color = if (usageOn && overlayOn) Palette.Right else Palette.Wrong,
                style = MaterialTheme.typography.titleMedium,
            )
            PermissionRow("Truy cập dữ liệu sử dụng", usageOn, actions.openUsageAccess)
            PermissionRow("Hiển thị trên ứng dụng khác", overlayOn, actions.openOverlay)
            Spacer(Modifier.height(8.dp))
            ToggleRow(
                title = "Chế độ nghiêm",
                subtitle = if (settings.strict) {
                    "Không có nút bỏ qua. Chỉ học 1 thẻ hoặc dùng vé."
                } else {
                    "Chế độ mềm: được bỏ qua ${Rules.SOFT_SKIPS_PER_DAY} lần mỗi ngày."
                },
                checked = settings.strict,
                onChange = { v -> actions.update { it.copy(strict = v) } },
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Ứng dụng bị chặn (${settings.blocked.size})",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Palette.Text,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { showApps = !showApps }) { Text(if (showApps) "Thu gọn" else "Chọn") }
            }
            if (showApps) {
                AppPicker(
                    blocked = settings.blocked,
                    onToggle = { pkg, on -> actions.update { it.copy(blocked = if (on) it.blocked + pkg else it.blocked - pkg) } },
                    preview = appsPreview,
                )
            }
        }

        Panel {
            SectionTitle("NỘI DUNG")
            Text("Chủ đề (để trống = tất cả)", style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Topic.entries.forEach { topic ->
                    val on = topic in settings.topics
                    FilterChip(
                        selected = on,
                        onClick = { actions.update { it.copy(topics = if (on) it.topics - topic else it.topics + topic) } },
                        label = { Text("${topic.emoji} ${topic.label}") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Palette.Accent.copy(alpha = 0.18f),
                            selectedLabelColor = Palette.Text,
                            labelColor = Palette.Muted,
                        ),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            ToggleRow(
                title = "Đọc to nội dung",
                subtitle = "Dùng giọng đọc có sẵn trên máy",
                checked = settings.tts,
                onChange = { v -> actions.update { it.copy(tts = v) } },
            )
        }

        Panel {
            SectionTitle("NHẮC GIỮ CHUỖI")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Nhắc lúc ${settings.saverHour}:00 nếu hôm nay chưa học",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Palette.Text,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { actions.update { it.copy(saverHour = (it.saverHour + 23) % 24) } }) { Text("−") }
                TextButton(onClick = { actions.update { it.copy(saverHour = (it.saverHour + 1) % 24) } }) { Text("+") }
            }
        }

        Panel {
            SectionTitle("GIỮ BỘ CHẶN CHẠY (XIAOMI)")
            Text(
                "HyperOS hay tắt app chạy nền. Bật cả ba mục dưới đây để bộ chặn không bị tắt.",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.Muted,
            )
            Spacer(Modifier.height(10.dp))
            SetupButton("Tự khởi chạy (Autostart)", actions.openAutostart)
            SetupButton("Hiện cửa sổ bật lên khi chạy nền", actions.openPopups)
            SetupButton("Pin: Không giới hạn", actions.openBattery)
        }

        Panel {
            SectionTitle("QUYỀN RIÊNG TƯ")
            Text(
                "SnapBrain dùng quyền \"Truy cập dữ liệu sử dụng\" chỉ để biết ứng dụng nào đang mở, và quyền " +
                    "\"Hiển thị trên ứng dụng khác\" để hiện thẻ kiến thức. Nó không đọc nội dung màn hình, " +
                    "không ghi lại thao tác và không gửi dữ liệu đi đâu. Mọi thứ nằm trên máy của bạn.",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.Muted,
            )
        }
    }
}

@Composable
private fun PermissionRow(title: String, granted: Boolean, onOpen: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "${if (granted) "✓" else "○"}  $title",
            style = MaterialTheme.typography.bodyLarge,
            color = if (granted) Palette.Text else Palette.Muted,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onOpen) { Text(if (granted) "Xem" else "Bật") }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = Palette.Muted, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Palette.Text)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Palette.Accent, checkedThumbColor = Palette.OnAccent),
        )
    }
}

@Composable
fun SetupButton(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        shape = RoundedCornerShape(14.dp),
    ) { Text(label, color = Palette.Text) }
}
