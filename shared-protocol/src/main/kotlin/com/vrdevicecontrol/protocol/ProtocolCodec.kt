package com.vrdevicecontrol.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer

sealed interface DecodeResult {
    data class Ok(val envelope: Envelope) : DecodeResult

    /** [messageId] is filled in when the header could be read, so the error can be correlated. */
    data class Rejected(val error: ErrorInfo, val messageId: String? = null) : DecodeResult
}

/**
 * Encodes and validates control messages. Unknown optional fields are tolerated; incompatible
 * major versions, oversize frames, unknown types and malformed headers are rejected with a typed
 * error rather than an exception.
 */
class ProtocolCodec(
    private val acceptedTypes: Set<String>,
    val json: Json = DefaultJson,
) {
    fun encode(envelope: Envelope): String {
        val text = json.encodeToString(Envelope.serializer(), envelope)
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_CONTROL_MESSAGE_BYTES) {
            "Control message ${envelope.type} exceeds $MAX_CONTROL_MESSAGE_BYTES bytes"
        }
        return text
    }

    fun decode(text: String): DecodeResult {
        if (text.length > MAX_CONTROL_MESSAGE_BYTES ||
            text.toByteArray(Charsets.UTF_8).size > MAX_CONTROL_MESSAGE_BYTES
        ) {
            return DecodeResult.Rejected(ErrorInfo(ErrorCode.MESSAGE_TOO_LARGE))
        }
        val tree = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            return DecodeResult.Rejected(ErrorInfo(ErrorCode.MALFORMED, e.message))
        }
        val messageId = runCatching { tree["messageId"]?.jsonPrimitive?.content }.getOrNull()
        val major = runCatching { tree["protocolMajor"]?.jsonPrimitive?.intOrNull }.getOrNull()
        if (major != PROTOCOL_MAJOR) {
            return DecodeResult.Rejected(
                ErrorInfo(ErrorCode.PROTOCOL_MISMATCH, "expected $PROTOCOL_MAJOR, got $major"),
                messageId,
            )
        }
        val envelope = try {
            json.decodeFromJsonElement(Envelope.serializer(), tree)
        } catch (e: SerializationException) {
            return DecodeResult.Rejected(ErrorInfo(ErrorCode.MALFORMED, e.message), messageId)
        } catch (e: IllegalArgumentException) {
            return DecodeResult.Rejected(ErrorInfo(ErrorCode.MALFORMED, e.message), messageId)
        }
        if (envelope.type !in acceptedTypes) {
            return DecodeResult.Rejected(
                ErrorInfo(ErrorCode.UNKNOWN_COMMAND, envelope.type),
                envelope.messageId,
            )
        }
        if (envelope.type in MessageType.SESSION_COMMANDS &&
            (envelope.runId == null || envelope.controllerEpoch == null ||
                envelope.generation == null || envelope.receiverInstanceId == null)
        ) {
            return DecodeResult.Rejected(
                ErrorInfo(ErrorCode.MALFORMED, "session command without run context"),
                envelope.messageId,
            )
        }
        return DecodeResult.Ok(envelope)
    }

    fun <T> payloadOf(envelope: Envelope, serializer: KSerializer<T>): T =
        json.decodeFromJsonElement(serializer, envelope.payload)

    inline fun <reified T> payloadOf(envelope: Envelope): T = payloadOf(envelope, serializer<T>())

    fun <T> toPayload(value: T, serializer: KSerializer<T>): JsonObject =
        json.encodeToJsonElement(serializer, value).jsonObject

    inline fun <reified T> toPayload(value: T): JsonObject = toPayload(value, serializer<T>())

    /**
     * Canonical semantic fingerprint of a command, used to detect a reused message ID with changed
     * content. Connection and sender process fields are excluded: a retry over a new connection is
     * the same command.
     */
    fun fingerprint(envelope: Envelope): String =
        listOf(
            envelope.type,
            envelope.runId,
            envelope.controllerEpoch,
            envelope.generation?.toString(),
            json.encodeToString(JsonObject.serializer(), envelope.payload),
        ).joinToString("|")

    companion object {
        val DefaultJson: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }

        fun forHeadset() = ProtocolCodec(MessageType.CONTROLLER_TO_HEADSET)
        fun forController() = ProtocolCodec(MessageType.HEADSET_TO_CONTROLLER)
    }
}
