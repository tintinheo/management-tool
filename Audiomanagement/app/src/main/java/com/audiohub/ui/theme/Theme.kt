package com.audiohub.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Three "quiet luxury" styles. All share the same rules — flat surfaces, hairline dividers, a single accent
 * reserved for active state — but differ in palette, type voice, shape language and album-art motion:
 *
 *  - Atelier: warm ivory / charcoal, champagne gold, editorial upright serif, gentle 12 dp curves.
 *  - Noir:    monochrome porcelain / true black, platinum-steel accent, airy light sans, razor 2 dp edges.
 *  - Riviera: linen / forest night, emerald (light) and brass (dark), italic serif, soft pill curves, vinyl.
 */

/** How the album art reacts to play / pause. */
enum class ArtMotion(val id: String, val label: String, val description: String) {
    Still("still", "Still", "No movement"),
    Lift("lift", "Lift", "Art settles back when paused, lifts when playing"),
    Pulse("pulse", "Pulse", "Slow breathing while playing"),
    Spin("spin", "Vinyl", "Round art spins like a record while playing"),
    ;

    companion object {
        fun fromId(id: String): ArtMotion? = entries.firstOrNull { it.id == id }
    }
}

enum class UiStyle(
    val id: String,
    val label: String,
    val tagline: String,
    val defaultMotion: ArtMotion,
) {
    Atelier("atelier", "Atelier", "Warm ivory, champagne gold, editorial serif", ArtMotion.Lift),
    Noir("noir", "Noir", "Monochrome, platinum accent, light sans, sharp edges", ArtMotion.Pulse),
    Riviera("riviera", "Riviera", "Emerald & linen, brass, italic serif, soft curves", ArtMotion.Spin),

    // Layout styles: besides palette and type, these rearrange the tab bar, mini player and Now Playing.
    Washi("washi", "Washi", "Rice paper & sumi ink, vermilion · editorial layout, icon-only tabs", ArtMotion.Still),
    Bordeaux("bordeaux", "Bordeaux", "Wine & blush, rose gold, bold serif · full-bleed art, pill player", ArtMotion.Pulse),
    Glacier("glacier", "Glacier", "Ice & navy, steel blue, modern sans · frosted card, floating tabs", ArtMotion.Lift),
    ;

    companion object {
        val Default = Atelier
        fun fromId(id: String): UiStyle = entries.firstOrNull { it.id == id } ?: Default
    }
}

/** Bottom tab bar: labelled icons, bare icons with a dot, or a floating pill. */
enum class NavStyle { Labeled, IconsDot, FloatingPill }

/** Mini player: floating card, flat full-width bar, or a compact pill with a progress ring. */
enum class MiniStyle { Card, Bar, Pill }

/** Now Playing arrangement. */
enum class NowPlayingLayout {
    /** Art on top, title and controls below. */
    Classic,
    /** Edge-to-edge art with the title laid over its fading lower edge. */
    Immersive,
    /** Big title first like a magazine headline, art below, outlined play button. */
    Editorial,
    /** Art, title and seek bar together on a translucent card. */
    Frosted,
}

/** Per-style tokens that Material's theme doesn't cover. */
@Immutable
class UiTokens(
    val style: UiStyle,
    val motion: ArtMotion,
    /** Multiplier for artwork corner radii (Noir is nearly square, Riviera rounder). */
    val cornerScale: Float,
    /** Big Now Playing artwork, when not spinning (spinning art is always round). */
    val heroArtShape: Shape,
    val navStyle: NavStyle = NavStyle.Labeled,
    val miniStyle: MiniStyle = MiniStyle.Card,
    val nowPlaying: NowPlayingLayout = NowPlayingLayout.Classic,
)

val LocalUiTokens = staticCompositionLocalOf {
    UiTokens(UiStyle.Default, UiStyle.Default.defaultMotion, 1f, RoundedCornerShape(12.dp))
}

// ------------------------------------------------------------------ Palettes

private fun lightScheme(
    accent: Color, onAccent: Color, ink: Color, inkSoft: Color, background: Color, paper: Color,
    tint: Color, hairline: Color, outline: Color,
) = lightColorScheme(
    primary = accent, onPrimary = onAccent, primaryContainer = tint, onPrimaryContainer = ink,
    secondary = ink, onSecondary = paper, secondaryContainer = tint, onSecondaryContainer = ink, tertiary = ink,
    background = background, onBackground = ink, surface = background, onSurface = ink,
    surfaceVariant = tint, onSurfaceVariant = inkSoft, surfaceTint = Color.Transparent,
    surfaceContainerLowest = paper, surfaceContainerLow = paper, surfaceContainer = paper,
    surfaceContainerHigh = paper, surfaceContainerHighest = tint, surfaceBright = paper, surfaceDim = tint,
    inverseSurface = ink, inverseOnSurface = background, outline = outline, outlineVariant = hairline,
)

