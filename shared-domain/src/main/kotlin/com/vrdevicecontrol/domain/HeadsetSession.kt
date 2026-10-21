package com.vrdevicecontrol.domain

import com.vrdevicecontrol.protocol.AssetDescriptor
import com.vrdevicecontrol.protocol.Control
import com.vrdevicecontrol.protocol.ErrorCode
import com.vrdevicecontrol.protocol.ErrorInfo
import com.vrdevicecontrol.protocol.MessageType
import com.vrdevicecontrol.protocol.PlaybackState
import com.vrdevicecontrol.protocol.Prepare
import com.vrdevicecontrol.protocol.PresentationDescriptor
import com.vrdevicecontrol.protocol.RunRecordKind
import com.vrdevicecontrol.protocol.StartAt

/** The fixed run a headset has been asked to prepare. */
data class RunContext(
    val runId: String,
    val generation: Int,
    val controllerEpoch: String,
    val asset: AssetDescriptor,
    val presentation: PresentationDescriptor,
)

data class HeadsetSessionState(
    val playback: PlaybackState = PlaybackState.STANDBY,
    /** Presenting, mounted and with a usable surface. Network state is deliberately absent. */
    val appActive: Boolean = false,
    val lifecycleGeneration: Int = 0,
    val run: RunContext? = null,
    val prepareToken: String? = null,
    val playerPrepared: Boolean = false,
    val clockEstimateId: String? = null,
    val clockAcceptable: Boolean = false,
    val armedCommandId: String? = null,
    val armedDeadlineLocalUs: Long? = null,
    val pausedByLifecycle: Boolean = false,
    val lastError: ErrorInfo? = null,
)

/** Identity of a session command after connection/process/epoch validation has passed. */
data class CommandMeta(
    val messageId: String,
    val runId: String,
    val generation: Int,
    val controllerEpoch: String,
)

sealed interface SessionInput {
    data class PrepareCommand(
        val meta: CommandMeta,
        val prepare: Prepare,
        /** Controller time now, from the current clock mapping; null when no mapping exists. */
        val nowControllerUs: Long?,
        /** Missing/unverified asset or an unsupported presentation, checked by the caller. */
        val assetIssue: ErrorInfo?,
        val newToken: String,
    ) : SessionInput

    data class PlayerPrepared(val token: String) : SessionInput
    data class PlayerPrepareFailed(val token: String, val error: ErrorInfo) : SessionInput
    data class ClockChanged(val estimateId: String?, val acceptable: Boolean) : SessionInput

    data class StartAtCommand(
        val meta: CommandMeta,
        val start: StartAt,
        val nowLocalUs: Long,
        /** The estimate named by `clockSampleId`, if still retained. */
        val estimate: ClockEstimate?,
        val verdict: ClockVerdict,
    ) : SessionInput

    data class DeadlineReached(val armId: String, val nowLocalUs: Long) : SessionInput
    data class PlaybackCompleted(val durationUs: Long) : SessionInput
    data class PlaybackFailed(val error: ErrorInfo) : SessionInput

    data class ControlCommand(
        val meta: CommandMeta,
        val type: String,
        val control: Control,
        val nowControllerUs: Long?,
    ) : SessionInput

    data class LifecycleChanged(val active: Boolean) : SessionInput

    /** Local acknowledgement of an error (setup panel) returning to standby. */
    data object Reset : SessionInput
}

sealed interface SessionEffect {
    data class PreparePlayer(val token: String, val run: RunContext) : SessionEffect
    data object ReleasePlayer : SessionEffect
    data class ArmStart(val armId: String, val localDeadlineUs: Long, val lifecycleGeneration: Int) : SessionEffect
    data class DisarmStart(val armId: String) : SessionEffect

    /** Switch from standby to video and request playback from position zero. */
    data object RevealAndPlayFromZero : SessionEffect
    data object PausePlayer : SessionEffect
    data object ResumePlayer : SessionEffect
    data object ShowStandby : SessionEffect

    data class SendReady(val token: String, val clockEstimateId: String) : SessionEffect
    data class SendNotReady(val error: ErrorInfo) : SessionEffect
    data class SendStartAccepted(
        val correlationId: String,
        val startAtControllerUs: Long,
        val localDeadlineUs: Long,
        val clockEstimateId: String,
    ) : SessionEffect

    data class SendControlResult(
        val correlationId: String,
        val commandType: String,
        val accepted: Boolean,
        val error: ErrorInfo? = null,
    ) : SessionEffect

