package com.vrdevicecontrol.domain

import com.vrdevicecontrol.protocol.AssetDescriptor
import com.vrdevicecontrol.protocol.ErrorInfo
import com.vrdevicecontrol.protocol.PlaybackState
import com.vrdevicecontrol.protocol.PresentationDescriptor
import com.vrdevicecontrol.protocol.RunRecordKind
import kotlinx.serialization.Serializable

@Serializable
enum class RunPhase { DRAFT, PREPARING, READY, STARTING, ACTIVE, FINISHED, NEEDS_ATTENTION, UNCERTAIN }

@Serializable
enum class PrepareStatus { NOT_SENT, SENT, ACCEPTED, READY, NOT_READY, REJECTED, UNREACHABLE }

/** Start-command acknowledgement. Acceptance is not observed playback. */
@Serializable
enum class StartAck { NONE, SENT, ACCEPTED, REJECTED, UNREACHABLE }

/** What the headset has actually reported doing, possibly much later via its result outbox. */
@Serializable
enum class Observed { NONE, STARTED, PAUSED, INTERRUPTED, COMPLETED, STOPPED, ERROR }

@Serializable
enum class ControlStatus { PENDING, ACCEPTED, REJECTED, UNREACHABLE, NO_RESPONSE }

@Serializable
data class ControlOutcome(
    val type: String,
    val commandId: String,
    val status: ControlStatus,
    val error: ErrorInfo? = null,
)

@Serializable
data class Participant(
    val deviceId: String,
    val prepare: PrepareStatus = PrepareStatus.NOT_SENT,
    val prepareCommandId: String? = null,
    val prepareToken: String? = null,
    val readyClockEstimateId: String? = null,
    val prepareError: ErrorInfo? = null,
    val startCommandId: String? = null,
    val startAck: StartAck = StartAck.NONE,
    val startError: ErrorInfo? = null,
    val observed: Observed = Observed.NONE,
    val observedAtUtc: String? = null,
    val resultError: ErrorInfo? = null,
    val lastControl: ControlOutcome? = null,
    /** Headset-reported scheduler lateness at start; diagnostic evidence only. */
    val startLatenessUs: Long? = null,
) {
    val isTerminal: Boolean
        get() = observed in TERMINAL_OBSERVED || startAck == StartAck.REJECTED

    companion object {
        val TERMINAL_OBSERVED = setOf(Observed.COMPLETED, Observed.STOPPED, Observed.ERROR)
    }
}

/** Live, ephemeral view of a device used to derive readiness. Never persisted as authority. */
data class DeviceLive(
    val deviceId: String,
    val online: Boolean,
    val playback: PlaybackState?,
    val appActive: Boolean,
    val clockAcceptable: Boolean,
    val clockReason: String? = null,
    val hasAsset: Boolean,
)

@Serializable
data class Run(
    val runId: String,
    val generation: Int,
    val controllerEpoch: String,
    val asset: AssetDescriptor,
    val presentation: PresentationDescriptor,
    /** Selected devices. Frozen once preparation begins; later arrivals never join silently. */
    val participants: List<Participant>,
    val frozen: Boolean = false,
    val startAtControllerUs: Long? = null,
    val startRequestedAtUtc: String? = null,
    val createdAtUtc: String,
    val closedAtUtc: String? = null,
) {
    fun participant(deviceId: String) = participants.firstOrNull { it.deviceId == deviceId }

    fun update(deviceId: String, block: (Participant) -> Participant): Run =
        copy(participants = participants.map { if (it.deviceId == deviceId) block(it) else it })

    fun withSelection(deviceIds: List<String>): Run {
        check(!frozen) { "Participants are frozen once preparation begins" }
        return copy(participants = deviceIds.distinct().map { id -> participant(id) ?: Participant(id) })
    }

    /** Applies a journaled/live headset result. Records from other runs or generations are ignored. */
    fun applyRecord(deviceId: String, runId: String, generation: Int, kind: RunRecordKind, atUtc: String, error: ErrorInfo?): Run {
        if (runId != this.runId || generation != this.generation) return this
        val observed = when (kind) {
            RunRecordKind.ACCEPTED -> return update(deviceId) {
                if (it.startAck == StartAck.ACCEPTED) it else it.copy(startAck = StartAck.ACCEPTED)
            }
            RunRecordKind.STARTED, RunRecordKind.RESUMED -> Observed.STARTED
            RunRecordKind.PAUSED -> Observed.PAUSED
            RunRecordKind.INTERRUPTED -> Observed.INTERRUPTED
            RunRecordKind.STOPPED -> Observed.STOPPED
            RunRecordKind.COMPLETED -> Observed.COMPLETED
            RunRecordKind.ERROR -> Observed.ERROR
        }
        return update(deviceId) { p ->
            // A late STARTED record must not overwrite a terminal result that already arrived.
            if (p.observed in Participant.TERMINAL_OBSERVED && observed !in Participant.TERMINAL_OBSERVED) p
            else p.copy(observed = observed, observedAtUtc = atUtc, resultError = error ?: p.resultError)
        }
    }
}

