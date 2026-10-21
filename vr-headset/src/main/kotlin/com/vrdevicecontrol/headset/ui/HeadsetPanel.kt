package com.vrdevicecontrol.headset.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vrdevicecontrol.headset.HeadsetGraph
import com.vrdevicecontrol.headset.net.DiscoveredController
import com.vrdevicecontrol.headset.net.DiscoveryState
import com.vrdevicecontrol.headset.net.LinkState
import com.vrdevicecontrol.headset.net.PairingState
import com.vrdevicecontrol.protocol.PlaybackState
import com.vrdevicecontrol.protocol.TransferState
import kotlinx.coroutines.launch

private val Night = Color(0xFF0B1026)
private val Deep = Color(0xFF13204A)
private val Teal = Color(0xFF1F6F78)
private val Mist = Color(0xFFB8C7E0)
private val Accent = Color(0xFF7FD1C7)
private val Warn = Color(0xFFF2B66D)
private val Bad = Color(0xFFF28B82)

@Composable
fun HeadsetPanel(graph: HeadsetGraph) {
    val link by graph.connection.state.collectAsState()
    val session by graph.session.state.collectAsState()
    val pairing by graph.identity.pairing.collectAsState()
    var setupOpen by remember { mutableStateOf(false) }
    val showSetup = setupOpen || pairing == null || link == LinkState.Unauthorized

    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Night, surface = Deep)) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(36.dp))
                .background(Brush.verticalGradient(listOf(Night, Deep, Teal.copy(alpha = 0.85f)))),
        ) {
            AnimatedContent(targetState = showSetup, label = "mode") { setup ->
                if (setup) {
                    SetupScreen(graph, onDone = { setupOpen = false })
                } else {
                    StandbyScreen(graph, link, session.playback, onSetup = { setupOpen = true })
                }
            }
        }
    }
}

// --- Standby ------------------------------------------------------------------------------------

@Composable
private fun StandbyScreen(graph: HeadsetGraph, link: LinkState, playback: PlaybackState, onSetup: () -> Unit) {
    val label by graph.identity.label.collectAsState()
    val breathing = rememberInfiniteTransition(label = "breathing")
    val pulse by breathing.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(tween(4_000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    Column(Modifier.fillMaxSize().padding(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        Box(contentAlignment = Alignment.Center) {
            Box(Modifier.size(120.dp).scale(pulse).alpha(0.35f).clip(CircleShape).background(Accent))
            Box(Modifier.size(64.dp).clip(CircleShape).background(Accent.copy(alpha = 0.8f)))
        }
        Spacer(Modifier.height(40.dp))
        Text("Relax and get comfortable", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Light, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text("Your experience will begin shortly.", color = Mist, fontSize = 24.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            StatusDot(linkColor(link))
            Spacer(Modifier.width(10.dp))
            Text("$label · ${linkShort(link)}${playbackSuffix(playback)}", color = Mist.copy(alpha = 0.7f), fontSize = 16.sp)
            Spacer(Modifier.weight(1f))
            if (playback == PlaybackState.STANDBY || playback == PlaybackState.ERROR) {
                TextButton(onClick = onSetup) { Text("Setup", color = Mist.copy(alpha = 0.6f), fontSize = 16.sp) }
            }
        }
    }
}

private fun playbackSuffix(p: PlaybackState) = when (p) {
    PlaybackState.READY, PlaybackState.ARMED -> " · Ready"
    PlaybackState.PREPARING -> " · Preparing"
    PlaybackState.ERROR -> " · Needs attention"
    else -> ""
}

// --- Setup --------------------------------------------------------------------------------------

@Composable
private fun SetupScreen(graph: HeadsetGraph, onDone: () -> Unit) {
    val link by graph.connection.state.collectAsState()
    val pairing by graph.identity.pairing.collectAsState()
    val pairingState by graph.connection.pairingState.collectAsState()
    val discovered by graph.discovery.controllers.collectAsState()
    val discoveryState by graph.discovery.state.collectAsState()
    val label by graph.identity.label.collectAsState()
    val session by graph.session.state.collectAsState()
    val transfers by graph.content.transfers.collectAsState()
    val clock by graph.connection.clockSync.latest.collectAsState()
    var manualEntry by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(36.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Headset setup", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                Text("$label  ·  ID ${graph.identity.deviceId.take(8)}", color = Mist, fontSize = 15.sp)
            }
            if (pairing != null && link != LinkState.Unauthorized) {
                Button(onClick = onDone) { Text("Done") }
            }
        }
        Spacer(Modifier.height(20.dp))

        when {
            pairingState is PairingState.AwaitingApproval -> PairingCode(pairingState as PairingState.AwaitingApproval) { graph.connection.cancelPairing() }
            pairingState is PairingState.Requesting -> Info("Contacting ${(pairingState as PairingState.Requesting).controllerName}…")
            manualEntry -> ManualEntry(graph, onClose = { manualEntry = false })
            pairing != null && link != LinkState.Unauthorized -> PairedSummary(graph, link)
            else -> Column {
                if (link == LinkState.Unauthorized) Info("This tablet no longer recognises the headset. Pair again.", Bad)
                (pairingState as? PairingState.Failed)?.let { Info(it.reason, Bad) }
                Text("Choose the operator tablet", color = Color.White, fontSize = 20.sp)
                Spacer(Modifier.height(10.dp))
                ControllerList(discovered, discoveryState) { c -> graph.connection.pair(c.host, c.port, c.name) }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { manualEntry = true }) { Text("Enter address manually") }
            }
        }

        Spacer(Modifier.weight(1f))
        // Diagnostics strip.
        val active = transfers.values.filter { it.state != TransferState.AVAILABLE }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Diag("Playback", session.playback.name.lowercase().replaceFirstChar { it.uppercase() })
            Diag("App active", if (session.appActive) "Yes" else "No")
            Diag("Clock", clock?.let { "±${it.baseUncertaintyUs / 1000} ms" } ?: "—")
            Diag("Transfers", if (active.isEmpty()) "Idle" else active.joinToString { "${it.committedBytes * 100 / it.totalBytes.coerceAtLeast(1)}%" })
        }
        session.lastError?.let {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Last error: ${it.code}${it.detail?.let { d -> " — $d" } ?: ""}", color = Bad, fontSize = 14.sp, modifier = Modifier.weight(1f))
                if (session.playback == PlaybackState.ERROR) TextButton(onClick = { graph.session.acknowledgeError() }) { Text("Clear") }
            }
        }
    }
}

