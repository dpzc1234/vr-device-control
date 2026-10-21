package com.vrdevicecontrol.controller.transfer

import com.vrdevicecontrol.controller.catalog.CatalogRepository
import com.vrdevicecontrol.controller.devices.DeviceRegistry
import com.vrdevicecontrol.controller.diagnostics.EventLog
import com.vrdevicecontrol.controller.platform.JsonStore
import com.vrdevicecontrol.controller.platform.utcNow
import com.vrdevicecontrol.domain.QueuedTransfer
import com.vrdevicecontrol.domain.TransferDeviceView
import com.vrdevicecontrol.domain.TransferPlanner
import com.vrdevicecontrol.domain.Tuning
import com.vrdevicecontrol.protocol.AssetResult
import com.vrdevicecontrol.protocol.EnsureAsset
import com.vrdevicecontrol.protocol.ErrorInfo
import com.vrdevicecontrol.protocol.MessageType
import com.vrdevicecontrol.protocol.ProtocolCodec
import com.vrdevicecontrol.protocol.RemoveAsset
import com.vrdevicecontrol.protocol.TransferProgress
import com.vrdevicecontrol.protocol.TransferState
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class TransferJob(
    val operationId: String,
    val deviceId: String,
    val assetId: String,
    val sha256: String,
    val sizeBytes: Long,
    val state: TransferState = TransferState.QUEUED,
    val committedBytes: Long = 0,
    val error: ErrorInfo? = null,
    /** ENSURE_ASSET delivered on the current connection; cleared on disconnect, re-sent on reconnect. */
    val dispatched: Boolean = false,
    val createdAtUtc: String,
    val updatedAtUtc: String,
)

@Serializable
private data class TransferData(val jobs: List<TransferJob> = emptyList())

data class RemovalOutcome(val deviceId: String, val sha256: String, val pending: Boolean, val ok: Boolean?, val error: ErrorInfo?)

/**
 * Per-device content queues. The tablet asks (`ENSURE_ASSET`); the headset pulls bytes over HTTP
 * and owns its checkpoint. Offline queues only ever hold content work, never playback commands.
 * Confined to the controller actor.
 */