private fun darkScheme(
    accent: Color, onAccent: Color, text: Color, textSoft: Color, background: Color, raised: Color,
    mist: Color, hairline: Color, outline: Color,
) = darkColorScheme(
    primary = accent, onPrimary = onAccent, primaryContainer = mist, onPrimaryContainer = text,
    secondary = text, onSecondary = background, secondaryContainer = mist, onSecondaryContainer = text, tertiary = text,
    background = background, onBackground = text, surface = background, onSurface = text,
    surfaceVariant = mist, onSurfaceVariant = textSoft, surfaceTint = Color.Transparent,
    surfaceContainerLowest = background, surfaceContainerLow = raised, surfaceContainer = raised,
    surfaceContainerHigh = raised, surfaceContainerHighest = mist, surfaceBright = mist, surfaceDim = background,
    inverseSurface = text, inverseOnSurface = background, outline = outline, outlineVariant = hairline,
)

private val AtelierLight = lightScheme(
    accent = Color(0xFFA8823F), onAccent = Color.White, ink = Color(0xFF151413), inkSoft = Color(0xFF7C766E),
    background = Color(0xFFFAF8F4), paper = Color.White, tint = Color(0xFFF1EDE6),
    hairline = Color(0xFFE6E0D6), outline = Color(0xFFCBC3B6),
)
private val AtelierDark = darkScheme(
    accent = Color(0xFFD2B47A), onAccent = Color(0xFF0D0D0C), text = Color(0xFFF2EEE7), textSoft = Color(0xFF948D83),
    background = Color(0xFF0D0D0C), raised = Color(0xFF171615), mist = Color(0xFF211F1D),
    hairline = Color(0xFF2A2724), outline = Color(0xFF45403A),
)

private val NoirLight = lightScheme(
    accent = Color(0xFF55606E), onAccent = Color.White, ink = Color(0xFF0B0B0C), inkSoft = Color(0xFF6E6E73),
    background = Color(0xFFF5F5F3), paper = Color.White, tint = Color(0xFFEAEAE8),
    hairline = Color(0xFFDDDDDB), outline = Color(0xFFBDBDBB),
)
private val NoirDark = darkScheme(
    accent = Color(0xFFC9CFD8), onAccent = Color.Black, text = Color(0xFFEDEDED), textSoft = Color(0xFF8A8A8F),
    background = Color.Black, raised = Color(0xFF0F0F10), mist = Color(0xFF1A1A1C),
    hairline = Color(0xFF232325), outline = Color(0xFF3C3C40),
)

private val RivieraLight = lightScheme(
    accent = Color(0xFF1E5B47), onAccent = Color(0xFFF7F3E8), ink = Color(0xFF13261F), inkSoft = Color(0xFF6B776F),
    background = Color(0xFFF3EFE4), paper = Color(0xFFFBF9F3), tint = Color(0xFFE6E1D2),
    hairline = Color(0xFFDCD6C4), outline = Color(0xFFBFB8A3),
)
private val RivieraDark = darkScheme(
    accent = Color(0xFFCFAE6E), onAccent = Color(0xFF0B1612), text = Color(0xFFEDE6D3), textSoft = Color(0xFF92A097),
    background = Color(0xFF0B1612), raised = Color(0xFF12201B), mist = Color(0xFF1A2B24),
    hairline = Color(0xFF22332C), outline = Color(0xFF3A4D44),
)

private val WashiLight = lightScheme(
    accent = Color(0xFFB5442F), onAccent = Color(0xFFFBF8F2), ink = Color(0xFF1B1A19), inkSoft = Color(0xFF7A746B),
    background = Color(0xFFF7F4EE), paper = Color(0xFFFBF9F5), tint = Color(0xFFEDE8DE),
    hairline = Color(0xFFE2DCD0), outline = Color(0xFFC7BFB1),
)
private val WashiDark = darkScheme(
    accent = Color(0xFFE07A5F), onAccent = Color(0xFF121110), text = Color(0xFFEDE7DB), textSoft = Color(0xFF9A9286),
    background = Color(0xFF121110), raised = Color(0xFF1A1918), mist = Color(0xFF24221F),
    hairline = Color(0xFF2C2A26), outline = Color(0xFF48443E),
)