    /** Durable run journal entry; also reported live when connected. */
    data class Record(
        val run: RunContext,
        val kind: RunRecordKind,
        val error: ErrorInfo? = null,
        val detail: String? = null,
        val deadlineLocalUs: Long? = null,
        val callbackLocalUs: Long? = null,
        val durationUs: Long? = null,
    ) : SessionEffect
}

data class Transition(val state: HeadsetSessionState, val effects: List<SessionEffect>)

/**
 * Serialized playback/session rules for one headset. Pure: no clocks, threads, sockets or player
 * calls. The caller executes effects in order on the player looper. Connectivity never appears as
 * an input, so network loss cannot pause, cancel, seek or restart playback.
 */
class HeadsetSessionReducer(private val tuning: Tuning = Tuning()) {

    fun reduce(state: HeadsetSessionState, input: SessionInput): Transition = when (input) {
        is SessionInput.PrepareCommand -> prepare(state, input)
        is SessionInput.PlayerPrepared -> playerPrepared(state, input)
        is SessionInput.PlayerPrepareFailed -> playerPrepareFailed(state, input)
        is SessionInput.ClockChanged -> clockChanged(state, input)
        is SessionInput.StartAtCommand -> startAt(state, input)
        is SessionInput.DeadlineReached -> deadline(state, input)
        is SessionInput.PlaybackCompleted -> completed(state, input)
        is SessionInput.PlaybackFailed -> failed(state, input)
        is SessionInput.ControlCommand -> control(state, input)
        is SessionInput.LifecycleChanged -> lifecycle(state, input)
        SessionInput.Reset -> reset(state)
    }

    // --- Preparation --------------------------------------------------------------------------

    private fun prepare(state: HeadsetSessionState, input: SessionInput.PrepareCommand): Transition {
        val id = input.meta.messageId
        fun reject(code: ErrorCode, detail: String? = null) = Transition(
            state,
            listOf(SessionEffect.SendControlResult(id, MessageType.PREPARE, false, ErrorInfo(code, detail))),
        )

        if (state.playback in ACTIVE_RUN_STATES) {
            return reject(ErrorCode.INVALID_STATE, "Busy with run ${state.run?.runId} (${state.playback})")
        }
        val now = input.nowControllerUs ?: return reject(ErrorCode.CLOCK_UNCERTAIN, "No clock mapping")
        if (now > input.prepare.validUntilControllerUs) return reject(ErrorCode.TOO_LATE, "Prepare expired")
        if (!state.appActive) return reject(ErrorCode.NOT_ACTIVE, "Headset app is not active")
        input.assetIssue?.let { return Transition(state, listOf(SessionEffect.SendControlResult(id, MessageType.PREPARE, false, it))) }

        val run = RunContext(
            runId = input.meta.runId,
            generation = input.meta.generation,
            controllerEpoch = input.meta.controllerEpoch,
            asset = input.prepare.asset,
            presentation = input.prepare.presentation,
        )

        // Same run, generation and media already prepared: report readiness again, no rebuild.
        if (state.playback == PlaybackState.READY && state.run == run && state.prepareToken != null) {
            return Transition(
                state,
                listOf(
                    SessionEffect.SendControlResult(id, MessageType.PREPARE, true),
                    SessionEffect.SendReady(state.prepareToken, state.clockEstimateId!!),
                ),
            )
        }

        val effects = mutableListOf<SessionEffect>()
        if (state.playback == PlaybackState.PREPARING || state.playback == PlaybackState.READY) {
            effects += SessionEffect.ReleasePlayer
        }
        effects += SessionEffect.SendControlResult(id, MessageType.PREPARE, true)
        effects += SessionEffect.PreparePlayer(input.newToken, run)
        return Transition(
            state.copy(
                playback = PlaybackState.PREPARING,
                run = run,
                prepareToken = input.newToken,
                playerPrepared = false,
                armedCommandId = null,
                armedDeadlineLocalUs = null,
                pausedByLifecycle = false,
                lastError = null,
            ),
            effects,
        )
    }

    private fun playerPrepared(state: HeadsetSessionState, input: SessionInput.PlayerPrepared): Transition {
        if (state.playback != PlaybackState.PREPARING || state.prepareToken != input.token) {
            return Transition(state, emptyList()) // Superseded preparation; caller already released it.
        }
        val next = state.copy(playerPrepared = true)
        return promoteIfReady(next)
    }

