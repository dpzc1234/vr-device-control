package com.vrdevicecontrol.simulator

import com.vrdevicecontrol.domain.ClockEstimate
import com.vrdevicecontrol.domain.ClockEstimator
import com.vrdevicecontrol.domain.ClockSample
import com.vrdevicecontrol.domain.CommandDeduplicator
import com.vrdevicecontrol.domain.CommandMeta
import com.vrdevicecontrol.domain.HeadsetSessionReducer
import com.vrdevicecontrol.domain.HeadsetSessionState
import com.vrdevicecontrol.domain.MonotonicClock
import com.vrdevicecontrol.domain.SessionEffect
import com.vrdevicecontrol.domain.SessionInput
import com.vrdevicecontrol.domain.Tuning
import com.vrdevicecontrol.protocol.AssetResult
import com.vrdevicecontrol.protocol.Capabilities
import com.vrdevicecontrol.protocol.ClockProbe
import com.vrdevicecontrol.protocol.ClockReply
import com.vrdevicecontrol.protocol.ClockStatus
import com.vrdevicecontrol.protocol.Completed
import com.vrdevicecontrol.protocol.Control
import com.vrdevicecontrol.protocol.ControlResult
import com.vrdevicecontrol.protocol.DecodeResult
import com.vrdevicecontrol.protocol.DeviceCredential
import com.vrdevicecontrol.protocol.EnsureAsset
import com.vrdevicecontrol.protocol.Endpoints
import com.vrdevicecontrol.protocol.Envelope
import com.vrdevicecontrol.protocol.ErrorCode
import com.vrdevicecontrol.protocol.ErrorInfo
import com.vrdevicecontrol.protocol.HeadsetStatus
import com.vrdevicecontrol.protocol.Heartbeat
import com.vrdevicecontrol.protocol.Hello
import com.vrdevicecontrol.protocol.Inventory
import com.vrdevicecontrol.protocol.InventoryItem
import com.vrdevicecontrol.protocol.MessageType
import com.vrdevicecontrol.protocol.NotReady
import com.vrdevicecontrol.protocol.PAIRING_NONCE_HEADER
import com.vrdevicecontrol.protocol.PairingRequest
import com.vrdevicecontrol.protocol.PairingResponse
import com.vrdevicecontrol.protocol.PairingStatus
import com.vrdevicecontrol.protocol.PlaybackState
import com.vrdevicecontrol.protocol.Prepare
import com.vrdevicecontrol.protocol.ProtocolCodec
import com.vrdevicecontrol.protocol.Ready
import com.vrdevicecontrol.protocol.RemoveAsset
import com.vrdevicecontrol.protocol.ResultBatch
import com.vrdevicecontrol.protocol.ResultsAck
import com.vrdevicecontrol.protocol.RunError
import com.vrdevicecontrol.protocol.RunRecord
import com.vrdevicecontrol.protocol.RunRecordKind
import com.vrdevicecontrol.protocol.StartAccepted
import com.vrdevicecontrol.protocol.StartAt
import com.vrdevicecontrol.protocol.Started
import com.vrdevicecontrol.protocol.TransferProgress
import com.vrdevicecontrol.protocol.TransferState
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Simulated headsets for exercising the tablet app without Quest hardware. Each simulated headset
 * runs the real [HeadsetSessionReducer] and protocol codec with a fake player and real HTTP
 * downloads, so pairing, transfers, preparation, scheduled start, controls, offline result upload
 * and reconnect behave as they would on a device. Timing results from the simulator are not
 * evidence of headset start alignment.
 *
 * ./gradlew :tools:headset-simulator:run --args="--host 127.0.0.1 --port 47800 --count 4 --offline 3"
 */
fun main(args: Array<String>) {
    val opts = args.toList().windowed(2, 2, partialWindows = true).associate { it[0].removePrefix("--") to it.getOrElse(1) { "" } }
    val host = opts["host"] ?: "127.0.0.1"
    val port = opts["port"]?.toInt() ?: 47800
    val count = opts["count"]?.toInt() ?: 4
    val stateDir = File(opts["state"] ?: "build/simulator").apply { mkdirs() }
    val offlineIndex = opts["offline"]?.toIntOrNull()
    // Relaxing the clock budget is only for exercising the UI over slow links (e.g. adb forward).
    val clockBudgetMs = opts["clock-budget-ms"]?.toLongOrNull()
    if (clockBudgetMs != null) println("WARNING: clock uncertainty budget relaxed to $clockBudgetMs ms — not a qualification setting.")
    println("Simulating $count headset(s) against $host:$port (state in ${stateDir.absolutePath})")
    if (offlineIndex != null) println("Headset #$offlineIndex will lose contact 4 s after starting playback, for 25 s.")
    runBlocking {
        val sims = (1..count).map { SimHeadset(it, host, port, stateDir, dropAfterStart = it == offlineIndex, clockBudgetMs = clockBudgetMs) }
        sims.forEach { it.start(this) }
    }
}