private val BordeauxLight = lightScheme(
    accent = Color(0xFF7A1F2B), onAccent = Color(0xFFFBF3F1), ink = Color(0xFF2A1215), inkSoft = Color(0xFF8A6F72),
    background = Color(0xFFF8F1EF), paper = Color.White, tint = Color(0xFFEFE2E0),
    hairline = Color(0xFFE7D7D5), outline = Color(0xFFCDB4B2),
)
private val BordeauxDark = darkScheme(
    accent = Color(0xFFE0A899), onAccent = Color(0xFF140A0C), text = Color(0xFFF3E6E4), textSoft = Color(0xFFA88F92),
    background = Color(0xFF140A0C), raised = Color(0xFF1E1013), mist = Color(0xFF2A171B),
    hairline = Color(0xFF341E22), outline = Color(0xFF53363B),
)

private val GlacierLight = lightScheme(
    accent = Color(0xFF2F5D8A), onAccent = Color.White, ink = Color(0xFF0F1B2D), inkSoft = Color(0xFF6B7A8C),
    background = Color(0xFFF2F5F8), paper = Color.White, tint = Color(0xFFE3E9F0),
    hairline = Color(0xFFDCE3EB), outline = Color(0xFFB8C4D2),
)
private val GlacierDark = darkScheme(
    accent = Color(0xFF9CC3E6), onAccent = Color(0xFF0A111B), text = Color(0xFFE6EEF6), textSoft = Color(0xFF8A9BB0),
    background = Color(0xFF0A111B), raised = Color(0xFF111A26), mist = Color(0xFF1A2533),
    hairline = Color(0xFF1F2B3A), outline = Color(0xFF39485B),
)

/** Light/dark swatches, also used by the style picker. */
fun UiStyle.colors(dark: Boolean): ColorScheme = when (this) {
    UiStyle.Atelier -> if (dark) AtelierDark else AtelierLight
    UiStyle.Noir -> if (dark) NoirDark else NoirLight
    UiStyle.Riviera -> if (dark) RivieraDark else RivieraLight
    UiStyle.Washi -> if (dark) WashiDark else WashiLight
    UiStyle.Bordeaux -> if (dark) BordeauxDark else BordeauxLight
    UiStyle.Glacier -> if (dark) GlacierDark else GlacierLight
}

// ------------------------------------------------------------------ Type

/** Large-title voice of each style; the family can be swapped by the user's heading font. */
class DisplayVoice(
    val family: FontFamily,
    val weight: FontWeight,
    val style: FontStyle,
    /** Letter spacing for the 32 sp headline; smaller sizes scale it down. */
    val tracking: Float,
)

/** Large-title voice; also drawn by the style picker. */
fun UiStyle.voice() = when (this) {
    UiStyle.Atelier -> DisplayVoice(FontFamily.Serif, FontWeight.Normal, FontStyle.Normal, -0.6f)
    UiStyle.Noir -> DisplayVoice(FontFamily.SansSerif, FontWeight.Light, FontStyle.Normal, 0.4f)
    UiStyle.Riviera -> DisplayVoice(FontFamily.Serif, FontWeight.Normal, FontStyle.Italic, -0.4f)
    UiStyle.Washi -> DisplayVoice(FontFamily.Serif, FontWeight.Light, FontStyle.Normal, 0.6f)
    UiStyle.Bordeaux -> DisplayVoice(FontFamily.Serif, FontWeight.Bold, FontStyle.Normal, -1.0f)
    UiStyle.Glacier -> DisplayVoice(FontFamily.SansSerif, FontWeight.Medium, FontStyle.Normal, -0.9f)
}

private fun typography(style: UiStyle, heading: FontFamily?, body: FontFamily?): Typography {
    val v = style.voice()
    val display = heading ?: v.family
    val bodyFamily = body ?: FontFamily.Default
    val labelTracking = when (style) {
        UiStyle.Noir -> 0.6f
        UiStyle.Washi -> 1.0f
        else -> 0.2f
    }
    fun TextStyle.head(size: Int, line: Int, trackingScale: Float) = copy(
        fontFamily = display, fontSize = size.sp, lineHeight = line.sp, fontWeight = v.weight,
        fontStyle = v.style, letterSpacing = (v.tracking * trackingScale).sp,
    )
    fun TextStyle.body(size: Int, weight: FontWeight? = fontWeight, tracking: TextUnit = 0.sp) =
        copy(fontFamily = bodyFamily, fontSize = size.sp, fontWeight = weight, letterSpacing = tracking)
    return Typography().run {
        copy(
            displaySmall = displaySmall.head(34, 40, 1f),
            headlineLarge = headlineLarge.head(32, 38, 1f),
            headlineMedium = headlineMedium.head(26, 32, 0.7f),
            headlineSmall = headlineSmall.head(22, 28, 0.5f),
            titleLarge = titleLarge.body(19, FontWeight.Medium, (-0.2).sp),
            titleMedium = titleMedium.body(16, if (style == UiStyle.Noir) FontWeight.Medium else FontWeight.SemiBold),
            titleSmall = titleSmall.body(15, FontWeight.Medium),
            bodyLarge = bodyLarge.body(16),
            bodyMedium = bodyMedium.body(14),
            bodySmall = bodySmall.body(13),
            labelLarge = labelLarge.body(14, FontWeight.Medium),
            labelMedium = labelMedium.body(12, FontWeight.Medium, labelTracking.sp),
            labelSmall = labelSmall.body(11, tracking = labelTracking.sp),
        )
    }
}

