package com.vrdevicecontrol.controller.host

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.wifi.WifiManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.vrdevicecontrol.controller.catalog.CatalogRepository
import com.vrdevicecontrol.controller.devices.DeviceLink
import com.vrdevicecontrol.controller.devices.DeviceRegistry
import com.vrdevicecontrol.controller.diagnostics.EventLog
import com.vrdevicecontrol.domain.MonotonicClock
import com.vrdevicecontrol.domain.Tuning
import com.vrdevicecontrol.protocol.CatalogPage
import com.vrdevicecontrol.protocol.ClockProbe
import com.vrdevicecontrol.protocol.ClockReply
import com.vrdevicecontrol.protocol.DecodeResult
import com.vrdevicecontrol.protocol.DeviceConfig
import com.vrdevicecontrol.protocol.DeviceCredential
import com.vrdevicecontrol.protocol.Endpoints
import com.vrdevicecontrol.protocol.Envelope
import com.vrdevicecontrol.protocol.ErrorCode
import com.vrdevicecontrol.protocol.ErrorInfo
import com.vrdevicecontrol.protocol.ErrorMessage
import com.vrdevicecontrol.protocol.Heartbeat
import com.vrdevicecontrol.protocol.Hello
import com.vrdevicecontrol.protocol.MAX_CONTROL_MESSAGE_BYTES
import com.vrdevicecontrol.protocol.MessageType
import com.vrdevicecontrol.protocol.PAIRING_NONCE_HEADER
import com.vrdevicecontrol.protocol.PROTOCOL_MAJOR
import com.vrdevicecontrol.protocol.PairingRequest
import com.vrdevicecontrol.protocol.PairingResponse
import com.vrdevicecontrol.protocol.PingResponse
import com.vrdevicecontrol.protocol.ProtocolCodec
import com.vrdevicecontrol.protocol.SERVICE_TYPE
import com.vrdevicecontrol.protocol.Welcome
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.EntityTagVersion
import io.ktor.server.http.content.LocalFileContent
import io.ktor.http.content.versions
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import java.net.Inet4Address
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

data class HostState(
    val running: Boolean = false,
    val port: Int? = null,
    val advertised: Boolean = false,
    val serviceName: String? = null,
    val error: String? = null,
)

/** Inbound message from a headset, with the transport receive time. */
data class InboundMessage(val deviceId: String, val connectionId: String, val envelope: Envelope, val receivedAtUs: Long)

/**
 * Embedded server lifecycle, NSD advertisement and authenticated connections. Owned by the
 * application, independent of activity recreation. The server binds all interfaces; reachability on
 * the venue network must still be verified (client isolation cannot be bypassed from here).
 */
