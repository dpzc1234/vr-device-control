package com.vrdevicecontrol.controller.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vrdevicecontrol.controller.devices.DeviceView
import com.vrdevicecontrol.controller.ui.components.Chip
import com.vrdevicecontrol.controller.ui.components.EmptyState
import com.vrdevicecontrol.controller.ui.components.Panel
import com.vrdevicecontrol.controller.ui.components.SlimProgress
import com.vrdevicecontrol.controller.ui.components.StatusDot
import com.vrdevicecontrol.controller.ui.components.StatusPill
import com.vrdevicecontrol.controller.ui.components.VideoThumbnail
import com.vrdevicecontrol.controller.ui.components.codecLabel
import com.vrdevicecontrol.controller.ui.components.formatAgo
import com.vrdevicecontrol.controller.ui.components.formatBytes
import com.vrdevicecontrol.controller.ui.components.formatClockTime
import com.vrdevicecontrol.controller.ui.components.formatDuration
import com.vrdevicecontrol.controller.ui.components.projectionLabel
import com.vrdevicecontrol.controller.ui.components.stereoLabel
import com.vrdevicecontrol.controller.ui.components.toneColor
import com.vrdevicecontrol.controller.ui.theme.Tone
import com.vrdevicecontrol.domain.ControlStatus
import com.vrdevicecontrol.domain.Observed
import com.vrdevicecontrol.domain.OperatorMessages
import com.vrdevicecontrol.domain.Participant
import com.vrdevicecontrol.domain.PrepareStatus
import com.vrdevicecontrol.domain.RunPhase
import com.vrdevicecontrol.domain.StartAck
import com.vrdevicecontrol.protocol.MessageType
import com.vrdevicecontrol.protocol.PlaybackState

@Composable
fun RunScreen(state: UiState, vm: ControllerViewModel, onOpenLibrary: () -> Unit) {
    if (!state.developer) {
        OperatorRunScreen(state, vm)
        return
    }
    var picker by remember { mutableStateOf(false) }
    val runUi = state.run

    Column(Modifier.fillMaxSize().padding(start = 28.dp, end = 28.dp, top = 24.dp, bottom = 20.dp)) {
        RunHeader(state, runUi, onNewRun = vm::newRun)

        if (runUi == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (state.library.isEmpty()) {
                    EmptyState(Icons.Rounded.VideoLibrary, "No videos yet", "Import the experience video into the library, send it to the headsets, then come back here.") {
                        Button(onClick = onOpenLibrary) { Text("Open library") }
                    }
                } else {
                    EmptyState(Icons.Rounded.PlayArrow, "Start a new run", "Choose the video for this session. All online headsets are selected by default.") {
                        Button(onClick = { picker = true }) { Text("Choose video") }
                    }
                }
            }
        } else {
            if (runUi.fromPreviousEpoch) PreviousEpochBanner(onClose = vm::newRun)
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                VideoColumn(state, runUi, vm, onChange = { picker = true })
                HeadsetGrid(state, runUi, vm, Modifier.weight(1f).fillMaxHeight())
            }
            Spacer(Modifier.height(16.dp))
            ActionBar(state, runUi, vm)
        }
    }

    if (picker) {
        VideoPicker(state, vm, onDismiss = { picker = false }, onPick = {
            vm.selectVideo(it)
            picker = false
        })
    }
}

// --- Header -------------------------------------------------------------------------------------

@Composable
private fun RunHeader(state: UiState, runUi: RunUi?, onNewRun: () -> Unit, operatorMode: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Session", style = MaterialTheme.typography.headlineMedium)
            Text(
                "${state.onlineCount} of ${state.devices.size} headsets online",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (runUi != null && !(operatorMode && runUi.phase == RunPhase.DRAFT)) {
            val (label, tone) = phaseLabel(runUi.phase)
            StatusPill(label, tone)
            if (runUi.run.startAtControllerUs != null || runUi.phase == RunPhase.FINISHED) {
                Spacer(Modifier.width(12.dp))
                OutlinedButton(onClick = onNewRun) { Text("Close run") }
            }
        }
    }
}

private fun phaseLabel(phase: RunPhase): Pair<String, Tone> = when (phase) {
    RunPhase.DRAFT -> "Choosing headsets" to Tone.NEUTRAL
    RunPhase.PREPARING -> "Preparing" to Tone.INFO
    RunPhase.READY -> "Ready to start" to Tone.GOOD
    RunPhase.STARTING -> "Starting…" to Tone.LIVE
    RunPhase.ACTIVE -> "Playing" to Tone.LIVE
    RunPhase.FINISHED -> "Finished" to Tone.GOOD
    RunPhase.NEEDS_ATTENTION -> "Needs attention" to Tone.WARN
    RunPhase.UNCERTAIN -> "Outcome uncertain" to Tone.WARN
}

