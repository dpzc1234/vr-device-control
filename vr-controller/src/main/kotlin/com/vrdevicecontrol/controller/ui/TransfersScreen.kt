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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vrdevicecontrol.controller.transfer.TransferJob
import com.vrdevicecontrol.controller.ui.components.EmptyState
import com.vrdevicecontrol.controller.ui.components.Panel
import com.vrdevicecontrol.controller.ui.components.ScreenHeader
import com.vrdevicecontrol.controller.ui.components.SlimProgress
import com.vrdevicecontrol.controller.ui.components.StatusDot
import com.vrdevicecontrol.controller.ui.components.StatusPill
import com.vrdevicecontrol.controller.ui.components.formatBytes
import com.vrdevicecontrol.controller.ui.theme.Tone
import com.vrdevicecontrol.domain.OperatorMessages
import com.vrdevicecontrol.protocol.TransferState

@Composable
fun TransfersScreen(state: UiState, vm: ControllerViewModel) {
    val byDevice = state.transfers.groupBy { it.deviceId }
    val active = state.transfers.count { it.state !in setOf(TransferState.AVAILABLE, TransferState.CANCELLED, TransferState.FAILED) }
    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 24.dp)) {
        ScreenHeader("Transfers", if (active == 0) "Nothing in progress" else "$active in progress · at most 2 at a time, 1 per headset") {
            if (state.transfers.any { it.state in setOf(TransferState.AVAILABLE, TransferState.CANCELLED, TransferState.FAILED) }) {
                OutlinedButton(onClick = vm::clearFinishedTransfers) { Text("Clear finished") }
            }
        }
        if (state.transfers.isEmpty()) {
            EmptyState(Icons.Rounded.Download, "No transfers", "Use “Send to headsets” in the Library. Progress, queued and verified copies appear here per headset.")
            return@Column
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(byDevice.entries.toList(), key = { it.key }) { (deviceId, jobs) ->
                val device = state.device(deviceId)
                Panel(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(if (device?.online == true) Tone.GOOD else Tone.WARN)
                        Spacer(Modifier.width(10.dp))
                        Text(device?.label ?: "Forgotten headset", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        device?.status?.let {
                            Text("${formatBytes(it.freeBytes)} free${if (it.transfersDeferred) " · paused during run" else ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    jobs.sortedByDescending { it.updatedAtUtc }.forEachIndexed { i, job ->
                        if (i > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        TransferRow(job, state, vm)
                    }
                }
            }
        }
    }
}

@Composable
private fun TransferRow(job: TransferJob, state: UiState, vm: ControllerViewModel) {
    val title = state.entry(job.assetId)?.asset?.title ?: state.library.firstOrNull { it.asset.sha256 == job.sha256 }?.asset?.title ?: "Video ${job.sha256.take(8)}"
    val blocked = state.transferBlocked[job.operationId]
    val (label, tone) = when (job.state) {
        TransferState.QUEUED -> (if (job.dispatched) "Starting" else "Queued") to Tone.NEUTRAL
        TransferState.DOWNLOADING -> "Downloading" to Tone.INFO
        TransferState.VERIFYING -> "Verifying checksum" to Tone.INFO
        TransferState.AVAILABLE -> "Verified on headset" to Tone.GOOD
        TransferState.PAUSED -> "Paused" to Tone.WARN
        TransferState.FAILED -> "Failed" to Tone.BAD
        TransferState.CANCELLED -> "Cancelled" to Tone.NEUTRAL
    }
    Column(Modifier.padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val reason = job.error?.let(OperatorMessages::full) ?: blocked?.takeIf { !job.dispatched }
                Text(
                    "${formatBytes(job.committedBytes)} of ${formatBytes(job.sizeBytes)}${reason?.let { " · $it" } ?: ""}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            StatusPill(label, tone)
            Spacer(Modifier.width(8.dp))
            when (job.state) {
                TransferState.FAILED -> TextButton(onClick = { vm.retryTransfer(job.operationId) }) { Text("Retry") }
                TransferState.AVAILABLE, TransferState.CANCELLED -> Unit
                else -> TextButton(onClick = { vm.cancelTransfer(job.operationId) }) { Text("Cancel") }
            }
        }
        if (job.state !in setOf(TransferState.AVAILABLE, TransferState.CANCELLED)) {
            Spacer(Modifier.height(8.dp))
            SlimProgress(job.committedBytes.toFloat() / job.sizeBytes.coerceAtLeast(1), tone, faded = job.state == TransferState.PAUSED)
        }
    }
}
