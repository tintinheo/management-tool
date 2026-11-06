package com.audiohub.ui.theme

import android.content.res.AssetManager
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

/** A font the user can pick for headings or body text. */
class FontOption(val id: String, val label: String, val family: FontFamily)

/**
 * Fonts offered in Settings → Appearance.
 *
 * Besides three always-available system families, every family bundled under `assets/fonts/` is listed
 * automatically. Files follow the Google Fonts "static" naming, `<Family>-<Style>.ttf|otf`, e.g.
 * `PlayfairDisplay-Regular.ttf`, `PlayfairDisplay-SemiBoldItalic.ttf`; files of one family are grouped
 * and their weight/italic is read from the style part. Add a family = drop its files in that folder.
 */
object AppFonts {
    const val DIR = "fonts"
    private const val SYSTEM_PREFIX = "sys:"
    private const val ASSET_PREFIX = "asset:"

    private val system = listOf(
        FontOption("${SYSTEM_PREFIX}serif", "System Serif", FontFamily.Serif),
        FontOption("${SYSTEM_PREFIX}sans", "System Sans", FontFamily.SansSerif),
        FontOption("${SYSTEM_PREFIX}condensed", "System Condensed", FontFamily(Font(DeviceFontFamilyName("sans-serif-condensed")))),
    )

    @Volatile private var cached: List<FontOption>? = null

    /** All options; the asset folder is listed once per process (a few ms) and cached. */
    fun all(assets: AssetManager): List<FontOption> = cached ?: synchronized(this) {
        cached ?: (system + bundled(assets)).also { cached = it }
    }

    /** The picked family, or null for "the style's own font" / a font that's no longer bundled. */
    fun resolve(assets: AssetManager, id: String): FontFamily? =
        if (id.isBlank()) null else all(assets).firstOrNull { it.id == id }?.family

    private fun bundled(assets: AssetManager): List<FontOption> {
        val files = runCatching { assets.list(DIR)?.toList() }.getOrNull().orEmpty()
            .filter { it.endsWith(".ttf", true) || it.endsWith(".otf", true) }
        return files.groupBy { familyKey(it) }
            .toSortedMap()
            .mapNotNull { (key, group) ->
                val fonts = group.map { file ->
                    val (weight, style) = weightAndStyle(file)
                    Font("$DIR/$file", assets, weight, style)
                }
                if (fonts.isEmpty()) null else FontOption(ASSET_PREFIX + key, displayName(key), FontFamily(fonts))
            }
    }

    /** "PlayfairDisplay-SemiBoldItalic.ttf" → "PlayfairDisplay". */
    internal fun familyKey(file: String): String = file.substringBeforeLast('.').substringBeforeLast('-')

    /** "PlayfairDisplay" → "Playfair Display", "BeVietnamPro" → "Be Vietnam Pro". */
    internal fun displayName(key: String): String =
        key.replace('_', ' ').replace(Regex("(?<=[a-z])(?=[A-Z])"), " ").trim()

    internal fun weightAndStyle(file: String): Pair<FontWeight, FontStyle> {
        val base = file.substringBeforeLast('.')
        val part = if ('-' in base) base.substringAfterLast('-').lowercase() else "regular"
        val italic = part.endsWith("italic")
        val weightName = part.removeSuffix("italic")
        val weight = when (weightName) {
            "thin", "hairline" -> FontWeight.Thin
            "extralight", "ultralight" -> FontWeight.ExtraLight
            "light" -> FontWeight.Light
            "medium" -> FontWeight.Medium
            "semibold", "demibold" -> FontWeight.SemiBold
            "bold" -> FontWeight.Bold
            "extrabold", "ultrabold" -> FontWeight.ExtraBold
            "black", "heavy" -> FontWeight.Black
            else -> FontWeight.Normal // "regular", "" (just "Italic"), "book"…
        }
        return weight to if (italic) FontStyle.Italic else FontStyle.Normal
    }
}
