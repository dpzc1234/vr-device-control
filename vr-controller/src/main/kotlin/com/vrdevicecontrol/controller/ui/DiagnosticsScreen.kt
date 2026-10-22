package com.vrdevicecontrol.controller.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vrdevicecontrol.controller.ui.components.LabeledValue
import com.vrdevicecontrol.controller.ui.components.Panel
import com.vrdevicecontrol.controller.ui.components.ScreenHeader
import com.vrdevicecontrol.controller.ui.components.StatusPill
import com.vrdevicecontrol.controller.ui.components.formatClockTime
import com.vrdevicecontrol.controller.ui.theme.Tone
import com.vrdevicecontrol.protocol.PROTOCOL_MAJOR
import com.vrdevicecontrol.protocol.PROTOCOL_MINOR
import com.vrdevicecontrol.protocol.SERVICE_TYPE
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Composable
fun DiagnosticsScreen(state: UiState, vm: ControllerViewModel, onUnlockGesture: () -> Unit) {
    // Hidden gesture: 7 taps on the app version within 4 s opens the developer PIN prompt.
    // No ripple and no countdown feedback, so nothing hints at it in customer mode.
    val taps = remember { mutableListOf<Long>() }
    val versionTap = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
        if (state.developer) return@clickable
        val now = System.currentTimeMillis()
        taps.removeAll { now - it > 4_000 }
        taps += now
        if (taps.size >= 7) {
            taps.clear()
            onUnlockGesture()
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let(vm::exportDiagnostics) }
    val host = state.host

    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 24.dp)) {
        ScreenHeader("Diagnostics", "Network, clock quality and logs") {
            Button(onClick = { exporter.launch("vr-diagnostics-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))}.json") }) {
                Icon(Icons.Rounded.FileDownload, null); Spacer(Modifier.width(8.dp)); Text("Export diagnostics")
            }
        }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                state.lastCrash?.let { crash ->
                    Panel(Modifier.fillMaxWidth(), highlight = Tone.BAD) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("The app closed unexpectedly last time", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = vm::clearCrash) { Text("Clear") }
                        }
                        Text("Use Export diagnostics to send this to the developers.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(10.dp))
                        Text(crash.lines().take(18).joinToString("\n"), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Panel(Modifier.fillMaxWidth()) {
                    Text("This tablet", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusPill(if (host.running) "Server running" else "Server stopped", if (host.running) Tone.GOOD else Tone.BAD)
                        StatusPill(if (host.advertised) "Discoverable" else "Not advertised", if (host.advertised) Tone.GOOD else Tone.WARN)
                    }
                    host.error?.let { Spacer(Modifier.height(8.dp)); Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth()) {
                        LabeledValue("Name", state.controllerName, Modifier.weight(1f))
                        LabeledValue("Port", host.port?.toString() ?: "—", Modifier.weight(0.5f))
                    }
                    Spacer(Modifier.height(10.dp))
                    LabeledValue("Service", "${host.serviceName ?: state.controllerName} · $SERVICE_TYPE")
                    Spacer(Modifier.height(10.dp))
                    LabeledValue("Controller ID", state.controllerId)
                    Spacer(Modifier.height(10.dp))
                    Text("ADDRESSES", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (state.addresses.isEmpty()) Text("No network connection", color = MaterialTheme.colorScheme.error)
                    state.addresses.forEach { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium) }
                }
                Panel(Modifier.fillMaxWidth()) {
                    Text("If a headset cannot find this tablet", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    val address = state.addresses.firstOrNull()?.substringBefore(' ') ?: "<tablet IP>"
                    Text(
                        "1. Both devices must be on the same Wi-Fi network.\n" +
                            "2. In the headset: Setup → Enter address manually → $address:${host.port ?: 47800} → Test connection.\n" +
                            "3. “Cannot reach” while discovery also finds nothing usually means the network blocks device-to-device traffic (client isolation). Use an allowed local network or hotspot; a manual address cannot bypass isolation.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Panel(Modifier.fillMaxWidth()) {
                    Text("Versions", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth()) {
                        LabeledValue("Tablet app", state.appVersion, Modifier.weight(1f).then(versionTap))
                        LabeledValue("Protocol", "$PROTOCOL_MAJOR.$PROTOCOL_MINOR", Modifier.weight(1f))
                        LabeledValue("Transport", "HTTP/WS + device credential", Modifier.weight(1.4f), tone = Tone.WARN)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("TLS with a pinned tablet identity is not yet enabled (open M0 decision gate). Use a trusted local network.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(Modifier.weight(1.2f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Panel(Modifier.fillMaxWidth()) {
                    Text("Headset clocks", style = MaterialTheme.typography.titleMedium)
                    Text("Start needs a fresh estimate within ±20 ms on every selected headset.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth()) {
                        listOf("Headset" to 1.4f, "Offset" to 1f, "± Uncert." to 1f, "RTT" to 0.8f, "Age" to 0.8f, "OK" to 0.6f).forEach { (h, w) ->
                            Text(h.uppercase(), Modifier.weight(w), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    state.devices.forEach { d ->
                        val c = d.clock
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(d.label, Modifier.weight(1.4f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(c?.let { "${it.offsetUs / 1000} ms" } ?: "—", Modifier.weight(1f), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                            Text(c?.let { "${"%.1f".format(it.uncertaintyUs / 1000.0)} ms" } ?: "—", Modifier.weight(1f), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                            Text(c?.let { "${"%.1f".format(it.rttUs / 1000.0)} ms" } ?: "—", Modifier.weight(0.8f), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                            Text(d.clockAgeMs?.let { "${it / 1000} s" } ?: "—", Modifier.weight(0.8f), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                            Text(if (!d.online) "offline" else if (d.clockAcceptable) "✓" else "✗", Modifier.weight(0.6f), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Panel(Modifier.fillMaxWidth().weight(1f)) {
                    Text("Recent events", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    LazyColumn {
                        items(state.events) { e ->
                            Row(Modifier.padding(vertical = 3.dp)) {
                                Text(formatClockTime(e.atUtc), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    buildString {
                                        append(e.type)
                                        e.deviceId?.let { id -> append(" · ").append(state.device(id)?.label ?: id.take(8)) }
                                        e.detail?.let { append(" · ").append(it) }
                                    },
                                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