@Composable
private fun PreviousEpochBanner(onClose: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().padding(bottom = 16.dp),
        shape = MaterialTheme.shapes.medium,
        color = toneColor(Tone.WARN).copy(alpha = 0.12f),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.WarningAmber, null, tint = toneColor(Tone.WARN))
            Spacer(Modifier.width(12.dp))
            Text(
                "This run began before the tablet app restarted. Results update as headsets report back; controls still reach connected headsets. A new run needs your confirmation.",
                Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onClose) { Text("Close run") }
        }
    }
}

// --- Video column -------------------------------------------------------------------------------

@Composable
private fun VideoColumn(state: UiState, runUi: RunUi, vm: ControllerViewModel, onChange: () -> Unit) {
    val run = runUi.run
    val asset = run.asset
    Panel(Modifier.width(330.dp).fillMaxHeight()) {
        VideoThumbnail(vm.thumbnail(asset.assetId), asset.assetId, Modifier.fillMaxWidth().aspectRatio(16f / 9f), run.presentation.projection)
        Spacer(Modifier.height(14.dp))
        Text(asset.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip(projectionLabel(run.presentation.projection))
            Chip(stereoLabel(run.presentation.stereo))
            Chip(formatDuration(asset.durationUs))
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "${asset.width}×${asset.height} · ${codecLabel(asset.videoCodec)}${asset.frameRate?.let { " · ${it.toInt()} fps" } ?: ""} · ${formatBytes(asset.sizeBytes)}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (runUi.entry?.presentationConfirmed == false && state.developer) {
            Spacer(Modifier.height(10.dp))
            Chip("Layout not checked yet", tone = Tone.WARN)
        }
        Spacer(Modifier.height(14.dp))
        if (!run.frozen) {
            OutlinedButton(onClick = onChange, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.SwapHoriz, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Change video")
            }
        }
        Spacer(Modifier.weight(1f))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(14.dp))
        RunTimeline(runUi)
    }
}

@Composable
private fun RunTimeline(runUi: RunUi) {
    val run = runUi.run
    val ps = run.participants
    @Composable
    fun line(label: String, value: String, tone: Tone? = null) {
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = tone?.let { toneColor(it) } ?: MaterialTheme.colorScheme.onSurface)
        }
    }
    line("Headsets in run", "${ps.size}")
    if (run.frozen && run.startAtControllerUs == null) {
        line("Ready", "${ps.count { it.prepare == PrepareStatus.READY && it.deviceId !in runUi.blockers }} / ${ps.size}", if (runUi.canStart) Tone.GOOD else null)
    }
    if (run.startAtControllerUs != null) {
        line("Start sent", formatClockTime(run.startRequestedAtUtc))
        line("Start accepted", "${ps.count { it.startAck == StartAck.ACCEPTED }} / ${ps.size}")
        line("Playing confirmed", "${ps.count { it.observed != Observed.NONE }} / ${ps.size}", Tone.LIVE)
        line("Completed", "${ps.count { it.observed == Observed.COMPLETED }} / ${ps.size}", Tone.GOOD)
        runUi.estimatedPositionUs?.let { line("Elapsed (estimated)", "${formatDuration(it)} / ${formatDuration(run.asset.durationUs)}") }
    }
}

// --- Headset grid -------------------------------------------------------------------------------

@Composable
private fun HeadsetGrid(state: UiState, runUi: RunUi, vm: ControllerViewModel, modifier: Modifier) {
    val run = runUi.run
    val shown: List<String> = if (!run.frozen) {
        state.devices.map { it.deviceId }
    } else {
        run.participants.map { it.deviceId }
    }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (run.frozen) "Headsets in this run" else "Select headsets", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (!run.frozen && state.devices.isNotEmpty()) {
                TextButton(onClick = vm::selectAllOnline) { Text("Select all online") }
            }
        }
        if (shown.isEmpty()) {
            EmptyState(Icons.Rounded.ViewInAr, "No headsets paired", "Open the Headsets tab to pair the Quest headsets with this tablet.")
            return
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(250.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 8.dp),
        ) {
            items(shown, key = { it }) { id ->
                HeadsetTile(state.device(id), run.participant(id), runUi, vm, id)
            }
        }
    }
}

