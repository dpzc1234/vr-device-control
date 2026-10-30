package com.vrdevicecontrol.controller.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vrdevicecontrol.controller.ui.components.StatusDot
import com.vrdevicecontrol.controller.ui.theme.Tone
import com.vrdevicecontrol.protocol.PairingStatus
import com.vrdevicecontrol.protocol.TransferState

enum class Tab(val label: String, val icon: ImageVector) {
    RUN("Run", Icons.Rounded.PlayCircle),
    HEADSETS("Headsets", Icons.Rounded.ViewInAr),
    LIBRARY("Library", Icons.Rounded.VideoLibrary),
    TRANSFERS("Transfers", Icons.Rounded.Download),
    DIAGNOSTICS("Diagnostics", Icons.Rounded.Insights),
}

@Composable
fun ControllerApp(vm: ControllerViewModel, dark: Boolean, onDarkChange: (Boolean) -> Unit) {
    val state by vm.ui.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.RUN) }
    val snackbar = remember { SnackbarHostState() }
    var accessDialog by remember { mutableStateOf<AccessDialog?>(null) }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(state.developer) {
        if (!state.developer && tab != Tab.RUN) tab = Tab.RUN
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
        Column(Modifier.fillMaxSize()) {
        DeveloperBar(
            state.access,
            onSetPin = { accessDialog = AccessDialog.SetPin(changing = false) },
            onChangePin = { accessDialog = AccessDialog.SetPin(changing = true) },
            onLock = vm::lockDeveloperMode,
        )
        Row(Modifier.fillMaxSize()) {
            Rail(state, tab, dark = dark, onDarkChange = onDarkChange, onSelect = { tab = it })
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Box(Modifier.weight(1f).fillMaxHeight()) {
                Crossfade(tab, label = "tab") { current ->
                    when (current) {
                        Tab.RUN -> RunScreen(state, vm, onOpenLibrary = { tab = Tab.LIBRARY })
                        Tab.HEADSETS -> HeadsetsScreen(state, vm)
                        Tab.LIBRARY -> LibraryScreen(state, vm)
                        Tab.TRANSFERS -> TransfersScreen(state, vm)
                        Tab.DIAGNOSTICS -> DiagnosticsScreen(state, vm, onUnlockGesture = { accessDialog = AccessDialog.Unlock })
                    }
                }
            }
        }
        }
        if (!state.developer) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(72.dp)
                    .then(hiddenUnlockModifier { accessDialog = AccessDialog.Unlock }),
            )
        }
        }
    }
    AccessDialogs(accessDialog, vm) { accessDialog = it }
}

@Composable
private fun Rail(
    state: UiState,
    selected: Tab,
    dark: Boolean,
    onDarkChange: (Boolean) -> Unit,
    onSelect: (Tab) -> Unit,
) {
    val pendingPairings = state.pairing.count { it.status == PairingStatus.PENDING }
    val activeTransfers = state.transfers.count { it.state in setOf(TransferState.QUEUED, TransferState.DOWNLOADING, TransferState.VERIFYING, TransferState.PAUSED) }
    val failedTransfers = state.transfers.count { it.state == TransferState.FAILED }
    val tabs = if (state.developer) Tab.entries else listOf(Tab.RUN)

    NavigationRail(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier.width(104.dp),
        header = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 12.dp, bottom = 24.dp)) {
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.ViewInAr, null, tint = MaterialTheme.colorScheme.primary) }
            }
        },
    ) {
        tabs.forEach { t ->
            val badge: String? = when (t) {
                Tab.HEADSETS -> pendingPairings.takeIf { it > 0 }?.toString()
                Tab.TRANSFERS -> (if (failedTransfers > 0) "!" else activeTransfers.takeIf { it > 0 }?.toString())
                Tab.DIAGNOSTICS -> if (state.lastCrash != null) "!" else null
                else -> null
            }
            NavigationRailItem(
                selected = t == selected,
                onClick = { onSelect(t) },
                icon = {
                    BadgedBox(badge = { if (badge != null) Badge { Text(badge) } }) { Icon(t.icon, t.label) }
                },
                label = { Text(t.label) },
                colors = NavigationRailItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        if (state.developer) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = 12.dp, start = 8.dp, end = 8.dp)) {
                val (tone, text) = when {
                    state.host.error != null -> Tone.BAD to "Server error"
                    !state.host.running -> Tone.WARN to "Starting"
                    !state.host.advertised -> Tone.WARN to "Not discoverable"
                    else -> Tone.GOOD to "${state.onlineCount}/${state.devices.size} online"
                }
                StatusDot(tone, 10.dp)
                Spacer(Modifier.height(6.dp))
                Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
        ThemeModeSelector(dark, onDarkChange)
    }
}

@Composable
private fun ThemeModeSelector(dark: Boolean, onDarkChange: (Boolean) -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Switch(checked = dark, onCheckedChange = onDarkChange)
        Text(
            if (dark) "Dark" else "Light",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Seven taps within 4 s, with no ripple, so customer mode gives no hint that developer mode exists. */
@Composable
private fun hiddenUnlockModifier(onUnlock: () -> Unit): Modifier {
    val taps = remember { mutableListOf<Long>() }
    return Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
        val now = System.currentTimeMillis()
        taps.removeAll { now - it > 4_000 }
        taps += now
        if (taps.size >= 7) {
            taps.clear()
            onUnlock()
        }
    }
}