@Composable
private fun PairedSummary(graph: HeadsetGraph, link: LinkState) {
    val pairing by graph.identity.pairing.collectAsState()
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = 0.06f)).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(linkColor(link))
            Spacer(Modifier.width(10.dp))
            Text("Paired with ${pairing?.controllerName}", color = Color.White, fontSize = 20.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text(linkLong(link), color = Mist, fontSize = 16.sp)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { graph.connection.reconnectNow("Operator request") }) { Text("Reconnect") }
            OutlinedButton(onClick = { graph.connection.unpair() }, colors = ButtonDefaults.outlinedButtonColors(contentColor = Bad)) { Text("Unpair") }
        }
    }
}

@Composable
private fun ControllerList(list: List<DiscoveredController>, state: DiscoveryState, onPick: (DiscoveredController) -> Unit) {
    if (list.isEmpty()) {
        val msg = when (state) {
            is DiscoveryState.Failed -> "${state.reason}. Use manual address entry."
            DiscoveryState.Searching -> "Searching the network… Make sure the tablet app is open."
            DiscoveryState.Idle -> "Discovery idle."
        }
        Info(msg, if (state is DiscoveryState.Failed) Warn else Mist)
        return
    }
    LazyColumn(Modifier.height(170.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(list, key = { it.serviceName }) { c ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.08f))
                    .clickable { onPick(c) }.padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(c.name, color = Color.White, fontSize = 18.sp)
                    Text("${c.host}:${c.port}", color = Mist, fontSize = 14.sp)
                }
                Text("Pair  ›", color = Accent, fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun PairingCode(state: PairingState.AwaitingApproval, onCancel: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Confirm this code on ${state.controllerName}", color = Mist, fontSize = 20.sp)
        Spacer(Modifier.height(16.dp))
        Text(
            state.code.chunked(3).joinToString("  "),
            color = Color.White, fontSize = 72.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(12.dp))
        Text("Approve only if the tablet shows the same code.", color = Mist.copy(alpha = 0.7f), fontSize = 15.sp)
        TextButton(onClick = onCancel) { Text("Cancel") }
    }
}

@Composable
private fun ManualEntry(graph: HeadsetGraph, onClose: () -> Unit) {
    var text by remember { mutableStateOf(graph.identity.pairing.value?.let { "${it.hostHint}:${it.portHint}" } ?: "") }
    var result by remember { mutableStateOf<Pair<String, Color>?>(null) }
    val scope = rememberCoroutineScope()
    val discovered by graph.discovery.controllers.collectAsState()
    fun parse(): Pair<String, Int>? {
        val parts = text.split(":")
        val port = parts.getOrNull(1)?.toIntOrNull() ?: return null
        return parts[0].takeIf { it.count { c -> c == '.' } == 3 }?.let { it to port }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.weight(1f)) {
            Text("Tablet address", color = Mist, fontSize = 15.sp)
            Box(
                Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(RoundedCornerShape(14.dp))
                    .border(1.dp, Accent.copy(alpha = 0.5f), RoundedCornerShape(14.dp)).padding(14.dp),
            ) {
                Text(text.ifEmpty { "192.168.1.20:47800" }, color = if (text.isEmpty()) Mist.copy(alpha = 0.4f) else Color.White, fontSize = 26.sp, fontFamily = FontFamily.Monospace)
            }
            result?.let { (msg, color) -> Text(msg, color = color, fontSize = 15.sp) }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = {
                    val target = parse() ?: run { result = "Enter address as host:port" to Warn; return@OutlinedButton }
                    result = "Testing…" to Mist
                    scope.launch {
                        result = graph.connection.ping(target.first, target.second).fold(
                            onSuccess = { "Reached ${it.controllerName}" to Accent },
                            onFailure = {
                                val discoveryNote = if (discovered.isEmpty()) " Discovery also found nothing — the network may block device-to-device traffic." else ""
                                "Cannot reach ${target.first}:${target.second} (${it.message}).$discoveryNote" to Bad
                            },
                        )
                    }
                }) { Text("Test connection") }
                Button(onClick = {
                    val target = parse() ?: run { result = "Enter address as host:port" to Warn; return@Button }
                    graph.connection.pair(target.first, target.second, target.first)
                }) { Text("Pair") }
                TextButton(onClick = onClose) { Text("Back") }
            }
        }
        Keypad(onKey = { k -> text = if (k == "⌫") text.dropLast(1) else (text + k).take(21) })
    }
}