private data class TileStatus(val label: String, val detail: String?, val tone: Tone, val icon: ImageVector)

private fun tileStatus(view: DeviceView?, p: Participant?, runUi: RunUi): TileStatus {
    val run = runUi.run
    val online = view?.online == true
    val hasVideo = view?.let { run.asset.sha256 in it.inventory } == true
    val offlineNote = if (online) null else "Out of contact · last seen ${formatAgo(view?.record?.lastSeenUtc)}"

    if (!run.frozen) {
        return when {
            p == null -> TileStatus("Not selected", offlineNote, Tone.NEUTRAL, Icons.Rounded.Close)
            !online -> TileStatus("Out of contact", "Selected, but not reachable now", Tone.WARN, Icons.Rounded.WifiOff)
            !hasVideo -> TileStatus("Video missing", "Send the video before preparing", Tone.WARN, Icons.Rounded.WarningAmber)
            view.busyWithRun -> TileStatus("Busy", "Still playing a previous run", Tone.WARN, Icons.Rounded.HourglassTop)
            else -> TileStatus("Selected", "Video on headset", Tone.INFO, Icons.Rounded.Check)
        }
    }
    p ?: return TileStatus("Not in run", null, Tone.NEUTRAL, Icons.Rounded.Close)

    if (run.startAtControllerUs == null) {
        val blocker = runUi.blockers[p.deviceId]
        return when (p.prepare) {
            PrepareStatus.READY -> if (blocker == null) TileStatus("Ready", "Holding at the first frame", Tone.GOOD, Icons.Rounded.CheckCircle)
            else TileStatus("Not ready", blocker, Tone.WARN, Icons.Rounded.WarningAmber)
            PrepareStatus.SENT, PrepareStatus.ACCEPTED -> TileStatus("Preparing…", blocker?.takeIf { it != "Preparing…" } ?: "Loading video and syncing clock", Tone.INFO, Icons.Rounded.HourglassTop)
            PrepareStatus.NOT_READY, PrepareStatus.REJECTED -> TileStatus("Not ready", p.prepareError?.let(OperatorMessages::full) ?: blocker, Tone.BAD, Icons.Rounded.Error)
            PrepareStatus.UNREACHABLE, PrepareStatus.NOT_SENT -> TileStatus("Not reached", offlineNote ?: "Prepare could not be delivered", Tone.BAD, Icons.Rounded.WifiOff)
        }
    }

    val liveStatus = view?.takeIf { it.statusIsLive }?.status
    return when (p.observed) {
        Observed.COMPLETED -> TileStatus("Completed", "Finished at ${formatClockTime(p.observedAtUtc)}", Tone.GOOD, Icons.Rounded.CheckCircle)
        Observed.STOPPED -> TileStatus("Stopped", "Back in standby", Tone.NEUTRAL, Icons.Rounded.Stop)
        Observed.ERROR -> TileStatus("Playback error", p.resultError?.let(OperatorMessages::full), Tone.BAD, Icons.Rounded.Error)
        Observed.PAUSED -> TileStatus("Paused", offlineNote ?: "Paused by operator", Tone.WARN, Icons.Rounded.Pause)
        Observed.INTERRUPTED -> TileStatus("Interrupted", offlineNote ?: "Headset paused itself (removed or asleep)", Tone.WARN, Icons.Rounded.Pause)
        Observed.STARTED -> if (online && liveStatus?.playbackState == PlaybackState.PLAYING) {
            TileStatus("Playing", "Confirmed by headset", Tone.LIVE, Icons.Rounded.PlayArrow)
        } else {
            TileStatus("Playing", offlineNote?.let { "$it · last confirmed playing" } ?: "Started at ${formatClockTime(p.observedAtUtc)}", Tone.LIVE, Icons.Rounded.PlayArrow)
        }
        Observed.NONE -> when (p.startAck) {
            StartAck.ACCEPTED -> TileStatus("Start accepted", if (runUi.phase == RunPhase.STARTING) "Scheduled — starts with the group" else offlineNote ?: "Awaiting playback report", Tone.INFO, Icons.Rounded.Timer)
            StartAck.SENT -> if (runUi.phase == RunPhase.STARTING) {
                TileStatus("Awaiting acknowledgement", null, Tone.INFO, Icons.Rounded.HourglassTop)
            } else {
                TileStatus("Outcome unknown", "No acknowledgement — it may or may not be playing", Tone.WARN, Icons.Rounded.WarningAmber)
            }
            StartAck.REJECTED -> TileStatus("Start rejected", p.startError?.let(OperatorMessages::full), Tone.BAD, Icons.Rounded.Error)
            StartAck.UNREACHABLE -> TileStatus("Start not delivered", offlineNote ?: "Headset was out of contact", Tone.BAD, Icons.Rounded.WifiOff)
            StartAck.NONE -> TileStatus("Waiting", null, Tone.NEUTRAL, Icons.Rounded.HourglassTop)
        }
    }
}

