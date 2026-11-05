package com.audiohub.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.audiohub.data.SortField
import com.audiohub.data.SortOrder

/** "⇅ Title ↑" — opens the sort fields; picking the active field again flips the direction. */
@Composable
fun SortButton(
    order: SortOrder,
    onChange: (SortOrder) -> Unit,
    modifier: Modifier = Modifier,
    labels: Map<SortField, String> = emptyMap(),
) {
    var open by remember { mutableStateOf(false) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    fun label(f: SortField) = labels[f] ?: f.label
    Box(modifier) {
        Row(
            Modifier
                .clip(MaterialTheme.shapes.small)
                .clickable { open = true }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = "Sort", tint = muted, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(label(order.field), style = MaterialTheme.typography.labelMedium, color = muted)
            Icon(
                if (order.descending) Icons.Rounded.ArrowDownward else Icons.Rounded.ArrowUpward,
                contentDescription = if (order.descending) "Descending" else "Ascending",
                tint = muted,
                modifier = Modifier.padding(start = 2.dp).size(14.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SortField.entries.forEach { f ->
                val active = f == order.field
                DropdownMenuItem(
                    text = {
                        Text(
                            label(f),
                            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    trailingIcon = if (active) {
                        {
                            Icon(
                                if (order.descending) Icons.Rounded.ArrowDownward else Icons.Rounded.ArrowUpward,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    } else null,
                    onClick = {
                        open = false
                        onChange(order.select(f))
                    },
                )
            }
        }
    }
}