@Composable
private fun Keypad(onKey: (String) -> Unit) {
    val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf(".", "0", ":"), listOf("⌫"))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { key ->
                    Box(
                        Modifier.size(width = if (key == "⌫") 196.dp else 60.dp, height = 52.dp).clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(alpha = 0.1f)).clickable { onKey(key) },
                        contentAlignment = Alignment.Center,
                    ) { Text(key, color = Color.White, fontSize = 22.sp) }
                }
            }
        }
    }
}

// --- Small pieces -------------------------------------------------------------------------------

@Composable
private fun Info(text: String, color: Color = Mist) {
    Text(text, color = color, fontSize = 16.sp, modifier = Modifier.padding(vertical = 6.dp))
}

@Composable
private fun Diag(label: String, value: String) {
    Column {
        Text(label.uppercase(), color = Mist.copy(alpha = 0.6f), fontSize = 11.sp, letterSpacing = 1.sp)
        Text(value, color = Color.White, fontSize = 15.sp)
    }
}

@Composable
private fun StatusDot(color: Color) {
    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
}

private fun linkColor(link: LinkState) = when (link) {
    is LinkState.Connected -> Accent
    LinkState.Unauthorized -> Bad
    LinkState.NotPaired -> Warn
    else -> Warn
}

private fun linkShort(link: LinkState) = when (link) {
    is LinkState.Connected -> "Connected"
    LinkState.NotPaired -> "Not paired"
    LinkState.Unauthorized -> "Pairing revoked"
    else -> "Offline"
}

private fun linkLong(link: LinkState) = when (link) {
    is LinkState.Connected -> "Connected to ${link.welcome.controllerName} at ${link.host}:${link.port}"
    is LinkState.Connecting -> "Connecting to ${link.host}:${link.port}…"
    is LinkState.Searching -> "Looking for ${link.controllerName} on the network…"
    is LinkState.Waiting -> link.reason
    LinkState.NotPaired -> "Not paired"
    LinkState.Unauthorized -> "The tablet refused this headset's credential"
}
