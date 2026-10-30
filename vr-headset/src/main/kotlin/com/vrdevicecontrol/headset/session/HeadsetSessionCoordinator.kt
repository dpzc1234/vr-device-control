package com.vrdevicecontrol.headset.session

import android.content.Context
import android.media.MediaCodecList
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.vrdevicecontrol.domain.CommandDeduplicator
import com.vrdevicecontrol.domain.CommandMeta
import com.vrdevicecontrol.domain.HeadsetSessionReducer
import com.vrdevicecontrol.domain.HeadsetSessionState
import com.vrdevicecontrol.domain.MonotonicClock
import com.vrdevicecontrol.domain.SessionEffect
import com.vrdevicecontrol.domain.SessionInput
import com.vrdevicecontrol.domain.Tuning
import com.vrdevicecontrol.headset.boot.StartOnBoot
import com.vrdevicecontrol.headset.content.LocalContentRepository
import com.vrdevicecontrol.headset.identity.HeadsetIdentity
import com.vrdevicecontrol.headset.net.ControllerConnection
import com.vrdevicecontrol.headset.net.Inbound
import com.vrdevicecontrol.headset.net.LinkState
import com.vrdevicecontrol.headset.platform.readPowerAndStorage
import com.vrdevicecontrol.headset.playback.PlaybackEngine
import com.vrdevicecontrol.headset.playback.PlaybackEvent
import com.vrdevicecontrol.headset.scene.ImmersiveScene
import com.vrdevicecontrol.protocol.AssetDescriptor
import com.vrdevicecontrol.protocol.Completed
import com.vrdevicecontrol.protocol.Control
import com.vrdevicecontrol.protocol.ControlResult
import com.vrdevicecontrol.protocol.DeviceConfig
import com.vrdevicecontrol.protocol.EnsureAsset
import com.vrdevicecontrol.protocol.Envelope
import com.vrdevicecontrol.protocol.ErrorCode
import com.vrdevicecontrol.protocol.ErrorInfo
import com.vrdevicecontrol.protocol.ErrorMessage
import com.vrdevicecontrol.protocol.HeadsetStatus
import com.vrdevicecontrol.protocol.Inventory
import com.vrdevicecontrol.protocol.MessageType
import com.vrdevicecontrol.protocol.NotReady
import com.vrdevicecontrol.protocol.PlaybackState
import com.vrdevicecontrol.protocol.Prepare
import com.vrdevicecontrol.protocol.Ready
import com.vrdevicecontrol.protocol.Recenter
import com.vrdevicecontrol.protocol.RecenterResult
import com.vrdevicecontrol.protocol.RemoveAsset
import com.vrdevicecontrol.protocol.ResultBatch
import com.vrdevicecontrol.protocol.ResultsAck
import com.vrdevicecontrol.protocol.RunError
import com.vrdevicecontrol.protocol.RunRecordKind
import com.vrdevicecontrol.protocol.StartAccepted
import com.vrdevicecontrol.protocol.StartAt
import com.vrdevicecontrol.protocol.Started
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/** A reply sent for a command, cached so a duplicate gets the identical answer. */
private data class CachedReply(val type: String, val payload: JsonObject, val runId: String?, val generation: Int?)

/**
 * Serialized command/state transitions for the headset. Every method runs on the main looper, which
 * is also the player's application looper. Network callbacks only enqueue; they never touch the
 * player. The playback scope is independent of the networking scope, so reconnect failures cannot
 * propagate into playback.
 */