class ControllerHost(
    private val context: Context,
    val controllerId: String,
    val controllerName: String,
    val controllerEpoch: String,
    val instanceId: String,
    private val registry: DeviceRegistry,
    private val catalog: CatalogRepository,
    private val clock: MonotonicClock,
    private val tuning: Tuning,
    private val log: EventLog,
    private val actor: CoroutineDispatcher,
    private val scope: CoroutineScope,
) {
    private val codec = ProtocolCodec.forController()
    private val nsd = context.getSystemService(NsdManager::class.java)
    private var server: EmbeddedServer<*, *>? = null
    private var registration: NsdManager.RegistrationListener? = null

    private val _state = MutableStateFlow(HostState())
    val state: StateFlow<HostState> = _state.asStateFlow()

    /** Consumed by the controller actor, in arrival order. */
    val inbound = Channel<InboundMessage>(capacity = 2_048)

    fun start() {
        if (server != null) return
        scope.launch(Dispatchers.IO) {
            val engine = runCatching { buildServer(PREFERRED_PORT).also { it.start(wait = false) } }
                .recoverCatching { buildServer(0).also { it.start(wait = false) } }
                .getOrElse {
                    Log.e(TAG, "Server failed to start", it)
                    _state.value = HostState(error = "Server failed to start: ${it.message}")
                    return@launch
                }
            server = engine
            val port = engine.engine.resolvedConnectors().first().port
            _state.value = HostState(running = true, port = port)
            log.log("SERVER_STARTED", detail = "port $port")
            acquireLocks()
            advertise(port)
            watchNetwork(port)
        }
    }

    // --- Network robustness ----------------------------------------------------------------------

    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var advertisedAddresses: Set<String> = emptySet()
    private var readvertiseJob: kotlinx.coroutines.Job? = null

    /**
     * Some Wi-Fi drivers drop multicast in power-save, so discovery queries from headsets never reach
     * the tablet. Hold a multicast lock and a low-latency Wi-Fi lock while the server runs.
     */
    private fun acquireLocks() {
        runCatching {
            multicastLock = wifi.createMulticastLock("vrdc-controller").apply { setReferenceCounted(false); acquire() }
            wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "vrdc-controller").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure { Log.w(TAG, "Could not acquire Wi-Fi locks", it) }
    }

    private fun ipv4(lp: LinkProperties?): Set<String> =
        lp?.linkAddresses.orEmpty().map { it.address }.filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }.toSet()

    /**
     * When the tablet joins another network or gets a new IP, re-announce the service so headsets
     * resolve the new address instead of a stale one. Headsets find the tablet; the tablet never
     * needs to know headset addresses in advance.
     */
    private fun watchNetwork(port: Int) {
        advertisedAddresses = ipv4(connectivity.getLinkProperties(connectivity.activeNetwork))
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                val now = ipv4(lp)
                if (now.isEmpty() || now == advertisedAddresses) return
                advertisedAddresses = now
                // Debounce: DHCP and Wi-Fi roaming often produce several changes in a row.
                readvertiseJob?.cancel()
                readvertiseJob = scope.launch {
                    delay(1_500)
                    log.log("NETWORK_CHANGED", detail = "now ${now.joinToString()}; re-announcing")
                    readvertise(port)
                }
            }
        }
        networkCallback = callback
        runCatching { connectivity.registerDefaultNetworkCallback(callback) }
    }

    private fun readvertise(port: Int) {
        registration?.let { runCatching { nsd.unregisterService(it) } }
        registration = null
        _state.value = _state.value.copy(advertised = false)
        advertise(port)
    }

    fun stop() {
        networkCallback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        networkCallback = null
        multicastLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        registration?.let { runCatching { nsd.unregisterService(it) } }
        registration = null
        server?.stop(500, 1_000)
        server = null
        _state.value = HostState()
    }

    private fun advertise(port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = controllerName
            serviceType = SERVICE_TYPE
            this.port = port
            // TXT records carry no secrets: controller ID, protocol major and display name only.
            setAttribute("cid", controllerId)
            setAttribute("pv", PROTOCOL_MAJOR.toString())
            setAttribute("name", controllerName)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                _state.value = _state.value.copy(advertised = true, serviceName = info.serviceName)
                log.log("NSD_REGISTERED", detail = info.serviceName)
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                _state.value = _state.value.copy(advertised = false, error = "Discovery advertisement failed ($errorCode)")
                log.log("NSD_FAILED", detail = "code $errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                _state.value = _state.value.copy(advertised = false)
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        registration = listener
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun buildServer(port: Int) = embeddedServer(CIO, port = port, host = "0.0.0.0") {
        install(WebSockets) {
            maxFrameSize = MAX_CONTROL_MESSAGE_BYTES.toLong()
            pingPeriod = null // Application heartbeats drive staleness.
            timeout = 15.seconds
        }
        install(PartialContent)
        install(AutoHeadResponse)

        routing {
            get(Endpoints.PING) {
                call.respondText(codec.json.encodeToString(PingResponse.serializer(), PingResponse(controllerId, controllerName)), ContentType.Application.Json)
            }

            post(Endpoints.PAIRING_REQUESTS) {
                val request = runCatching { codec.json.decodeFromString(PairingRequest.serializer(), call.receiveText().take(4_096)) }.getOrNull()
                if (request == null || request.protocolMajor != PROTOCOL_MAJOR) {
                    return@post call.respond(HttpStatusCode.BadRequest)
                }
                val remote = call.request.origin.remoteHost
                val pending = withContext(actor) { registry.createPairing(request, remote) }
                    ?: return@post call.respond(HttpStatusCode.Forbidden)
                val body = PairingResponse(pending.requestId, pending.status, controllerId, controllerName)
                call.respondText(codec.json.encodeToString(PairingResponse.serializer(), body), ContentType.Application.Json, HttpStatusCode.Created)
            }

            get("${Endpoints.PAIRING_REQUESTS}/{id}") {
                val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.NotFound)
                val nonce = call.request.headers[PAIRING_NONCE_HEADER] ?: return@get call.respond(HttpStatusCode.Unauthorized)
                val p = withContext(actor) { registry.pollPairing(id, nonce) } ?: return@get call.respond(HttpStatusCode.NotFound)
                val body = PairingResponse(p.requestId, p.status, controllerId, controllerName, credentialSecret = p.secret)
                call.respondText(codec.json.encodeToString(PairingResponse.serializer(), body), ContentType.Application.Json)
            }

            get(Endpoints.CATALOG) {
                authorizedDevice(call.request.headers[HttpHeaders.Authorization]) ?: return@get call.respond(HttpStatusCode.Unauthorized)
                val assets = catalog.entries.value.map { it.asset }
                call.respondText(codec.json.encodeToString(CatalogPage.serializer(), CatalogPage(0, 1, assets)), ContentType.Application.Json)
            }

            get("${Endpoints.ASSETS}/{sha}") {
                authorizedDevice(call.request.headers[HttpHeaders.Authorization]) ?: return@get call.respond(HttpStatusCode.Unauthorized)
                val sha = call.parameters["sha"].orEmpty()
                val file = catalog.fileFor(sha) ?: return@get call.respond(HttpStatusCode.NotFound)
                // Immutable content: the hash is a strong ETag, so If-Range resumes are safe.
                call.response.header(HttpHeaders.CacheControl, "private, max-age=31536000, immutable")
                val content = LocalFileContent(file, ContentType.Video.MP4)
                content.versions += EntityTagVersion(sha)
                call.respond(content)
            }

            webSocket(Endpoints.CONTROL) {
                val authenticated = authorizedDevice(call.request.headers[HttpHeaders.Authorization])
                if (authenticated == null) {
                    // Discovery or a stale credential never authorizes commands.
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unauthorized"))
                    return@webSocket
                }
                val deviceId: String = authenticated
                val connectionId = UUID.randomUUID().toString()

                // First message must be HELLO from the authenticated identity.
                val helloFrame = withTimeoutOrNull(6_000) { incoming.receive() } as? Frame.Text
                val helloEnv = helloFrame?.let { (codec.decode(it.readText()) as? DecodeResult.Ok)?.envelope }
                if (helloEnv == null || helloEnv.type != MessageType.HELLO || helloEnv.senderId != deviceId) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Expected HELLO"))
                    return@webSocket
                }
                val hello = codec.payloadOf<Hello>(helloEnv)
                if (hello.deviceId != deviceId) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Identity mismatch"))
                    return@webSocket
                }
                val session = this
                val outgoing = Channel<String>(capacity = 1_024)
                val link = object : DeviceLink {
                    override val deviceId = deviceId
                    override val connectionId = connectionId
                    override val processInstanceId = helloEnv.senderInstanceId

                    override fun send(type: String, payload: JsonObject, runId: String?, generation: Int?, messageId: String, correlationId: String?): Envelope? {
                        val env = Envelope(
                            type = type,
                            messageId = messageId,
                            senderId = controllerId,
                            senderInstanceId = instanceId,
                            connectionId = connectionId,
                            receiverInstanceId = processInstanceId,
                            controllerEpoch = controllerEpoch,
                            runId = runId,
                            generation = generation,
                            correlationId = correlationId,
                            payload = payload,
                        )
                        return if (outgoing.trySend(codec.encode(env)).isSuccess) env else null
                    }

                    override fun close(reason: String) {
                        outgoing.close()
                        scope.launch { runCatching { session.close(CloseReason(CloseReason.Codes.NORMAL, reason)) } }
                    }
                }
                val remoteAddress = call.request.origin.remoteHost
                val connected = withContext(actor) {
                    registry.onConnected(link, hello, remoteAddress)
                    registry.view(deviceId)
                }
                link.send(
                    MessageType.WELCOME,
                    codec.toPayload(
                        Welcome(
                            controllerId, controllerName, controllerEpoch, connectionId,
                            heartbeatIntervalMs = tuning.heartbeatIntervalMs,
                            staleAfterMs = tuning.staleAfterMs,
                            deviceLabel = connected?.label,
                        ),
                    ),
                )
                // Push operator prefs so a rebooted headset matches the tablet even if its local
                // default differs (e.g. freshly installed, or toggled while offline).
                link.send(
                    MessageType.DEVICE_CONFIG,
                    codec.toPayload(
                        DeviceConfig(
                            startOnBoot = connected?.record?.startOnBoot ?: true,
                            volume = connected?.record?.volume,
                        ),
                    ),
                )

                var lastInboundUs = clock.nowUs()
                val writer = launch { for (text in outgoing) send(Frame.Text(text)) }
                val heartbeat = launch {
                    while (isActive) {
                        delay(tuning.heartbeatIntervalMs)
                        link.send(MessageType.HEARTBEAT, codec.toPayload(Heartbeat(clock.nowUs())))
                        if (clock.nowUs() - lastInboundUs > tuning.staleAfterMs * 1_000) {
                            log.log("STALE", deviceId)
                            link.close("Stale connection")
                        }
                    }
                }
                try {
                    for (frame in incoming) {
                        val receivedAt = clock.nowUs() // c2: captured before decoding or any app work.
                        lastInboundUs = receivedAt
                        if (frame !is Frame.Text) continue
                        when (val decoded = codec.decode(frame.readText())) {
                            is DecodeResult.Rejected -> link.send(
                                MessageType.ERROR, codec.toPayload(ErrorMessage(decoded.error)), correlationId = decoded.messageId,
                            )
                            is DecodeResult.Ok -> {
                                val env = decoded.envelope
                                if (env.senderId != deviceId || env.connectionId != connectionId) {
                                    link.send(MessageType.ERROR, codec.toPayload(ErrorMessage(ErrorInfo(ErrorCode.STALE_CONNECTION))), correlationId = env.messageId)
                                    continue
                                }
                                if (env.type == MessageType.CLOCK_PROBE) {
                                    val probe = codec.payloadOf<ClockProbe>(env)
                                    val reply = codec.toPayload(ClockReply(probe.probeId, probe.h1Us, receivedAt, clock.nowUs()))
                                    link.send(MessageType.CLOCK_REPLY, reply, correlationId = env.messageId)
                                } else {
                                    inbound.trySend(InboundMessage(deviceId, connectionId, env, receivedAt))
                                }
                            }
                        }
                    }
                } finally {
                    heartbeat.cancel()
                    writer.cancel()
                    outgoing.close()
                    withContext(actor) { registry.onDisconnected(deviceId, connectionId) }
                }
            }
        }
    }

    private fun authorizedDevice(header: String?): String? {
        val (deviceId, secret) = DeviceCredential.parse(header) ?: return null
        return registry.authenticate(deviceId, secret)?.deviceId
    }

    private companion object {
        const val TAG = "ControllerHost"
        const val PREFERRED_PORT = 47800
    }
}
