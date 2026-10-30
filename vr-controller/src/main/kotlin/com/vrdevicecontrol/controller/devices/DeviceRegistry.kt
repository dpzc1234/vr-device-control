package com.vrdevicecontrol.controller.devices

import com.vrdevicecontrol.controller.diagnostics.EventLog
import com.vrdevicecontrol.controller.platform.JsonStore
import com.vrdevicecontrol.controller.platform.utcNow
import com.vrdevicecontrol.domain.DeviceLive
import com.vrdevicecontrol.domain.MonotonicClock
import com.vrdevicecontrol.domain.Tuning
import com.vrdevicecontrol.protocol.ClockStatus
import com.vrdevicecontrol.protocol.Envelope
import com.vrdevicecontrol.protocol.HeadsetStatus
import com.vrdevicecontrol.protocol.Hello
import com.vrdevicecontrol.protocol.Inventory
import com.vrdevicecontrol.protocol.InventoryItem
import com.vrdevicecontrol.protocol.PairingRequest
import com.vrdevicecontrol.protocol.PairingStatus
import com.vrdevicecontrol.protocol.PlaybackState
import com.vrdevicecontrol.protocol.RecenterResult
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** One live control connection to a headset process. */
interface DeviceLink {
    val deviceId: String
    val connectionId: String
    val processInstanceId: String

    /** Sends a message; returns the envelope sent, or null if the socket is gone. */
    fun send(
        type: String,
        payload: JsonObject,
        runId: String? = null,
        generation: Int? = null,
        messageId: String = UUID.randomUUID().toString(),
        correlationId: String? = null,
    ): Envelope?

    fun close(reason: String)
}

@Serializable
data class DeviceRecord(
    val deviceId: String,
    val label: String,
    val reportedName: String,
    val secretHash: String,
    val pairedAtUtc: String,
    val lastSeenUtc: String? = null,
    val model: String? = null,
    val osVersion: String? = null,
    val appVersion: String? = null,
    val decoders: List<String> = emptyList(),
    /** Last confirmed snapshot; shown as "last known" while out of contact. */
    val lastStatus: HeadsetStatus? = null,
    val lastStatusAtUtc: String? = null,
    val inventory: List<InventoryItem> = emptyList(),
    /** Address the headset last connected from; changes freely with DHCP and is display-only. */
    val lastAddress: String? = null,
    /** Operator preference: launch the headset app after reboot. Pushed on connect and when toggled. */
    val startOnBoot: Boolean = true,
    /** Operator preference: linear playback gain, 0 to 1. Pushed on connect and when changed. */
    val volume: Float = 1f,
)

@Serializable
private data class RegistryData(val devices: List<DeviceRecord> = emptyList())

data class LiveDevice(
    val link: DeviceLink,
    val connectedAtUs: Long,
    val remoteAddress: String? = null,
    val status: HeadsetStatus? = null,
    val clock: ClockStatus? = null,
    val clockReceivedAtUs: Long = 0,
    val inventoryPages: Map<Int, List<InventoryItem>> = emptyMap(),
    val recenterResult: RecenterResult? = null,
    val recenterReceivedAtUs: Long = 0,
    val recenterPending: Boolean = false,
)

data class PendingPairing(
    val requestId: String,
    val deviceId: String,
    val deviceName: String,
    val code: String,
    val nonce: String,
    val remoteHost: String,
    val createdAtUs: Long,
    val status: PairingStatus = PairingStatus.PENDING,
    /** Held in memory only until delivered once to the requesting headset. */
    val secret: String? = null,
)