class HeadsetSessionCoordinator(
    private val context: Context,
    private val identity: HeadsetIdentity,
    private val connection: ControllerConnection,
    private val content: LocalContentRepository,
    private val journal: RunJournal,
    private val clock: MonotonicClock,
    private val tuning: Tuning,
    /** Main-dispatcher scope for session work. */
    private val sessionScope: CoroutineScope,
    /** IO scope for clock bursts (owned by networking). */
    private val networkScope: CoroutineScope,
) {
    private val reducer = HeadsetSessionReducer(tuning)
    private val dedup = CommandDeduplicator<List<CachedReply>>(capacity = 256)
    private val codec = connection.codec
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scheduler = StartScheduler(mainHandler, clock)

    private val _state = MutableStateFlow(HeadsetSessionState())
    val state: StateFlow<HeadsetSessionState> = _state.asStateFlow()

    private var engine: PlaybackEngine? = null
    private var pendingVolume: Float? = null
    private var scene: ImmersiveScene? = null
    private var pinnedSha: String? = null

    // Lifecycle inputs combined into a single "active" flag.
    private var sessionPresenting = false
    private var vrReady = true // Until the SDK reports onVRPause.
    private var mounted = true
    var worn: Boolean = true
        private set

    /** Replies produced while executing the current command, for the dedup cache. */
    private var capturing: MutableList<CachedReply>? = null
    private var lastClockAcceptable: Boolean? = null

    fun start() {
        journal.markUnfinishedInterrupted() // Never replay a saved start after relaunch.
        sessionScope.launch { for (msg in connection.inbound) handle(msg) }
        sessionScope.launch { connection.connected.collect { onConnected() } }
        sessionScope.launch {
            _state.map { it.playback to it.appActive }.distinctUntilChanged().collect { (playback, _) ->
                content.deferred.value = playback in HeadsetSessionReducer.ACTIVE_RUN_STATES
                sendStatus()
            }
        }
        sessionScope.launch {
            var tick = 0
            while (isActive) {
                delay(1_000)
                tick++
                reevaluateClock()
                if (tick % 5 == 0) sendStatus()
            }
        }
        networkScope.launch {
            while (isActive) {
                delay(4_000)
                val playback = _state.value.playback
                if (connection.state.value is LinkState.Connected && playback !in HeadsetSessionReducer.ACTIVE_RUN_STATES) {
                    refreshClock()
                }
            }
        }
        content.onProgress = { p -> connection.send(MessageType.TRANSFER_PROGRESS, p) }
        sessionScope.launch { content.inventory.collect { sendInventory() } }
    }

    // --- Player and lifecycle attachment (main thread) -------------------------------------------

    fun attachPlayer(engine: PlaybackEngine, scene: ImmersiveScene) {
        this.engine = engine
        this.scene = scene
        engine.listener = { event -> mainHandler.post { onPlaybackEvent(event) } }
        pendingVolume?.let(engine::setVolume)
        updateActive()
    }

    fun detachPlayer() {
        updateActive(force = false)
        engine?.release()
        engine = null
        scene = null
    }

    fun onSessionPresenting(presenting: Boolean) { sessionPresenting = presenting; updateActive() }
    fun onVrReady(ready: Boolean) { vrReady = ready; updateActive() }
    fun onMounted(isMounted: Boolean) { mounted = isMounted; worn = isMounted; updateActive() }

    fun acknowledgeError() = dispatch(SessionInput.Reset)

    private fun updateActive(force: Boolean? = null) {
        val active = force ?: (sessionPresenting && vrReady && mounted && engine != null)
        if (active != _state.value.appActive) dispatch(SessionInput.LifecycleChanged(active))
    }

    private fun onPlaybackEvent(event: PlaybackEvent) = when (event) {
        is PlaybackEvent.Prepared -> dispatch(SessionInput.PlayerPrepared(event.token))
        is PlaybackEvent.PrepareFailed -> dispatch(SessionInput.PlayerPrepareFailed(event.token, event.error))
        is PlaybackEvent.Completed -> dispatch(SessionInput.PlaybackCompleted(event.durationUs))
        is PlaybackEvent.Failed -> dispatch(SessionInput.PlaybackFailed(event.error))
    }

    // --- Inbound commands ------------------------------------------------------------------------

    private fun handle(inbound: Inbound) {
        val env = inbound.envelope
        try {
            when (env.type) {
                in MessageType.SESSION_COMMANDS -> handleSession(env)
                MessageType.ENSURE_ASSET -> content.ensure(codec.payloadOf<EnsureAsset>(env)) { connection.currentTarget() }
                MessageType.REMOVE_ASSET -> {
                    val result = content.remove(codec.payloadOf<RemoveAsset>(env))
                    connection.send(MessageType.ASSET_RESULT, result, correlationId = env.messageId)
                }
                MessageType.RESULTS_ACK -> journal.acknowledge(codec.payloadOf<ResultsAck>(env).recordIds)
                MessageType.DEVICE_CONFIG -> {
                    val config = codec.payloadOf<DeviceConfig>(env)
                    StartOnBoot.setEnabled(context, config.startOnBoot)
                    Log.i(TAG, "Start on boot ${if (config.startOnBoot) "enabled" else "disabled"}")
                    config.volume?.let { level ->
                        val clamped = level.coerceIn(0f, 1f)
                        pendingVolume = clamped
                        mainHandler.post { engine?.setVolume(clamped) }
                        Log.i(TAG, "Volume set to ${(clamped * 100).toInt()}%")
                    }
                    sendStatus()
                }
                MessageType.RECENTER -> handleRecenter(env)
                MessageType.ERROR -> Log.w(TAG, "Controller reported ${codec.payloadOf<ErrorMessage>(env)}")
                else -> Unit
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle ${env.type}", e)
            connection.send(MessageType.ERROR, ErrorMessage(ErrorInfo(ErrorCode.MALFORMED, e.message), env.type), correlationId = env.messageId)
        }
    }

    private fun handleRecenter(env: Envelope) {
        // Decode even though the command currently has no fields, so a malformed payload is rejected.
        codec.payloadOf<Recenter>(env)
        val welcome = connection.welcome
        if (env.receiverInstanceId != identity.processInstanceId || welcome == null ||
            env.connectionId != welcome.connectionId || env.senderId != welcome.controllerId ||
            env.controllerEpoch != welcome.controllerEpoch
        ) {
            sendRecenterResult(env, RecenterResult(false, error = ErrorInfo(ErrorCode.STALE_CONNECTION, "Request addressed to a stale headset connection")))
            return
        }

        val fingerprint = codec.fingerprint(env)
        when (val lookup = dedup.lookup(env.messageId, fingerprint)) {
            is CommandDeduplicator.Lookup.Duplicate -> {
                lookup.result.forEach { connection.send(it.type, it.payload, env.messageId, it.runId, it.generation) }
                return
            }
            CommandDeduplicator.Lookup.Conflict -> {
                sendRecenterResult(env, RecenterResult(false, error = ErrorInfo(ErrorCode.DUPLICATE_CONFLICT, "Message ID reused with different content")))
                return
            }
            CommandDeduplicator.Lookup.New -> Unit
        }

        val s = _state.value
        val result = when {
            scene == null || !s.appActive || !worn -> RecenterResult(
                false,
                error = ErrorInfo(ErrorCode.NOT_ACTIVE, "The headset must be worn with the player visible"),
            )
            s.playback in setOf(PlaybackState.ARMED, PlaybackState.PLAYING, PlaybackState.PAUSED) -> RecenterResult(
                false,
                error = ErrorInfo(ErrorCode.INVALID_STATE, "Recenter is available only between runs"),
            )
            else -> runCatching {
                scene!!.recenterToCurrentPose()
                RecenterResult(true, centeredAtUs = clock.nowUs())
            }.getOrElse { RecenterResult(false, error = ErrorInfo(ErrorCode.NOT_ACTIVE, it.message ?: "Scene recenter failed")) }
        }
        val payload = codec.toPayload(result)
        dedup.record(env.messageId, fingerprint, listOf(CachedReply(MessageType.RECENTER_RESULT, payload, null, null)))
        connection.send(MessageType.RECENTER_RESULT, payload, correlationId = env.messageId)
        if (result.accepted) Log.i(TAG, "Scene recentered by operator request")
    }

    private fun sendRecenterResult(env: Envelope, result: RecenterResult) {
        connection.send(MessageType.RECENTER_RESULT, result, correlationId = env.messageId)
    }

    private fun handleSession(env: Envelope) {
        val welcome = connection.welcome
        // Identity, process, connection and epoch are validated before any cached result lookup.
        if (env.receiverInstanceId != identity.processInstanceId) {
            return reject(env, ErrorCode.STALE_CONNECTION, "Command addressed to a previous headset process")
        }
        if (welcome == null || env.connectionId != welcome.connectionId || env.controllerEpoch != welcome.controllerEpoch) {
            return reject(env, ErrorCode.STALE_CONNECTION, "Command from a previous connection or controller epoch")
        }
        val fingerprint = codec.fingerprint(env)
        when (val lookup = dedup.lookup(env.messageId, fingerprint)) {
            is CommandDeduplicator.Lookup.Duplicate -> {
                // Same command again: identical answer, no re-arm, no extended expiry.
                lookup.result.forEach { connection.send(it.type, it.payload, env.messageId, it.runId, it.generation) }
                return
            }
            CommandDeduplicator.Lookup.Conflict -> return reject(env, ErrorCode.DUPLICATE_CONFLICT, "Message ID reused with different content")
            CommandDeduplicator.Lookup.New -> Unit
        }

        val meta = CommandMeta(env.messageId, env.runId!!, env.generation!!, env.controllerEpoch!!)
        val input = when (env.type) {
            MessageType.PREPARE -> {
                val prepare = codec.payloadOf<Prepare>(env)
                if (connection.clockSync.latest.value?.let { clock.nowUs() - it.createdAtLocalUs > 3_000_000 } != false) {
                    networkScope.launch { refreshClock() }
                }
                SessionInput.PrepareCommand(
                    meta = meta,
                    prepare = prepare,
                    nowControllerUs = connection.clockSync.nowControllerUs(),
                    assetIssue = assetIssue(prepare),
                    newToken = UUID.randomUUID().toString(),
                )
            }
            MessageType.START_AT -> {
                val start = codec.payloadOf<StartAt>(env)
                val estimate = connection.clockSync.estimate(start.clockSampleId)
                SessionInput.StartAtCommand(meta, start, clock.nowUs(), estimate, connection.clockSync.verdict(estimate))
            }
            else -> SessionInput.ControlCommand(meta, env.type, codec.payloadOf<Control>(env), connection.clockSync.nowControllerUs())
        }
        val replies = mutableListOf<CachedReply>()
        capturing = replies
        try {
            dispatch(input)
        } finally {
            capturing = null
        }
        dedup.record(env.messageId, fingerprint, replies)
    }

    private fun reject(env: Envelope, code: ErrorCode, detail: String) {
        Log.w(TAG, "Rejecting ${env.type} ${env.messageId}: $code $detail")
        connection.send(
            MessageType.CONTROL_RESULT,
            ControlResult(env.type, false, _state.value.playback, ErrorInfo(code, detail)),
            correlationId = env.messageId,
            runId = env.runId,
            generation = env.generation,
        )
    }

    private fun assetIssue(prepare: Prepare): ErrorInfo? {
        if (content.verifiedFile(prepare.asset.sha256) == null) {
            return ErrorInfo(ErrorCode.ASSET_MISSING, "Verified copy of ${prepare.asset.title} not on headset")
        }
        scene?.unsupportedReason(prepare.presentation)?.let { return ErrorInfo(ErrorCode.UNSUPPORTED_MEDIA, it) }
        val mime = videoMime(prepare.asset)
        if (mime != null && hardwareDecoders().none { it.equals(mime, ignoreCase = true) }) {
            return ErrorInfo(ErrorCode.UNSUPPORTED_MEDIA, "No hardware decoder for $mime")
        }
        return null
    }

    // --- Reducer dispatch and effects (main thread) ----------------------------------------------

    private fun dispatch(input: SessionInput) {
        val before = _state.value
        val transition = reducer.reduce(before, input)
        _state.value = transition.state
        if (before.playback != transition.state.playback) {
            Log.i(TAG, "Playback ${before.playback} -> ${transition.state.playback} on ${input::class.simpleName}")
        }
        transition.effects.forEach(::execute)
    }

    private fun execute(effect: SessionEffect) {
        val run = _state.value.run
        when (effect) {
            is SessionEffect.PreparePlayer -> {
                val file = content.verifiedFile(effect.run.asset.sha256)
                val player = engine
                if (file == null || player == null) {
                    mainHandler.post {
                        dispatch(SessionInput.PlayerPrepareFailed(effect.token, ErrorInfo(ErrorCode.NOT_ACTIVE, "Player unavailable")))
                    }
                    return
                }
                pinnedSha?.let(content::unpin)
                pinnedSha = effect.run.asset.sha256.also(content::pin)
                player.prepare(effect.token, file, effect.run.asset, effect.run.presentation)
            }
            SessionEffect.ReleasePlayer -> {
                engine?.release()
                pinnedSha?.let(content::unpin)
                pinnedSha = null
            }
            is SessionEffect.ArmStart -> scheduler.arm(effect.armId, effect.localDeadlineUs) { id, now ->
                dispatch(SessionInput.DeadlineReached(id, now))
            }
            is SessionEffect.DisarmStart -> scheduler.disarm(effect.armId)
            SessionEffect.RevealAndPlayFromZero -> {
                scene?.showVideo()
                engine?.play()
            }
            SessionEffect.PausePlayer -> {
                engine?.pause()
                scene?.showStandby() // Paused wearers see their surroundings, not a frozen frame.
            }
            SessionEffect.ResumePlayer -> {
                scene?.showVideo()
                engine?.resume()
            }
            SessionEffect.ShowStandby -> scene?.showStandby()
            is SessionEffect.SendReady -> run?.let {
                reply(MessageType.READY, codec.toPayload(Ready(effect.token, it.asset.sha256, it.presentation.revision, effect.clockEstimateId, clock.nowUs())), null, it.runId, it.generation)
            }
            is SessionEffect.SendNotReady -> reply(MessageType.NOT_READY, codec.toPayload(NotReady(effect.error)), null, run?.runId, run?.generation)
            is SessionEffect.SendStartAccepted -> reply(
                MessageType.START_ACCEPTED,
                codec.toPayload(StartAccepted(effect.startAtControllerUs, effect.localDeadlineUs, effect.clockEstimateId)),
                effect.correlationId, run?.runId, run?.generation,
            )
            is SessionEffect.SendControlResult -> reply(
                MessageType.CONTROL_RESULT,
                codec.toPayload(ControlResult(effect.commandType, effect.accepted, _state.value.playback, effect.error)),
                effect.correlationId, run?.runId, run?.generation,
            )
            is SessionEffect.Record -> record(effect)
        }
    }

    private fun reply(type: String, payload: JsonObject, correlationId: String?, runId: String?, generation: Int?) {
        if (correlationId != null) capturing?.add(CachedReply(type, payload, runId, generation))
        connection.send(type, payload, correlationId, runId, generation)
    }

    private fun record(effect: SessionEffect.Record) {
        val position = engine?.positionUs() ?: 0
        val record = journal.append(effect.run, effect.kind, position, effect.detail, effect.error)
        val run = effect.run
        // Live events are a convenience; the journaled record is the source of truth after reconnect.
        when (effect.kind) {
            RunRecordKind.STARTED -> connection.send(
                MessageType.STARTED,
                Started(effect.deadlineLocalUs ?: 0, effect.callbackLocalUs ?: 0, (effect.callbackLocalUs ?: 0) - (effect.deadlineLocalUs ?: 0)),
                runId = run.runId, generation = run.generation,
            )
            RunRecordKind.COMPLETED -> connection.send(MessageType.COMPLETED, Completed(effect.durationUs ?: 0), runId = run.runId, generation = run.generation)
            RunRecordKind.ERROR -> connection.send(MessageType.RUN_ERROR, RunError(effect.error ?: ErrorInfo(ErrorCode.PLAYBACK_FAILED)), runId = run.runId, generation = run.generation)
            else -> Unit
        }
        if (effect.kind == RunRecordKind.STARTED) {
            mainHandler.postDelayed({ engine?.lastStartTimings?.let { Log.i(TAG, "Start timings run=${run.runId} deadline=${effect.deadlineLocalUs} callback=${effect.callbackLocalUs} $it") } }, 1_000)
        }
        connection.send(MessageType.RESULT_BATCH, ResultBatch(listOf(record)))
    }

    // --- Clock -----------------------------------------------------------------------------------

    private suspend fun refreshClock() {
        connection.clockSync.runBurst()
        connection.clockSync.status()?.let { connection.send(MessageType.CLOCK_STATUS, it) }
        sessionScope.launch { reevaluateClock(force = true) }
    }

    private fun reevaluateClock(force: Boolean = false) {
        val estimate = connection.clockSync.latest.value
        val acceptable = connection.clockSync.verdict(estimate).acceptable
        if (force || acceptable != lastClockAcceptable || estimate?.id != _state.value.clockEstimateId) {
            lastClockAcceptable = acceptable
            dispatch(SessionInput.ClockChanged(estimate?.id, acceptable))
        }
    }

    // --- Snapshots -------------------------------------------------------------------------------

    private fun onConnected() {
        sendStatus()
        sendInventory()
        content.transfers.value.values.forEach { connection.send(MessageType.TRANSFER_PROGRESS, it) }
        journal.outbox().takeIf { it.isNotEmpty() }?.let { connection.send(MessageType.RESULT_BATCH, ResultBatch(it)) }
        networkScope.launch { refreshClock() }
    }

    fun statusSnapshot(): HeadsetStatus {
        val s = _state.value
        val power = readPowerAndStorage(context, content.storageDir)
        return HeadsetStatus(
            playbackState = s.playback,
            runId = s.run?.runId,
            generation = s.run?.generation,
            assetSha256 = s.run?.asset?.sha256,
            positionUs = if (s.playback == PlaybackState.PLAYING || s.playback == PlaybackState.PAUSED) engine?.positionUs() ?: 0 else 0,
            durationUs = s.run?.asset?.durationUs ?: 0,
            capturedAtUs = clock.nowUs(),
            appActive = s.appActive,
            worn = worn,
            batteryPercent = power.batteryPercent,
            charging = power.charging,
            freeBytes = power.freeBytes,
            totalBytes = power.totalBytes,
            transfersDeferred = content.deferred.value,
            lastError = s.lastError,
            startOnBoot = StartOnBoot.isEnabled(context),
        )
    }

    private fun sendStatus() {
        if (connection.state.value !is LinkState.Connected) return
        connection.send(MessageType.STATUS, statusSnapshot())
    }

    private fun sendInventory() {
        if (connection.state.value !is LinkState.Connected) return
        val pages = content.inventory.value.chunked(INVENTORY_PAGE).ifEmpty { listOf(emptyList()) }
        pages.forEachIndexed { i, items -> connection.send(MessageType.INVENTORY, Inventory(i, pages.size, items)) }
    }

    private fun videoMime(asset: AssetDescriptor): String? = asset.videoCodec?.takeIf { it.startsWith("video/") }

    private val decoderCache by lazy {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder && it.isHardwareAccelerated }
            .flatMap { it.supportedTypes.toList() }
            .filter { it.startsWith("video/") }
            .distinct()
    }

    fun hardwareDecoders(): List<String> = decoderCache

    private companion object {
        const val TAG = "HeadsetSession"
        const val INVENTORY_PAGE = 150
    }
}
