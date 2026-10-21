package com.vrdevicecontrol.headset.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import com.vrdevicecontrol.domain.Backoff
import com.vrdevicecontrol.domain.MonotonicClock
import com.vrdevicecontrol.domain.Tuning
import com.vrdevicecontrol.headset.identity.HeadsetIdentity
import com.vrdevicecontrol.headset.identity.Pairing
import com.vrdevicecontrol.protocol.ClockReply
import com.vrdevicecontrol.protocol.DecodeResult
import com.vrdevicecontrol.protocol.DeviceCredential
import com.vrdevicecontrol.protocol.Endpoints
import com.vrdevicecontrol.protocol.Envelope
import com.vrdevicecontrol.protocol.ErrorMessage
import com.vrdevicecontrol.protocol.Heartbeat
import com.vrdevicecontrol.protocol.Hello
import com.vrdevicecontrol.protocol.MessageType
import com.vrdevicecontrol.protocol.PAIRING_NONCE_HEADER
import com.vrdevicecontrol.protocol.PROTOCOL_MAJOR
import com.vrdevicecontrol.protocol.PairingRequest
import com.vrdevicecontrol.protocol.PairingResponse
import com.vrdevicecontrol.protocol.PairingStatus
import com.vrdevicecontrol.protocol.PingResponse
import com.vrdevicecontrol.protocol.ProtocolCodec
import com.vrdevicecontrol.protocol.Welcome
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

sealed interface LinkState {
    data object NotPaired : LinkState
    data class Searching(val controllerName: String) : LinkState
    data class Connecting(val host: String, val port: Int) : LinkState
    data class Connected(val host: String, val port: Int, val welcome: Welcome) : LinkState
    data class Waiting(val reason: String) : LinkState
    /** The controller refused our credential. Requires explicit re-pairing; no automatic retry. */
    data object Unauthorized : LinkState
}

sealed interface PairingState {
    data object Idle : PairingState
    data class Requesting(val controllerName: String) : PairingState
    data class AwaitingApproval(val controllerName: String, val code: String) : PairingState
    data class Failed(val reason: String) : PairingState
}

data class Inbound(val envelope: Envelope, val receivedAtUs: Long)

/** Base URL and credential for authenticated HTTP requests to the current controller. */
data class ControllerTarget(val baseUrl: String, val authorization: String)

/**
 * Discovery, pairing, reconnect and inbound validation for the controller link. Owns no player
 * state: losing this connection never changes playback.
 */