@Serializable
private data class SimIdentity(
    val deviceId: String,
    val name: String,
    val secret: String? = null,
    val inventory: List<InventoryItem> = emptyList(),
)

private object NanoClock : MonotonicClock {
    // Deliberately unrelated epoch to the tablet's elapsedRealtime, like real devices.
    private val base = System.nanoTime() - 3_600_000_000_000L
    override fun nowUs(): Long = (System.nanoTime() - base) / 1_000
}

private class SimHeadset(
    private val index: Int,
    private val host: String,
    private val port: Int,
    stateDir: File,
    private val dropAfterStart: Boolean,
    clockBudgetMs: Long?,
) {
    private val codec = ProtocolCodec.forHeadset()
    private val json = ProtocolCodec.DefaultJson
    private val http = OkHttpClient()
    private val clock = NanoClock
    private val tuning = clockBudgetMs?.let { Tuning(clockMaxUncertaintyUs = it * 1_000, clockMaxSampleRttUs = 4 * it * 1_000) } ?: Tuning()
    private val reducer = HeadsetSessionReducer(tuning)
    private val estimator = ClockEstimator(tuning)
    private val dedup = CommandDeduplicator<List<Triple<String, JsonObject, Pair<String?, Int?>>>>()
    private val idFile = File(stateDir, "headset-$index.json")
    private val mediaDir = File(stateDir, "media-$index").apply { mkdirs() }
    private val processInstanceId = UUID.randomUUID().toString()
    private val base = "http://$host:$port"

    /** All session work is serialized on one thread, like the headset's main looper. */
    private val session = CoroutineScope(SupervisorJob() + Executors.newSingleThreadExecutor().asCoroutineDispatcher())

    private var identity: SimIdentity = runCatching { json.decodeFromString(SimIdentity.serializer(), idFile.readText()) }
        .getOrElse { SimIdentity(UUID.randomUUID().toString(), "Sim Quest $index") }
        .also { save(it) }

    private var state = HeadsetSessionState(appActive = true)
    private var ws: WebSocket? = null
    private var connectionId: String? = null
    private var epoch: String? = null
    private var playbackJob: Job? = null
    private var playStartedUs = 0L
    private var pausedAtPositionUs = 0L
    private var armJob: Job? = null
    private val estimates = ConcurrentHashMap<String, ClockEstimate>()
    @Volatile private var latestEstimate: ClockEstimate? = null
    private val pendingProbes = ConcurrentHashMap<String, Long>()
    private val samples = java.util.Collections.synchronizedList(mutableListOf<ClockSample>())
    private val outbox = mutableListOf<RunRecord>()
    private var sequence = 0L
    @Volatile private var forcedOfflineUntilMs = 0L

    private fun log(msg: String) = println("[Sim Quest $index] $msg")

    private fun save(id: SimIdentity) {
        identity = id
        idFile.writeText(json.encodeToString(SimIdentity.serializer(), id))
    }

    fun start(scope: CoroutineScope) {
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            while (isActive) {
                if (identity.secret == null) pair() else connectOnce()
                delay(1_500 + (index * 150L))
            }
        }
    }

    // --- Pairing -------------------------------------------------------------------------------

    private suspend fun pair() {
        val code = (100_000..999_999).random().toString()
        val nonce = UUID.randomUUID().toString()
        val body = json.encodeToString(PairingRequest.serializer(), PairingRequest(identity.deviceId, identity.name, code, nonce))
        val created = runCatching {
            http.newCall(Request.Builder().url(base + Endpoints.PAIRING_REQUESTS).post(body.toRequestBody("application/json".toMediaType())).build())
                .execute().use { r ->
                    if (r.code == 403) error("pairing mode is off on the tablet")
                    check(r.isSuccessful) { "HTTP ${r.code}" }
                    json.decodeFromString(PairingResponse.serializer(), r.body!!.string())
                }
        }.getOrElse { log("Pairing request failed: ${it.message}"); delay(3_000); return }
        log("Pairing requested — approve code ${code.chunked(3).joinToString(" ")} on the tablet")
        repeat(120) {
            delay(1_500)
            val status = runCatching {
                http.newCall(Request.Builder().url(base + Endpoints.pairingRequest(created.requestId)).header(PAIRING_NONCE_HEADER, nonce).build())
                    .execute().use { r -> json.decodeFromString(PairingResponse.serializer(), r.body!!.string()) }
            }.getOrNull() ?: return@repeat
            when (status.status) {
                PairingStatus.APPROVED -> {
                    save(identity.copy(secret = status.credentialSecret))
                    log("Paired with ${status.controllerName}")
                    return
                }
                PairingStatus.PENDING -> Unit
                else -> { log("Pairing ${status.status}"); return }
            }
        }
    }

    // --- Connection ----------------------------------------------------------------------------

    private suspend fun connectOnce() {
        if (System.currentTimeMillis() < forcedOfflineUntilMs) return
        val closed = Channel<Int>(Channel.CONFLATED)
        val welcomed = Channel<Unit>(Channel.CONFLATED)
        val socket = http.newWebSocket(
            Request.Builder().url("ws://$host:$port${Endpoints.CONTROL}").header("Authorization", DeviceCredential.header(identity.deviceId, identity.secret!!)).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    sendRaw(webSocket, MessageType.HELLO, codec.toPayload(hello()))
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val receivedAt = clock.nowUs()
                    if (System.currentTimeMillis() < forcedOfflineUntilMs) return
                    when (val d = codec.decode(text)) {
                        is DecodeResult.Rejected -> log("Rejected inbound: ${d.error}")
                        is DecodeResult.Ok -> when (d.envelope.type) {
                            MessageType.WELCOME -> {
                                val w = codec.payloadOf<com.vrdevicecontrol.protocol.Welcome>(d.envelope)
                                connectionId = w.connectionId
                                epoch = w.controllerEpoch
                                ws = webSocket
                                welcomed.trySend(Unit)
                            }
                            MessageType.CLOCK_REPLY -> onClockReply(codec.payloadOf(d.envelope), receivedAt)
                            MessageType.HEARTBEAT -> Unit
                            else -> session.launch { handle(d.envelope) }
                        }
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.trySend(code) }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null); closed.trySend(code) }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { closed.trySend(response?.code ?: -1) }
            },
        )
        if (withTimeoutOrNull(6_000) { welcomed.receive() } == null) {
            socket.cancel()
            val code = withTimeoutOrNull(100) { closed.receive() }
            if (code == 1008 || code == 401) { log("Credential refused — pairing again"); save(identity.copy(secret = null)) }
            return
        }
        log("Connected")
        val loops = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        loops.launch { session.launch { onConnected() } }
        loops.launch { while (isActive) { delay(2_000); send(MessageType.HEARTBEAT, codec.toPayload(Heartbeat(clock.nowUs()))); session.launch { sendStatus() } } }
        loops.launch {
            while (isActive) {
                if (state.playback !in HeadsetSessionReducer.ACTIVE_RUN_STATES) clockBurst()
                delay(4_000)
            }
        }
        loops.launch {
            while (isActive) {
                delay(250)
                if (System.currentTimeMillis() < forcedOfflineUntilMs) socket.cancel()
            }
        }
        closed.receive()
        loops.coroutineContext[Job]?.cancel()
        ws = null
        connectionId = null
        log("Disconnected")
    }

    private fun hello() = Hello(
        deviceId = identity.deviceId,
        deviceName = identity.name,
        capabilities = Capabilities("Simulated Quest 3", "Simulator", "sim-0.1", listOf("video/hevc", "video/avc"), 8192, 8192),
    )

    private fun sendRaw(socket: WebSocket, type: String, payload: JsonObject, correlationId: String? = null, runId: String? = null, generation: Int? = null): Boolean {
        val env = Envelope(
            type = type, messageId = UUID.randomUUID().toString(), senderId = identity.deviceId, senderInstanceId = processInstanceId,
            connectionId = connectionId, controllerEpoch = epoch, runId = runId, generation = generation, correlationId = correlationId, payload = payload,
        )
        return socket.send(codec.encode(env))
    }

    private fun send(type: String, payload: JsonObject, correlationId: String? = null, runId: String? = null, generation: Int? = null): Boolean =
        ws?.let { sendRaw(it, type, payload, correlationId, runId, generation) } ?: false

    private inline fun <reified T> sendValue(type: String, value: T, correlationId: String? = null, runId: String? = null, generation: Int? = null) =
        send(type, codec.toPayload(value, serializer<T>()), correlationId, runId, generation)

    private fun onConnected() {
        sendStatus()
        sendValue(MessageType.INVENTORY, Inventory(0, 1, identity.inventory))
        if (outbox.isNotEmpty()) sendValue(MessageType.RESULT_BATCH, ResultBatch(outbox.toList()))
    }

    // --- Clock -----------------------------------------------------------------------------------

    private suspend fun clockBurst() {
        samples.clear()
        repeat(tuning.clockProbeCount) {
            val id = UUID.randomUUID().toString()
            val h1 = clock.nowUs()
            pendingProbes[id] = h1
            sendValue(MessageType.CLOCK_PROBE, ClockProbe(id, h1))
            delay(tuning.clockProbeSpacingMs)
        }
        delay(300)
        val e = estimator.estimate(samples.toList(), UUID.randomUUID().toString(), clock.nowUs())
        if (e == null) {
            log("Clock burst unusable: ${samples.size} replies, best RTT ${samples.minOfOrNull { it.rttUs }?.div(1000) ?: "-"} ms")
            return
        }
        estimates[e.id] = e
        latestEstimate = e
        val v = estimator.verdict(e, clock.nowUs())
        sendValue(MessageType.CLOCK_STATUS, ClockStatus(e.id, e.offsetUs, e.baseUncertaintyUs, e.rttUs, e.sampleCount, 0, v.acceptable, v.reason))
        session.launch { dispatch(SessionInput.ClockChanged(e.id, v.acceptable)) }
    }

    private fun onClockReply(reply: ClockReply, h4: Long) {
        val h1 = pendingProbes.remove(reply.probeId) ?: return
        samples += ClockSample(reply.probeId, h1, reply.c2Us, reply.c3Us, h4)
    }

    // --- Commands ------------------------------------------------------------------------------

    private suspend fun handle(env: Envelope) {
        when (env.type) {
            in MessageType.SESSION_COMMANDS -> handleSession(env)
            MessageType.ENSURE_ASSET -> download(codec.payloadOf(env))
            MessageType.REMOVE_ASSET -> {
                val r = codec.payloadOf<RemoveAsset>(env)
                val inUse = state.run?.asset?.sha256 == r.sha256 && state.playback != PlaybackState.STANDBY
                if (!inUse) {
                    save(identity.copy(inventory = identity.inventory.filterNot { it.sha256 == r.sha256 }))
                    File(mediaDir, r.sha256).delete()
                }
                sendValue(MessageType.ASSET_RESULT, AssetResult(r.operationId, r.sha256, !inUse, if (inUse) ErrorInfo(ErrorCode.ASSET_IN_USE) else null))
                sendValue(MessageType.INVENTORY, Inventory(0, 1, identity.inventory))
            }
            MessageType.RESULTS_ACK -> {
                val ids = codec.payloadOf<ResultsAck>(env).recordIds.toSet()
                outbox.removeAll { it.recordId in ids }
            }
            MessageType.DEVICE_CONFIG -> Unit // No-op: simulators are not real devices.
            else -> Unit
        }
    }

    private fun handleSession(env: Envelope) {
        if (env.receiverInstanceId != processInstanceId || env.connectionId != connectionId || env.controllerEpoch != epoch) {
            sendValue(MessageType.CONTROL_RESULT, ControlResult(env.type, false, state.playback, ErrorInfo(ErrorCode.STALE_CONNECTION)), env.messageId, env.runId, env.generation)
            return
        }
        val fp = codec.fingerprint(env)
        when (val l = dedup.lookup(env.messageId, fp)) {
            is CommandDeduplicator.Lookup.Duplicate -> { l.result.forEach { (t, p, rg) -> send(t, p, env.messageId, rg.first, rg.second) }; return }
            CommandDeduplicator.Lookup.Conflict -> {
                sendValue(MessageType.CONTROL_RESULT, ControlResult(env.type, false, state.playback, ErrorInfo(ErrorCode.DUPLICATE_CONFLICT)), env.messageId)
                return
            }
            CommandDeduplicator.Lookup.New -> Unit
        }
        val meta = CommandMeta(env.messageId, env.runId!!, env.generation!!, env.controllerEpoch!!)
        val nowController = latestEstimate?.toController(clock.nowUs())
        val input = when (env.type) {
            MessageType.PREPARE -> {
                val p = codec.payloadOf<Prepare>(env)
                val missing = identity.inventory.none { it.sha256 == p.asset.sha256 }
                SessionInput.PrepareCommand(meta, p, nowController, if (missing) ErrorInfo(ErrorCode.ASSET_MISSING, "Not on simulated headset") else null, UUID.randomUUID().toString())
            }
            MessageType.START_AT -> {
                val s = codec.payloadOf<StartAt>(env)
                val e = estimates[s.clockSampleId]
                SessionInput.StartAtCommand(meta, s, clock.nowUs(), e, estimator.verdict(e, clock.nowUs()))
            }
            else -> SessionInput.ControlCommand(meta, env.type, codec.payloadOf<Control>(env), nowController)
        }
        capture = mutableListOf()
        dispatch(input)
        dedup.record(env.messageId, fp, capture!!.toList())
        capture = null
    }

    private var capture: MutableList<Triple<String, JsonObject, Pair<String?, Int?>>>? = null

    private fun dispatch(input: SessionInput) {
        val t = reducer.reduce(state, input)
        if (t.state.playback != state.playback) log("${state.playback} -> ${t.state.playback}")
        state = t.state
        t.effects.forEach(::execute)
    }

    private fun reply(type: String, payload: JsonObject, correlationId: String?) {
        val run = state.run
        if (correlationId != null) capture?.add(Triple(type, payload, run?.runId to run?.generation))
        send(type, payload, correlationId, run?.runId, run?.generation)
    }

    private fun execute(effect: SessionEffect) {
        when (effect) {
            is SessionEffect.PreparePlayer -> session.launch {
                delay(700L + index * 250L) // Decoder warm-up and first frame behind standby.
                dispatch(SessionInput.PlayerPrepared(effect.token))
            }
            SessionEffect.ReleasePlayer -> { playbackJob?.cancel(); playbackJob = null }
            is SessionEffect.ArmStart -> {
                armJob?.cancel()
                armJob = session.launch {
                    val wait = (effect.localDeadlineUs - clock.nowUs()) / 1_000
                    if (wait > 0) delay(wait)
                    while (clock.nowUs() < effect.localDeadlineUs) Unit
                    dispatch(SessionInput.DeadlineReached(effect.armId, clock.nowUs()))
                }
            }
            is SessionEffect.DisarmStart -> armJob?.cancel()
            SessionEffect.RevealAndPlayFromZero -> {
                playStartedUs = clock.nowUs()
                pausedAtPositionUs = 0
                startPlaybackTimer(state.run!!.asset.durationUs)
                if (dropAfterStart) session.launch {
                    delay(4_000)
                    log("Simulating loss of Wi-Fi coverage for 25 s (playback continues)")
                    forcedOfflineUntilMs = System.currentTimeMillis() + 25_000
                }
            }
            SessionEffect.PausePlayer -> {
                pausedAtPositionUs = positionUs()
                playbackJob?.cancel()
            }
            SessionEffect.ResumePlayer -> {
                playStartedUs = clock.nowUs() - pausedAtPositionUs
                startPlaybackTimer(state.run!!.asset.durationUs - pausedAtPositionUs)
            }
            SessionEffect.ShowStandby -> Unit
            is SessionEffect.SendReady -> state.run?.let {
                reply(MessageType.READY, codec.toPayload(Ready(effect.token, it.asset.sha256, it.presentation.revision, effect.clockEstimateId, clock.nowUs())), null)
            }
            is SessionEffect.SendNotReady -> reply(MessageType.NOT_READY, codec.toPayload(NotReady(effect.error)), null)
            is SessionEffect.SendStartAccepted -> reply(MessageType.START_ACCEPTED, codec.toPayload(StartAccepted(effect.startAtControllerUs, effect.localDeadlineUs, effect.clockEstimateId)), effect.correlationId)
            is SessionEffect.SendControlResult -> reply(MessageType.CONTROL_RESULT, codec.toPayload(ControlResult(effect.commandType, effect.accepted, state.playback, effect.error)), effect.correlationId)
            is SessionEffect.Record -> {
                val r = RunRecord(
                    UUID.randomUUID().toString(), effect.run.runId, effect.run.generation, effect.run.controllerEpoch, processInstanceId,
                    sequence++, effect.kind, Instant.now().toString(), clock.nowUs(), positionUs(), effect.detail, effect.error,
                )
                outbox += r
                val rg = effect.run.runId to effect.run.generation
                when (effect.kind) {
                    RunRecordKind.STARTED -> send(MessageType.STARTED, codec.toPayload(Started(effect.deadlineLocalUs ?: 0, effect.callbackLocalUs ?: 0, (effect.callbackLocalUs ?: 0) - (effect.deadlineLocalUs ?: 0))), null, rg.first, rg.second)
                    RunRecordKind.COMPLETED -> send(MessageType.COMPLETED, codec.toPayload(Completed(effect.durationUs ?: 0)), null, rg.first, rg.second)
                    RunRecordKind.ERROR -> send(MessageType.RUN_ERROR, codec.toPayload(RunError(effect.error ?: ErrorInfo(ErrorCode.PLAYBACK_FAILED))), null, rg.first, rg.second)
                    else -> Unit
                }
                sendValue(MessageType.RESULT_BATCH, ResultBatch(listOf(r)))
                log("Recorded ${effect.kind}${effect.error?.let { " ${it.code}" } ?: ""}")
            }
        }
        sendStatus()
    }

    private fun startPlaybackTimer(remainingUs: Long) {
        playbackJob?.cancel()
        playbackJob = session.launch {
            delay((remainingUs / 1_000).coerceAtLeast(1))
            dispatch(SessionInput.PlaybackCompleted(state.run?.asset?.durationUs ?: 0))
        }
    }

    private fun positionUs(): Long = when (state.playback) {
        PlaybackState.PLAYING -> clock.nowUs() - playStartedUs
        PlaybackState.PAUSED -> pausedAtPositionUs
        else -> 0
    }

    private fun sendStatus() {
        val run = state.run
        sendValue(
            MessageType.STATUS,
            HeadsetStatus(
                playbackState = state.playback, runId = run?.runId, generation = run?.generation, assetSha256 = run?.asset?.sha256,
                positionUs = positionUs(), durationUs = run?.asset?.durationUs ?: 0, capturedAtUs = clock.nowUs(),
                appActive = true, worn = true, batteryPercent = 92 - index * 9, charging = index == 1,
                freeBytes = 96L * 1024 * 1024 * 1024 - index * 7L * 1024 * 1024 * 1024, totalBytes = 128L * 1024 * 1024 * 1024,
                transfersDeferred = state.playback in HeadsetSessionReducer.ACTIVE_RUN_STATES, lastError = state.lastError,
            ),
        )
    }

    // --- Downloads -----------------------------------------------------------------------------

    private fun download(request: EnsureAsset) {
        val asset = request.asset
        if (identity.inventory.any { it.sha256 == asset.sha256 }) {
            sendValue(MessageType.TRANSFER_PROGRESS, TransferProgress(request.operationId, asset.sha256, TransferState.AVAILABLE, asset.sizeBytes, asset.sizeBytes))
            return
        }
        CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val file = File(mediaDir, asset.sha256)
            val digest = MessageDigest.getInstance("SHA-256")
            var committed = 0L
            runCatching {
                http.newCall(
                    Request.Builder().url(base + Endpoints.asset(asset.sha256)).header("Authorization", DeviceCredential.header(identity.deviceId, identity.secret!!)).build(),
                ).execute().use { r ->
                    check(r.isSuccessful) { "HTTP ${r.code}" }
                    file.outputStream().use { out ->
                        val input = r.body!!.byteStream()
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            committed += n
                            // Throttle so progress is visible on the tablet.
                            if (committed % (512 * 1024) < 64 * 1024) {
                                sendValue(MessageType.TRANSFER_PROGRESS, TransferProgress(request.operationId, asset.sha256, TransferState.DOWNLOADING, committed, asset.sizeBytes))
                                delay(60L * index)
                            }
                        }
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                check(actual == asset.sha256) { "hash mismatch" }
            }.onSuccess {
                session.launch {
                    save(identity.copy(inventory = identity.inventory + InventoryItem(asset.sha256, asset.sizeBytes, Instant.now().toString())))
                    sendValue(MessageType.TRANSFER_PROGRESS, TransferProgress(request.operationId, asset.sha256, TransferState.AVAILABLE, asset.sizeBytes, asset.sizeBytes))
                    sendValue(MessageType.INVENTORY, Inventory(0, 1, identity.inventory))
                    log("Verified ${asset.title}")
                }
            }.onFailure {
                file.delete()
                sendValue(MessageType.TRANSFER_PROGRESS, TransferProgress(request.operationId, asset.sha256, TransferState.FAILED, committed, asset.sizeBytes, ErrorInfo(ErrorCode.TRANSFER_FAILED, it.message)))
            }
        }
    }
}
