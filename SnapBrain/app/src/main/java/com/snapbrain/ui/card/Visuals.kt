package com.snapbrain.ui.card

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snapbrain.data.content.Visual
import com.snapbrain.data.content.VisualType
import com.snapbrain.ui.theme.Palette

@Composable
fun CardVisual(visual: Visual, modifier: Modifier = Modifier) {
    when (visual.type) {
        VisualType.KEYS -> Keycaps(visual.items, modifier)
        VisualType.EMOJI -> Text(
            visual.items.firstOrNull().orEmpty(),
            fontSize = 72.sp,
            textAlign = TextAlign.Center,
            modifier = modifier.fillMaxWidth(),
        )
        VisualType.QUOTE -> Quote(visual.items.firstOrNull().orEmpty(), modifier)
        VisualType.COMPARE -> Compare(visual.items.getOrElse(0) { "" }, visual.items.getOrElse(1) { "" }, modifier)
    }
}

@Composable
private fun Keycaps(keys: List<String>, modifier: Modifier) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        keys.forEachIndexed { i, key ->
            if (i > 0) Text("+", color = Palette.Muted, fontSize = 22.sp, modifier = Modifier.padding(horizontal = 10.dp))
            Box(
                Modifier
                    .background(Palette.SurfaceHigh, RoundedCornerShape(12.dp))
                    .border(1.dp, Palette.Hairline, RoundedCornerShape(12.dp))
                    .padding(horizontal = 18.dp, vertical = 12.dp),
            ) {
                Text(key, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, color = Palette.Text)
            }
        }
    }
}

@Composable
private fun Quote(text: String, modifier: Modifier) {
    Row(modifier.fillMaxWidth()) {
        Box(Modifier.width(3.dp).height(64.dp).background(Palette.Accent))
        Text(
            text,
            style = MaterialTheme.typography.headlineSmall,
            fontStyle = FontStyle.Italic,
            color = Palette.Text,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

@Composable
private fun Compare(dont: String, doThis: String, modifier: Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CompareRow("✕", dont, Palette.Wrong)
        CompareRow("✓", doThis, Palette.Right)
    }
}

@Composable
private fun CompareRow(mark: String, text: String, tint: androidx.compose.ui.graphics.Color) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(tint.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
            .border(1.dp, tint.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(mark, color = tint, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(text, color = Palette.Text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
    }
}
