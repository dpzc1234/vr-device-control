package com.vrdevicecontrol.controller.ui.components

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vrdevicecontrol.controller.ui.theme.LocalStatusColors
import com.vrdevicecontrol.controller.ui.theme.Tone
import com.vrdevicecontrol.protocol.Projection
import com.vrdevicecontrol.protocol.StereoLayout
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun toneColor(tone: Tone): Color = LocalStatusColors.current.of(tone)

@Composable
fun StatusDot(tone: Tone, size: Dp = 9.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(toneColor(tone)))
}

/** Rounded status pill: tinted background, coloured dot and label. */
@Composable
fun StatusPill(text: String, tone: Tone, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val color = toneColor(tone)
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
        } else {
            StatusDot(tone, 8.dp)
        }
        Spacer(Modifier.width(8.dp))
        Text(text, color = color, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

@Composable
fun Chip(text: String, modifier: Modifier = Modifier, tone: Tone? = null) {
    val color = tone?.let { toneColor(it) } ?: MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text,
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (tone != null) color.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        color = color,
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
    )
}

@Composable
fun Panel(
    modifier: Modifier = Modifier,
    highlight: Tone? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val border = highlight?.let { toneColor(it).copy(alpha = 0.55f) } ?: MaterialTheme.colorScheme.outlineVariant
    Surface(
        modifier = modifier.border(1.dp, border, MaterialTheme.shapes.large),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(20.dp), content = content)
    }
}

@Composable
fun ScreenHeader(title: String, subtitle: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier, action: @Composable () -> Unit = {}) {
    Column(modifier.fillMaxWidth().padding(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(34.dp)) }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        action()
    }
}

@Composable
fun LabeledValue(label: String, value: String, modifier: Modifier = Modifier, tone: Tone? = null) {
    Column(modifier) {
        Text(label.uppercase(Locale.ROOT), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(2.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = tone?.let { toneColor(it) } ?: MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun SlimProgress(fraction: Float, tone: Tone = Tone.LIVE, modifier: Modifier = Modifier, faded: Boolean = false) {
    val color = toneColor(tone)
    LinearProgressIndicator(
        progress = { fraction.coerceIn(0f, 1f) },
        modifier = modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
        color = if (faded) color.copy(alpha = 0.45f) else color,
        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        strokeCap = StrokeCap.Round,
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}

private val thumbCache = LruCache<String, ImageBitmap>(48)

/** Video thumbnail with a graceful placeholder (8K streams often cannot be decoded on the tablet). */
@Composable
fun VideoThumbnail(file: File?, key: String, modifier: Modifier = Modifier, projection: Projection? = null) {
    val bitmap by produceState(thumbCache.get(key), file, key) {
        if (value == null && file != null) {
            value = withContext(Dispatchers.IO) {
                runCatching { BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() }.getOrNull()
            }?.also { thumbCache.put(key, it) }
        }
    }
    Box(
        modifier.clip(MaterialTheme.shapes.medium).background(
            Brush.linearGradient(listOf(Color(0xFF1B2A44), Color(0xFF12303A))),
        ),
        contentAlignment = Alignment.Center,
    ) {
        val b = bitmap
        if (b != null) {
            Image(b, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(Icons.Rounded.Movie, null, tint = Color.White.copy(alpha = 0.35f), modifier = Modifier.size(40.dp))
        }
        if (projection != null && projection != Projection.FLAT) {
            Text(
                if (projection == Projection.EQUIRECT_360) "360°" else "180°",
                Modifier.align(Alignment.TopEnd).padding(8.dp).clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 6.dp, vertical = 2.dp),
                color = Color.White, style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

// --- Formatting ---------------------------------------------------------------------------------

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes / 1024.0
    var i = 0
    while (value >= 1024 && i < units.lastIndex) { value /= 1024; i++ }
    return if (value >= 100) String.format(Locale.ROOT, "%.0f %s", value, units[i]) else String.format(Locale.ROOT, "%.1f %s", value, units[i])
}

fun formatDuration(us: Long): String {
    val total = (us / 1_000_000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
}

fun formatAgo(utc: String?): String {
    val then = utc?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return "never"
    val seconds = Duration.between(then, Instant.now()).seconds.coerceAtLeast(0)
    return when {
        seconds < 10 -> "just now"
        seconds < 60 -> "${seconds}s ago"
        seconds < 3600 -> "${seconds / 60} min ago"
        seconds < 86_400 -> "${seconds / 3600} h ago"
        else -> "${seconds / 86_400} d ago"
    }
}

fun formatClockTime(utc: String?): String {
    val then = utc?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return "—"
    return java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss").withZone(java.time.ZoneId.systemDefault()).format(then)
}

fun projectionLabel(p: Projection) = when (p) {
    Projection.FLAT -> "Flat"
    Projection.EQUIRECT_180 -> "180°"
    Projection.EQUIRECT_360 -> "360°"
}

fun stereoLabel(s: StereoLayout) = when (s) {
    StereoLayout.MONO -> "Mono"
    StereoLayout.SIDE_BY_SIDE -> "Side-by-side 3D"
    StereoLayout.TOP_BOTTOM -> "Top/bottom 3D"
}

fun codecLabel(mime: String?) = when (mime?.lowercase()) {
    null -> "Unknown codec"
    "video/hevc" -> "HEVC"
    "video/avc" -> "H.264"
    "video/av01" -> "AV1"
    "video/x-vnd.on2.vp9" -> "VP9"
    else -> mime.removePrefix("video/").uppercase(Locale.ROOT)
}