/**
 * Derivation rules for the controller's run view. Pure so the operator-visible states can be tested
 * against every network-loss point.
 */
class RunRules(private val tuning: Tuning = Tuning()) {

    /** Why each participant blocks Start. An empty map with participants present means Start is allowed. */
    fun startBlockers(run: Run, devices: Map<String, DeviceLive>): Map<String, String> {
        if (!run.frozen || run.startAtControllerUs != null) return emptyMap()
        val blockers = linkedMapOf<String, String>()
        for (p in run.participants) {
            val live = devices[p.deviceId]
            val reason = when {
                live == null || !live.online -> "Out of contact"
                !live.appActive -> "Headset app inactive (asleep or removed)"
                !live.hasAsset -> "Video not on headset"
                p.prepare == PrepareStatus.NOT_READY || p.prepare == PrepareStatus.REJECTED ->
                    p.prepareError?.let { OperatorMessages.short(it) } ?: "Not ready"
                p.prepare == PrepareStatus.UNREACHABLE -> "Prepare could not be delivered"
                // Clock first: a headset holds PREPARING until its sync is good enough.
                !live.clockAcceptable -> live.clockReason ?: "Clock sync not good enough"
                p.prepare != PrepareStatus.READY -> "Preparing…"
                live.playback != PlaybackState.READY -> "Reports ${live.playback?.name?.lowercase() ?: "unknown"}"
                else -> null
            }
            if (reason != null) blockers[p.deviceId] = reason
        }
        return blockers
    }

    fun canStart(run: Run, devices: Map<String, DeviceLive>): Boolean =
        run.frozen && run.startAtControllerUs == null && run.closedAtUtc == null &&
            run.participants.isNotEmpty() && startBlockers(run, devices).isEmpty()

    fun phase(run: Run, devices: Map<String, DeviceLive>, nowControllerUs: Long): RunPhase {
        if (!run.frozen) return RunPhase.DRAFT
        val ps = run.participants
        val startAt = run.startAtControllerUs
        if (startAt == null) {
            if (ps.isEmpty()) return RunPhase.NEEDS_ATTENTION
            if (ps.any { it.prepare in PREPARE_FAILURES } || ps.any { devices[it.deviceId]?.online != true }) {
                return RunPhase.NEEDS_ATTENTION
            }
            return if (startBlockers(run, devices).isEmpty()) RunPhase.READY else RunPhase.PREPARING
        }
        if (ps.all { it.isTerminal }) return RunPhase.FINISHED
        if (nowControllerUs < startAt + START_SETTLE_US) return RunPhase.STARTING
        if (ps.any { it.observed == Observed.STARTED || it.observed == Observed.PAUSED || it.observed == Observed.INTERRUPTED }) {
            return RunPhase.ACTIVE
        }
        if (ps.all { it.startAck == StartAck.REJECTED || it.startAck == StartAck.UNREACHABLE }) {
            return RunPhase.NEEDS_ATTENTION
        }
        // Accepted or unacknowledged starts without an observed report stay uncertain.
        return RunPhase.UNCERTAIN
    }

    /** Explicitly estimated media position. Never presented as live confirmation. */
    fun estimatedPositionUs(run: Run, nowControllerUs: Long): Long? {
        val startAt = run.startAtControllerUs ?: return null
        val elapsed = nowControllerUs - startAt
        if (elapsed < 0) return null
        return if (run.asset.durationUs > 0) elapsed.coerceAtMost(run.asset.durationUs) else elapsed
    }

    fun startTimes(nowControllerUs: Long): Pair<Long, Long> {
        val startAt = nowControllerUs + tuning.startLeadUs
        return startAt to (startAt - tuning.minAcceptLeadUs)
    }

    companion object {
        val PREPARE_FAILURES = setOf(PrepareStatus.NOT_READY, PrepareStatus.REJECTED, PrepareStatus.UNREACHABLE)

        /** Grace after the scheduled start before missing reports make the run uncertain. */
        const val START_SETTLE_US = 1_500_000L
    }
}
