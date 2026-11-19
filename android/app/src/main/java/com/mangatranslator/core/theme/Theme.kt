package com.mangatranslator.core.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** One spacing scale for the whole app (4dp grid). */
object Spacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val screen = 20.dp
}

object Radii {
    val sm = 10.dp
    val md = 14.dp
    val lg = 20.dp
    val xl = 28.dp
}

val MtShapes = Shapes(
    extraSmall = RoundedCornerShape(Radii.sm),
    small = RoundedCornerShape(Radii.sm),
    medium = RoundedCornerShape(Radii.md),
    large = RoundedCornerShape(Radii.lg),
    extraLarge = RoundedCornerShape(Radii.xl),
)

/** Extra semantic colours Material3 doesn't have. */
@Immutable
data class ExtendedColors(
    val card: Color,
    val border: Color,
    val textSecondary: Color,
    val success: Color,
    val warning: Color,
    val accent: Color,
    val glass: Color,
)

val LocalExtendedColors = staticCompositionLocalOf {
    ExtendedColors(MtColors.Card, MtColors.Border, MtColors.TextSecondary, MtColors.Success, MtColors.Warning, MtColors.Accent, Color(0x14FFFFFF))
}

private val DarkScheme = darkColorScheme(
    primary = MtColors.Purple,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF2A1F4D),
    onPrimaryContainer = MtColors.PurpleLight,
    secondary = MtColors.PurpleLight,
    tertiary = MtColors.Accent,
    background = MtColors.BgPrimary,
    onBackground = MtColors.TextPrimary,
    surface = MtColors.BgSecondary,
    onSurface = MtColors.TextPrimary,
    surfaceVariant = MtColors.Card,
    onSurfaceVariant = MtColors.TextSecondary,
    surfaceContainer = MtColors.Card,
    surfaceContainerHigh = Color(0xFF20202F),
    outline = MtColors.Border,
    outlineVariant = MtColors.Border,
    error = MtColors.Error,
    onError = Color(0xFF2B0B0B),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF7C4DEB),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEDE6FF),
    onPrimaryContainer = Color(0xFF3B1F8A),
    secondary = Color(0xFF7C4DEB),
    tertiary = Color(0xFF9D4EDD),
    background = MtColors.LightBg,
    onBackground = MtColors.LightText,
    surface = MtColors.LightBg,
    onSurface = MtColors.LightText,
    surfaceVariant = MtColors.LightCard,
    onSurfaceVariant = MtColors.LightTextSecondary,
    surfaceContainer = MtColors.LightCard,
    surfaceContainerHigh = Color(0xFFF0EEF7),
    outline = MtColors.LightBorder,
    outlineVariant = MtColors.LightBorder,
    error = Color(0xFFDC2626),
)

@Composable
fun MangaTranslatorTheme(themeSetting: String = "dark", content: @Composable () -> Unit) {
    val dark = when (themeSetting) {
        "light" -> false
        "system" -> isSystemInDarkTheme()
        else -> true
    }
    val ext = if (dark) {
        ExtendedColors(MtColors.Card, MtColors.Border, MtColors.TextSecondary, MtColors.Success, MtColors.Warning, MtColors.Accent, Color(0x14FFFFFF))
    } else {
        ExtendedColors(MtColors.LightCard, MtColors.LightBorder, MtColors.LightTextSecondary, Color(0xFF059669), Color(0xFFD97706), Color(0xFF9D4EDD), Color(0x0F000000))
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalExtendedColors provides ext) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            typography = MtTypography,
            shapes = MtShapes,
            content = content,
        )
    }
}

object MtTheme {
    val colors: ExtendedColors
        @Composable get() = LocalExtendedColors.current
}
