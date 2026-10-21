package com.vrdevicecontrol.protocol

import kotlinx.serialization.Serializable

object Endpoints {
    const val PAIRING_REQUESTS = "/v1/pairing/requests"
    const val CONTROL = "/v1/control"
    const val CATALOG = "/v1/catalog"
    const val ASSETS = "/v1/assets"
    const val PING = "/v1/ping"

    fun asset(sha256: String) = "$ASSETS/$sha256"
    fun pairingRequest(requestId: String) = "$PAIRING_REQUESTS/$requestId"
}

/** Header carrying the per-request pairing nonce while a pairing request is pending. */
const val PAIRING_NONCE_HEADER = "X-Pairing-Nonce"

/**
 * Per-device credential sent as `Authorization: Bearer <deviceId>.<secret>`. Never placed in a
 * query string.
 */
object DeviceCredential {
    fun header(deviceId: String, secret: String) = "Bearer $deviceId.$secret"

    /** Returns (deviceId, secret) or null when the header is not a well-formed device credential. */
    fun parse(header: String?): Pair<String, String>? {
        if (header == null || !header.startsWith("Bearer ")) return null
        val token = header.removePrefix("Bearer ").trim()
        val dot = token.indexOf('.')
        if (dot <= 0 || dot == token.lastIndex) return null
        return token.substring(0, dot) to token.substring(dot + 1)
    }
}

@Serializable
data class PingResponse(
    val controllerId: String,
    val controllerName: String,
    val protocolMajor: Int = PROTOCOL_MAJOR,
)

/**
 * A headset asks to be paired. [pairingCode] is shown inside the headset; the operator approves
 * only when the tablet shows the same code.
 */
@Serializable
data class PairingRequest(
    val deviceId: String,
    val deviceName: String,
    val pairingCode: String,
    val nonce: String,
    val protocolMajor: Int = PROTOCOL_MAJOR,
)

@Serializable
enum class PairingStatus { PENDING, APPROVED, REJECTED, EXPIRED, CLOSED }

@Serializable
data class PairingResponse(
    val requestId: String,
    val status: PairingStatus,
    val controllerId: String,
    val controllerName: String,
    /** Delivered once, only after operator approval. */
    val credentialSecret: String? = null,
)

@Serializable
data class CatalogPage(
    val page: Int,
    val pageCount: Int,
    val assets: List<AssetDescriptor>,
)
