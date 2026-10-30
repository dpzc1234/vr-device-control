package com.vrdevicecontrol.protocol

import kotlinx.serialization.Serializable

@Serializable
enum class PlaybackState { STANDBY, PREPARING, READY, ARMED, PLAYING, PAUSED, ERROR }

@Serializable
data class Capabilities(
    val model: String,
    val osVersion: String,
    val appVersion: String,
    /** Video MIME types with a hardware decoder, e.g. `video/hevc`. */
    val videoDecoders: List<String> = emptyList(),
    val maxDecodeWidth: Int = 0,
    val maxDecodeHeight: Int = 0,
)

// --- Connection -----------------------------------------------------------------------------

@Serializable
data class Hello(
    val deviceId: String,
    val deviceName: String,
    val protocolMinor: Int = PROTOCOL_MINOR,
    val capabilities: Capabilities,
    /** Epoch of the controller this headset last spoke to, for reconciliation diagnostics. */
    val lastControllerEpoch: String? = null,
)

@Serializable
data class Welcome(
    val controllerId: String,
    val controllerName: String,
    val controllerEpoch: String,
    val connectionId: String,
    val protocolMinor: Int = PROTOCOL_MINOR,
    val heartbeatIntervalMs: Long,
    val staleAfterMs: Long,
    /** Operator-assigned label, e.g. "Headset 2"; shown inside the headset. */
    val deviceLabel: String? = null,
)

@Serializable
data class Heartbeat(val sentAtUs: Micros)

@Serializable
data class HeadsetStatus(
    val playbackState: PlaybackState,
    val runId: String? = null,
    val generation: Int? = null,
    val assetSha256: String? = null,
    val positionUs: Micros = 0,
    val durationUs: Micros = 0,
    /** Monotonic headset time of this snapshot. */
    val capturedAtUs: Micros = 0,
    val appActive: Boolean,
    val worn: Boolean? = null,
    val batteryPercent: Int? = null,
    val charging: Boolean? = null,
    val freeBytes: Long = 0,
    val totalBytes: Long = 0,
    val transfersDeferred: Boolean = false,
    val lastError: ErrorInfo? = null,
    /** Whether the headset app launches itself after a reboot. */
    val startOnBoot: Boolean = true,
)

/** Operator preferences pushed from the tablet; applied locally on the headset. */
@Serializable
data class DeviceConfig(
    val startOnBoot: Boolean,
    /** Linear gain from 0 to 1. Null leaves the headset's current volume unchanged. */
    val volume: Float? = null,
)

/** Immediate operator request to align app space with the rider's current position and heading. */
@Serializable
data object Recenter

@Serializable
data class RecenterResult(
    val accepted: Boolean,
    /** Headset monotonic time at which the scene origin was updated. */
    val centeredAtUs: Micros? = null,
    val error: ErrorInfo? = null,
)

// --- Clock ----------------------------------------------------------------------------------

@Serializable
data class ClockProbe(val probeId: String, val h1Us: Micros)

@Serializable
data class ClockReply(val probeId: String, val h1Us: Micros, val c2Us: Micros, val c3Us: Micros)

@Serializable
data class ClockStatus(
    val estimateId: String,
    val offsetUs: Micros,
    val uncertaintyUs: Micros,
    val rttUs: Micros,
    val sampleCount: Int,
    val ageMs: Long,
    val acceptable: Boolean,
    val reason: String? = null,
)

// --- Content --------------------------------------------------------------------------------

@Serializable
data class Inventory(
    val page: Int,
    val pageCount: Int,
    val assets: List<InventoryItem>,
)

@Serializable
data class TransferProgress(
    val operationId: String,
    val sha256: String,
    val state: TransferState,
    val committedBytes: Long,
    val totalBytes: Long,
    val error: ErrorInfo? = null,
)

/** Durable content request: the headset pulls the bytes over HTTP and owns its checkpoint. */
@Serializable
data class EnsureAsset(
    val operationId: String,
    val asset: AssetDescriptor,
)

@Serializable
data class RemoveAsset(
    val operationId: String,
    val sha256: String,
)

@Serializable
data class AssetResult(
    val operationId: String,
    val sha256: String,
    val ok: Boolean,
    val error: ErrorInfo? = null,
)

// --- Run session ----------------------------------------------------------------------------

@Serializable
data class Prepare(
    val asset: AssetDescriptor,
    val presentation: PresentationDescriptor,
    val validUntilControllerUs: Micros,
    val clockEstimateId: String? = null,
)

@Serializable
data class Ready(
    val prepareToken: String,
    val assetSha256: String,
    val presentationRevision: Int,
    val clockEstimateId: String,
    val preparedAtUs: Micros,
)

@Serializable
data class NotReady(val error: ErrorInfo)

@Serializable
data class StartAt(
    val assetSha256: String,
    val presentationRevision: Int,
    val prepareToken: String,
    val clockSampleId: String,
    val startAtControllerUs: Micros,
    val acceptBeforeControllerUs: Micros,
    val positionUs: Micros = 0,
)

@Serializable
data class StartAccepted(
    val startAtControllerUs: Micros,
    val localDeadlineUs: Micros,
    val clockEstimateId: String,
)

@Serializable
data class Started(
    val deadlineLocalUs: Micros,
    val callbackLocalUs: Micros,
    val latenessUs: Micros,
)

@Serializable
data class Completed(val durationUs: Micros)

@Serializable
data class RunError(val error: ErrorInfo)

/** Transient control (`CANCEL_START`, `PAUSE`, `RESUME`, `STOP`) with a short acceptance window. */
@Serializable
data class Control(
    val validUntilControllerUs: Micros,
    val clockEstimateId: String? = null,
)

@Serializable
data class ControlResult(
    val commandType: String,
    val accepted: Boolean,
    val playbackState: PlaybackState,
    val error: ErrorInfo? = null,
)

// --- Results outbox -------------------------------------------------------------------------

@Serializable
enum class RunRecordKind { ACCEPTED, STARTED, PAUSED, RESUMED, INTERRUPTED, STOPPED, COMPLETED, ERROR }

@Serializable
data class RunRecord(
    val recordId: String,
    val runId: String,
    val generation: Int,
    val controllerEpoch: String,
    val processInstanceId: String,
    val sequence: Long,
    val kind: RunRecordKind,
    val atUtc: String,
    val monotonicUs: Micros,
    val positionUs: Micros = 0,
    val detail: String? = null,
    val error: ErrorInfo? = null,
)

@Serializable
data class ResultBatch(val records: List<RunRecord>)

@Serializable
data class ResultsAck(val recordIds: List<String>)

@Serializable
data class ErrorMessage(val error: ErrorInfo, val refType: String? = null)
