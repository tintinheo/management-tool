package com.audiohub.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * "Quiet luxury" design system: warm ivory / deep charcoal neutrals, a single champagne-gold accent
 * reserved for active state (now playing, progress, toggles), editorial serif display type over a
 * clean sans body, flat surfaces, hairline dividers.
 */

private val Gold = Color(0xFFA8823F)
private val GoldDark = Color(0xFFD2B47A)

private val Ink = Color(0xFF151413)
private val InkSoft = Color(0xFF7C766E)
private val Ivory = Color(0xFFFAF8F4)
private val Paper = Color(0xFFFFFFFF)
private val Sand = Color(0xFFF1EDE6)
private val Hairline = Color(0xFFE6E0D6)

private val Night = Color(0xFF0D0D0C)
private val NightRaised = Color(0xFF171615)
private val NightMist = Color(0xFF211F1D)
private val Pearl = Color(0xFFF2EEE7)
private val PearlSoft = Color(0xFF948D83)
private val NightHairline = Color(0xFF2A2724)

private val LightColors = lightColorScheme(
    primary = Gold,
    onPrimary = Paper,
    primaryContainer = Sand,
    onPrimaryContainer = Ink,
    secondary = Ink,
    onSecondary = Paper,
    secondaryContainer = Sand,
    onSecondaryContainer = Ink,
    tertiary = Ink,
    background = Ivory,
    onBackground = Ink,
    surface = Ivory,
    onSurface = Ink,
    surfaceVariant = Sand,
    onSurfaceVariant = InkSoft,
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = Paper,
    surfaceContainerLow = Paper,
    surfaceContainer = Paper,
    surfaceContainerHigh = Paper,
    surfaceContainerHighest = Sand,
    surfaceBright = Paper,
    surfaceDim = Sand,
    inverseSurface = Ink,
    inverseOnSurface = Ivory,
    outline = Color(0xFFCBC3B6),
    outlineVariant = Hairline,
)

private val DarkColors = darkColorScheme(
    primary = GoldDark,
    onPrimary = Night,
    primaryContainer = NightMist,
    onPrimaryContainer = Pearl,
    secondary = Pearl,
    onSecondary = Night,
    secondaryContainer = NightMist,
    onSecondaryContainer = Pearl,
    tertiary = Pearl,
    background = Night,
    onBackground = Pearl,
    surface = Night,
    onSurface = Pearl,
    surfaceVariant = NightMist,
    onSurfaceVariant = PearlSoft,
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = Night,
    surfaceContainerLow = NightRaised,
    surfaceContainer = NightRaised,
    surfaceContainerHigh = NightRaised,
    surfaceContainerHighest = NightMist,
    surfaceBright = NightMist,
    surfaceDim = Night,
    inverseSurface = Pearl,
    inverseOnSurface = Night,
    outline = Color(0xFF45403A),
    outlineVariant = NightHairline,
)

/** Editorial serif for large titles (Noto Serif on Android). */
val DisplayFamily = FontFamily.Serif

private val AppTypography = Typography().run {
    copy(
        displaySmall = displaySmall.copy(fontFamily = DisplayFamily, fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Normal, letterSpacing = (-0.6).sp),
        headlineLarge = headlineLarge.copy(fontFamily = DisplayFamily, fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Normal, letterSpacing = (-0.6).sp),
        headlineMedium = headlineMedium.copy(fontFamily = DisplayFamily, fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Normal, letterSpacing = (-0.4).sp),
        headlineSmall = headlineSmall.copy(fontFamily = DisplayFamily, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Normal, letterSpacing = (-0.3).sp),
        titleLarge = titleLarge.copy(fontSize = 19.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.2).sp),
        titleMedium = titleMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = titleSmall.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
        bodyLarge = bodyLarge.copy(fontSize = 16.sp, letterSpacing = 0.sp),
        bodyMedium = bodyMedium.copy(fontSize = 14.sp, letterSpacing = 0.sp),
        bodySmall = bodySmall.copy(fontSize = 13.sp, letterSpacing = 0.sp),
        labelLarge = labelLarge.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
        labelMedium = labelMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp),
        labelSmall = labelSmall.copy(fontSize = 11.sp, letterSpacing = 0.2.sp),
    )
}

/** Small caps-like section label ("RECENTLY ADDED"). */
val SectionLabelStyle: TextStyle
    @Composable get() = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp, fontWeight = FontWeight.Medium)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun AudioHubTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /** Off by default: the minimalist palette is part of the design. */
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, typography = AppTypography, shapes = AppShapes, content = content)
}
