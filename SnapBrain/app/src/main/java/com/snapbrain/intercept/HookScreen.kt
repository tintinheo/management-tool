package com.snapbrain.intercept

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.snapbrain.domain.Rules
import com.snapbrain.ui.theme.Palette

@Composable
fun HookScreen(
    appName: String,
    expired: Boolean,
    streak: Int,
    balance: Int,
    skipsLeft: Int,
    strict: Boolean,
    onLearn: () -> Unit,
    onTicket: () -> Unit,
    onSkip: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.background(Palette.Ink).padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("SNAPBRAIN", style = MaterialTheme.typography.labelMedium, color = Palette.Accent)
        Spacer(Modifier.weight(1f))
        Text(if (expired) "⏰" else "🧠", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(20.dp))
        Text(
            if (expired) "Hết giờ lướt $appName rồi!" else "Lại lướt vô thức nữa rồi!",
            style = MaterialTheme.typography.headlineMedium,
            color = Palette.Text,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            "Cho xin 30 giây nạp nhanh 1 mẹo, rồi lướt tiếp $appName ${Rules.PASS_MINUTES} phút. Chịu không?",
            style = MaterialTheme.typography.bodyLarge,
            color = Palette.Muted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill("🔥 $streak ngày")
            Pill("⭐ $balance điểm")
        }
        Spacer(Modifier.weight(1.2f))

        Button(
            onClick = onLearn,
            modifier = Modifier.fillMaxWidth().height(60.dp),
            shape = RoundedCornerShape(20.dp),
        ) { Text("Ok, nạp ngay", style = MaterialTheme.typography.titleMedium) }
        Spacer(Modifier.height(12.dp))
        if (balance >= Rules.POINTS_PER_TICKET) {
            OutlinedButton(
                onClick = onTicket,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(18.dp),
            ) {
                Text(
                    "Dùng ${Rules.POINTS_PER_TICKET} điểm · lướt ${Rules.TICKET_MINUTES} phút",
                    style = MaterialTheme.typography.labelLarge,
                    color = Palette.Text,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
        when {
            strict -> Unit
            skipsLeft > 0 -> TextButton(onClick = onSkip) {
                Text(
                    "Không, tôi bận việc thật (còn $skipsLeft/${Rules.SOFT_SKIPS_PER_DAY} hôm nay)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.Muted,
                )
            }
            else -> Text(
                "Hôm nay đã hết lượt bỏ qua",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.Muted,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }
        TextButton(onClick = onClose) {
            Text("Thôi, đóng $appName", style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
        }
    }
}

@Composable
private fun Pill(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = Palette.Text,
        modifier = Modifier
            .background(Palette.Surface, RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}