class TransferCoordinator(
    file: File,
    json: Json,
    private val registry: DeviceRegistry,
    private val catalog: CatalogRepository,
    tuning: Tuning,
    private val log: EventLog,
) {
    private val codec = ProtocolCodec.forController()
    private val planner = TransferPlanner(tuning)
    private val store = JsonStore(file, TransferData.serializer(), json) { TransferData() }

    private val _jobs = MutableStateFlow(store.current.jobs.map { it.copy(dispatched = false) })
    val jobs: StateFlow<List<TransferJob>> = _jobs.asStateFlow()

    private val _blocked = MutableStateFlow<Map<String, String>>(emptyMap())
    /** Why each waiting job has not been dispatched yet. */
    val blocked: StateFlow<Map<String, String>> = _blocked.asStateFlow()

    private val _removals = MutableStateFlow<Map<String, RemovalOutcome>>(emptyMap())
    val removals: StateFlow<Map<String, RemovalOutcome>> = _removals.asStateFlow()

    fun distribute(assetId: String, deviceIds: Collection<String>): Int {
        val entry = catalog.entry(assetId) ?: return 0
        val sha = entry.asset.sha256
        var added = 0
        val now = utcNow()
        val jobs = _jobs.value.toMutableList()
        for (deviceId in deviceIds) {
            if (sha in registry.view(deviceId)?.inventory.orEmpty()) continue
            if (jobs.any { it.deviceId == deviceId && it.sha256 == sha && it.state !in FINISHED }) continue
            jobs.removeAll { it.deviceId == deviceId && it.sha256 == sha }
            jobs += TransferJob(UUID.randomUUID().toString(), deviceId, assetId, sha, entry.asset.sizeBytes, createdAtUtc = now, updatedAtUtc = now)
            added++
        }
        save(jobs)
        log.log("DISTRIBUTE", detail = "${entry.asset.title} to $added headset(s)")
        pump()
        return added
    }

    fun retry(operationId: String) {
        save(_jobs.value.map { if (it.operationId == operationId && it.state == TransferState.FAILED) it.copy(state = TransferState.QUEUED, error = null, dispatched = false, updatedAtUtc = utcNow()) else it })
        pump()
    }

    fun cancel(operationId: String) {
        val job = _jobs.value.firstOrNull { it.operationId == operationId } ?: return
        // Removing an unfinished asset cancels the download and deletes exactly its temp files.
        if (job.state != TransferState.AVAILABLE) {
            registry.link(job.deviceId)?.send(MessageType.REMOVE_ASSET, codec.toPayload(RemoveAsset(UUID.randomUUID().toString(), job.sha256)))
        }
        save(_jobs.value.map { if (it.operationId == operationId) it.copy(state = TransferState.CANCELLED, dispatched = false, updatedAtUtc = utcNow()) else it })
        pump()
    }

    fun clearFinished() = save(_jobs.value.filterNot { it.state in FINISHED })

    /** Deliberate removal of the app-managed copy on one headset. Never queued for offline devices. */
    fun removeFromDevice(assetId: String, deviceId: String): Boolean {
        val sha = catalog.entry(assetId)?.asset?.sha256 ?: return false
        val opId = UUID.randomUUID().toString()
        val sent = registry.link(deviceId)?.send(MessageType.REMOVE_ASSET, codec.toPayload(RemoveAsset(opId, sha))) != null
        if (sent) _removals.value = _removals.value + (opId to RemovalOutcome(deviceId, sha, true, null, null))
        return sent
    }

    fun onAssetResult(deviceId: String, result: AssetResult) {
        val existing = _removals.value[result.operationId] ?: return
        _removals.value = _removals.value + (result.operationId to existing.copy(pending = false, ok = result.ok, error = result.error))
        log.log(if (result.ok) "REMOVED_FROM_HEADSET" else "REMOVE_REJECTED", deviceId, detail = result.error?.code?.name)
    }

    fun onProgress(deviceId: String, progress: TransferProgress) {
        val jobs = _jobs.value
        val match = jobs.firstOrNull { it.operationId == progress.operationId }
            ?: jobs.lastOrNull { it.deviceId == deviceId && it.sha256 == progress.sha256 && it.state !in FINISHED }
            ?: return
        val updated = match.copy(
            state = progress.state,
            committedBytes = progress.committedBytes,
            error = progress.error,
            dispatched = progress.state !in setOf(TransferState.AVAILABLE, TransferState.FAILED, TransferState.CANCELLED),
            updatedAtUtc = utcNow(),
        )
        if (updated.state != match.state) log.log("TRANSFER_${updated.state}", deviceId, detail = updated.error?.detail)
        save(jobs.map { if (it.operationId == match.operationId) updated else it })
        if (progress.state in FINISHED) pump()
    }

    fun onInventoryChanged(deviceId: String) {
        val inventory = registry.view(deviceId)?.inventory.orEmpty()
        save(_jobs.value.map {
            if (it.deviceId == deviceId && it.sha256 in inventory && it.state !in FINISHED) {
                it.copy(state = TransferState.AVAILABLE, committedBytes = it.sizeBytes, dispatched = false, updatedAtUtc = utcNow())
            } else it
        })
        pump()
    }

    fun onDisconnected(deviceId: String) {
        save(_jobs.value.map { if (it.deviceId == deviceId) it.copy(dispatched = false) else it })
        pump()
    }

    /** Dispatch whatever the planner allows. Safe to call often. */
    fun pump() {
        val views = registry.views().associateBy { it.deviceId }
        val waiting = _jobs.value.filter { it.state !in FINISHED }
        val plan = planner.plan(
            queue = waiting.map { QueuedTransfer(it.operationId, it.deviceId, it.sha256, it.sizeBytes - it.committedBytes) },
            activeOperationIds = waiting.filter { it.dispatched }.map { it.operationId }.toSet(),
            devices = views.mapValues { (_, v) ->
                TransferDeviceView(v.deviceId, v.online, v.busyWithRun, v.status?.freeBytes ?: Long.MAX_VALUE)
            },
        )
        _blocked.value = plan.blocked
        if (plan.dispatch.isEmpty()) return
        val dispatchedIds = mutableSetOf<String>()
        for (item in plan.dispatch) {
            val job = waiting.first { it.operationId == item.operationId }
            val entry = catalog.entry(job.assetId) ?: catalog.entryBySha(job.sha256)
            if (entry == null) {
                save(_jobs.value.map { if (it.operationId == job.operationId) it.copy(state = TransferState.FAILED, error = ErrorInfo(com.vrdevicecontrol.protocol.ErrorCode.ASSET_MISSING, "Removed from tablet")) else it })
                continue
            }
            val sent = registry.link(job.deviceId)?.send(MessageType.ENSURE_ASSET, codec.toPayload(EnsureAsset(job.operationId, entry.asset)))
            if (sent != null) dispatchedIds += job.operationId
        }
        if (dispatchedIds.isNotEmpty()) {
            save(_jobs.value.map { if (it.operationId in dispatchedIds) it.copy(dispatched = true, updatedAtUtc = utcNow()) else it })
        }
    }

    private fun save(list: List<TransferJob>) {
        _jobs.value = list
        store.update { TransferData(list) }
    }

    companion object {
        val FINISHED = setOf(TransferState.AVAILABLE, TransferState.FAILED, TransferState.CANCELLED)
    }
}
