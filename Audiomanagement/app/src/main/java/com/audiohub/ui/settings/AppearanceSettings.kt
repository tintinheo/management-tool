package com.audiohub.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.audiohub.data.settings.AppSettings
import com.audiohub.ui.theme.AppFonts
import com.audiohub.ui.theme.ArtMotion
import com.audiohub.ui.theme.FontOption
import com.audiohub.ui.theme.UiStyle
import com.audiohub.ui.theme.colors

/** Settings → Appearance: UI style, album-art motion, heading and body fonts. */
@Composable
fun AppearanceSection(
    settings: AppSettings,
    onStyle: (UiStyle) -> Unit,
    onMotion: (String) -> Unit,
    onHeadingFont: (String) -> Unit,
    onBodyFont: (String) -> Unit,
) {
    val style = UiStyle.fromId(settings.uiStyle)
    Text("Style", style = MaterialTheme.typography.bodyLarge)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        UiStyle.entries.forEach { s ->
            StyleCard(s, selected = s == style, onClick = { onStyle(s) }, modifier = Modifier.weight(1f))
        }
    }
    Text(style.tagline, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

    val motion = ArtMotion.fromId(settings.artMotion)
    Picker(
        title = "Album art motion",
        subtitle = (motion ?: style.defaultMotion).description,
        value = motion?.label ?: "Default",
        options = listOf("" to "Style default (${style.defaultMotion.label})") + ArtMotion.entries.map { it.id to it.label },
        onPick = onMotion,
    )

    val fonts = AppFonts.all(LocalContext.current.assets)
    FontPicker("Heading font", "Large titles and the song name", settings.headingFont, fonts, onHeadingFont)
    FontPicker("Body font", "Lists, buttons and everything else", settings.bodyFont, fonts, onBodyFont)
}

/** A miniature of the style: its own background, accent, shape and headline voice. */
@Composable
private fun StyleCard(style: UiStyle, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val c = style.colors(isSystemInDarkTheme())
    val corner = when (style) {
        UiStyle.Atelier -> 12.dp
        UiStyle.Noir -> 2.dp
        UiStyle.Riviera -> 22.dp
    }
    val shape = RoundedCornerShape(corner)
    Column(
        modifier
            .clip(shape)
            .border(if (selected) 1.5.dp else 0.5.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape)
            .background(c.background)
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        // Tiny art + accent line, in the style's own shape language.
        Box(
            Modifier.size(34.dp).background(
                c.surfaceVariant,
                if (style == UiStyle.Riviera) CircleShape else RoundedCornerShape(corner / 2),
            ),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Aa",
            color = c.onBackground,
            fontSize = 22.sp,
            fontFamily = if (style == UiStyle.Noir) FontFamily.SansSerif else FontFamily.Serif,
            fontWeight = if (style == UiStyle.Noir) FontWeight.Light else FontWeight.Normal,
            fontStyle = if (style == UiStyle.Riviera) FontStyle.Italic else FontStyle.Normal,
        )
        Box(Modifier.padding(top = 4.dp).width(28.dp).height(2.dp).background(c.primary))
        Spacer(Modifier.height(8.dp))
        Text(style.label, style = MaterialTheme.typography.labelLarge, color = c.onBackground)
    }
}

@Composable
private fun FontPicker(title: String, subtitle: String, current: String, fonts: List<FontOption>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val selected = fonts.firstOrNull { it.id == current }
    Row(
        Modifier.fillMaxWidth().clickable { open = true }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
            Text(
                selected?.label ?: "Style default",
                style = MaterialTheme.typography.bodyLarge.copy(fontFamily = selected?.family ?: MaterialTheme.typography.bodyLarge.fontFamily),
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(text = { Text("Style default") }, onClick = { open = false; onPick("") })
                fonts.forEach { f ->
                    // Each name is drawn in its own font, with Vietnamese to check the accents.
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(f.label, fontFamily = f.family, fontSize = 17.sp)
                                Text("Lạc Trôi · Đông", fontFamily = f.family, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        onClick = { open = false; onPick(f.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Picker(title: String, subtitle: String, value: String, options: List<Pair<String, String>>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable { open = true }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
            Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (id, label) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { open = false; onPick(id) })
                }
            }
        }
    }
}
