package com.snapbrain.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.snapbrain.data.DayStats
import com.snapbrain.domain.Rules
import com.snapbrain.domain.Streak
import com.snapbrain.ui.theme.Palette
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class HomeState(
    val streak: Streak.Status,
    val balance: Int,
    val freeUntil: Long,
    val freeIsTicket: Boolean,
    val now: Long,
    val guardOn: Boolean,
    val today: DayStats,
    val allTime: DayStats,
    val mastered: Int,
    val totalCards: Int,
)

@Composable
fun HomeContent(
    state: HomeState,
    onEnableGuard: () -> Unit,
    onOpenFeed: () -> Unit,
    onSaver: () -> Unit,
    onBuyTicket: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("SnapBrain", style = MaterialTheme.typography.headlineMedium, color = Palette.Text)
        Text("Từ cuộn vô thức đến cuộn trưởng thành", style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
        Spacer(Modifier.height(4.dp))

        if (!state.guardOn) {
            Panel(border = Palette.Wrong.copy(alpha = 0.5f)) {
                Text("Bộ chặn đang tắt", style = MaterialTheme.typography.titleMedium, color = Palette.Text)
                Text(
                    "SnapBrain chưa thể chặn TikTok/Facebook. Cấp 2 quyền trong Cài đặt để bắt đầu.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.Muted,
                )
                Spacer(Modifier.height(10.dp))
                Button(onClick = onEnableGuard, shape = RoundedCornerShape(14.dp)) { Text("Bật bộ chặn") }
            }
        }

        val s = state.streak
        Panel {
            Row {
                Column(Modifier.weight(1f)) {
                    Text("CHUỖI NGÀY", style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
                    Text("🔥 ${s.current}", style = MaterialTheme.typography.displaySmall, color = Palette.Text)
                }
                Column(Modifier.weight(1f)) {
                    Text("ĐIỂM TRI THỨC", style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
                    Text("⭐ ${state.balance}", style = MaterialTheme.typography.displaySmall, color = Palette.Text)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    s.repairableDay != null -> "Hôm qua bạn lỡ mất chuỗi ${s.brokenLength} ngày."
                    s.studiedToday -> "Hôm nay đã giữ chuỗi. Giỏi lắm!"
                    s.current > 0 -> "Học 1 thẻ hôm nay để giữ chuỗi."
                    else -> "Học 1 thẻ để bắt đầu chuỗi mới."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (s.repairableDay != null) Palette.Wrong else Palette.Muted,
            )
            if (s.repairableDay != null) {
                Spacer(Modifier.height(10.dp))
                Button(onClick = onSaver, shape = RoundedCornerShape(14.dp)) {
                    Text("Cứu chuỗi: đúng ${Rules.SAVER_STREAK} câu liên tiếp")
                }
            }
        }

        Panel {
            Text("VÉ LƯỚT MẠNG", style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
            Spacer(Modifier.height(6.dp))
            if (state.freeUntil > state.now) {
                Text(
                    "${if (state.freeIsTicket) "Đang dùng vé" else "Đang lướt tự do"} tới ${clock(state.freeUntil)}",
                    style = MaterialTheme.typography.titleMedium,
                    color = Palette.Accent,
                )
                Spacer(Modifier.height(6.dp))
            }
            Text(
                "${Rules.POINTS_PER_TICKET} điểm = ${Rules.TICKET_MINUTES} phút lướt mạng xã hội không bị chặn.",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.Muted,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = onBuyTicket,
                enabled = state.balance >= Rules.POINTS_PER_TICKET,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.Text),
            ) { Text("Đổi ${Rules.POINTS_PER_TICKET} điểm → ${Rules.TICKET_MINUTES} phút") }
        }

        Panel {
            Text("HÔM NAY", style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
            Spacer(Modifier.height(8.dp))
            Row {
                Stat("${state.today.intercepted}", "lần bị chặn", Modifier.weight(1f))
                Stat("${state.today.learned}", "lần học thay", Modifier.weight(1f), Palette.Right)
                Stat("${state.today.closed}", "lần bỏ lướt", Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Tổng cộng: bẻ hướng ${state.allTime.learned + state.allTime.closed}/${state.allTime.intercepted} lần · đã thuộc ${state.mastered}/${state.totalCards} thẻ",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.Muted,
            )
        }

        Spacer(Modifier.height(4.dp))
        Button(
            onClick = onOpenFeed,
            modifier = Modifier.fillMaxWidth().height(58.dp),
            shape = RoundedCornerShape(18.dp),
        ) { Text("Lướt tri thức", style = MaterialTheme.typography.titleMedium) }
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier, color: Color = Palette.Text) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.headlineMedium, color = color)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
    }
}

@Composable
fun Panel(
    modifier: Modifier = Modifier,
    border: Color = Palette.Hairline,
    content: @Composable () -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Palette.Surface, RoundedCornerShape(22.dp))
            .border(1.dp, border, RoundedCornerShape(22.dp))
            .padding(18.dp),
    ) { content() }
}

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")

private fun clock(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(timeFormat)
