package com.vrdevicecontrol.protocol

import kotlinx.serialization.Serializable

@Serializable
enum class Projection { FLAT, EQUIRECT_180, EQUIRECT_360 }

@Serializable
enum class StereoLayout { MONO, SIDE_BY_SIDE, TOP_BOTTOM }

/** Which half of a stereo frame belongs to the left eye. */
@Serializable
enum class EyeOrder { LEFT_FIRST, RIGHT_FIRST }

/**
 * Immutable media object identified by content hash. Filenames are labels only; devices match
 * content by [sha256].
 */
@Serializable
data class AssetDescriptor(
    val assetId: String,
    val sha256: String,
    val sizeBytes: Long,
    val title: String,
    val mimeType: String,
    val durationUs: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val bitrate: Long? = null,
    val frameRate: Float? = null,
)

/**
 * How an asset is presented. Any edit increments [revision], which invalidates prepared sessions
 * without requiring the bytes to be transferred again.
 */
@Serializable
data class PresentationDescriptor(
    val assetId: String,
    val revision: Int,
    val projection: Projection,
    val stereo: StereoLayout,
    val eyeOrder: EyeOrder = EyeOrder.LEFT_FIRST,
    /** Width of a flat screen in metres; ignored for spherical projections. */
    val flatWidthMeters: Float = 4f,
    /** Display aspect ratio (width / height) of one eye's image; 0 means derive from the stream. */
    val aspectRatio: Float = 0f,
    val initialYawDegrees: Float = 0f,
)

@Serializable
data class InventoryItem(
    val sha256: String,
    val sizeBytes: Long,
    val verifiedAtUtc: String,
)

@Serializable
enum class TransferState { QUEUED, DOWNLOADING, VERIFYING, AVAILABLE, PAUSED, FAILED, CANCELLED }
