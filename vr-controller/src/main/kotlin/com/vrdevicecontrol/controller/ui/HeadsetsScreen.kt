package com.vrdevicecontrol.controller.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vrdevicecontrol.controller.devices.DeviceView
import com.vrdevicecontrol.controller.devices.PendingPairing
import com.vrdevicecontrol.controller.ui.components.EmptyState
import com.vrdevicecontrol.controller.ui.components.LabeledValue
import com.vrdevicecontrol.controller.ui.components.Panel
import com.vrdevicecontrol.controller.ui.components.ScreenHeader
import com.vrdevicecontrol.controller.ui.components.SlimProgress
import com.vrdevicecontrol.controller.ui.components.StatusPill
import com.vrdevicecontrol.controller.ui.components.formatAgo
import com.vrdevicecontrol.controller.ui.components.formatBytes
import com.vrdevicecontrol.controller.ui.components.toneColor
import com.vrdevicecontrol.controller.ui.theme.Tone
import com.vrdevicecontrol.protocol.PairingStatus
import com.vrdevicecontrol.protocol.PlaybackState

@Composable
fun HeadsetsScreen(state: UiState, vm: ControllerViewModel) {
    var renaming by remember { mutableStateOf<DeviceView?>(null) }
    var forgetting by remember { mutableStateOf<DeviceView?>(null) }

    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 24.dp)) {
        ScreenHeader("Headsets", "${state.onlineCount} online · ${state.devices.size} paired") {
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Pairing mode", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.width(12.dp))
                    Switch(checked = state.pairingMode, onCheckedChange = vm::setPairingMode)
                }
                Text(
                    if (state.pairingMode) "New headsets can request pairing (turns off after 10 min)" else "Turn on to pair a new headset",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(330.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            val requests = state.pairing.filter { it.status != PairingStatus.CLOSED }
            if (requests.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text("Pairing requests", style = MaterialTheme.typography.titleMedium)
                }
                items(requests, key = { it.requestId }) { PairingCard(it, vm) }
                item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(4.dp)) }
            }
            if (state.devices.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyState(
                        Icons.Rounded.Link, "No headsets paired yet",
                        "1. Turn on Pairing mode.  2. On each headset open the app and choose this tablet.  3. Approve it here when the codes match.",
                    ) {
                        if (!state.pairingMode) Button(onClick = { vm.setPairingMode(true) }) { Text("Turn on pairing mode") }
                    }
                }
            } else {
                items(state.devices, key = { it.deviceId }) { d ->
                    DeviceCard(d, state, onRename = { renaming = d }, onForget = { forgetting = d })
                }
            }
        }
    }

    renaming?.let { d ->
        var text by remember(d.deviceId) { mutableStateOf(d.label) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename headset") },
            text = {
                Column {
                    Text("Shown on this tablet and inside the headset.", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(text, { text = it.take(32) }, singleLine = true, label = { Text("Name") })
                }
            },
            confirmButton = { Button(onClick = { vm.rename(d.deviceId, text); renaming = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
    forgetting?.let { d ->
        AlertDialog(
            onDismissRequest = { forgetting = null },
            title = { Text("Forget ${d.label}?") },
            text = { Text("Its credential is revoked and it disconnects. Videos already on the headset are not deleted. It must pair again to be used.") },
            confirmButton = {
                Button(onClick = { vm.revoke(d.deviceId); forgetting = null }, colors = ButtonDefaults.buttonColors(containerColor = toneColor(Tone.BAD))) { Text("Forget") }
            },
            dismissButton = { TextButton(onClick = { forgetting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PairingCard(p: PendingPairing, vm: ControllerViewModel) {
    Panel(highlight = if (p.status == PairingStatus.PENDING) Tone.INFO else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.deviceName, style = MaterialTheme.typography.titleMedium)
                Text("from ${p.remoteHost}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when (p.status) {
                PairingStatus.APPROVED -> StatusPill("Approved", Tone.GOOD)
                PairingStatus.REJECTED -> StatusPill("Rejected", Tone.NEUTRAL)
                PairingStatus.EXPIRED -> StatusPill("Expired", Tone.NEUTRAL)
                else -> Unit
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("Code shown in the headset", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(p.code.chunked(3).joinToString(" "), fontSize = 40.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        if (p.status == PairingStatus.PENDING) {
            Text("Approve only if this matches the code inside the headset.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { vm.rejectPairing(p.requestId) }) { Text("Reject") }
                Button(onClick = { vm.approvePairing(p.requestId) }) { Text("Codes match — approve") }
            }
        } else {
            TextButton(onClick = { vm.dismissPairing(p.requestId) }) { Text("Dismiss") }
        }
    }
}

@Composable
private fun DeviceCard(d: DeviceView, state: UiState, onRename: () -> Unit, onForget: () -> Unit) {
    val status = d.status
    Panel(highlight = if (d.online) null else Tone.WARN) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.ViewInAr, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(d.label, style = MaterialTheme.typography.titleLarge)
                Text("${d.record.model ?: "Quest"} · ${d.record.reportedName}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onRename) { Icon(Icons.Rounded.Edit, "Rename") }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill(if (d.online) "Online" else "Out of contact", if (d.online) Tone.GOOD else Tone.WARN)
            status?.let {
                val (label, tone) = playbackLabel(it.playbackState)
                StatusPill(if (d.statusIsLive) label else "Last: $label", if (d.statusIsLive) tone else Tone.NEUTRAL)
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth()) {
            LabeledValue("Battery", status?.batteryPercent?.let { "$it%${if (status.charging == true) " ⚡" else ""}" } ?: "—", Modifier.weight(1f),
                tone = status?.batteryPercent?.let { if (it < 20) Tone.BAD else if (it < 40) Tone.WARN else null })
            LabeledValue("App", when {
                status == null -> "—"
                !status.appActive -> "Inactive"
                status.worn == false -> "Not worn"
                else -> "Active"
            }, Modifier.weight(1f))
            LabeledValue("Last seen", if (d.online) "now" else formatAgo(d.record.lastSeenUtc), Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        status?.takeIf { it.totalBytes > 0 }?.let {
            Row {
                Text("Storage", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text("${formatBytes(it.freeBytes)} free of ${formatBytes(it.totalBytes)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(6.dp))
            val used = 1f - it.freeBytes.toFloat() / it.totalBytes
            SlimProgress(used, if (used > 0.9f) Tone.BAD else Tone.INFO)
            Spacer(Modifier.height(12.dp))
        }
        Row(Modifier.fillMaxWidth()) {
            LabeledValue("Videos", "${state.library.count { it.asset.sha256 in d.inventory }} of ${state.library.size}", Modifier.weight(1f))
            LabeledValue("App version", d.record.appVersion ?: "—", Modifier.weight(1f))
            LabeledValue("Clock", d.clock?.let { "±${it.uncertaintyUs / 1000} ms" } ?: "—", Modifier.weight(1f),
                tone = if (d.online && !d.clockAcceptable) Tone.WARN else null)
        }
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onForget, colors = ButtonDefaults.textButtonColors(contentColor = toneColor(Tone.BAD))) { Text("Forget headset") }
    }
}

fun playbackLabel(state: PlaybackState): Pair<String, Tone> = when (state) {
    PlaybackState.STANDBY -> "Standby" to Tone.NEUTRAL
    PlaybackState.PREPARING -> "Preparing" to Tone.INFO
    PlaybackState.READY -> "Ready" to Tone.GOOD
    PlaybackState.ARMED -> "Start scheduled" to Tone.LIVE
    PlaybackState.PLAYING -> "Playing" to Tone.LIVE
    PlaybackState.PAUSED -> "Paused" to Tone.WARN
    PlaybackState.ERROR -> "Error" to Tone.BAD
}
