package com.vrdevicecontrol.controller.run

import com.vrdevicecontrol.controller.catalog.CatalogRepository
import com.vrdevicecontrol.controller.devices.DeviceRegistry
import com.vrdevicecontrol.controller.devices.DeviceView
import com.vrdevicecontrol.controller.diagnostics.EventLog
import com.vrdevicecontrol.controller.platform.JsonStore
import com.vrdevicecontrol.controller.platform.utcNow
import com.vrdevicecontrol.domain.ControlOutcome
import com.vrdevicecontrol.domain.ControlStatus
import com.vrdevicecontrol.domain.DeviceLive
import com.vrdevicecontrol.domain.MonotonicClock
import com.vrdevicecontrol.domain.Participant
import com.vrdevicecontrol.domain.PrepareStatus
import com.vrdevicecontrol.domain.Run
import com.vrdevicecontrol.domain.RunPhase
import com.vrdevicecontrol.domain.RunRules
import com.vrdevicecontrol.domain.StartAck
import com.vrdevicecontrol.domain.Tuning
import com.vrdevicecontrol.protocol.Control
import com.vrdevicecontrol.protocol.ControlResult
import com.vrdevicecontrol.protocol.Envelope
import com.vrdevicecontrol.protocol.ErrorCode
import com.vrdevicecontrol.protocol.ErrorInfo
import com.vrdevicecontrol.protocol.MessageType
import com.vrdevicecontrol.protocol.NotReady
import com.vrdevicecontrol.protocol.PlaybackState
import com.vrdevicecontrol.protocol.Prepare
import com.vrdevicecontrol.protocol.ProtocolCodec
import com.vrdevicecontrol.protocol.Ready
import com.vrdevicecontrol.protocol.RunRecord
import com.vrdevicecontrol.protocol.StartAt
import com.vrdevicecontrol.protocol.Started
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

@Serializable
private data class RunData(val current: Run? = null, val history: List<Run> = emptyList())

private data class PendingControl(val deviceId: String, val type: String, val expiresAtUs: Long)

/**
 * Participant snapshot, readiness barrier, start scheduling and acknowledgements. Preparation is a
 * readiness barrier, not a distributed transaction: after `START_AT` is sent the UI shows per-device
 * acceptance and observed results and never claims an atomic group start. Confined to the actor.
 */