    private fun playerPrepareFailed(state: HeadsetSessionState, input: SessionInput.PlayerPrepareFailed): Transition {
        if (state.prepareToken != input.token || state.playback != PlaybackState.PREPARING) {
            return Transition(state, emptyList())
        }
        return Transition(
            state.copy(playback = PlaybackState.ERROR, prepareToken = null, playerPrepared = false, lastError = input.error),
            listOf(SessionEffect.ReleasePlayer, SessionEffect.ShowStandby, SessionEffect.SendNotReady(input.error)),
        )
    }

    private fun clockChanged(state: HeadsetSessionState, input: SessionInput.ClockChanged): Transition {
        val next = state.copy(clockEstimateId = input.estimateId, clockAcceptable = input.acceptable)
        // A clock refresh never moves an armed deadline or touches a running media clock.
        return if (next.playback == PlaybackState.PREPARING) promoteIfReady(next) else Transition(next, emptyList())
    }

    private fun promoteIfReady(state: HeadsetSessionState): Transition {
        if (!state.playerPrepared || !state.clockAcceptable || state.clockEstimateId == null || !state.appActive) {
            return Transition(state, emptyList())
        }
        return Transition(
            state.copy(playback = PlaybackState.READY),
            listOf(SessionEffect.SendReady(state.prepareToken!!, state.clockEstimateId)),
        )
    }

    // --- Scheduled start ------------------------------------------------------------------------

    private fun startAt(state: HeadsetSessionState, input: SessionInput.StartAtCommand): Transition {
        val id = input.meta.messageId
        val start = input.start
        fun reject(code: ErrorCode, detail: String? = null) = Transition(
            state,
            listOf(SessionEffect.SendControlResult(id, MessageType.START_AT, false, ErrorInfo(code, detail))),
        )

        val run = state.run
        if (run == null || run.runId != input.meta.runId || run.generation != input.meta.generation ||
            run.controllerEpoch != input.meta.controllerEpoch
        ) {
            return reject(ErrorCode.STALE_RUN)
        }
        if (state.playback == PlaybackState.ARMED) return reject(ErrorCode.ALREADY_ARMED)
        if (state.playback != PlaybackState.READY) return reject(ErrorCode.INVALID_STATE, "State is ${state.playback}")
        if (start.prepareToken != state.prepareToken) return reject(ErrorCode.STALE_PREPARATION, "Preparation token changed")
        if (start.assetSha256 != run.asset.sha256 || start.presentationRevision != run.presentation.revision) {
            return reject(ErrorCode.STALE_PREPARATION, "Media revision changed")
        }
        if (!state.appActive) return reject(ErrorCode.NOT_ACTIVE)
        if (start.positionUs != 0L) return reject(ErrorCode.INVALID_STATE, "Runs always start from position zero")
        val estimate = input.estimate
        if (estimate == null || estimate.id != start.clockSampleId) {
            return reject(ErrorCode.CLOCK_UNCERTAIN, "Referenced clock estimate is not available")
        }
        if (!input.verdict.acceptable) return reject(ErrorCode.CLOCK_UNCERTAIN, input.verdict.reason)

        val nowController = estimate.toController(input.nowLocalUs)
        if (nowController > start.acceptBeforeControllerUs ||
            start.startAtControllerUs - nowController < tuning.minAcceptLeadUs
        ) {
            return reject(ErrorCode.TOO_LATE, "Start arrived ${(nowController - start.acceptBeforeControllerUs) / 1000} ms after its acceptance window")
        }

        // Freeze the translated deadline now; later clock updates must not move it.
        val localDeadline = estimate.toLocal(start.startAtControllerUs)
        return Transition(
            state.copy(
                playback = PlaybackState.ARMED,
                armedCommandId = id,
                armedDeadlineLocalUs = localDeadline,
            ),
            listOf(
                SessionEffect.ArmStart(id, localDeadline, state.lifecycleGeneration),
                SessionEffect.SendStartAccepted(id, start.startAtControllerUs, localDeadline, estimate.id),
                SessionEffect.Record(run, RunRecordKind.ACCEPTED, deadlineLocalUs = localDeadline),
            ),
        )
    }