/** Small caps-like section label ("RECENTLY ADDED"). */
val SectionLabelStyle: TextStyle
    @Composable get() = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp, fontWeight = FontWeight.Medium)

// ------------------------------------------------------------------ Shapes

private fun shapes(xs: Dp, s: Dp, m: Dp, l: Dp, xl: Dp) = Shapes(
    extraSmall = RoundedCornerShape(xs), small = RoundedCornerShape(s), medium = RoundedCornerShape(m),
    large = RoundedCornerShape(l), extraLarge = RoundedCornerShape(xl),
)

private val AtelierShapes = shapes(6.dp, 8.dp, 12.dp, 18.dp, 28.dp)
private val NoirShapes = shapes(2.dp, 2.dp, 2.dp, 3.dp, 4.dp)
private val RivieraShapes = shapes(10.dp, 14.dp, 20.dp, 28.dp, 36.dp)
private val WashiShapes = shapes(0.dp, 0.dp, 0.dp, 0.dp, 0.dp)
private val BordeauxShapes = shapes(12.dp, 16.dp, 22.dp, 30.dp, 40.dp)
private val GlacierShapes = shapes(10.dp, 14.dp, 18.dp, 24.dp, 32.dp)

private fun UiStyle.shapes() = when (this) {
    UiStyle.Atelier -> AtelierShapes
    UiStyle.Noir -> NoirShapes
    UiStyle.Riviera -> RivieraShapes
    UiStyle.Washi -> WashiShapes
    UiStyle.Bordeaux -> BordeauxShapes
    UiStyle.Glacier -> GlacierShapes
}

/** Corner of the style's medium shape, for previews. */
fun UiStyle.previewCorner(): Dp = when (this) {
    UiStyle.Atelier -> 12.dp
    UiStyle.Noir -> 2.dp
    UiStyle.Riviera -> 22.dp
    UiStyle.Washi -> 0.dp
    UiStyle.Bordeaux -> 22.dp
    UiStyle.Glacier -> 18.dp
}

fun UiStyle.tokens(motion: ArtMotion = defaultMotion) = when (this) {
    UiStyle.Atelier -> UiTokens(this, motion, 1f, RoundedCornerShape(12.dp))
    UiStyle.Noir -> UiTokens(this, motion, 0.25f, RoundedCornerShape(2.dp))
    UiStyle.Riviera -> UiTokens(this, motion, 1.6f, RoundedCornerShape(28.dp))
    UiStyle.Washi -> UiTokens(
        this, motion, 0f, RoundedCornerShape(0.dp),
        navStyle = NavStyle.IconsDot, miniStyle = MiniStyle.Bar, nowPlaying = NowPlayingLayout.Editorial,
    )
    UiStyle.Bordeaux -> UiTokens(
        this, motion, 1.4f, RoundedCornerShape(0.dp),
        navStyle = NavStyle.Labeled, miniStyle = MiniStyle.Pill, nowPlaying = NowPlayingLayout.Immersive,
    )
    UiStyle.Glacier -> UiTokens(
        this, motion, 1.3f, RoundedCornerShape(20.dp),
        navStyle = NavStyle.FloatingPill, miniStyle = MiniStyle.Card, nowPlaying = NowPlayingLayout.Frosted,
    )
}

@Composable
fun AudioHubTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    style: UiStyle = UiStyle.Default,
    headingFont: FontFamily? = null,
    bodyFont: FontFamily? = null,
    /** null = the style's default motion. */
    artMotion: ArtMotion? = null,
    /** Off by default: the palettes are part of the design. */
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        else -> style.colors(darkTheme)
    }
    val type = remember(style, headingFont, bodyFont) { typography(style, headingFont, bodyFont) }
    val motion = artMotion ?: style.defaultMotion
    val tokens = remember(style, motion) { style.tokens(motion) }
    CompositionLocalProvider(LocalUiTokens provides tokens) {
        MaterialTheme(colorScheme = colors, typography = type, shapes = style.shapes(), content = content)
    }
}