class RunCoordinator(
    file: File,
    json: Json,
    private val registry: DeviceRegistry,
    private val catalog: CatalogRepository,
    private val clock: MonotonicClock,
    private val tuning: Tuning,
    private val log: EventLog,
    val controllerEpoch: String,
) {
    private val codec = ProtocolCodec.forController()
    val rules = RunRules(tuning)
    private val store = JsonStore(file, RunData.serializer(), json) { RunData() }

    private val _current = MutableStateFlow(store.current.current)
    val current: StateFlow<Run?> = _current.asStateFlow()

    private val _history = MutableStateFlow(store.current.history)
    val history: StateFlow<List<Run>> = _history.asStateFlow()

    /** The operator changed the default "all online" selection; stop auto-adding arrivals. */
    private var selectionTouched = false

    /** START_AT envelopes for idempotent re-delivery of the *same* command within this epoch. */
    private val startEnvelopes = mutableMapOf<String, Pair<String, JsonObject>>()
    private val pendingControls = mutableMapOf<String, PendingControl>()

    /** A run restored from a previous controller process: reconciled, never re-commanded to start. */
    fun isFromPreviousEpoch(run: Run) = run.controllerEpoch != controllerEpoch

    fun phase(run: Run): RunPhase = rules.phase(run, liveFor(run), clock.nowUs())
    fun blockers(run: Run): Map<String, String> = rules.startBlockers(run, liveFor(run))
    fun canStart(run: Run): Boolean = !isFromPreviousEpoch(run) && rules.canStart(run, liveFor(run))
    fun estimatedPositionUs(run: Run): Long? = rules.estimatedPositionUs(run, clock.nowUs())

    fun liveFor(run: Run): Map<String, DeviceLive> {
        val views = registry.views().associateBy { it.deviceId }
        return registry.liveForRules().mapValues { (id, live) ->
            live.copy(hasAsset = run.asset.sha256 in views[id]?.inventory.orEmpty())
        }
    }

    // --- Draft ---------------------------------------------------------------------------------

    fun selectAsset(assetId: String) {
        val entry = catalog.entry(assetId) ?: return
        val run = _current.value
        if (run != null && run.frozen) return
        if (run == null) {
            selectionTouched = false
            save(
                Run(
                    runId = UUID.randomUUID().toString(),
                    generation = 1,
                    controllerEpoch = controllerEpoch,
                    asset = entry.asset,
                    presentation = entry.presentation,
                    participants = onlineDeviceIds().map { Participant(it) },
                    createdAtUtc = utcNow(),
                ),
            )
        } else {
            save(run.copy(asset = entry.asset, presentation = entry.presentation))
        }
    }

    fun toggleParticipant(deviceId: String) {
        val run = _current.value?.takeIf { !it.frozen } ?: return
        selectionTouched = true
        val ids = run.participants.map { it.deviceId }
        save(run.withSelection(if (deviceId in ids) ids - deviceId else ids + deviceId))
    }

    fun selectAllOnline() {
        val run = _current.value?.takeIf { !it.frozen } ?: return
        selectionTouched = false
        save(run.withSelection(onlineDeviceIds()))
    }

    /** Close the current run (keeping its results) and start a fresh draft with the same video. */
    fun newRun() {
        val previous = _current.value
        if (previous != null) archive(previous.copy(closedAtUtc = utcNow()))
        _current.value = null
        save(null)
        previous?.let { selectAsset(it.asset.assetId) }
        log.log("RUN_CLOSED", runId = previous?.runId)
    }

    // --- Preparation ---------------------------------------------------------------------------

    fun prepare() {
        val draft = _current.value?.takeIf { !it.frozen && it.participants.isNotEmpty() } ?: return
        // Freeze the latest presentation revision together with the participants.
        val entry = catalog.entry(draft.asset.assetId) ?: return
        var run = draft.copy(
            asset = entry.asset,
            presentation = entry.presentation,
            frozen = true,
            participants = draft.participants.map { Participant(it.deviceId) },
        )
        for (p in run.participants) run = sendPrepare(run, p.deviceId)
        save(run)
        log.log("PREPARE", runId = run.runId, detail = "${run.participants.size} headset(s), gen ${run.generation}")
    }

    fun retryPrepare(deviceId: String) {
        val run = startablePreparation() ?: return
        save(sendPrepare(run, deviceId))
    }

    fun retryAllNotReady() {
        var run = startablePreparation() ?: return
        run.participants.filter { it.prepare != PrepareStatus.READY }.forEach { run = sendPrepare(run, it.deviceId) }
        save(run)
    }

    /** Explicitly drop an unavailable participant before Start. Never done silently. */
    fun removeParticipant(deviceId: String) {
        val run = startablePreparation() ?: return
        releaseIfPrepared(run, run.participant(deviceId))
        save(run.copy(participants = run.participants.filterNot { it.deviceId == deviceId }))
        log.log("PARTICIPANT_REMOVED", deviceId, run.runId)
    }

    /** Return to selection; invalidates the previous preparation by bumping the generation. */
    fun backToDraft() {
        val run = startablePreparation() ?: return
        run.participants.forEach { releaseIfPrepared(run, it) }
        save(run.copy(generation = run.generation + 1, frozen = false, participants = run.participants.map { Participant(it.deviceId) }))
    }

    private fun startablePreparation(): Run? =
        _current.value?.takeIf { it.frozen && it.startAtControllerUs == null && !isFromPreviousEpoch(it) }

    private fun releaseIfPrepared(run: Run, p: Participant?) {
        if (p == null || p.prepare == PrepareStatus.NOT_SENT || p.prepare == PrepareStatus.UNREACHABLE) return
        registry.link(p.deviceId)?.send(
            MessageType.STOP,
            codec.toPayload(Control(clock.nowUs() + tuning.controlAcceptWindowUs, registry.view(p.deviceId)?.clock?.estimateId)),
            run.runId, run.generation,
        )
    }

    private fun sendPrepare(run: Run, deviceId: String): Run {
        val link = registry.link(deviceId)
            ?: return run.update(deviceId) { it.copy(prepare = PrepareStatus.UNREACHABLE, prepareError = ErrorInfo(ErrorCode.NOT_ACTIVE, "Headset out of contact")) }
        val prepare = Prepare(
            asset = run.asset,
            presentation = run.presentation,
            validUntilControllerUs = clock.nowUs() + tuning.prepareAcceptWindowUs,
            clockEstimateId = registry.view(deviceId)?.clock?.estimateId,
        )
        val env = link.send(MessageType.PREPARE, codec.toPayload(prepare), run.runId, run.generation)
            ?: return run.update(deviceId) { it.copy(prepare = PrepareStatus.UNREACHABLE) }
        return run.update(deviceId) {
            it.copy(prepare = PrepareStatus.SENT, prepareCommandId = env.messageId, prepareToken = null, prepareError = null)
        }
    }

    // --- Start ---------------------------------------------------------------------------------

    fun start(): Boolean {
        val run = _current.value ?: return false
        if (!canStart(run)) return false
        val (startAt, acceptBefore) = rules.startTimes(clock.nowUs())
        val commandIds = run.participants.associate { it.deviceId to UUID.randomUUID().toString() }
        // Persist per-device command intent before dispatch.
        var scheduled = store.saveNow { data ->
            data.copy(current = run.copy(
                startAtControllerUs = startAt,
                startRequestedAtUtc = utcNow(),
                participants = run.participants.map { it.copy(startCommandId = commandIds[it.deviceId], startAck = StartAck.SENT) },
            ))
        }.current!!
        _current.value = scheduled
        startEnvelopes.clear()
        for (p in scheduled.participants) {
            val view = registry.view(p.deviceId)
            val payload = codec.toPayload(
                StartAt(
                    assetSha256 = run.asset.sha256,
                    presentationRevision = run.presentation.revision,
                    prepareToken = p.prepareToken ?: "",
                    clockSampleId = view?.clock?.estimateId ?: "",
                    startAtControllerUs = startAt,
                    acceptBeforeControllerUs = acceptBefore,
                    positionUs = 0,
                ),
            )
            val messageId = commandIds.getValue(p.deviceId)
            startEnvelopes[p.deviceId] = messageId to payload
            val sent = registry.link(p.deviceId)?.send(MessageType.START_AT, payload, run.runId, run.generation, messageId = messageId)
            if (sent == null) scheduled = scheduled.update(p.deviceId) { it.copy(startAck = StartAck.UNREACHABLE) }
        }
        save(scheduled)
        log.log("START_SENT", runId = run.runId, detail = "startAt=$startAt participants=${run.participants.size}")
        return true
    }

    /** Best-effort cancellation before execution. An unacknowledged cancel is never treated as success. */
    fun cancelStart() {
        var run = _current.value ?: return
        if (run.startAtControllerUs == null) return
        run.participants.filter { it.startAck == StartAck.SENT || it.startAck == StartAck.ACCEPTED }
            .forEach { run = sendControl(run, it.deviceId, MessageType.CANCEL_START) }
        save(run)
        log.log("CANCEL_START_SENT", runId = run.runId)
    }

    // --- Controls on reachable devices ---------------------------------------------------------

    fun control(type: String, deviceIds: Collection<String>? = null) {
        var run = _current.value ?: return
        val targets = deviceIds ?: run.participants.map { it.deviceId }
        targets.forEach { run = sendControl(run, it, type) }
        save(run)
        log.log("CONTROL_$type", runId = run.runId, detail = "${targets.size} headset(s)")
    }

    /** Restart is a new prepare/start generation from position zero, never a reconnect action. */
    fun restart() {
        val run = _current.value ?: return
        if (run.startAtControllerUs == null) return
        var stopped = run
        run.participants.forEach { stopped = sendControl(stopped, it.deviceId, MessageType.STOP) }
        archive(stopped.copy(closedAtUtc = utcNow()))
        var next = run.copy(
            generation = run.generation + 1,
            controllerEpoch = controllerEpoch,
            startAtControllerUs = null,
            startRequestedAtUtc = null,
            participants = run.participants.map { Participant(it.deviceId) },
            frozen = true,
            closedAtUtc = null,
        )
        startEnvelopes.clear()
        for (p in next.participants) next = sendPrepare(next, p.deviceId)
        save(next)
        log.log("RESTART", runId = run.runId, detail = "gen ${next.generation}")
    }

    private fun sendControl(run: Run, deviceId: String, type: String): Run {
        val link = registry.link(deviceId)
        if (link == null) {
            // Never queued for later delivery when the participant returns.
            return run.update(deviceId) { it.copy(lastControl = ControlOutcome(type, "", ControlStatus.UNREACHABLE)) }
        }
        val validUntil = clock.nowUs() + tuning.controlAcceptWindowUs
        val env = link.send(type, codec.toPayload(Control(validUntil, registry.view(deviceId)?.clock?.estimateId)), run.runId, run.generation)
            ?: return run.update(deviceId) { it.copy(lastControl = ControlOutcome(type, "", ControlStatus.UNREACHABLE)) }
        pendingControls[env.messageId] = PendingControl(deviceId, type, validUntil + 1_000_000)
        return run.update(deviceId) { it.copy(lastControl = ControlOutcome(type, env.messageId, ControlStatus.PENDING)) }
    }

    // --- Inbound -------------------------------------------------------------------------------

    fun onMessage(deviceId: String, env: Envelope) {
        val run = _current.value ?: return
        when (env.type) {
            MessageType.CONTROL_RESULT -> onControlResult(run, deviceId, env.correlationId, codec.payloadOf(env))
            MessageType.READY -> {
                if (!matches(run, env) || run.startAtControllerUs != null) return
                val ready = codec.payloadOf<Ready>(env)
                if (ready.assetSha256 != run.asset.sha256 || ready.presentationRevision != run.presentation.revision) return
                save(run.update(deviceId) { it.copy(prepare = PrepareStatus.READY, prepareToken = ready.prepareToken, readyClockEstimateId = ready.clockEstimateId, prepareError = null) })
            }
            MessageType.NOT_READY -> {
                if (!matches(run, env) || run.startAtControllerUs != null) return
                val error = codec.payloadOf<NotReady>(env).error
                save(run.update(deviceId) { it.copy(prepare = PrepareStatus.NOT_READY, prepareToken = null, prepareError = error) })
                log.log("NOT_READY", deviceId, run.runId, error.code.name)
            }
            MessageType.START_ACCEPTED -> {
                val p = run.participant(deviceId) ?: return
                if (env.correlationId == null || env.correlationId != p.startCommandId) return
                save(run.update(deviceId) { it.copy(startAck = StartAck.ACCEPTED, startError = null) })
            }
            MessageType.STARTED -> if (matches(run, env)) {
                val started = codec.payloadOf<Started>(env)
                save(run.update(deviceId) { it.copy(startLatenessUs = started.latenessUs) })
                log.log("STARTED", deviceId, run.runId, "lateness=${started.latenessUs}us")
            }
            else -> Unit
        }
    }

    private fun onControlResult(run: Run, deviceId: String, correlationId: String?, result: ControlResult) {
        correlationId ?: return
        val p = run.participant(deviceId) ?: return
        when {
            correlationId == p.prepareCommandId && run.startAtControllerUs == null -> save(
                run.update(deviceId) {
                    when {
                        !result.accepted -> it.copy(prepare = PrepareStatus.REJECTED, prepareError = result.error)
                        it.prepare == PrepareStatus.SENT -> it.copy(prepare = PrepareStatus.ACCEPTED)
                        else -> it
                    }
                },
            )
            correlationId == p.startCommandId -> if (!result.accepted) {
                save(run.update(deviceId) { it.copy(startAck = StartAck.REJECTED, startError = result.error) })
                log.log("START_REJECTED", deviceId, run.runId, result.error?.code?.name)
            }
            pendingControls.containsKey(correlationId) -> {
                pendingControls.remove(correlationId)
                save(run.update(deviceId) {
                    val last = it.lastControl
                    if (last?.commandId != correlationId) it
                    else it.copy(lastControl = last.copy(status = if (result.accepted) ControlStatus.ACCEPTED else ControlStatus.REJECTED, error = result.error))
                })
            }
        }
    }

    /** Applies journaled results idempotently to the current run or history. Returns IDs to acknowledge. */
    fun onRecords(deviceId: String, records: List<RunRecord>): List<String> {
        var current = _current.value
        var history = _history.value
        for (r in records) {
            if (current != null && current.runId == r.runId && current.generation == r.generation) {
                current = current.applyRecord(deviceId, r.runId, r.generation, r.kind, r.atUtc, r.error)
            } else {
                history = history.map { h -> if (h.runId == r.runId && h.generation == r.generation) h.applyRecord(deviceId, r.runId, r.generation, r.kind, r.atUtc, r.error) else h }
            }
            log.log("RESULT_${r.kind}", deviceId, r.runId, r.error?.code?.name ?: r.detail)
        }
        _history.value = history
        save(current)
        return records.map { it.recordId }
    }

    fun onDeviceConnected(view: DeviceView) {
        val run = _current.value ?: return
        if (!run.frozen && !selectionTouched && run.participant(view.deviceId) == null) {
            save(run.withSelection(run.participants.map { it.deviceId } + view.deviceId))
            return
        }
        // Re-deliver an unacknowledged START_AT with the same ID: the headset returns its cached
        // outcome or rejects it as expired. This can never create a second start.
        val p = run.participant(view.deviceId) ?: return
        val envelope = startEnvelopes[view.deviceId]
        if (envelope != null && (p.startAck == StartAck.SENT || p.startAck == StartAck.UNREACHABLE) && !isFromPreviousEpoch(run)) {
            registry.link(view.deviceId)?.send(MessageType.START_AT, envelope.second, run.runId, run.generation, messageId = envelope.first)
            if (p.startAck == StartAck.UNREACHABLE) save(run.update(view.deviceId) { it.copy(startAck = StartAck.SENT) })
        }
    }

    /** Readiness is lost if a reconnected headset no longer reports this preparation as ready. */
    fun onStatus(deviceId: String) {
        val run = _current.value ?: return
        if (!run.frozen || run.startAtControllerUs != null) return
        val p = run.participant(deviceId) ?: return
        if (p.prepare != PrepareStatus.READY) return
        val status = registry.view(deviceId)?.takeIf { it.statusIsLive }?.status ?: return
        if (status.playbackState != PlaybackState.READY || status.runId != run.runId || status.generation != run.generation) {
            save(run.update(deviceId) {
                it.copy(prepare = PrepareStatus.NOT_READY, prepareToken = null, prepareError = ErrorInfo(ErrorCode.STALE_PREPARATION, "Headset reports ${status.playbackState.name.lowercase()}"))
            })
        }
    }

    fun tick() {
        val now = clock.nowUs()
        val expired = pendingControls.filterValues { it.expiresAtUs < now }
        if (expired.isEmpty()) return
        expired.keys.forEach(pendingControls::remove)
        var run = _current.value ?: return
        for ((id, pending) in expired) {
            run = run.update(pending.deviceId) {
                val last = it.lastControl
                if (last?.commandId == id && last.status == ControlStatus.PENDING) it.copy(lastControl = last.copy(status = ControlStatus.NO_RESPONSE)) else it
            }
        }
        save(run)
    }

    private fun matches(run: Run, env: Envelope) = env.runId == run.runId && env.generation == run.generation

    private fun onlineDeviceIds() = registry.views().filter { it.online }.map { it.deviceId }

    private fun archive(run: Run) {
        _history.value = (listOf(run) + _history.value.filterNot { it.runId == run.runId && it.generation == run.generation }).take(MAX_HISTORY)
    }

    private fun save(run: Run?) {
        _current.value = run
        store.update { RunData(run, _history.value) }
    }

    private companion object {
        const val MAX_HISTORY = 50
    }
}
