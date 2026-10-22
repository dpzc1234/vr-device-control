package com.vrdevicecontrol.controller.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vrdevicecontrol.controller.ControllerApplication
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
import com.vrdevicecontrol.protocol.EyeOrder
import com.vrdevicecontrol.protocol.MessageType
import com.vrdevicecontrol.protocol.Projection
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
    val loaded: Boolean = false,
) {
    fun device(id: String) = devices.firstOrNull { it.deviceId == id }
    fun entry(assetId: String) = library.firstOrNull { it.asset.assetId == assetId }
    val onlineCount get() = devices.count { it.online }
}

class ControllerViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = (app as ControllerApplication).graph

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

    fun import(uri: Uri) {
        viewModelScope.launch {
            val result = graph.catalog.import(uri)
            _messages.trySend(result.fold({ "Imported “${it.asset.title}” — check its layout" }, { "Import failed: ${it.message}" }))
        }
    }

    fun updateLayout(assetId: String, projection: Projection, stereo: StereoLayout, eyeOrder: EyeOrder, flatWidth: Float) =
        act("Layout saved") { catalog.updatePresentation(assetId, projection, stereo, eyeOrder, flatWidth) }

    fun renameVideo(assetId: String, title: String) = act { catalog.rename(assetId, title) }

    fun deleteVideo(assetId: String, alsoFromHeadsets: Boolean) = graph.act {
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

    fun thumbnail(assetId: String): File? = graph.catalog.thumbnail(assetId)

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
