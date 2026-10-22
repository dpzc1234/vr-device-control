package com.vrdevicecontrol.controller.ui

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vrdevicecontrol.controller.catalog.CatalogEntry
import com.vrdevicecontrol.controller.ui.components.Chip
import com.vrdevicecontrol.controller.ui.components.EmptyState
import com.vrdevicecontrol.controller.ui.components.Panel
import com.vrdevicecontrol.controller.ui.components.ScreenHeader
import com.vrdevicecontrol.controller.ui.components.SlimProgress
import com.vrdevicecontrol.controller.ui.components.StatusDot
import com.vrdevicecontrol.controller.ui.components.VideoThumbnail
import com.vrdevicecontrol.controller.ui.components.codecLabel
import com.vrdevicecontrol.controller.ui.components.formatBytes
import com.vrdevicecontrol.controller.ui.components.formatDuration
import com.vrdevicecontrol.controller.ui.components.projectionLabel
import com.vrdevicecontrol.controller.ui.components.stereoLabel
import com.vrdevicecontrol.controller.ui.components.toneColor
import com.vrdevicecontrol.controller.ui.theme.Tone
import com.vrdevicecontrol.protocol.EyeOrder
import com.vrdevicecontrol.protocol.Projection
import com.vrdevicecontrol.protocol.StereoLayout
import com.vrdevicecontrol.protocol.TransferState

