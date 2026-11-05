package com.audiohub.ui.settings

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.audiohub.system.DiagnosticsLog
import com.audiohub.ui.components.ScreenHeader
import com.audiohub.ui.library.DetailTopBar
import com.audiohub.ui.library.LocalTrackActions

/** Shows the on-device log and lets the user share it (e.g. via Zalo / email) when something goes wrong. */
@Composable
fun DiagnosticsScreen() {
    val context = LocalContext.current
    val actions = LocalTrackActions.current
    var text by remember { mutableStateOf(DiagnosticsLog.read()) }
    Column(Modifier.fillMaxSize()) {
        DetailTopBar("", onBack = actions::back)
        ScreenHeader("Diagnostics", subtitle = "Share this when playback misbehaves")
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    val send = Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, "My Audio Hub diagnostics")
                        .putExtra(Intent.EXTRA_TEXT, text.takeLast(90_000))
                    context.startActivity(Intent.createChooser(send, "Share diagnostics"))
                },
                enabled = text.isNotBlank(),
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.onSurface,
                    contentColor = MaterialTheme.colorScheme.surface,
                ),
                modifier = Modifier.weight(1f),
            ) { Text("Share") }
            OutlinedButton(
                onClick = { DiagnosticsLog.clear(); text = DiagnosticsLog.read() },
                shape = MaterialTheme.shapes.medium,
                border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.weight(1f),
            ) { Text("Clear", color = MaterialTheme.colorScheme.onSurface) }
        }
        Text(
            text.ifBlank { "Nothing logged yet." },
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState(Int.MAX_VALUE))
                .horizontalScroll(rememberScrollState())
                .padding(20.dp),
        )
    }
}