/** Merged persistent + live view for UI and run rules. */
data class DeviceView(
    val record: DeviceRecord,
    val online: Boolean,
    val processInstanceId: String?,
    val status: HeadsetStatus?,
    val statusIsLive: Boolean,
    val clock: ClockStatus?,
    val clockAgeMs: Long?,
    val clockAcceptable: Boolean,
    val inventory: Set<String>,
    /** Current address while online, otherwise the last one seen. */
    val address: String? = null,
    val recenterResult: RecenterResult? = null,
    val recenterAgeMs: Long? = null,
    val recenterPending: Boolean = false,
) {
    val deviceId get() = record.deviceId
    val label get() = record.label
    val busyWithRun get() = status?.playbackState in setOf(PlaybackState.ARMED, PlaybackState.PLAYING, PlaybackState.PAUSED)
}

/**
 * Persistent identities, credentials (stored as hashes), last-seen snapshots and ephemeral
 * connection state. Confined to the controller actor; not thread-safe on its own.
 */
class DeviceRegistry(
    file: File,
    json: Json,
    private val clock: MonotonicClock,
    private val tuning: Tuning,
    private val log: EventLog,
) {
    private val store = JsonStore(file, RegistryData.serializer(), json) { RegistryData() }
    private val random = SecureRandom()

    private val _records = MutableStateFlow(store.current.devices)
    val records: StateFlow<List<DeviceRecord>> = _records.asStateFlow()

    private val _live = MutableStateFlow<Map<String, LiveDevice>>(emptyMap())
    val live: StateFlow<Map<String, LiveDevice>> = _live.asStateFlow()

    private val _pairing = MutableStateFlow<List<PendingPairing>>(emptyList())
    val pairing: StateFlow<List<PendingPairing>> = _pairing.asStateFlow()

    private val _pairingMode = MutableStateFlow(false)
    val pairingMode: StateFlow<Boolean> = _pairingMode.asStateFlow()
    private var pairingModeUntilUs = 0L

    var onDeviceConnected: (DeviceView) -> Unit = {}
    var onDeviceDisconnected: (deviceId: String) -> Unit = {}
    var onInventoryChanged: (deviceId: String) -> Unit = {}
    var onStatusChanged: (deviceId: String) -> Unit = {}

    // --- Authentication ----------------------------------------------------------------------

    /** Thread-safe: reads an immutable snapshot. */
    fun authenticate(deviceId: String, secret: String): DeviceRecord? {
        val record = _records.value.firstOrNull { it.deviceId == deviceId } ?: return null
        val presented = hash(secret).toByteArray()
        return record.takeIf { MessageDigest.isEqual(presented, it.secretHash.toByteArray()) }
    }

    // --- Pairing -------------------------------------------------------------------------------

    fun setPairingMode(enabled: Boolean) {
        _pairingMode.value = enabled
        pairingModeUntilUs = if (enabled) clock.nowUs() + PAIRING_WINDOW_US else 0
        log.log(if (enabled) "PAIRING_MODE_ON" else "PAIRING_MODE_OFF")
    }

    /** Returns null when pairing mode is off or too many requests are pending. */
    fun createPairing(request: PairingRequest, remoteHost: String): PendingPairing? {
        expirePairing()
        if (!_pairingMode.value) return null
        if (!CODE.matches(request.pairingCode) || request.nonce.length < 16 || request.deviceId.isBlank()) return null
        val others = _pairing.value.filterNot { it.deviceId == request.deviceId && it.status == PairingStatus.PENDING }
        if (others.count { it.status == PairingStatus.PENDING } >= MAX_PENDING) return null
        val pending = PendingPairing(
            requestId = UUID.randomUUID().toString(),
            deviceId = request.deviceId,
            deviceName = request.deviceName.take(40),
            code = request.pairingCode,
            nonce = request.nonce,
            remoteHost = remoteHost,
            createdAtUs = clock.nowUs(),
        )
        _pairing.value = others + pending
        log.log("PAIRING_REQUEST", request.deviceId, detail = "from $remoteHost")
        return pending
    }

    /** Polled by the headset. Delivers the credential exactly once. */
    fun pollPairing(requestId: String, nonce: String): PendingPairing? {
        expirePairing()
        val p = _pairing.value.firstOrNull { it.requestId == requestId } ?: return null
        if (!MessageDigest.isEqual(p.nonce.toByteArray(), nonce.toByteArray())) return null
        if (p.status == PairingStatus.APPROVED && p.secret != null) {
            _pairing.update { list -> list.map { if (it.requestId == requestId) it.copy(status = PairingStatus.CLOSED, secret = null) else it } }
        }
        return p
    }

    fun approve(requestId: String) {
        val p = _pairing.value.firstOrNull { it.requestId == requestId && it.status == PairingStatus.PENDING } ?: return
        val secretBytes = ByteArray(32).also(random::nextBytes)
        val secret = Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes)
        val existing = _records.value.firstOrNull { it.deviceId == p.deviceId }
        val record = (existing ?: DeviceRecord(
            deviceId = p.deviceId,
            label = nextLabel(),
            reportedName = p.deviceName,
            secretHash = "",
            pairedAtUtc = utcNow(),
        )).copy(secretHash = hash(secret), pairedAtUtc = utcNow())
        // Re-pairing rotates the credential; the old connection must re-authenticate.
        _live.value[p.deviceId]?.link?.close("Credential rotated")
        saveRecords(_records.value.filterNot { it.deviceId == p.deviceId } + record)
        _pairing.update { list -> list.map { if (it.requestId == requestId) it.copy(status = PairingStatus.APPROVED, secret = secret) else it } }
        log.log("PAIRING_APPROVED", p.deviceId, detail = record.label)
    }

    fun reject(requestId: String) {
        _pairing.update { list -> list.map { if (it.requestId == requestId) it.copy(status = PairingStatus.REJECTED, secret = null) else it } }
        log.log("PAIRING_REJECTED", detail = requestId)
    }

    fun dismissPairing(requestId: String) = _pairing.update { list -> list.filterNot { it.requestId == requestId } }

    private fun expirePairing() {
        val now = clock.nowUs()
        if (_pairingMode.value && now > pairingModeUntilUs) setPairingMode(false)
        _pairing.update { list ->
            list.mapNotNull {
                when {
                    now - it.createdAtUs > PAIRING_TTL_US * 2 -> null
                    it.status == PairingStatus.PENDING && now - it.createdAtUs > PAIRING_TTL_US -> it.copy(status = PairingStatus.EXPIRED)
                    else -> it
                }
            }
        }
    }

    fun tick() = expirePairing()

    // --- Device management ---------------------------------------------------------------------

    fun rename(deviceId: String, label: String) {
        val clean = label.trim().take(32).ifEmpty { return }
        saveRecords(_records.value.map { if (it.deviceId == deviceId) it.copy(label = clean) else it })
        log.log("DEVICE_RENAMED", deviceId, detail = clean)
    }

    /**
     * Stores the operator's start-on-boot preference. Callers that hold a [DeviceLink] should also
     * send [com.vrdevicecontrol.protocol.MessageType.DEVICE_CONFIG] so an online headset applies it
     * immediately; offline headsets pick it up on the next connect.
     */
    fun setStartOnBoot(deviceId: String, enabled: Boolean) {
        val current = _records.value.firstOrNull { it.deviceId == deviceId } ?: return
        if (current.startOnBoot == enabled) return
        updateRecord(deviceId) { it.copy(startOnBoot = enabled) }
        log.log(if (enabled) "START_ON_BOOT_ON" else "START_ON_BOOT_OFF", deviceId)
    }

    /** Stores playback volume. Callers send [com.vrdevicecontrol.protocol.MessageType.DEVICE_CONFIG] when the headset is online. */
    fun setVolume(deviceId: String, volume: Float) {
        val current = _records.value.firstOrNull { it.deviceId == deviceId } ?: return
        val clamped = volume.coerceIn(0f, 1f)
        if (kotlin.math.abs(current.volume - clamped) < 0.005f) return
        updateRecord(deviceId) { it.copy(volume = clamped) }
        log.log("VOLUME_SET", deviceId, detail = "${(clamped * 100).toInt()}%")
    }

    /** Deletes the pairing. The headset must pair again before it can connect. */
    fun revoke(deviceId: String) {
        _live.value[deviceId]?.link?.close("Pairing revoked")
        saveRecords(_records.value.filterNot { it.deviceId == deviceId })
        _live.update { it - deviceId }
        log.log("DEVICE_REVOKED", deviceId)
    }

    fun label(deviceId: String) = _records.value.firstOrNull { it.deviceId == deviceId }?.label ?: deviceId.take(8)

    // --- Connection lifecycle --------------------------------------------------------------------

    fun onConnected(link: DeviceLink, hello: Hello, remoteAddress: String? = null) {
        // A headset that reconnects (e.g. from a new IP after a restart) replaces its old connection
        // immediately, instead of waiting for the old socket's heartbeat to expire.
        val previous = _live.value[link.deviceId]?.takeIf { it.link.connectionId != link.connectionId }
        previous?.link?.close("Replaced by a newer connection")
        _live.update { it + (link.deviceId to LiveDevice(link, clock.nowUs(), remoteAddress)) }
        updateRecord(link.deviceId) {
            it.copy(
                reportedName = hello.deviceName,
                model = hello.capabilities.model,
                osVersion = hello.capabilities.osVersion,
                appVersion = hello.capabilities.appVersion,
                decoders = hello.capabilities.videoDecoders,
                lastSeenUtc = utcNow(),
                lastAddress = remoteAddress ?: it.lastAddress,
            )
        }
        val moved = previous?.remoteAddress?.let { old -> remoteAddress != null && old != remoteAddress } == true
        log.log(
            "CONNECTED", link.deviceId,
            detail = "from $remoteAddress, process ${link.processInstanceId.take(8)}" + if (moved) " (was ${previous.remoteAddress})" else "",
        )
        view(link.deviceId)?.let(onDeviceConnected)
    }

    fun onDisconnected(deviceId: String, connectionId: String) {
        val current = _live.value[deviceId] ?: return
        if (current.link.connectionId != connectionId) return
        _live.update { it - deviceId }
        updateRecord(deviceId) { it.copy(lastSeenUtc = utcNow()) }
        log.log("DISCONNECTED", deviceId)
        onDeviceDisconnected(deviceId)
    }

    fun onInbound(deviceId: String) {
        // Keep last-seen reasonably fresh without rewriting the file on every heartbeat.
        val record = _records.value.firstOrNull { it.deviceId == deviceId } ?: return
        val last = record.lastSeenUtc?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }
        if (last == null || java.time.Duration.between(last, java.time.Instant.now()).seconds >= 15) {
            updateRecord(deviceId) { it.copy(lastSeenUtc = utcNow()) }
        }
    }

    fun onStatus(deviceId: String, status: HeadsetStatus) {
        _live.update { map -> map[deviceId]?.let { map + (deviceId to it.copy(status = status)) } ?: map }
        val previous = _records.value.firstOrNull { it.deviceId == deviceId }?.lastStatus
        if (previous?.playbackState != status.playbackState || previous.runId != status.runId ||
            previous.batteryPercent != status.batteryPercent || previous.appActive != status.appActive
        ) {
            updateRecord(deviceId) { it.copy(lastStatus = status, lastStatusAtUtc = utcNow(), lastSeenUtc = utcNow()) }
        }
        onStatusChanged(deviceId)
    }

    fun onClock(deviceId: String, status: ClockStatus) {
        _live.update { map -> map[deviceId]?.let { map + (deviceId to it.copy(clock = status, clockReceivedAtUs = clock.nowUs())) } ?: map }
    }

    fun onRecenterResult(deviceId: String, result: RecenterResult) {
        _live.update { map ->
            map[deviceId]?.let {
                map + (deviceId to it.copy(recenterResult = result, recenterReceivedAtUs = clock.nowUs(), recenterPending = false))
            } ?: map
        }
        log.log(
            if (result.accepted) "RECENTERED" else "RECENTER_FAILED",
            deviceId,
            detail = result.error?.detail,
        )
    }

    fun onRecenterRequested(deviceId: String) {
        _live.update { map ->
            map[deviceId]?.let {
                map + (deviceId to it.copy(recenterResult = null, recenterReceivedAtUs = 0, recenterPending = true))
            } ?: map
        }
    }

    fun onInventory(deviceId: String, page: Inventory) {
        val live = _live.value[deviceId] ?: return
        val pages = live.inventoryPages + (page.page to page.assets)
        _live.update { it + (deviceId to live.copy(inventoryPages = if (pages.size >= page.pageCount) emptyMap() else pages)) }
        if (pages.size >= page.pageCount) {
            val items = (0 until page.pageCount).flatMap { pages[it].orEmpty() }
            updateRecord(deviceId) { it.copy(inventory = items) }
            onInventoryChanged(deviceId)
        }
    }

    // --- Views ---------------------------------------------------------------------------------

    fun link(deviceId: String): DeviceLink? = _live.value[deviceId]?.link

    fun views(): List<DeviceView> = _records.value.map { buildView(it) }

    fun view(deviceId: String): DeviceView? = _records.value.firstOrNull { it.deviceId == deviceId }?.let(::buildView)

    fun liveForRules(): Map<String, DeviceLive> = views().associate { v ->
        v.deviceId to DeviceLive(
            deviceId = v.deviceId,
            online = v.online,
            playback = if (v.statusIsLive) v.status?.playbackState else null,
            appActive = v.statusIsLive && v.status?.appActive == true,
            clockAcceptable = v.clockAcceptable,
            clockReason = v.clock?.reason ?: if (v.clock == null) "No clock sync yet" else null,
            hasAsset = true, // Content presence is checked per run asset by the caller.
        )
    }

    private fun buildView(record: DeviceRecord): DeviceView {
        val live = _live.value[record.deviceId]
        val now = clock.nowUs()
        val clockAgeMs = live?.clock?.let { it.ageMs + (now - live.clockReceivedAtUs) / 1000 }
        return DeviceView(
            record = record,
            online = live != null,
            processInstanceId = live?.link?.processInstanceId,
            status = live?.status ?: record.lastStatus,
            statusIsLive = live?.status != null,
            clock = live?.clock,
            clockAgeMs = clockAgeMs,
            clockAcceptable = live?.clock?.acceptable == true && clockAgeMs != null && clockAgeMs * 1000 <= tuning.clockMaxEstimateAgeUs,
            inventory = record.inventory.map { it.sha256 }.toSet(),
            address = live?.remoteAddress ?: record.lastAddress,
            recenterResult = live?.recenterResult,
            recenterAgeMs = live?.recenterResult?.let { (now - live.recenterReceivedAtUs) / 1_000 },
            recenterPending = live?.recenterPending == true,
        )
    }

    private fun updateRecord(deviceId: String, block: (DeviceRecord) -> DeviceRecord) {
        saveRecords(_records.value.map { if (it.deviceId == deviceId) block(it) else it })
    }

    private fun saveRecords(list: List<DeviceRecord>) {
        _records.value = list
        store.update { RegistryData(list) }
    }

    private fun nextLabel(): String {
        val used = _records.value.mapNotNull { LABEL.matchEntire(it.label)?.groupValues?.get(1)?.toIntOrNull() }.toSet()
        return "Headset ${generateSequence(1) { it + 1 }.first { it !in used }}"
    }

    private fun hash(secret: String): String =
        MessageDigest.getInstance("SHA-256").digest(secret.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        val CODE = Regex("\\d{6}")
        val LABEL = Regex("Headset (\\d+)")
        const val MAX_PENDING = 8
        const val PAIRING_TTL_US = 5 * 60 * 1_000_000L
        const val PAIRING_WINDOW_US = 10 * 60 * 1_000_000L
    }
}