    private fun deadline(state: HeadsetSessionState, input: SessionInput.DeadlineReached): Transition {
        if (state.playback != PlaybackState.ARMED || state.armedCommandId != input.armId) {
            return Transition(state, emptyList()) // Disarmed or superseded schedule.
        }
        val deadline = state.armedDeadlineLocalUs!!
        val run = state.run!!
        if (input.nowLocalUs < deadline) {
            // Woke early: re-arm for the remainder rather than starting ahead of the group.
            return Transition(state, listOf(SessionEffect.ArmStart(input.armId, deadline, state.lifecycleGeneration)))
        }
        val lateness = input.nowLocalUs - deadline
        if (lateness > tuning.maxSchedulerLatenessUs) {
            val error = ErrorInfo(ErrorCode.TOO_LATE, "Missed start deadline by ${lateness / 1000} ms")
            return toError(state, run, error)
        }
        if (!state.appActive) return toError(state, run, ErrorInfo(ErrorCode.NOT_ACTIVE, "Headset became inactive before start"))
        return Transition(
            state.copy(playback = PlaybackState.PLAYING, armedCommandId = null, prepareToken = null),
            listOf(
                SessionEffect.RevealAndPlayFromZero,
                SessionEffect.Record(
                    run, RunRecordKind.STARTED,
                    deadlineLocalUs = deadline, callbackLocalUs = input.nowLocalUs,
                ),
            ),
        )
    }

    // --- Playback outcomes ----------------------------------------------------------------------

    private fun completed(state: HeadsetSessionState, input: SessionInput.PlaybackCompleted): Transition {
        if (state.playback != PlaybackState.PLAYING) return Transition(state, emptyList())
        val run = state.run!!
        // Record the terminal result before returning the UI to standby; never replay.
        return Transition(
            standby(state),
            listOf(
                SessionEffect.Record(run, RunRecordKind.COMPLETED, durationUs = input.durationUs),
                SessionEffect.ShowStandby,
                SessionEffect.ReleasePlayer,
            ),
        )
    }

    private fun failed(state: HeadsetSessionState, input: SessionInput.PlaybackFailed): Transition {
        val run = state.run
        return when (state.playback) {
            PlaybackState.PLAYING, PlaybackState.PAUSED, PlaybackState.ARMED -> toError(state, run!!, input.error)
            PlaybackState.PREPARING, PlaybackState.READY -> Transition(
                state.copy(playback = PlaybackState.ERROR, prepareToken = null, playerPrepared = false, lastError = input.error),
                listOf(SessionEffect.ReleasePlayer, SessionEffect.ShowStandby, SessionEffect.SendNotReady(input.error)),
            )
            else -> Transition(state, emptyList())
        }
    }

    // --- Explicit operator controls -------------------------------------------------------------

    private fun control(state: HeadsetSessionState, input: SessionInput.ControlCommand): Transition {
        val id = input.meta.messageId
        val type = input.type
        fun reject(code: ErrorCode, detail: String? = null) = Transition(
            state, listOf(SessionEffect.SendControlResult(id, type, false, ErrorInfo(code, detail))),
        )
        fun accept(next: HeadsetSessionState, vararg effects: SessionEffect) = Transition(
            next, effects.toList() + SessionEffect.SendControlResult(id, type, true),
        )

        val run = state.run
        if (run == null || run.runId != input.meta.runId || run.generation != input.meta.generation) {
            return reject(ErrorCode.STALE_RUN)
        }
        val now = input.nowControllerUs ?: return reject(ErrorCode.CLOCK_UNCERTAIN, "No clock mapping")
        if (now > input.control.validUntilControllerUs) return reject(ErrorCode.TOO_LATE, "Control expired")

        return when (type) {
            MessageType.PAUSE -> when (state.playback) {
                PlaybackState.PLAYING -> accept(
                    state.copy(playback = PlaybackState.PAUSED, pausedByLifecycle = false),
                    SessionEffect.PausePlayer,
                    SessionEffect.Record(run, RunRecordKind.PAUSED),
                )
                else -> reject(ErrorCode.INVALID_STATE, "Not playing (${state.playback})")
            }
            MessageType.RESUME -> when {
                state.playback != PlaybackState.PAUSED -> reject(ErrorCode.INVALID_STATE, "Not paused (${state.playback})")
                !state.appActive -> reject(ErrorCode.NOT_ACTIVE)
                else -> accept(
                    state.copy(playback = PlaybackState.PLAYING, pausedByLifecycle = false),
                    SessionEffect.ResumePlayer,
                    SessionEffect.Record(run, RunRecordKind.RESUMED),
                )
            }
            MessageType.STOP -> when (state.playback) {
                PlaybackState.PLAYING, PlaybackState.PAUSED -> accept(
                    standby(state),
                    SessionEffect.Record(run, RunRecordKind.STOPPED),
                    SessionEffect.ShowStandby,
                    SessionEffect.ReleasePlayer,
                )
                PlaybackState.ARMED -> accept(
                    standby(state),
                    SessionEffect.DisarmStart(state.armedCommandId!!),
                    SessionEffect.Record(run, RunRecordKind.STOPPED, detail = "Stopped before start"),
                    SessionEffect.ShowStandby,
                    SessionEffect.ReleasePlayer,
                )
                PlaybackState.PREPARING, PlaybackState.READY, PlaybackState.ERROR -> accept(
                    standby(state), SessionEffect.ShowStandby, SessionEffect.ReleasePlayer,
                )
                PlaybackState.STANDBY -> accept(state)
            }
            MessageType.CANCEL_START -> when (state.playback) {
                PlaybackState.ARMED -> accept(
                    standby(state),
                    SessionEffect.DisarmStart(state.armedCommandId!!),
                    SessionEffect.Record(run, RunRecordKind.STOPPED, detail = "Start cancelled"),
                    SessionEffect.ShowStandby,
                    SessionEffect.ReleasePlayer,
                )
                PlaybackState.PLAYING, PlaybackState.PAUSED -> reject(ErrorCode.INVALID_STATE, "Already started")
                else -> reject(ErrorCode.INVALID_STATE, "No armed start (${state.playback})")
            }
            else -> reject(ErrorCode.UNKNOWN_COMMAND, type)
        }
    }

