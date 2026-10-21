package com.vrdevicecontrol.controller

import android.app.Application
import android.content.Context
import android.os.Build
import com.vrdevicecontrol.controller.catalog.CatalogRepository
import com.vrdevicecontrol.controller.devices.DeviceRegistry
import com.vrdevicecontrol.controller.diagnostics.EventLog
import com.vrdevicecontrol.controller.diagnostics.LogEvent
import com.vrdevicecontrol.controller.host.ControllerHost
import com.vrdevicecontrol.controller.host.InboundMessage
import com.vrdevicecontrol.controller.platform.ElapsedRealtimeClock
import com.vrdevicecontrol.controller.platform.utcNow
import com.vrdevicecontrol.controller.run.RunCoordinator
import com.vrdevicecontrol.controller.transfer.TransferCoordinator
import com.vrdevicecontrol.controller.transfer.TransferJob
import com.vrdevicecontrol.domain.Run
import com.vrdevicecontrol.domain.Tuning
import com.vrdevicecontrol.protocol.AssetResult
import com.vrdevicecontrol.protocol.ClockStatus
import com.vrdevicecontrol.protocol.HeadsetStatus
import com.vrdevicecontrol.protocol.Inventory
import com.vrdevicecontrol.protocol.MessageType
import com.vrdevicecontrol.protocol.ProtocolCodec
import com.vrdevicecontrol.protocol.ResultBatch
import com.vrdevicecontrol.protocol.ResultsAck
import com.vrdevicecontrol.protocol.TransferProgress
import java.io.File
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class ControllerApplication : Application() {
    lateinit var graph: ControllerGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = ControllerGraph(this).also { it.start() }
    }
}

@Serializable
private data class DiagnosticsExport(
    val exportedAtUtc: String,
    val controllerId: String,
    val controllerName: String,
    val controllerEpoch: String,
    val appVersion: String,
    val devices: List<com.vrdevicecontrol.controller.devices.DeviceRecord>,
    val currentRun: Run?,
    val runHistory: List<Run>,
    val transfers: List<TransferJob>,
    val events: List<LogEvent>,
)

/**
 * Explicit composition root. All controller state is confined to one serialized dispatcher
 * ([actor]); the socket loops only capture timestamps, answer clock probes and enqueue.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ControllerGraph(app: Application) {
    val tuning = Tuning()
    val clock = ElapsedRealtimeClock
    private val json: Json = ProtocolCodec.DefaultJson
    private val codec = ProtocolCodec.forController()
    val log = EventLog(clock)

    private val prefs = app.getSharedPreferences("controller", Context.MODE_PRIVATE)
    val controllerId: String = prefs.getString("controllerId", null) ?: UUID.randomUUID().toString().also {
        prefs.edit().putString("controllerId", it).apply()
    }
    val controllerName: String = prefs.getString("controllerName", null) ?: "Experience Control ${Build.MODEL}".take(40)

    /** New authority epoch per process: old-process commands are rejected by headsets. */
    val controllerEpoch: String = UUID.randomUUID().toString()
    private val instanceId: String = UUID.randomUUID().toString()

    val actor = Dispatchers.Default.limitedParallelism(1)
    val scope = CoroutineScope(SupervisorJob() + actor)

    val registry = DeviceRegistry(File(app.filesDir, "devices.json"), json, clock, tuning, log)
    val catalog = CatalogRepository(app.contentResolver, app.filesDir, json, log)
    val transfers = TransferCoordinator(File(app.filesDir, "transfers.json"), json, registry, catalog, tuning, log)
    val runs = RunCoordinator(File(app.filesDir, "runs.json"), json, registry, catalog, clock, tuning, log, controllerEpoch)
    val host = ControllerHost(app, controllerId, controllerName, controllerEpoch, instanceId, registry, catalog, clock, tuning, log, actor, scope)

    fun start() {
        registry.onDeviceConnected = { view ->
            runs.onDeviceConnected(view)
            transfers.pump()
        }
        registry.onDeviceDisconnected = transfers::onDisconnected
        registry.onInventoryChanged = transfers::onInventoryChanged
        registry.onStatusChanged = { id ->
            runs.onStatus(id)
            transfers.pump()
        }
        host.start()
        scope.launch { for (message in host.inbound) route(message) }
        scope.launch {
            while (isActive) {
                delay(500)
                runs.tick()
                registry.tick()
            }
        }
        log.log("CONTROLLER_STARTED", detail = "epoch ${controllerEpoch.take(8)}")
    }

    /** Runs an operator action on the controller actor. */
    fun act(block: suspend ControllerGraph.() -> Unit) {
        scope.launch { block() }
    }

    private fun route(m: InboundMessage) {
        if (registry.link(m.deviceId)?.connectionId != m.connectionId) return // Superseded connection.
        registry.onInbound(m.deviceId)
        val env = m.envelope
        try {
            when (env.type) {
                MessageType.HEARTBEAT -> Unit
                MessageType.STATUS -> registry.onStatus(m.deviceId, codec.payloadOf<HeadsetStatus>(env))
                MessageType.CLOCK_STATUS -> registry.onClock(m.deviceId, codec.payloadOf<ClockStatus>(env))
                MessageType.INVENTORY -> registry.onInventory(m.deviceId, codec.payloadOf<Inventory>(env))
                MessageType.TRANSFER_PROGRESS -> transfers.onProgress(m.deviceId, codec.payloadOf<TransferProgress>(env))
                MessageType.ASSET_RESULT -> transfers.onAssetResult(m.deviceId, codec.payloadOf<AssetResult>(env))
                MessageType.RESULT_BATCH -> {
                    val ids = runs.onRecords(m.deviceId, codec.payloadOf<ResultBatch>(env).records)
                    registry.link(m.deviceId)?.send(MessageType.RESULTS_ACK, codec.toPayload(ResultsAck(ids)))
                }
                MessageType.ERROR -> log.log("HEADSET_ERROR", m.deviceId, detail = env.payload.toString().take(300))
                else -> runs.onMessage(m.deviceId, env)
            }
        } catch (e: Exception) {
            log.log("INBOUND_FAILED", m.deviceId, detail = "${env.type}: ${e.message}")
        }
    }

    fun exportDiagnostics(out: OutputStream) {
        val export = DiagnosticsExport(
            exportedAtUtc = utcNow(),
            controllerId = controllerId,
            controllerName = controllerName,
            controllerEpoch = controllerEpoch,
            appVersion = BuildConfig.VERSION_NAME,
            // Credential hashes are redacted from exports.
            devices = registry.records.value.map { it.copy(secretHash = "<redacted>") },
            currentRun = runs.current.value,
            runHistory = runs.history.value,
            transfers = transfers.jobs.value,
            events = log.events.value,
        )
        val pretty = Json(json) { prettyPrint = true }
        out.write(pretty.encodeToString(DiagnosticsExport.serializer(), export).toByteArray())
    }
}
