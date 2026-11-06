package com.snapbrain.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.snapbrain.ui.home.Panel
import com.snapbrain.ui.settings.AppEntry
import com.snapbrain.ui.settings.AppPicker
import com.snapbrain.ui.settings.SetupButton
import com.snapbrain.ui.theme.Palette

const val ONBOARDING_STEPS = 5

class OnboardingActions(
    val next: () -> Unit,
    val finish: () -> Unit,
    val toggleApp: (String, Boolean) -> Unit,
    val openUsageAccess: () -> Unit,
    val openOverlay: () -> Unit,
    val openAutostart: () -> Unit,
    val openPopups: () -> Unit,
    val openBattery: () -> Unit,
    val requestNotifications: () -> Unit,
)

@Composable
fun OnboardingContent(
    step: Int,
    blocked: Set<String>,
    usageOn: Boolean,
    overlayOn: Boolean,
    notificationsOn: Boolean,
    actions: OnboardingActions,
    modifier: Modifier = Modifier,
    appsPreview: List<AppEntry>? = null,
) {
    Column(modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(ONBOARDING_STEPS) { i ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(3.dp)
                        .background(if (i <= step) Palette.Accent else Palette.Hairline, RoundedCornerShape(2.dp)),
                )
            }
        }
        Spacer(Modifier.height(28.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when (step) {
                0 -> Welcome()
                1 -> {
                    Title("Ứng dụng nào hay khiến bạn lướt vô thức?")
                    Body("Khi bạn mở những app này, SnapBrain sẽ mời bạn học nhanh 1 thẻ trước. Đổi lại bất cứ lúc nào trong Cài đặt.")
                    AppPicker(blocked = blocked, onToggle = actions.toggleApp, preview = appsPreview)
                }
                2 -> {
                    Title("Bật bộ chặn")
                    Body("SnapBrain cần 2 quyền để biết khi bạn mở TikTok, Facebook… và hiện thẻ kiến thức lên trên.")
                    PermissionStep(
                        number = 1,
                        title = "Truy cập dữ liệu sử dụng",
                        hint = "Tìm SnapBrain trong danh sách → Bật",
                        granted = usageOn,
                        onOpen = actions.openUsageAccess,
                    )
                    PermissionStep(
                        number = 2,
                        title = "Hiển thị trên ứng dụng khác",
                        hint = "Bật \"Cho phép hiển thị trên ứng dụng khác\"",
                        granted = overlayOn,
                        onOpen = actions.openOverlay,
                    )
                    Panel {
                        Text("SnapBrain cam kết", style = MaterialTheme.typography.titleMedium, color = Palette.Text)
                        Spacer(Modifier.height(6.dp))
                        Body("• Chỉ biết tên ứng dụng đang mở\n• Không đọc nội dung màn hình, tin nhắn hay mật khẩu\n• Không gửi dữ liệu đi đâu, mọi thứ nằm trên máy")
                    }
                }
                3 -> {
                    Title("Giữ bộ chặn luôn chạy")
                    Body("HyperOS/MIUI hay tắt app chạy nền để tiết kiệm pin. Bật cả ba mục để bộ chặn không bị tắt giữa chừng.")
                    SetupButton("1. Tự khởi chạy (Autostart): Bật", actions.openAutostart)
                    SetupButton("2. Hiện cửa sổ bật lên khi chạy nền: Cho phép", actions.openPopups)
                    SetupButton("3. Tiết kiệm pin: Không giới hạn", actions.openBattery)
                    Body("Mẹo: trong màn hình đa nhiệm, nhấn giữ SnapBrain và chọn biểu tượng ổ khóa.")
                }
                else -> {
                    Title("Nhắc giữ chuỗi")
                    Body("SnapBrain nhắc bạn buổi tối nếu hôm nay chưa học, và báo khi bộ chặn bị tắt.")
                    Status(notificationsOn, "Đã cho phép thông báo", "Chưa cho phép thông báo")
                    if (!notificationsOn) {
                        Button(onClick = actions.requestNotifications, shape = RoundedCornerShape(14.dp)) { Text("Cho phép thông báo") }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        val last = step == ONBOARDING_STEPS - 1
        Button(
            onClick = if (last) actions.finish else actions.next,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(18.dp),
        ) {
            Text(
                when {
                    step == 0 -> "Bắt đầu"
                    last -> "Xong"
                    else -> "Tiếp tục"
                },
                style = MaterialTheme.typography.titleMedium,
            )
        }
        if (step in 2..3) {
            TextButton(onClick = actions.next, modifier = Modifier.fillMaxWidth()) {
                Text("Để sau", color = Palette.Muted)
            }
        }
    }
}

@Composable
private fun Welcome() {
    Spacer(Modifier.height(40.dp))
    Text("🧠", style = MaterialTheme.typography.displaySmall)
    Text("SnapBrain", style = MaterialTheme.typography.displaySmall, color = Palette.Text)
    Text("Từ cuộn vô thức đến cuộn trưởng thành", style = MaterialTheme.typography.titleMedium, color = Palette.Accent)
    Spacer(Modifier.height(12.dp))
    Body("Mỗi lần bạn mở TikTok hay Facebook theo thói quen, SnapBrain mời bạn học một mẩu kiến thức 30 giây trước.")
    Body("Trả lời đúng để nhận điểm. Đổi điểm lấy thời gian lướt. Không cấm đoán, bạn luôn có lối thoát.")
}

@Composable
private fun PermissionStep(number: Int, title: String, hint: String, granted: Boolean, onOpen: () -> Unit) {
    Panel(border = if (granted) Palette.Right.copy(alpha = 0.5f) else Palette.Hairline) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("$number. $title", style = MaterialTheme.typography.titleMedium, color = Palette.Text)
                Text(
                    if (granted) "✓ Đã cấp" else hint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (granted) Palette.Right else Palette.Muted,
                )
            }
            if (!granted) {
                Button(onClick = onOpen, shape = RoundedCornerShape(14.dp)) { Text("Mở") }
            }
        }
    }
}

@Composable
private fun Title(text: String) {
    Text(text, style = MaterialTheme.typography.headlineMedium, color = Palette.Text)
}

@Composable
private fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = Palette.Muted)
}

@Composable
private fun Status(ok: Boolean, yes: String, no: String) {
    Text(
        if (ok) "✓  $yes" else "○  $no",
        style = MaterialTheme.typography.titleMedium,
        color = if (ok) Palette.Right else Palette.Muted,
    )
}