@Composable
fun LibraryScreen(state: UiState, vm: ControllerViewModel) {
    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::import) }
    // Fallback for tablets whose system file picker is missing or disabled (some vendor/kiosk builds).
    val getContent = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let(vm::import) }
    val launchPicker = {
        try {
            openDocument.launch(arrayOf("video/*"))
        } catch (e: ActivityNotFoundException) {
            try {
                getContent.launch("video/*")
            } catch (e2: ActivityNotFoundException) {
                vm.notify("This tablet has no file picker app. Install a file manager, then try again.")
            }
        }
    }
    var layoutFor by remember { mutableStateOf<CatalogEntry?>(null) }
    var distributeFor by remember { mutableStateOf<CatalogEntry?>(null) }
    var deleteFor by remember { mutableStateOf<CatalogEntry?>(null) }
    var renameFor by remember { mutableStateOf<CatalogEntry?>(null) }
    val importing = state.importProgress

    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 24.dp)) {
        ScreenHeader("Library", "${state.library.size} video${if (state.library.size == 1) "" else "s"} on this tablet") {
            Button(onClick = launchPicker, enabled = importing == null, modifier = Modifier.height(48.dp)) {
                Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(8.dp)); Text("Import video")
            }
        }

        if (importing != null) {
            Panel(Modifier.fillMaxWidth().padding(bottom = 16.dp), highlight = Tone.INFO) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Importing “${importing.name}”", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text(importing.phase, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(10.dp))
                val total = importing.totalBytes
                SlimProgress(if (total != null && total > 0) importing.copiedBytes.toFloat() / total else 0f, Tone.INFO)
                Spacer(Modifier.height(6.dp))
                Text(
                    "${formatBytes(importing.copiedBytes)}${total?.let { " of ${formatBytes(it)}" } ?: ""} · copied into the tablet and checksummed",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (state.library.isEmpty() && importing == null) {
            EmptyState(
                Icons.Rounded.VideoLibrary, "Import your first video",
                "Videos are copied into the tablet, then sent to each headset before a session. Playback never streams.",
            ) { Button(onClick = launchPicker) { Text("Import video") } }
            return@Column
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(320.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            items(state.library, key = { it.asset.assetId }) { e ->
                VideoCard(e, state, vm, onLayout = { layoutFor = e }, onDistribute = { distributeFor = e }, onDelete = { deleteFor = e }, onRename = { renameFor = e })
            }
        }
    }

    layoutFor?.let { e -> LayoutDialog(e, onDismiss = { layoutFor = null }) { p, s, o, w -> vm.updateLayout(e.asset.assetId, p, s, o, w); layoutFor = null } }
    distributeFor?.let { e -> DistributeDialog(e, state, onDismiss = { distributeFor = null }) { ids -> vm.distribute(e.asset.assetId, ids); distributeFor = null } }
    renameFor?.let { e ->
        var text by remember(e.asset.assetId) { mutableStateOf(e.asset.title) }
        AlertDialog(
            onDismissRequest = { renameFor = null },
            title = { Text("Rename video") },
            text = { OutlinedTextField(text, { text = it.take(80) }, singleLine = true, label = { Text("Title") }) },
            confirmButton = { Button(onClick = { vm.renameVideo(e.asset.assetId, text); renameFor = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renameFor = null }) { Text("Cancel") } },
        )
    }
    deleteFor?.let { e ->
        var alsoHeadsets by remember(e.asset.assetId) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("Delete “${e.asset.title}”?") },
            text = {
                Column {
                    Text("This removes the tablet copy. Copies on headsets stay unless you also remove them.")
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.clickable { alsoHeadsets = !alsoHeadsets }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(alsoHeadsets, { alsoHeadsets = it })
                        Text("Also remove from headsets that are online now")
                    }
                }
            },
            confirmButton = {
                Button(onClick = { vm.deleteVideo(e.asset.assetId, alsoHeadsets); deleteFor = null }, colors = ButtonDefaults.buttonColors(containerColor = toneColor(Tone.BAD))) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun VideoCard(
    e: CatalogEntry,
    state: UiState,
    vm: ControllerViewModel,
    onLayout: () -> Unit,
    onDistribute: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
) {
    val a = e.asset
    var menu by remember { mutableStateOf(false) }
    val transfers = state.transfers.filter { it.sha256 == a.sha256 && it.state !in setOf(TransferState.AVAILABLE, TransferState.CANCELLED) }
    Panel {
        VideoThumbnail(vm.thumbnail(a.assetId), a.assetId, Modifier.fillMaxWidth().aspectRatio(16f / 9f), e.presentation.projection)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(a.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${a.width}×${a.height} · ${codecLabel(a.videoCodec)}${a.frameRate?.let { " · ${it.toInt()} fps" } ?: ""} · ${formatDuration(a.durationUs)} · ${formatBytes(a.sizeBytes)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                state.devices.filter { a.sha256 in it.inventory }.forEach { d ->
                    DropdownMenuItem(text = { Text("Remove from ${d.label}") }, enabled = d.online, onClick = { menu = false; vm.removeFromHeadset(a.assetId, d.deviceId) })
                }
                DropdownMenuItem(text = { Text("Delete from tablet", color = toneColor(Tone.BAD)) }, onClick = { menu = false; onDelete() })
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip(projectionLabel(e.presentation.projection))
            Chip(stereoLabel(e.presentation.stereo))
            if (!e.presentationConfirmed) Chip("Check layout", tone = Tone.WARN)
        }
        Spacer(Modifier.height(14.dp))
        // Per-headset availability at a glance.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val have = state.devices.count { a.sha256 in it.inventory }
            Text("On $have of ${state.devices.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.devices.forEach { d ->
                val tone = when {
                    a.sha256 in d.inventory -> Tone.GOOD
                    transfers.any { it.deviceId == d.deviceId && it.state == TransferState.FAILED } -> Tone.BAD
                    transfers.any { it.deviceId == d.deviceId } -> Tone.INFO
                    else -> Tone.NEUTRAL
                }
                StatusDot(tone, 10.dp)
            }
        }
        if (transfers.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            val committed = transfers.sumOf { it.committedBytes }
            val total = transfers.sumOf { it.sizeBytes }.coerceAtLeast(1)
            SlimProgress(committed.toFloat() / total, Tone.INFO)
            Text("Sending to ${transfers.size} headset${if (transfers.size == 1) "" else "s"}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(onClick = onDistribute) { Icon(Icons.AutoMirrored.Rounded.Send, null, Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)); Text("Send to headsets") }
            OutlinedButton(onClick = onLayout) { Icon(Icons.Rounded.Tune, null, Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)); Text("Layout") }
        }
    }
}

@Composable
private fun LayoutDialog(e: CatalogEntry, onDismiss: () -> Unit, onSave: (Projection, StereoLayout, EyeOrder, Float) -> Unit) {
    var projection by remember { mutableStateOf(e.presentation.projection) }
    var stereo by remember { mutableStateOf(e.presentation.stereo) }
    var eyes by remember { mutableStateOf(e.presentation.eyeOrder) }
    var width by remember { mutableFloatStateOf(e.presentation.flatWidthMeters) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Video layout") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "Check with a known sample in the headset: wrong settings look doubled, stretched or swap the eyes. Changing the layout makes already-prepared headsets prepare again.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Segmented("Projection", Projection.entries, projection, ::projectionLabel) { projection = it }
                Segmented("Stereo", StereoLayout.entries, stereo, ::stereoLabel) { stereo = it }
                if (stereo != StereoLayout.MONO) {
                    Segmented("Eye order", EyeOrder.entries, eyes, { if (it == EyeOrder.LEFT_FIRST) "Left first" else "Right first" }) { eyes = it }
                    if (eyes == EyeOrder.RIGHT_FIRST) Text("Right-eye-first is not supported by the headset player yet; preparation will report it.", color = toneColor(Tone.WARN), style = MaterialTheme.typography.bodySmall)
                }
                if (projection == Projection.FLAT) {
                    Column {
                        Text("Screen width: ${"%.1f".format(width)} m", style = MaterialTheme.typography.labelLarge)
                        Slider(width, { width = it }, valueRange = 1.5f..8f)
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onSave(projection, stereo, eyes, width) }) { Text("Save layout") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun <T> Segmented(label: String, options: List<T>, selected: T, text: (T) -> String, onSelect: (T) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, o ->
                SegmentedButton(
                    selected = o == selected,
                    onClick = { onSelect(o) },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                ) { Text(text(o), maxLines = 1) }
            }
        }
    }
}

@Composable
private fun DistributeDialog(e: CatalogEntry, state: UiState, onDismiss: () -> Unit, onSend: (List<String>) -> Unit) {
    val missing = state.devices.filter { e.asset.sha256 !in it.inventory }
    var chosen by remember { mutableStateOf(missing.map { it.deviceId }.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send “${e.asset.title}”") },
        text = {
            Column {
                Text(
                    "${formatBytes(e.asset.sizeBytes)} per headset. Offline headsets are queued and receive it when they reconnect. Transfers pause while a headset is in a run.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(state.devices, key = { it.deviceId }) { d ->
                        val has = e.asset.sha256 in d.inventory
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = !has) {
                                chosen = if (d.deviceId in chosen) chosen - d.deviceId else chosen + d.deviceId
                            }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = has || d.deviceId in chosen, enabled = !has, onCheckedChange = {
                                chosen = if (it) chosen + d.deviceId else chosen - d.deviceId
                            })
                            Column(Modifier.weight(1f)) {
                                Text(d.label, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    when {
                                        has -> "Already on headset"
                                        !d.online -> "Out of contact — will be queued"
                                        else -> d.status?.let { "${formatBytes(it.freeBytes)} free" } ?: "Online"
                                    },
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onSend(chosen.toList()) }, enabled = chosen.isNotEmpty()) { Text("Send to ${chosen.size}") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
