package com.vrdevicecontrol.domain

import com.vrdevicecontrol.protocol.ErrorCode
import com.vrdevicecontrol.protocol.ErrorInfo

/** Concise operator text for each error code; the device detail is shown separately. */
object OperatorMessages {
    fun short(code: ErrorCode): String = when (code) {
        ErrorCode.UNAUTHORIZED -> "Not paired with this tablet"
        ErrorCode.PROTOCOL_MISMATCH -> "App versions are incompatible"
        ErrorCode.UNKNOWN_COMMAND -> "Command not supported by headset app"
        ErrorCode.MESSAGE_TOO_LARGE -> "Message too large"
        ErrorCode.MALFORMED -> "Malformed message"
        ErrorCode.STALE_CONNECTION -> "Connection was replaced"
        ErrorCode.STALE_RUN -> "Command was for an older run"
        ErrorCode.STALE_PREPARATION -> "Preparation is out of date — prepare again"
        ErrorCode.ASSET_MISSING -> "Video not on headset"
        ErrorCode.ASSET_IN_USE -> "Video is in use"
        ErrorCode.HASH_MISMATCH -> "Downloaded file is corrupt"
        ErrorCode.UNSUPPORTED_MEDIA -> "Video format not supported"
        ErrorCode.STORAGE_FULL -> "Headset storage full"
        ErrorCode.NOT_ACTIVE -> "Headset app inactive (asleep or removed)"
        ErrorCode.CLOCK_UNCERTAIN -> "Clock sync not good enough"
        ErrorCode.TOO_LATE -> "Command arrived too late"
        ErrorCode.ALREADY_ARMED -> "Start already scheduled"
        ErrorCode.INVALID_STATE -> "Not possible in current state"
        ErrorCode.DUPLICATE_CONFLICT -> "Conflicting duplicate command"
        ErrorCode.PLAYBACK_FAILED -> "Playback failed"
        ErrorCode.TRANSFER_FAILED -> "Transfer failed"
        ErrorCode.INTERRUPTED -> "Interrupted on headset"
    }

    fun short(error: ErrorInfo): String = short(error.code)

    fun full(error: ErrorInfo): String =
        error.detail?.takeIf { it.isNotBlank() }?.let { "${short(error.code)}: $it" } ?: short(error.code)
}
