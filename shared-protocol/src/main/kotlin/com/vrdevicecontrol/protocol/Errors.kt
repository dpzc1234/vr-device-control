package com.vrdevicecontrol.protocol

import kotlinx.serialization.Serializable

@Serializable
enum class ErrorCode {
    UNAUTHORIZED,
    PROTOCOL_MISMATCH,
    UNKNOWN_COMMAND,
    MESSAGE_TOO_LARGE,
    MALFORMED,
    STALE_CONNECTION,
    STALE_RUN,
    STALE_PREPARATION,
    ASSET_MISSING,
    ASSET_IN_USE,
    HASH_MISMATCH,
    UNSUPPORTED_MEDIA,
    STORAGE_FULL,
    NOT_ACTIVE,
    CLOCK_UNCERTAIN,
    TOO_LATE,
    ALREADY_ARMED,
    INVALID_STATE,
    DUPLICATE_CONFLICT,
    PLAYBACK_FAILED,
    TRANSFER_FAILED,
    INTERRUPTED,
}

@Serializable
data class ErrorInfo(
    val code: ErrorCode,
    val detail: String? = null,
)