@Composable
private fun HeadsetTile(view: DeviceView?, p: Participant?, runUi: RunUi, vm: ControllerViewModel, deviceId: String) {
    val run = runUi.run
    val status = tileStatus(view, p, runUi)
    val selectable = !run.frozen
    val selected = p != null
    val accent = toneColor(status.tone)
    val borderColor by animateColorAsState(
        if (selectable && selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
        else if (run.frozen) accent.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outlineVariant,
        label = "border",
    )
    val online = view?.online == true

    Surface(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 196.dp)
            .border(1.5.dp, borderColor, MaterialTheme.shapes.large)
            .clip(MaterialTheme.shapes.large)
            .then(if (selectable) Modifier.clickable { vm.toggleParticipant(deviceId) } else Modifier),
        color = if (!online) MaterialTheme.colorScheme.surfaceContainerLowest else MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(16.dp).alpha(if (selectable && !selected) 0.6f else 1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selectable) {
                    Checkbox(
                        checked = selected, onCheckedChange = { vm.toggleParticipant(deviceId) },
                        colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                } else {
                    HeadsetBadge(view?.label ?: "?")
                    Spacer(Modifier.width(10.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(view?.label ?: "Unknown headset", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(if (online) Tone.GOOD else Tone.WARN, 7.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (online) "Online" else "Out of contact",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (run.frozen && p != null) TileMenu(runUi, p, online, vm)
            }

            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                LiveIcon(status)
                Spacer(Modifier.width(10.dp))
                Text(status.label, style = MaterialTheme.typography.titleLarge, color = accent)
            }
            status.detail?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }

            ProgressRow(view, p, runUi)
            p?.lastControl?.let { c ->
                Spacer(Modifier.height(6.dp))
                val (text, tone) = controlLabel(c.type, c.status)
                Chip(text, tone = tone)
            }

            Spacer(Modifier.weight(1f, fill = false))
            Spacer(Modifier.height(12.dp))
            DeviceFooter(view)
        }
    }
}

@Composable
private fun LiveIcon(status: TileStatus) {
    val color = toneColor(status.tone)
    if (status.tone == Tone.LIVE) {
        val pulse = rememberInfiniteTransition(label = "live")
        val scale by pulse.animateFloat(0.85f, 1.15f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "scale")
        Box(Modifier.size(28.dp).scale(scale).clip(CircleShape).background(color.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
            Icon(status.icon, null, tint = color, modifier = Modifier.size(18.dp))
        }
    } else {
        Box(Modifier.size(28.dp).clip(CircleShape).background(color.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(status.icon, null, tint = color, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun HeadsetBadge(label: String) {
    val number = Regex("(\\d+)").find(label)?.value ?: label.take(1)
    Box(
        Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) { Text(number, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer) }
}

@Composable
private fun ProgressRow(view: DeviceView?, p: Participant?, runUi: RunUi) {
    val run = runUi.run
    if (p == null || run.startAtControllerUs == null) return
    if (p.observed !in setOf(Observed.STARTED, Observed.PAUSED, Observed.INTERRUPTED)) return
    val duration = run.asset.durationUs.coerceAtLeast(1)
    val live = view?.takeIf { it.online && it.statusIsLive }?.status?.takeIf { it.runId == run.runId }
    Spacer(Modifier.height(10.dp))
    if (live != null && live.positionUs > 0) {
        SlimProgress(live.positionUs.toFloat() / duration, Tone.LIVE)
        Spacer(Modifier.height(4.dp))
        Text("${formatDuration(live.positionUs)} / ${formatDuration(duration)} · reported", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        val est = runUi.estimatedPositionUs ?: 0
        SlimProgress(est.toFloat() / duration, Tone.LIVE, faded = true)
        Spacer(Modifier.height(4.dp))
        Text("≈ ${formatDuration(est)} / ${formatDuration(duration)} · estimated, not confirmed", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DeviceFooter(view: DeviceView?) {
    val status = view?.status
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        status?.batteryPercent?.let { pct ->
            val tone = if (pct < 20) Tone.BAD else if (pct < 40) Tone.WARN else Tone.NEUTRAL
            FooterItem(if (status.charging == true) Icons.Rounded.BatteryChargingFull else Icons.Rounded.BatteryFull, "$pct%", tone)
        }
        status?.takeIf { it.totalBytes > 0 }?.let { FooterItem(Icons.Rounded.SdStorage, "${formatBytes(it.freeBytes)} free", Tone.NEUTRAL) }
        view?.clock?.let { c ->
            FooterItem(Icons.Rounded.Timer, "±${c.uncertaintyUs / 1000} ms", if (view.clockAcceptable) Tone.NEUTRAL else Tone.WARN)
        }
    }
}

@Composable
private fun FooterItem(icon: ImageVector, text: String, tone: Tone) {
    val color = if (tone == Tone.NEUTRAL) MaterialTheme.colorScheme.onSurfaceVariant else toneColor(tone)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

private fun controlLabel(type: String, status: ControlStatus): Pair<String, Tone> {
    val action = when (type) {
        MessageType.PAUSE -> "Pause"
        MessageType.RESUME -> "Resume"
        MessageType.STOP -> "Stop"
        MessageType.CANCEL_START -> "Cancel start"
        else -> type
    }
    return when (status) {
        ControlStatus.PENDING -> "$action: sending…" to Tone.INFO
        ControlStatus.ACCEPTED -> "$action: confirmed" to Tone.GOOD
        ControlStatus.REJECTED -> "$action: refused" to Tone.BAD
        ControlStatus.UNREACHABLE -> "$action: not delivered (offline)" to Tone.WARN
        ControlStatus.NO_RESPONSE -> "$action: no response" to Tone.WARN
    }
}

@Composable
private fun TileMenu(runUi: RunUi, p: Participant, online: Boolean, vm: ControllerViewModel) {
    var open by remember { mutableStateOf(false) }
    val run = runUi.run
    val started = run.startAtControllerUs != null
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Rounded.MoreVert, "Headset actions") }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            if (!started && !runUi.fromPreviousEpoch) {
                DropdownMenuItem(text = { Text("Retry prepare") }, enabled = online, onClick = { vm.retryPrepare(p.deviceId); open = false })
                DropdownMenuItem(text = { Text("Remove from this run") }, onClick = { vm.removeParticipant(p.deviceId); open = false })
                if (p.prepareError?.code == com.vrdevicecontrol.protocol.ErrorCode.ASSET_MISSING) {
                    DropdownMenuItem(text = { Text("Send video") }, onClick = { vm.distribute(run.asset.assetId, listOf(p.deviceId)); open = false })
                }
            } else if (started) {
                DropdownMenuItem(text = { Text("Pause") }, enabled = online, onClick = { vm.control(MessageType.PAUSE, p.deviceId); open = false })
                DropdownMenuItem(text = { Text("Resume") }, enabled = online, onClick = { vm.control(MessageType.RESUME, p.deviceId); open = false })
                DropdownMenuItem(text = { Text("Stop to standby") }, enabled = online, onClick = { vm.control(MessageType.STOP, p.deviceId); open = false })
            }
            if (!online) {
                DropdownMenuItem(text = { Text("Out of contact — controls unavailable") }, enabled = false, onClick = {})
            }
        }
    }
}

// --- Action bar ---------------------------------------------------------------------------------

@Composable
private fun ActionBar(state: UiState, runUi: RunUi, vm: ControllerViewModel) {
    val run = runUi.run
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    Surface(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp,
    ) {
        Row(Modifier.padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { ActionSummary(state, runUi) }
            Spacer(Modifier.width(16.dp))
            AnimatedContent(
                targetState = actionMode(runUi),
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "actions",
            ) { mode ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    when (mode) {
                        ActionMode.DRAFT -> {
                            val missing = run.participants.filter { p -> state.device(p.deviceId)?.let { run.asset.sha256 !in it.inventory } == true }
                            if (missing.isNotEmpty()) {
                                FilledTonalButton(onClick = { vm.distribute(run.asset.assetId, missing.map { it.deviceId }) }) {
                                    Text("Send video to ${missing.size}")
                                }
                            }
                            BigButton("Prepare ${run.participants.size} headset${if (run.participants.size == 1) "" else "s"}", Icons.Rounded.ViewInAr, enabled = run.participants.isNotEmpty(), onClick = vm::prepare)
                        }
                        ActionMode.PREPARED -> if (runUi.fromPreviousEpoch) {
                            // A restored preparation cannot be started; readiness belongs to the old process.
                            BigButton("New run", Icons.Rounded.PlayArrow, onClick = vm::newRun)
                        } else {
                            TextButton(onClick = vm::backToDraft) { Text("Edit selection") }
                            if (run.participants.any { it.prepare != PrepareStatus.READY } || runUi.blockers.isNotEmpty()) {
                                OutlinedButton(onClick = vm::retryAllNotReady) {
                                    Icon(Icons.Rounded.Replay, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Retry not ready")
                                }
                            }
                            StartButton(enabled = runUi.canStart, onClick = vm::start)
                        }
                        ActionMode.STARTING -> {
                            OutlinedButton(onClick = { confirm = "Try to cancel the scheduled start? Headsets that already accepted may still start if they cannot be reached." to vm::cancelStart }) {
                                Text("Cancel start")
                            }
                            StartingIndicator(runUi.untilStartUs)
                        }
                        ActionMode.LIVE -> {
                            OutlinedButton(onClick = vm::pauseAll) { Icon(Icons.Rounded.Pause, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Pause all") }
                            OutlinedButton(onClick = vm::resumeAll) { Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Resume all") }
                            OutlinedButton(
                                onClick = { confirm = "Stop playback and return reachable headsets to standby?" to vm::stopAll },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = toneColor(Tone.BAD)),
                            ) { Icon(Icons.Rounded.Stop, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Stop all") }
                            FilledTonalButton(onClick = { confirm = "Restart from the beginning? Reachable headsets stop now and prepare again; you will press Start again." to vm::restart }) {
                                Icon(Icons.Rounded.Replay, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Restart")
                            }
                        }
                        ActionMode.FINISHED -> {
                            OutlinedButton(onClick = vm::restart) { Icon(Icons.Rounded.Replay, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Same video again") }
                            BigButton("New run", Icons.Rounded.PlayArrow, onClick = vm::newRun)
                        }
                    }
                }
            }
        }
    }
    confirm?.let { (text, action) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Are you sure?") },
            text = { Text(text) },
            confirmButton = { Button(onClick = { action(); confirm = null }) { Text("Confirm") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Back") } },
        )
    }
}

private enum class ActionMode { DRAFT, PREPARED, STARTING, LIVE, FINISHED }

private fun actionMode(runUi: RunUi): ActionMode {
    val run = runUi.run
    return when {
        !run.frozen -> ActionMode.DRAFT
        run.startAtControllerUs == null -> ActionMode.PREPARED
        runUi.phase == RunPhase.STARTING -> ActionMode.STARTING
        runUi.phase == RunPhase.FINISHED -> ActionMode.FINISHED
        else -> ActionMode.LIVE
    }
}

@Composable
private fun ActionSummary(state: UiState, runUi: RunUi) {
    val run = runUi.run
    val ps = run.participants
    val (title, body) = when (actionMode(runUi)) {
        ActionMode.DRAFT -> {
            val offline = ps.count { state.device(it.deviceId)?.online != true }
            val missing = ps.count { p -> state.device(p.deviceId)?.let { run.asset.sha256 !in it.inventory } == true }
            "${ps.size} headset${if (ps.size == 1) "" else "s"} selected" to listOfNotNull(
                "$offline out of contact".takeIf { offline > 0 },
                "$missing missing the video".takeIf { missing > 0 },
            ).joinToString(" · ").ifEmpty { "Prepare loads the video silently behind the standby scene." }
        }
        ActionMode.PREPARED -> if (runUi.canStart) {
            "All ${ps.size} headsets ready" to "Press Start when participants are ready. The video's own countdown plays from the beginning."
        } else {
            val first = runUi.blockers.entries.firstOrNull()
            "Waiting for ${runUi.blockers.size} of ${ps.size}" to (first?.let { "${state.device(it.key)?.label ?: "Headset"}: ${it.value}" } ?: "Preparing…")
        }
        ActionMode.STARTING -> "Starting…" to "Coordinating start. Acceptances and observed playback are shown per headset."
        ActionMode.LIVE -> {
            val confirmed = ps.count { it.observed == Observed.STARTED }
            val unknown = ps.count { it.observed == Observed.NONE && it.startAck != StartAck.REJECTED }
            "$confirmed confirmed playing" to listOfNotNull(
                "${ps.count { it.observed == Observed.COMPLETED }} completed".takeIf { ps.any { it.observed == Observed.COMPLETED } },
                "$unknown not yet confirmed".takeIf { unknown > 0 },
                "Controls reach only headsets in contact.",
            ).joinToString(" · ")
        }
        ActionMode.FINISHED -> {
            val completed = ps.count { it.observed == Observed.COMPLETED }
            "Run finished" to "$completed of ${ps.size} completed${ps.count { it.observed == Observed.ERROR || it.startAck == StartAck.REJECTED }.takeIf { it > 0 }?.let { " · $it with problems" } ?: ""}"
        }
    }
    Text(title, style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(2.dp))
    Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun BigButton(text: String, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.height(56.dp), contentPadding = PaddingValues(horizontal = 28.dp)) {
        Icon(icon, null, Modifier.size(22.dp)); Spacer(Modifier.width(10.dp)); Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

/** The one action that matters: large, unmistakable, and disabled with a lock until everyone is ready. */
@Composable
private fun StartButton(enabled: Boolean, onClick: () -> Unit) {
    val green = toneColor(Tone.GOOD)
    val glow = rememberInfiniteTransition(label = "glow")
    val alpha by glow.animateFloat(0.35f, 0.8f, infiniteRepeatable(tween(1_200), RepeatMode.Reverse), label = "alpha")
    Box(
        Modifier
            .height(68.dp)
            .width(240.dp)
            .clip(RoundedCornerShape(34.dp))
            .then(
                if (enabled) Modifier.border(3.dp, green.copy(alpha = alpha), RoundedCornerShape(34.dp))
                    .background(Brush.horizontalGradient(listOf(Color(0xFF16A34A), Color(0xFF22C55E))))
                    .clickable(onClick = onClick)
                else Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (enabled) Icons.Rounded.PlayArrow else Icons.Rounded.Lock, null, tint = if (enabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(30.dp))
            Spacer(Modifier.width(10.dp))
            Text("START", style = MaterialTheme.typography.headlineSmall, color = if (enabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun StartingIndicator(untilStartUs: Long?) {
    Box(
        Modifier.height(68.dp).width(240.dp).clip(RoundedCornerShape(34.dp)).background(toneColor(Tone.LIVE).copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(24.dp), color = toneColor(Tone.LIVE), strokeWidth = 3.dp)
            Spacer(Modifier.width(14.dp))
            Text("Starting…", style = MaterialTheme.typography.headlineSmall, color = toneColor(Tone.LIVE))
        }
    }
}

// --- Video picker -------------------------------------------------------------------------------

@Composable
private fun VideoPicker(state: UiState, vm: ControllerViewModel, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose the experience video") },
        text = {
            if (state.library.isEmpty()) {
                Text("The library is empty. Import a video first.")
            } else {
                LazyColumn(Modifier.heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(state.library, key = { it.asset.assetId }) { e ->
                        val onHeadsets = state.devices.count { e.asset.sha256 in it.inventory }
                        Row(
                            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .clickable { onPick(e.asset.assetId) }.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            VideoThumbnail(vm.thumbnail(e.asset.assetId), e.asset.assetId, Modifier.width(120.dp).aspectRatio(16f / 9f), e.presentation.projection)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(e.asset.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${projectionLabel(e.presentation.projection)} · ${stereoLabel(e.presentation.stereo)} · ${formatDuration(e.asset.durationUs)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(4.dp))
                                Chip("On $onHeadsets of ${state.devices.size} headsets", tone = if (onHeadsets == state.devices.size && onHeadsets > 0) Tone.GOOD else Tone.WARN)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

// --- Operator (customer) run -------------------------------------------------------------------

@Composable
private fun OperatorRunScreen(state: UiState, vm: ControllerViewModel) {
    val runUi = state.run
    Column(Modifier.fillMaxSize().padding(start = 28.dp, end = 28.dp, top = 24.dp, bottom = 20.dp)) {
        RunHeader(state, runUi, onNewRun = vm::newRun, operatorMode = true)
        if (state.library.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                EmptyState(Icons.Rounded.VideoLibrary, "No videos installed", "Contact your content provider to have experience videos added to this tablet.")
            }
        } else {
            if (runUi?.fromPreviousEpoch == true) PreviousEpochBanner(onClose = vm::newRun)
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                ScenarioColumn(state, runUi, vm, Modifier.width(320.dp).fillMaxHeight())
                OperatorHeadsetColumn(state, runUi, vm, Modifier.weight(1f).fillMaxHeight())
            }
            if (runUi != null) {
                Spacer(Modifier.height(16.dp))
                ActionBar(state, runUi, vm)
            }
        }
    }
}

@Composable
private fun ScenarioColumn(state: UiState, runUi: RunUi?, vm: ControllerViewModel, modifier: Modifier) {
    val frozen = runUi?.run?.frozen == true
    val selectedId = runUi?.run?.asset?.assetId
    Column(modifier) {
        Text("Select Scenario:", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 8.dp),
        ) {
            items(state.library, key = { it.asset.assetId }) { entry ->
                val selected = entry.asset.assetId == selectedId
                Surface(
                    Modifier
                        .fillMaxWidth()
                        .border(
                            if (selected) 2.dp else 1.dp,
                            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            MaterialTheme.shapes.large,
                        )
                        .clip(MaterialTheme.shapes.large)
                        .then(if (frozen) Modifier else Modifier.clickable { vm.selectVideo(entry.asset.assetId) }),
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        VideoThumbnail(
                            vm.thumbnail(entry.asset.assetId),
                            entry.asset.assetId,
                            Modifier.width(120.dp).aspectRatio(16f / 9f),
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(entry.asset.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(8.dp))
                            Chip(projectionLabel(entry.presentation.projection))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OperatorHeadsetColumn(state: UiState, runUi: RunUi?, vm: ControllerViewModel, modifier: Modifier) {
    val frozen = runUi?.run?.frozen == true
    val shown = when {
        runUi == null || !frozen -> state.devices.map { it.deviceId }
        else -> runUi.run.participants.map { it.deviceId }
    }
    Column(modifier) {
        Text(
            if (frozen) "Headsets in this run" else "Headsets",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        if (shown.isEmpty()) {
            EmptyState(Icons.Rounded.ViewInAr, "No headsets paired", "Pair the Quest headsets with this tablet before starting a session.")
            return
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(280.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 8.dp),
        ) {
            items(shown, key = { it }) { id ->
                OperatorHeadsetTile(state.device(id), runUi?.run?.participant(id), runUi, vm, id)
            }
        }
    }
}

@Composable
private fun OperatorHeadsetTile(view: DeviceView?, p: Participant?, runUi: RunUi?, vm: ControllerViewModel, deviceId: String) {
    val run = runUi?.run
    val selectable = run != null && !run.frozen
    val selected = p != null
    val online = view?.online == true
    val status = if (runUi != null) tileStatus(view, p, runUi) else null
    var expanded by rememberSaveable(deviceId) { mutableStateOf(false) }
    val storedVolume = view?.record?.volume ?: 1f
    var volume by remember(deviceId, storedVolume) { mutableFloatStateOf(storedVolume) }
    val accent = status?.let { toneColor(it.tone) } ?: MaterialTheme.colorScheme.onSurface

    Surface(
        Modifier
            .fillMaxWidth()
            .border(
                1.5.dp,
                if (selectable && selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.7f) else MaterialTheme.colorScheme.outlineVariant,
                MaterialTheme.shapes.large,
            )
            .clip(MaterialTheme.shapes.large),
        color = if (!online) MaterialTheme.colorScheme.surfaceContainerLowest else MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(16.dp).alpha(if (selectable && !selected) 0.6f else 1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selectable) {
                    Checkbox(
                        checked = selected,
                        onCheckedChange = { vm.toggleParticipant(deviceId) },
                        colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Column(
                    Modifier
                        .weight(1f)
                        .then(if (selectable) Modifier.clickable { vm.toggleParticipant(deviceId) } else Modifier),
                ) {
                    Text(view?.label ?: "Unknown headset", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(if (online) Tone.GOOD else Tone.WARN, 7.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (online) "Online" else "Out of contact",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                view?.status?.let { live ->
                    live.batteryPercent?.let { pct ->
                        val tone = if (pct < 20) Tone.BAD else if (pct < 40) Tone.WARN else Tone.NEUTRAL
                        FooterItem(if (live.charging == true) Icons.Rounded.BatteryChargingFull else Icons.Rounded.BatteryFull, "$pct%", tone)
                    }
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (expanded) "Hide controls" else "Show controls")
                }
            }
            if (runUi != null && runUi.run.frozen && status != null) {
                Spacer(Modifier.height(10.dp))
                Text(status.label, style = MaterialTheme.typography.titleMedium, color = accent)
                status.detail?.takeUnless { it == "Video on headset" }?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                ProgressRow(view, p, runUi)
            }
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(top = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Rounded.VolumeUp, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Volume", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(64.dp))
                        Slider(
                            value = volume,
                            onValueChange = { volume = it },
                            onValueChangeFinished = { vm.setVolume(deviceId, volume) },
                            modifier = Modifier.weight(1f),
                        )
                        Text("${(volume * 100).toInt()}%", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(44.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { vm.recenter(deviceId) },
                        enabled = online,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        Icon(Icons.Rounded.CenterFocusStrong, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Recenter")
                    }
                }
            }
        }
    }
}
