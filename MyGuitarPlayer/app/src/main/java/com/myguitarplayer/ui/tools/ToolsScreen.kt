package com.myguitarplayer.ui.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.myguitarplayer.ui.common.Card
import com.myguitarplayer.ui.common.ScreenHeader
import com.myguitarplayer.ui.theme.Palette

enum class Tool(val title: String, val detail: String) {
    MIC_SETUP("Hiệu chỉnh micro", "Làm một lần trên máy mới: đo tiếng ồn phòng, thử quạt, chỉnh độ nhạy và độ chặt khi chấm."),
    TUNER("Lên dây", "Tuner tự nhận dây, kim chỉ lệch bao nhiêu cents. Làm trước mỗi buổi tập."),
    NOTES("Nốt trên cần đàn", "Chạm để nghe nốt ở từng dây, từng ngăn. Đố nốt: trả lời bằng cách chạm hoặc đánh trên đàn."),
    METRONOME("Nhịp & quạt", "Máy đếm nhịp chính xác kèm mẫu quạt ↓↑ chạy theo phách."),
    CHORD_CHECK("Kiểm tra hợp âm", "Bấm và quạt, micro báo đúng hay thiếu nốt nào."),
    CHANGES("Chuyển hợp âm 1 phút", "Đổi qua lại 2 hợp âm trong 60 giây, micro tự đếm, lưu kỷ lục."),
    RHYTHM("Chấm nhịp quạt", "Quạt theo click, micro đo từng cú sớm hay muộn bao nhiêu ms."),
    RECORD("Ghi âm & tự chấm", "Ghi lại khi đệm hát, nghe lại và chấm 5 tiêu chí."),
}

@Composable
fun ToolsScreen(onOpen: (Tool) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenHeader("Công cụ", "Dùng bất cứ lúc nào, ngoài giáo trình")
        Tool.entries.forEach { t ->
            Card(Modifier.clickable { onOpen(t) }) {
                Text(t.title, style = MaterialTheme.typography.headlineSmall)
                Text(t.detail, style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
            }
        }
    }
}
