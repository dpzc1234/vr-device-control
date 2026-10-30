package com.vrdevicecontrol.controller.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vrdevicecontrol.controller.ControllerApplication
import com.vrdevicecontrol.controller.access.AccessState
import com.vrdevicecontrol.controller.access.UnlockResult
import com.vrdevicecontrol.controller.catalog.CatalogEntry
import com.vrdevicecontrol.controller.catalog.ImportProgress
import com.vrdevicecontrol.controller.devices.DeviceView
import com.vrdevicecontrol.controller.diagnostics.CrashRecorder
import com.vrdevicecontrol.controller.devices.PendingPairing
import com.vrdevicecontrol.controller.diagnostics.LogEvent
import com.vrdevicecontrol.controller.host.HostState
import com.vrdevicecontrol.controller.transfer.RemovalOutcome
import com.vrdevicecontrol.controller.transfer.TransferJob
import com.vrdevicecontrol.domain.Run
import com.vrdevicecontrol.domain.RunPhase
import com.vrdevicecontrol.protocol.DeviceConfig
import com.vrdevicecontrol.protocol.EyeOrder
import com.vrdevicecontrol.protocol.MessageType
import com.vrdevicecontrol.protocol.Projection
import com.vrdevicecontrol.protocol.ProtocolCodec
import com.vrdevicecontrol.protocol.Recenter
import com.vrdevicecontrol.protocol.StereoLayout
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RunUi(
    val run: Run,
    val phase: RunPhase,
    val blockers: Map<String, String>,
    val canStart: Boolean,
    val estimatedPositionUs: Long?,
    val fromPreviousEpoch: Boolean,
    val entry: CatalogEntry?,
    /** Controller-time remaining until the scheduled start, if in the future. */
    val untilStartUs: Long?,
)

data class UiState(
    val controllerName: String = "",
    val controllerId: String = "",
    val appVersion: String = "",
    val host: HostState = HostState(),
    val addresses: List<String> = emptyList(),
    val devices: List<DeviceView> = emptyList(),
    val pairing: List<PendingPairing> = emptyList(),
    val pairingMode: Boolean = false,
    val library: List<CatalogEntry> = emptyList(),
    val importProgress: ImportProgress? = null,
    val transfers: List<TransferJob> = emptyList(),
    val transferBlocked: Map<String, String> = emptyMap(),
    val removals: Map<String, RemovalOutcome> = emptyMap(),
    val run: RunUi? = null,
    val history: List<Run> = emptyList(),
    val events: List<LogEvent> = emptyList(),
    /** Stack trace of the last crash, kept until the operator clears it. */
    val lastCrash: String? = null,
    val access: AccessState = AccessState(developer = false, pinSet = true),
    val loaded: Boolean = false,
) {
    val developer get() = access.developer

    fun device(id: String) = devices.firstOrNull { it.deviceId == id }
    fun entry(assetId: String) = library.firstOrNull { it.asset.assetId == assetId }
    val onlineCount get() = devices.count { it.online }
}

class ControllerViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = (app as ControllerApplication).graph
    private val codec = ProtocolCodec.forController()

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    init {
        val ticker = flow {
            while (true) {
                emit(Unit)
                delay(500)
            }
        }
        viewModelScope.launch {
            merge(
                ticker,
                graph.registry.records.map { },
                graph.registry.live.map { },
                graph.registry.pairing.map { },
                graph.catalog.entries.map { },
                graph.catalog.importProgress.map { },
                graph.transfers.jobs.map { },
                graph.runs.current.map { },
                graph.host.state.map { },
                graph.access.state.map { },
            ).conflate().collect {
                // Read actor-confined state on the actor for a consistent snapshot.
                _ui.value = withContext(graph.actor) { snapshot() }
                delay(120)
            }
        }
        viewModelScope.launch {
            while (isActive) {
                val addresses = withContext(kotlinx.coroutines.Dispatchers.IO) { localAddresses() }
                val crash = withContext(kotlinx.coroutines.Dispatchers.IO) { CrashRecorder.read() }
                _ui.value = _ui.value.copy(addresses = addresses, lastCrash = crash)
                delay(10_000)
            }
        }
    }

    private fun snapshot(): UiState {
        val runs = graph.runs
        val run = runs.current.value
        val now = graph.clock.nowUs()
        return _ui.value.copy(
            controllerName = graph.controllerName,
            controllerId = graph.controllerId,
            appVersion = com.vrdevicecontrol.controller.BuildConfig.VERSION_NAME,
            host = graph.host.state.value,
            devices = graph.registry.views().sortedWith(compareByDescending<DeviceView> { it.online }.thenBy { it.label }),
            pairing = graph.registry.pairing.value,
            pairingMode = graph.registry.pairingMode.value,
            library = graph.catalog.entries.value.sortedByDescending { it.importedAtUtc },
            importProgress = graph.catalog.importProgress.value,
            transfers = graph.transfers.jobs.value,
            transferBlocked = graph.transfers.blocked.value,
            removals = graph.transfers.removals.value,
            run = run?.let {
                RunUi(
                    run = it,
                    phase = runs.phase(it),
                    blockers = runs.blockers(it),
                    canStart = runs.canStart(it),
                    estimatedPositionUs = runs.estimatedPositionUs(it),
                    fromPreviousEpoch = runs.isFromPreviousEpoch(it),
                    entry = graph.catalog.entry(it.asset.assetId),
                    untilStartUs = it.startAtControllerUs?.let { s -> (s - now).takeIf { d -> d > 0 } },
                )
            },
            history = runs.history.value,
            events = graph.log.events.value.takeLast(200).reversed(),
            access = graph.access.state.value,
            loaded = true,
        )
    }

    private fun act(message: String? = null, block: suspend com.vrdevicecontrol.controller.ControllerGraph.() -> Unit) {
        graph.act {
            block()
            message?.let { _messages.trySend(it) }
        }
    }

    // --- Run -----------------------------------------------------------------------------------

    fun selectVideo(assetId: String) = act { runs.selectAsset(assetId) }
    fun toggleParticipant(deviceId: String) = act { runs.toggleParticipant(deviceId) }
    fun selectAllOnline() = act { runs.selectAllOnline() }
    fun prepare() = act("Preparing headsets…") { runs.prepare() }
    fun retryPrepare(deviceId: String) = act { runs.retryPrepare(deviceId) }
    fun retryAllNotReady() = act { runs.retryAllNotReady() }
    fun removeParticipant(deviceId: String) = act("Headset removed from this run") { runs.removeParticipant(deviceId) }
    fun backToDraft() = act { runs.backToDraft() }
    fun cancelStart() = act("Cancel requested — check each headset's response") { runs.cancelStart() }
    fun pauseAll() = act { runs.control(MessageType.PAUSE) }
    fun resumeAll() = act { runs.control(MessageType.RESUME) }
    fun stopAll() = act { runs.control(MessageType.STOP) }
    fun control(type: String, deviceId: String) = act { runs.control(type, listOf(deviceId)) }
    fun restart() = act("Restarting from the beginning — prepare in progress") { runs.restart() }
    fun newRun() = act { runs.newRun() }

    fun start() = graph.act {
        val ok = runs.start()
        _messages.trySend(if (ok) "Start sent" else "Start is not available yet")
    }

    // --- Content -------------------------------------------------------------------------------

    /** Defence in depth: library editing is refused in customer mode even if a control is reached. */
    private fun requireDeveloper(): Boolean {
        if (graph.access.isDeveloper) return true
        _messages.trySend("Library editing is not available on this tablet")
        return false
    }

    fun import(uri: Uri) {
        if (!requireDeveloper()) return
        viewModelScope.launch {
            val result = graph.catalog.import(uri)
            _messages.trySend(result.fold({ "Imported “${it.asset.title}” — check its layout" }, { "Import failed: ${it.message}" }))
        }
    }

    fun updateLayout(assetId: String, projection: Projection, stereo: StereoLayout, eyeOrder: EyeOrder, flatWidth: Float) {
        if (requireDeveloper()) act("Layout saved") { catalog.updatePresentation(assetId, projection, stereo, eyeOrder, flatWidth) }
    }

    fun renameVideo(assetId: String, title: String) {
        if (requireDeveloper()) act { catalog.rename(assetId, title) }
    }

    fun deleteVideo(assetId: String, alsoFromHeadsets: Boolean) = graph.act {
        if (!access.isDeveloper) {
            _messages.trySend("Library editing is not available on this tablet")
            return@act
        }
        val inRun = runs.current.value?.let { it.asset.assetId == assetId && it.frozen } == true
        if (inRun) {
            _messages.trySend("This video is used by the current run")
            return@act
        }
        if (alsoFromHeadsets) {
            registry.views().filter { it.online && catalog.entry(assetId)?.asset?.sha256 in it.inventory }
                .forEach { transfers.removeFromDevice(assetId, it.deviceId) }
        }
        catalog.delete(assetId)
        _messages.trySend("Video deleted from the tablet")
    }

    fun distribute(assetId: String, deviceIds: Collection<String>) = graph.act {
        val n = transfers.distribute(assetId, deviceIds)
        _messages.trySend(if (n == 0) "Those headsets already have this video" else "Queued for $n headset${if (n == 1) "" else "s"}")
    }

    fun removeFromHeadset(assetId: String, deviceId: String) = graph.act {
        val sent = transfers.removeFromDevice(assetId, deviceId)
        _messages.trySend(if (sent) "Removal requested" else "Headset is out of contact — nothing was sent")
    }

    fun retryTransfer(operationId: String) = act { transfers.retry(operationId) }
    fun cancelTransfer(operationId: String) = act { transfers.cancel(operationId) }
    fun clearFinishedTransfers() = act { transfers.clearFinished() }

    // --- Headsets ------------------------------------------------------------------------------

    fun setPairingMode(enabled: Boolean) = act { registry.setPairingMode(enabled) }
    fun approvePairing(requestId: String) = act("Headset approved") { registry.approve(requestId) }
    fun rejectPairing(requestId: String) = act { registry.reject(requestId) }
    fun dismissPairing(requestId: String) = act { registry.dismissPairing(requestId) }
    fun rename(deviceId: String, label: String) = act { registry.rename(deviceId, label) }
    fun revoke(deviceId: String) = act("Headset forgotten — it must pair again") { registry.revoke(deviceId) }

    fun setStartOnBoot(deviceId: String, enabled: Boolean) = act {
        registry.setStartOnBoot(deviceId, enabled)
        // Offline: preference is stored and pushed on the next connect (see ControllerHost).
        registry.link(deviceId)?.send(
            MessageType.DEVICE_CONFIG,
            codec.toPayload(DeviceConfig(enabled)),
        )
    }

    fun setVolume(deviceId: String, volume: Float) = act {
        registry.setVolume(deviceId, volume)
        val record = registry.view(deviceId)?.record ?: return@act
        val sent = registry.link(deviceId)?.send(
            MessageType.DEVICE_CONFIG,
            codec.toPayload(DeviceConfig(startOnBoot = record.startOnBoot, volume = record.volume)),
        ) != null
        if (!sent) _messages.trySend("Headset is out of contact — volume applies when it reconnects")
    }

    fun recenter(deviceId: String) = graph.act {
        val link = registry.link(deviceId)
        val sent = link?.send(MessageType.RECENTER, codec.toPayload(Recenter)) != null
        if (sent) {
            registry.onRecenterRequested(deviceId)
            log.log("RECENTER_REQUESTED", deviceId)
        }
        _messages.trySend(if (sent) "Recenter requested" else "Headset is out of contact — nothing was sent")
    }

    fun recenterAll() = graph.act {
        val online = registry.views().filter { it.online }
        val eligible = online.filter {
            it.statusIsLive && it.status?.appActive == true && it.status.worn != false && !it.busyWithRun
        }
        val sent = eligible.count { device ->
            val ok = registry.link(device.deviceId)?.send(MessageType.RECENTER, codec.toPayload(Recenter)) != null
            if (ok) {
                registry.onRecenterRequested(device.deviceId)
                log.log("RECENTER_REQUESTED", device.deviceId, detail = "center all")
            }
            ok
        }
        _messages.trySend(
            when {
                online.isEmpty() -> "No headsets are online"
                eligible.isEmpty() -> "No online headsets are worn and available between runs"
                sent == online.size -> "Recenter requested for all $sent online headsets"
                else -> "Recenter sent to $sent headset${if (sent == 1) "" else "s"}; ${online.size - eligible.size} unavailable"
            },
        )
    }

    fun thumbnail(assetId: String): File? = graph.catalog.thumbnail(assetId)

    // --- Developer access ----------------------------------------------------------------------
    // PBKDF2 is deliberately slow, so hashing runs off the main thread.

    fun unlock(pin: String, onResult: (UnlockResult) -> Unit) {
        viewModelScope.launch {
            onResult(withContext(kotlinx.coroutines.Dispatchers.Default) { graph.access.unlock(pin) })
        }
    }

    /** Sets or changes the PIN; returns the new recovery code to show once. */
    fun setPin(pin: String, onRecoveryCode: (String) -> Unit) {
        viewModelScope.launch {
            val code = withContext(kotlinx.coroutines.Dispatchers.Default) { runCatching { graph.access.setPin(pin) } }
            code.onSuccess(onRecoveryCode).onFailure { _messages.trySend(it.message ?: "Could not set the PIN") }
        }
    }

    fun recover(recoveryCode: String, newPin: String, onResult: (UnlockResult, String?) -> Unit) {
        viewModelScope.launch {
            val (result, code) = withContext(kotlinx.coroutines.Dispatchers.Default) { graph.access.recover(recoveryCode, newPin) }
            onResult(result, code)
        }
    }

    fun lockDeveloperMode() {
        graph.access.lock()
        _messages.trySend("Developer mode locked")
    }

    fun clearCrash() {
        CrashRecorder.clear()
        _ui.value = _ui.value.copy(lastCrash = null)
    }

    fun notify(message: String) {
        _messages.trySend(message)
    }

    fun exportDiagnostics(uri: Uri) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val ok = runCatching {
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { out ->
                    val bytes = java.io.ByteArrayOutputStream()
                    withContext(graph.actor) { graph.exportDiagnostics(bytes) }
                    out.write(bytes.toByteArray())
                }
            }.isSuccess
            _messages.trySend(if (ok) "Diagnostics exported" else "Export failed")
        }
    }

    private fun localAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nif -> nif.inetAddresses.toList().filterIsInstance<Inet4Address>().map { "${it.hostAddress} (${nif.name})" } }
    }.getOrDefault(emptyList())
}
