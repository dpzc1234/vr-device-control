package com.vrdevicecontrol.headset.playback

import com.vrdevicecontrol.protocol.AssetDescriptor
import com.vrdevicecontrol.protocol.ErrorInfo
import com.vrdevicecontrol.protocol.PresentationDescriptor
import java.io.File

sealed interface PlaybackEvent {
    /** Decoder configured, first frame rendered to the hidden surface, holding at position zero. */
    data class Prepared(val token: String) : PlaybackEvent
    data class PrepareFailed(val token: String, val error: ErrorInfo) : PlaybackEvent
    data class Completed(val durationUs: Long) : PlaybackEvent
    data class Failed(val error: ErrorInfo) : PlaybackEvent
}

/** Diagnostic timestamps (elapsed-realtime µs). Evidence only, not proof of physical display alignment. */
data class StartTimings(
    val playRequestedUs: Long,
    val firstFrameReleaseUs: Long? = null,
    val audioPositionAdvancedUs: Long? = null,
)

/**
 * Our playback adapter (not an SDK class). All calls must happen on the player's application
 * looper; socket callbacks never call it directly.
 */
interface PlaybackEngine {
    var listener: (PlaybackEvent) -> Unit
    val lastStartTimings: StartTimings?

    fun prepare(token: String, file: File, asset: AssetDescriptor, presentation: PresentationDescriptor)

    /** Requests playback from position zero. The scene reveals the video in the same frame. */
    fun play()
    fun pause()
    fun resume()
    fun release()

    fun positionUs(): Long
    fun durationUs(): Long

    /** Linear gain from 0 to 1. Applies to the current player and the next one created. */
    fun setVolume(volume: Float)
}
