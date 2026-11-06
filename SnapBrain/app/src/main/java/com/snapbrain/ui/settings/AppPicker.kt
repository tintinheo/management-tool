package com.snapbrain.ui.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.snapbrain.data.SuggestedApps
import com.snapbrain.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppEntry(val pkg: String, val label: String, val installed: Boolean, val suggested: Boolean)

/** Installed suggested apps first, then other installed apps (A→Z); uninstalled suggestions are skipped. */
fun loadApps(context: Context): List<AppEntry> {
    val pm = context.packageManager
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val installed = pm.queryIntentActivities(launcher, 0)
        .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
        .filter { it.first != context.packageName }
        .distinctBy { it.first }
        .toMap()
    val suggested = SuggestedApps.ALL.keys.filter { it in installed }
        .map { AppEntry(it, installed.getValue(it), installed = true, suggested = true) }
    val others = installed.filterKeys { it !in SuggestedApps.ALL }
        .map { (pkg, label) -> AppEntry(pkg, label, installed = true, suggested = false) }
        .sortedBy { it.label.lowercase() }
    return suggested + others
}

@Composable
fun AppPicker(
    blocked: Set<String>,
    onToggle: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    preview: List<AppEntry>? = null,
) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf(preview ?: emptyList()) }
    LaunchedEffect(Unit) {
        if (preview == null) apps = withContext(Dispatchers.IO) { loadApps(context) }
    }
    Column(modifier.fillMaxWidth()) {
        if (apps.isEmpty()) {
            Text("Đang tải danh sách ứng dụng…", color = Palette.Muted, modifier = Modifier.padding(vertical = 12.dp))
        }
        apps.forEach { app ->
            val checked = app.pkg in blocked
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onToggle(app.pkg, !checked) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(app.label, style = MaterialTheme.typography.bodyLarge, color = Palette.Text)
                    if (app.suggested) Text("Hay gây lướt vô thức", style = MaterialTheme.typography.labelMedium, color = Palette.Accent)
                }
                Checkbox(
                    checked = checked,
                    onCheckedChange = { onToggle(app.pkg, it) },
                    colors = CheckboxDefaults.colors(checkedColor = Palette.Accent, checkmarkColor = Palette.OnAccent),
                )
            }
        }
    }
}
