package com.vrdevicecontrol.controller.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Semantic status tones used consistently across every screen. */
enum class Tone { NEUTRAL, INFO, GOOD, WARN, BAD, LIVE }

@Immutable
data class StatusColors(
    val neutral: Color,
    val info: Color,
    val good: Color,
    val warn: Color,
    val bad: Color,
    val live: Color,
) {
    fun of(tone: Tone) = when (tone) {
        Tone.NEUTRAL -> neutral
        Tone.INFO -> info
        Tone.GOOD -> good
        Tone.WARN -> warn
        Tone.BAD -> bad
        Tone.LIVE -> live
    }
}

val LocalStatusColors = staticCompositionLocalOf { DarkStatus }

private val DarkStatus = StatusColors(
    neutral = Color(0xFF8B93A7),
    info = Color(0xFF60A5FA),
    good = Color(0xFF34D399),
    warn = Color(0xFFFBBF24),
    bad = Color(0xFFF87171),
    live = Color(0xFF4FD1C5),
)

private val LightStatus = StatusColors(
    neutral = Color(0xFF64748B),
    info = Color(0xFF2563EB),
    good = Color(0xFF059669),
    warn = Color(0xFFD97706),
    bad = Color(0xFFDC2626),
    live = Color(0xFF0F766E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4FD1C5),
    onPrimary = Color(0xFF00201D),
    primaryContainer = Color(0xFF123B3A),
    onPrimaryContainer = Color(0xFFB4F1EA),
    secondary = Color(0xFF9AA8C7),
    onSecondary = Color(0xFF111827),
    secondaryContainer = Color(0xFF263048),
    onSecondaryContainer = Color(0xFFDCE3F5),
    tertiary = Color(0xFF34D399),
    background = Color(0xFF0E1117),
    onBackground = Color(0xFFE6E9F0),
    surface = Color(0xFF0E1117),
    onSurface = Color(0xFFE6E9F0),
    surfaceVariant = Color(0xFF1E2430),
    onSurfaceVariant = Color(0xFF9AA3B5),
    surfaceContainerLowest = Color(0xFF0A0D12),
    surfaceContainerLow = Color(0xFF131720),
    surfaceContainer = Color(0xFF171C26),
    surfaceContainerHigh = Color(0xFF1D2330),
    surfaceContainerHighest = Color(0xFF242B3A),
    outline = Color(0xFF3A4356),
    outlineVariant = Color(0xFF2A3142),
    error = Color(0xFFF87171),
    onError = Color(0xFF2B0707),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0F766E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCCFBF1),
    onPrimaryContainer = Color(0xFF042F2E),
    secondary = Color(0xFF475569),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE2E8F0),
    onSecondaryContainer = Color(0xFF0F172A),
    tertiary = Color(0xFF059669),
    background = Color(0xFFF4F6F8),
    onBackground = Color(0xFF111827),
    surface = Color(0xFFF7F8FA),
    onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFE5E9F0),
    onSurfaceVariant = Color(0xFF4B5563),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8FAFC),
    surfaceContainer = Color(0xFFEEF2F6),
    surfaceContainerHigh = Color(0xFFE6EBF2),
    surfaceContainerHighest = Color(0xFFDDE3EC),
    outline = Color(0xFFCBD5E1),
    outlineVariant = Color(0xFFD6DEE8),
    error = Color(0xFFDC2626),
    onError = Color(0xFFFFFFFF),
)

private val AppTypography = Typography(
    headlineMedium = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun ControllerTheme(dark: Boolean = true, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalStatusColors provides if (dark) DarkStatus else LightStatus) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
