package com.vrdevicecontrol.controller.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
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

val LocalStatusColors = staticCompositionLocalOf {
    StatusColors(
        neutral = Color(0xFF8B93A7),
        info = Color(0xFF60A5FA),
        good = Color(0xFF34D399),
        warn = Color(0xFFFBBF24),
        bad = Color(0xFFF87171),
        live = Color(0xFF4FD1C5),
    )
}

private val Colors = darkColorScheme(
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
fun ControllerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, typography = AppTypography, shapes = AppShapes, content = content)
}
