package com.vrdevicecontrol.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.LongAsStringSerializer
import kotlinx.serialization.json.JsonObject

const val PROTOCOL_MAJOR = 1
const val PROTOCOL_MINOR = 1

/** Maximum encoded size of one control message. Media never travels over the control socket. */
const val MAX_CONTROL_MESSAGE_BYTES = 64 * 1024

/** DNS-SD service type advertised by the controller. */
const val SERVICE_TYPE = "_vrctrl._tcp"

/**
 * 64-bit monotonic microsecond timestamp, encoded as a decimal string so diagnostic JavaScript
 * tools do not lose precision.
 */
typealias Micros = @Serializable(with = LongAsStringSerializer::class) Long

/**
 * Common header for every control message. Session commands additionally carry [runId],
 * [controllerEpoch], [generation] and [receiverInstanceId].
 */
@Serializable
data class Envelope(
    val protocolMajor: Int = PROTOCOL_MAJOR,
    val type: String,
    val messageId: String,
    val senderId: String,
    val senderInstanceId: String,
    val connectionId: String? = null,
    val receiverInstanceId: String? = null,
    val controllerEpoch: String? = null,
    val runId: String? = null,
    val generation: Int? = null,
    /** Message ID of the command this message answers, for results and acknowledgements. */
    val correlationId: String? = null,
    val payload: JsonObject = JsonObject(emptyMap()),
)

object MessageType {
    const val HELLO = "HELLO"
    const val WELCOME = "WELCOME"
    const val HEARTBEAT = "HEARTBEAT"
    const val STATUS = "STATUS"
    const val CLOCK_PROBE = "CLOCK_PROBE"
    const val CLOCK_REPLY = "CLOCK_REPLY"
    const val CLOCK_STATUS = "CLOCK_STATUS"
    const val INVENTORY = "INVENTORY"
    const val TRANSFER_PROGRESS = "TRANSFER_PROGRESS"
    const val ENSURE_ASSET = "ENSURE_ASSET"
    const val REMOVE_ASSET = "REMOVE_ASSET"
    const val ASSET_RESULT = "ASSET_RESULT"
    const val PREPARE = "PREPARE"
    const val READY = "READY"
    const val NOT_READY = "NOT_READY"
    const val START_AT = "START_AT"
    const val START_ACCEPTED = "START_ACCEPTED"
    const val STARTED = "STARTED"
    const val COMPLETED = "COMPLETED"
    const val RUN_ERROR = "RUN_ERROR"
    const val CANCEL_START = "CANCEL_START"
    const val CONTROL_RESULT = "CONTROL_RESULT"
    const val PAUSE = "PAUSE"
    const val RESUME = "RESUME"
    const val STOP = "STOP"
    const val RESULT_BATCH = "RESULT_BATCH"
    const val RESULTS_ACK = "RESULTS_ACK"
    const val DEVICE_CONFIG = "DEVICE_CONFIG"
    const val RECENTER = "RECENTER"
    const val RECENTER_RESULT = "RECENTER_RESULT"
    const val ERROR = "ERROR"

    /** Types the controller may send; anything else from the controller is an unknown command. */
    val CONTROLLER_TO_HEADSET = setOf(
        WELCOME, HEARTBEAT, CLOCK_REPLY, ENSURE_ASSET, REMOVE_ASSET, PREPARE, START_AT,
        CANCEL_START, PAUSE, RESUME, STOP, RESULTS_ACK, DEVICE_CONFIG, RECENTER, ERROR,
    )

    /** Types a headset may send. */
    val HEADSET_TO_CONTROLLER = setOf(
        HELLO, HEARTBEAT, STATUS, CLOCK_PROBE, CLOCK_STATUS, INVENTORY, TRANSFER_PROGRESS,
        ASSET_RESULT, READY, NOT_READY, START_ACCEPTED, STARTED, COMPLETED, RUN_ERROR,
        CONTROL_RESULT, RESULT_BATCH, RECENTER_RESULT, ERROR,
    )

    /** Session commands must carry run, epoch, generation and receiver process identifiers. */
    val SESSION_COMMANDS = setOf(PREPARE, START_AT, CANCEL_START, PAUSE, RESUME, STOP)
}
