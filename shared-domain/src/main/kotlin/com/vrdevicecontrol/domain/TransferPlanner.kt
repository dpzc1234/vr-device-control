package com.vrdevicecontrol.domain

/** A queued content operation for one device. Offline queues hold content work only, never playback. */
data class QueuedTransfer(
    val operationId: String,
    val deviceId: String,
    val sha256: String,
    val sizeBytes: Long,
)

data class TransferDeviceView(
    val deviceId: String,
    val online: Boolean,
    /** True while the headset is armed or playing; transfers are deferred until it is idle. */
    val busyWithRun: Boolean,
    val freeBytes: Long,
)

/**
 * Chooses which queued transfers to dispatch, honoring fleet and per-device concurrency limits and
 * deferring devices that are offline or running.
 */
class TransferPlanner(private val tuning: Tuning = Tuning()) {

    data class Plan(val dispatch: List<QueuedTransfer>, val blocked: Map<String, String>)

    fun plan(
        queue: List<QueuedTransfer>,
        activeOperationIds: Set<String>,
        devices: Map<String, TransferDeviceView>,
    ): Plan {
        val active = queue.filter { it.operationId in activeOperationIds }
        var fleetSlots = tuning.maxConcurrentFleetTransfers - active.size
        val perDevice = active.groupingBy { it.deviceId }.eachCount().toMutableMap()
        val dispatch = mutableListOf<QueuedTransfer>()
        val blocked = linkedMapOf<String, String>()

        for (item in queue) {
            if (item.operationId in activeOperationIds) continue
            val device = devices[item.deviceId]
            val reason = when {
                device == null || !device.online -> "Waiting for headset to reconnect"
                device.busyWithRun -> "Deferred while headset is in a run"
                item.sizeBytes > device.freeBytes - tuning.storageReserveBytes -> "Not enough free space on headset"
                (perDevice[item.deviceId] ?: 0) >= tuning.maxConcurrentDeviceTransfers -> "Queued behind another transfer"
                fleetSlots <= 0 -> "Queued (fleet transfer limit)"
                else -> null
            }
            if (reason != null) {
                blocked[item.operationId] = reason
                continue
            }
            dispatch += item
            fleetSlots--
            perDevice[item.deviceId] = (perDevice[item.deviceId] ?: 0) + 1
        }
        return Plan(dispatch, blocked)
    }
}