    // --- Lifecycle ------------------------------------------------------------------------------

    private fun lifecycle(state: HeadsetSessionState, input: SessionInput.LifecycleChanged): Transition {
        if (input.active == state.appActive) return Transition(state, emptyList())
        if (input.active) {
            // Never autoplay on wake: restore activity only. A lifecycle-paused run stays paused.
            val next = state.copy(appActive = true)
            return Transition(next, emptyList())
        }
        val next = state.copy(appActive = false, lifecycleGeneration = state.lifecycleGeneration + 1)
        val notActive = ErrorInfo(ErrorCode.NOT_ACTIVE, "Headset app lost presentation")
        return when (state.playback) {
            PlaybackState.PREPARING, PlaybackState.READY -> Transition(
                standby(next),
                listOf(SessionEffect.ReleasePlayer, SessionEffect.ShowStandby, SessionEffect.SendNotReady(notActive)),
            )
            PlaybackState.ARMED -> toError(next, state.run!!, ErrorInfo(ErrorCode.INTERRUPTED, "Lifecycle loss while armed"))
            PlaybackState.PLAYING -> Transition(
                next.copy(playback = PlaybackState.PAUSED, pausedByLifecycle = true),
                listOf(
                    SessionEffect.PausePlayer,
                    SessionEffect.Record(state.run!!, RunRecordKind.INTERRUPTED, detail = "Paused by headset lifecycle"),
                ),
            )
            else -> Transition(next, emptyList())
        }
    }

    private fun reset(state: HeadsetSessionState): Transition =
        if (state.playback == PlaybackState.ERROR) {
            Transition(standby(state), listOf(SessionEffect.ShowStandby))
        } else {
            Transition(state, emptyList())
        }

    // --- Helpers --------------------------------------------------------------------------------

    private fun toError(state: HeadsetSessionState, run: RunContext, error: ErrorInfo): Transition {
        val effects = mutableListOf<SessionEffect>()
        state.armedCommandId?.let { effects += SessionEffect.DisarmStart(it) }
        effects += SessionEffect.Record(run, RunRecordKind.ERROR, error = error)
        effects += SessionEffect.ShowStandby
        effects += SessionEffect.ReleasePlayer
        return Transition(
            state.copy(
                playback = PlaybackState.ERROR,
                armedCommandId = null,
                armedDeadlineLocalUs = null,
                prepareToken = null,
                playerPrepared = false,
                lastError = error,
            ),
            effects,
        )
    }

    private fun standby(state: HeadsetSessionState) = state.copy(
        playback = PlaybackState.STANDBY,
        prepareToken = null,
        playerPrepared = false,
        armedCommandId = null,
        armedDeadlineLocalUs = null,
        pausedByLifecycle = false,
        lastError = null,
    )

    companion object {
        val ACTIVE_RUN_STATES = setOf(PlaybackState.ARMED, PlaybackState.PLAYING, PlaybackState.PAUSED)
    }
}
