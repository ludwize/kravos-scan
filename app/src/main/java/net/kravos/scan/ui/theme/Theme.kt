package net.kravos.scan.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import net.kravos.scan.data.ThemeMode

/** Accent colors shared by all screens. */
object K {
    val Live = Color(0xFFFF3B30)
    val Accent = Color(0xFF2EE59D)
    val Home = Color(0xFF3D8BFF)
    val Away = Color(0xFFFF9F0A)
    val Win = Color(0xFF30D158)
    val Draw = Color(0xFF8E8E93)
    val Loss = Color(0xFFFF453A)
    val Yellow = Color(0xFFFFD60A)
    val Pitch = Color(0xFF1E5631)
    val PitchLine = Color(0x66FFFFFF)
}

data class Palette(val dark: Boolean, val card: Color, val divider: Color, val dim: Color)
val LocalPalette = staticCompositionLocalOf { Palette(true, Color(0xFF15181D), Color(0xFF23272E), Color(0xFF8E8E93)) }

private val Dark = darkColorScheme(
    primary = K.Accent,
    onPrimary = Color(0xFF00281A),
    primaryContainer = Color(0xFF0F3D2C),
    onPrimaryContainer = Color(0xFFB7F5DA),
    secondary = Color(0xFFB9C1CC),
    secondaryContainer = Color(0xFF262B33),
    onSecondaryContainer = Color(0xFFE6E9EE),
    tertiary = K.Away,
    background = Color(0xFF000000),
    onBackground = Color(0xFFF2F3F5),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFF2F3F5),
    surfaceVariant = Color(0xFF1C1F24),
    onSurfaceVariant = Color(0xFF9EA4AD),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF0D0F12),
    surfaceContainer = Color(0xFF15181D),
    surfaceContainerHigh = Color(0xFF1C1F24),
    surfaceContainerHighest = Color(0xFF262A30),
    outline = Color(0xFF5A6069),
    outlineVariant = Color(0xFF23272E),
)

private val Light = lightColorScheme(
    primary = Color(0xFF00875A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC9F4E2),
    onPrimaryContainer = Color(0xFF002114),
    secondary = Color(0xFF545C66),
    secondaryContainer = Color(0xFFE4E7EB),
    tertiary = Color(0xFFC46A00),
    background = Color(0xFFF2F2F7),
    surface = Color(0xFFF2F2F7),
    surfaceVariant = Color(0xFFE5E5EA),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F7FA),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF0F0F4),
    surfaceContainerHighest = Color(0xFFE6E6EB),
    outlineVariant = Color(0xFFE0E0E5),
)

private val Type = Typography().let { t ->
    t.copy(
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 26.sp, letterSpacing = (-0.5).sp),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp),
    )
}

@Composable
fun ScanTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val scheme = if (dark) Dark else Light
    val pal = if (dark) Palette(true, Color(0xFF15181D), Color(0xFF23272E), Color(0xFF8E8E93))
    else Palette(false, Color.White, Color(0xFFE5E5EA), Color(0xFF8A8A8E))
    androidx.compose.runtime.CompositionLocalProvider(LocalPalette provides pal) {
        MaterialTheme(colorScheme = scheme, typography = Type, content = content)
    }
}
