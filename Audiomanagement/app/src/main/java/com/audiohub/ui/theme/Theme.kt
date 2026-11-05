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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Minimalist design system: near-monochrome neutrals, one accent used only for "active" state
 * (now playing, progress, toggles), flat surfaces (no tonal tints), hairline dividers, small radii.
 */

private val Accent = Color(0xFFE0314B)
private val AccentDark = Color(0xFFFF5A6E)

private val Ink = Color(0xFF111111)
private val InkSoft = Color(0xFF6B6B6B)
private val Paper = Color(0xFFFFFFFF)
private val Mist = Color(0xFFF4F4F4)
private val Hairline = Color(0xFFE8E8E8)

private val Night = Color(0xFF0B0B0B)
private val NightRaised = Color(0xFF161616)
private val NightMist = Color(0xFF1F1F1F)
private val Snow = Color(0xFFF2F2F2)
private val SnowSoft = Color(0xFF9A9A9A)
private val NightHairline = Color(0xFF262626)

private val LightColors = lightColorScheme(
    primary = Accent,
    onPrimary = Paper,
    primaryContainer = Mist,
    onPrimaryContainer = Ink,
    secondary = Ink,
    onSecondary = Paper,
    secondaryContainer = Mist,
    onSecondaryContainer = Ink,
    tertiary = Ink,
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = Mist,
    onSurfaceVariant = InkSoft,
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = Paper,
    surfaceContainerLow = Paper,
    surfaceContainer = Paper,
    surfaceContainerHigh = Paper,
    surfaceContainerHighest = Mist,
    surfaceBright = Paper,
    surfaceDim = Mist,
    inverseSurface = Ink,
    inverseOnSurface = Paper,
    outline = Color(0xFFCFCFCF),
    outlineVariant = Hairline,
)

private val DarkColors = darkColorScheme(
    primary = AccentDark,
    onPrimary = Night,
    primaryContainer = NightMist,
    onPrimaryContainer = Snow,
    secondary = Snow,
    onSecondary = Night,
    secondaryContainer = NightMist,
    onSecondaryContainer = Snow,
    tertiary = Snow,
    background = Night,
    onBackground = Snow,
    surface = Night,
    onSurface = Snow,
    surfaceVariant = NightMist,
    onSurfaceVariant = SnowSoft,
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = Night,
    surfaceContainerLow = NightRaised,
    surfaceContainer = NightRaised,
    surfaceContainerHigh = NightRaised,
    surfaceContainerHighest = NightMist,
    surfaceBright = NightMist,
    surfaceDim = Night,
    inverseSurface = Snow,
    inverseOnSurface = Night,
    outline = Color(0xFF3A3A3A),
    outlineVariant = NightHairline,
)

private val AppTypography = Typography().run {
    copy(
        headlineLarge = headlineLarge.copy(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        headlineMedium = headlineMedium.copy(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
        titleLarge = titleLarge.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
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
    @Composable get() = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(20.dp),
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