class ControllerConnection(
    context: Context,
    private val identity: HeadsetIdentity,
    private val discovery: ControllerDiscovery,
    private val clock: MonotonicClock,
    private val tuning: Tuning,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    private val helloProvider: () -> Hello,
) {
    val codec = ProtocolCodec.forHeadset()
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val jsonType = "application/json".toMediaType()

    private val _state = MutableStateFlow<LinkState>(LinkState.Waiting("Starting"))
    val state: StateFlow<LinkState> = _state.asStateFlow()

    private val _pairingState = MutableStateFlow<PairingState>(PairingState.Idle)
    val pairingState: StateFlow<PairingState> = _pairingState.asStateFlow()

    /** Validated commands after WELCOME, in arrival order. */
    val inbound = Channel<Inbound>(capacity = 512)

    private val _connected = MutableSharedFlow<Welcome>(extraBufferCapacity = 4)
    val connected: SharedFlow<Welcome> = _connected.asSharedFlow()

    val clockSync = ClockSynchronizer(clock, tuning) { probe -> send(MessageType.CLOCK_PROBE, probe) }

    @Volatile private var socket: WebSocket? = null
    @Volatile var welcome: Welcome? = null
        private set
    @Volatile private var target: ControllerTarget? = null
    @Volatile private var lastInboundUs = 0L
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var pairingJob: Job? = null

    fun start() {
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = reconnectNow("Network available")
            override fun onLost(network: Network) = Unit // Heartbeat expiry detects a dead link.
        })
        scope.launch { runLoop() }
    }

    fun currentTarget(): ControllerTarget? = target

    fun reconnectNow(reason: String) {
        Log.i(TAG, "Reconnect requested: $reason")
        socket?.cancel()
        discovery.restart()
        wake.trySend(Unit)
    }

    // --- Sending -------------------------------------------------------------------------------

    fun send(
        type: String,
        payload: JsonObject,
        correlationId: String? = null,
        runId: String? = null,
        generation: Int? = null,
    ): Boolean {
        val ws = socket ?: return false
        val w = welcome
        if (w == null && type != MessageType.HELLO) return false
        val envelope = Envelope(
            type = type,
            messageId = UUID.randomUUID().toString(),
            senderId = identity.deviceId,
            senderInstanceId = identity.processInstanceId,
            connectionId = w?.connectionId,
            controllerEpoch = w?.controllerEpoch,
            runId = runId,
            generation = generation,
            correlationId = correlationId,
            payload = payload,
        )
        return ws.send(codec.encode(envelope))
    }

    inline fun <reified T> send(
        type: String,
        value: T,
        correlationId: String? = null,
        runId: String? = null,
        generation: Int? = null,
    ): Boolean = send(type, codec.toPayload(value, serializer<T>()), correlationId, runId, generation)

    /** Re-sends a previously built reply envelope unchanged (duplicate command handling). */
    fun resend(envelope: Envelope): Boolean = socket?.send(codec.encode(envelope)) ?: false

    // --- Connection loop -----------------------------------------------------------------------

    private suspend fun runLoop() {
        val backoff = Backoff(tuning.reconnectMinMs, tuning.reconnectMaxMs)
        while (scope.isActive) {
            val pairing = identity.pairing.value
            if (pairing == null) {
                _state.value = LinkState.NotPaired
                discovery.start()
                identity.pairing.first { it != null }
                continue
            }
            discovery.start()
            val address = resolve(pairing)
            if (address == null) {
                _state.value = LinkState.Waiting("Looking for ${pairing.controllerName}")
                waitBackoff(backoff)
                continue
            }
            val outcome = connectOnce(pairing, address.first, address.second)
            when (outcome) {
                Outcome.UNAUTHORIZED -> {
                    _state.value = LinkState.Unauthorized
                    identity.pairing.first { it != pairing }
                }
                Outcome.WAS_CONNECTED -> {
                    backoff.reset()
                    _state.value = LinkState.Waiting("Connection lost — reconnecting")
                    waitBackoff(backoff)
                }
                Outcome.FAILED -> {
                    _state.value = LinkState.Waiting("Cannot reach ${pairing.controllerName}")
                    waitBackoff(backoff)
                }
            }
        }
    }

    private suspend fun waitBackoff(backoff: Backoff) {
        withTimeoutOrNull(backoff.nextDelayMs()) { wake.receive() }
    }

    /** Discovery result for our paired controller first; the remembered address is only a hint. */
    private suspend fun resolve(pairing: Pairing): Pair<String, Int>? {
        _state.value = LinkState.Searching(pairing.controllerName)
        val found = withTimeoutOrNull(3_000) {
            discovery.controllers.first { list -> list.any { it.controllerId == pairing.controllerId } }
        }?.firstOrNull { it.controllerId == pairing.controllerId }
        if (found != null) return found.host to found.port
        return if (pairing.hostHint.isNotBlank() && pairing.portHint > 0) pairing.hostHint to pairing.portHint else null
    }

    private enum class Outcome { WAS_CONNECTED, FAILED, UNAUTHORIZED }

    private sealed interface WsEvent {
        data object Open : WsEvent
        data class Welcomed(val welcome: Welcome) : WsEvent
        data class Closed(val code: Int, val reason: String) : WsEvent
        data class Failed(val error: Throwable, val httpCode: Int?) : WsEvent
    }

    private suspend fun connectOnce(pairing: Pairing, host: String, port: Int): Outcome {
        _state.value = LinkState.Connecting(host, port)
        val events = Channel<WsEvent>(Channel.UNLIMITED)
        val auth = DeviceCredential.header(identity.deviceId, pairing.secret)
        val request = Request.Builder()
            .url("ws://$host:$port${Endpoints.CONTROL}")
            .header("Authorization", auth)
            .build()
        val ws = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                events.trySend(WsEvent.Open)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val receivedAt = clock.nowUs() // Captured before any decoding or UI work.
                lastInboundUs = receivedAt
                onFrame(webSocket, text, receivedAt, events)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                events.trySend(WsEvent.Closed(code, reason))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                events.trySend(WsEvent.Closed(code, reason))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                events.trySend(WsEvent.Failed(t, response?.code))
            }
        })
        socket = ws
        try {
            when (val first = withTimeoutOrNull(6_000) { events.receive() }) {
                WsEvent.Open -> Unit
                is WsEvent.Failed -> return if (first.httpCode == 401 || first.httpCode == 403) Outcome.UNAUTHORIZED else Outcome.FAILED
                is WsEvent.Closed -> return if (first.code == CLOSE_UNAUTHORIZED) Outcome.UNAUTHORIZED else Outcome.FAILED
                else -> return Outcome.FAILED
            }
            send(MessageType.HELLO, helloProvider())
            val welcomed = when (val e = withTimeoutOrNull(6_000) { events.receive() }) {
                is WsEvent.Welcomed -> e.welcome
                is WsEvent.Closed -> return if (e.code == CLOSE_UNAUTHORIZED) Outcome.UNAUTHORIZED else Outcome.FAILED
                else -> return Outcome.FAILED
            }
            welcome = welcomed
            target = ControllerTarget("http://$host:$port", auth)
            identity.updateAddressHint(host, port)
            welcomed.deviceLabel?.let(identity::setLabel)
            discovery.stop()
            _state.value = LinkState.Connected(host, port, welcomed)
            _connected.tryEmit(welcomed)

            val heartbeat = scope.launch {
                while (isActive) {
                    delay(welcomed.heartbeatIntervalMs)
                    send(MessageType.HEARTBEAT, Heartbeat(clock.nowUs()))
                    if (clock.nowUs() - lastInboundUs > welcomed.staleAfterMs * 1_000) {
                        Log.w(TAG, "Connection stale; reconnecting")
                        ws.cancel()
                    }
                }
            }
            try {
                while (true) {
                    when (val e = events.receive()) {
                        is WsEvent.Closed -> return if (e.code == CLOSE_UNAUTHORIZED) Outcome.UNAUTHORIZED else Outcome.WAS_CONNECTED
                        is WsEvent.Failed -> return Outcome.WAS_CONNECTED
                        else -> Unit
                    }
                }
            } finally {
                heartbeat.cancel()
            }
        } finally {
            ws.cancel()
            socket = null
            welcome = null
            target = null
        }
    }

    private fun onFrame(ws: WebSocket, text: String, receivedAt: Long, events: Channel<WsEvent>) {
        when (val decoded = codec.decode(text)) {
            is DecodeResult.Rejected -> {
                Log.w(TAG, "Rejected inbound message: ${decoded.error}")
                send(MessageType.ERROR, ErrorMessage(decoded.error), correlationId = decoded.messageId)
            }
            is DecodeResult.Ok -> {
                val env = decoded.envelope
                when (env.type) {
                    MessageType.CLOCK_REPLY -> clockSync.onReply(codec.payloadOf<ClockReply>(env), receivedAt)
                    MessageType.HEARTBEAT -> Unit
                    MessageType.WELCOME -> events.trySend(WsEvent.Welcomed(codec.payloadOf(env)))
                    else -> if (welcome != null && env.connectionId == welcome?.connectionId) {
                        inbound.trySend(Inbound(env, receivedAt))
                    } else {
                        Log.w(TAG, "Dropping ${env.type} for a stale connection")
                    }
                }
            }
        }
    }

    // --- Pairing and diagnostics ---------------------------------------------------------------

    /** Ping a controller address. Distinguishes "cannot reach" from discovery failure. */
    suspend fun ping(host: String, port: Int): Result<PingResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url("http://$host:$port${Endpoints.PING}").build()
            http.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "HTTP ${response.code}" }
                codec.json.decodeFromString(PingResponse.serializer(), response.body!!.string())
            }
        }
    }

    fun pair(host: String, port: Int, displayName: String) {
        pairingJob?.cancel()
        pairingJob = scope.launch {
            _pairingState.value = PairingState.Requesting(displayName)
            val code = (SecureRandom().nextInt(900_000) + 100_000).toString()
            val nonce = UUID.randomUUID().toString()
            val base = "http://$host:$port"
            try {
                val created = withContext(Dispatchers.IO) {
                    val body = codec.json.encodeToString(
                        PairingRequest.serializer(),
                        PairingRequest(identity.deviceId, identity.label.value, code, nonce, PROTOCOL_MAJOR),
                    ).toRequestBody(jsonType)
                    http.newCall(Request.Builder().url(base + Endpoints.PAIRING_REQUESTS).post(body).build()).execute().use { r ->
                        check(r.isSuccessful) { if (r.code == 403) "Pairing mode is off on the tablet" else "HTTP ${r.code}" }
                        codec.json.decodeFromString(PairingResponse.serializer(), r.body!!.string())
                    }
                }
                _pairingState.value = PairingState.AwaitingApproval(created.controllerName, code)
                val deadline = clock.nowUs() + 180_000_000
                while (clock.nowUs() < deadline) {
                    delay(1_500)
                    val status = withContext(Dispatchers.IO) {
                        val req = Request.Builder().url(base + Endpoints.pairingRequest(created.requestId))
                            .header(PAIRING_NONCE_HEADER, nonce).build()
                        http.newCall(req).execute().use { r ->
                            check(r.isSuccessful) { "HTTP ${r.code}" }
                            codec.json.decodeFromString(PairingResponse.serializer(), r.body!!.string())
                        }
                    }
                    when (status.status) {
                        PairingStatus.PENDING -> continue
                        PairingStatus.APPROVED -> {
                            val secret = checkNotNull(status.credentialSecret) { "Approved without credential" }
                            identity.savePairing(Pairing(status.controllerId, status.controllerName, host, port, secret))
                            _pairingState.value = PairingState.Idle
                            wake.trySend(Unit)
                            return@launch
                        }
                        else -> {
                            _pairingState.value = PairingState.Failed("Pairing ${status.status.name.lowercase()} on the tablet")
                            return@launch
                        }
                    }
                }
                _pairingState.value = PairingState.Failed("Pairing timed out")
            } catch (e: Exception) {
                Log.w(TAG, "Pairing failed", e)
                _pairingState.value = PairingState.Failed(e.message ?: "Pairing failed")
            }
        }
    }

    fun cancelPairing() {
        pairingJob?.cancel()
        _pairingState.value = PairingState.Idle
    }

    fun unpair() {
        identity.clearPairing()
        socket?.cancel()
    }

    companion object {
        private const val TAG = "ControllerConnection"
        /** Policy-violation close code used by the controller for refused credentials. */
        const val CLOSE_UNAUTHORIZED = 1008
    }
}
