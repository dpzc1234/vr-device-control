package com.vrdevicecontrol.domain

import com.vrdevicecontrol.protocol.AssetDescriptor
import com.vrdevicecontrol.protocol.Control
import com.vrdevicecontrol.protocol.ErrorCode
import com.vrdevicecontrol.protocol.ErrorInfo
import com.vrdevicecontrol.protocol.MessageType
import com.vrdevicecontrol.protocol.PlaybackState
import com.vrdevicecontrol.protocol.Prepare
import com.vrdevicecontrol.protocol.PresentationDescriptor
import com.vrdevicecontrol.protocol.Projection
import com.vrdevicecontrol.protocol.RunRecordKind
import com.vrdevicecontrol.protocol.StartAt
import com.vrdevicecontrol.protocol.StereoLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HeadsetSessionReducerTest {
    private val tuning = Tuning()
    private val reducer = HeadsetSessionReducer(tuning)

    private val offset = 50_000_000L // controller = local + offset
    private val estimate = ClockEstimate("clk", offset, 2_000, 4_000, 6, createdAtLocalUs = 1_000_000)
    private val asset = AssetDescriptor("asset", "a".repeat(64), 1_000, "Sample", "video/mp4", durationUs = 60_000_000)
    private val presentation = PresentationDescriptor("asset", 3, Projection.EQUIRECT_360, StereoLayout.TOP_BOTTOM)
    private val meta = CommandMeta("prep-1", "run-1", 1, "epoch-1")

    private var state = HeadsetSessionState(appActive = true)
    private var effects: List<SessionEffect> = emptyList()

    private fun send(input: SessionInput): List<SessionEffect> {
        val t = reducer.reduce(state, input)
        state = t.state
        effects = t.effects
        return effects
    }

    private fun nowController(local: Long) = local + offset

    private fun prepare(local: Long = 1_000_000, token: String = "tok-1", m: CommandMeta = meta) = send(
        SessionInput.PrepareCommand(
            meta = m,
            prepare = Prepare(asset, presentation, validUntilControllerUs = nowController(local) + 5_000_000),
            nowControllerUs = nowController(local),
            assetIssue = null,
            newToken = token,
        ),
    )

    private fun ready() {
        prepare()
        send(SessionInput.ClockChanged("clk", true))
        send(SessionInput.PlayerPrepared("tok-1"))
        assertEquals(PlaybackState.READY, state.playback)
    }

    private fun startCommand(id: String = "start-1", startAtController: Long = nowController(3_000_000), token: String = "tok-1") =
        SessionInput.StartAtCommand(
            meta = meta.copy(messageId = id),
            start = StartAt(asset.sha256, presentation.revision, token, "clk", startAtController, startAtController - 500_000),
            nowLocalUs = 1_100_000,
            estimate = estimate,
            verdict = ClockVerdict(true),
        )

    private fun rejection(): ErrorCode? =
        effects.filterIsInstance<SessionEffect.SendControlResult>().singleOrNull { !it.accepted }?.error?.code

    private fun control(type: String, local: Long = 4_000_000, id: String = "c-$type") = send(
        SessionInput.ControlCommand(meta.copy(messageId = id), type, Control(nowController(local) + 1_000_000, "clk"), nowController(local)),
    )

    @Test
    fun `prepare holds silently and becomes ready only with player and clock`() {
        prepare()
        assertEquals(PlaybackState.PREPARING, state.playback)
        assertTrue(effects.any { it is SessionEffect.PreparePlayer })
        assertTrue(effects.none { it is SessionEffect.RevealAndPlayFromZero || it is SessionEffect.SendReady })

        send(SessionInput.PlayerPrepared("tok-1"))
        assertEquals(PlaybackState.PREPARING, state.playback, "no clock yet")
        send(SessionInput.ClockChanged("clk", true))
        assertEquals(PlaybackState.READY, state.playback)
        assertEquals("tok-1", assertIs<SessionEffect.SendReady>(effects.single()).token)
    }

    @Test
    fun `prepare is rejected when inactive, expired or missing content`() {
        state = state.copy(appActive = false)
        prepare(); assertEquals(ErrorCode.NOT_ACTIVE, rejection())

        state = state.copy(appActive = true)
        send(SessionInput.PrepareCommand(meta, Prepare(asset, presentation, 0), nowControllerUs = 10, assetIssue = null, newToken = "t"))
        assertEquals(ErrorCode.TOO_LATE, rejection())

        send(SessionInput.PrepareCommand(meta, Prepare(asset, presentation, Long.MAX_VALUE), null, null, "t"))
        assertEquals(ErrorCode.CLOCK_UNCERTAIN, rejection())

        send(SessionInput.PrepareCommand(meta, Prepare(asset, presentation, Long.MAX_VALUE), 1, ErrorInfo(ErrorCode.ASSET_MISSING), "t"))
        assertEquals(ErrorCode.ASSET_MISSING, rejection())
        assertEquals(PlaybackState.STANDBY, state.playback)
    }

    @Test
    fun `accepted start freezes local deadline and plays from zero at the deadline`() {
        ready()
        send(startCommand())
        assertEquals(PlaybackState.ARMED, state.playback)
        val arm = effects.filterIsInstance<SessionEffect.ArmStart>().single()
        assertEquals(3_000_000L, arm.localDeadlineUs)
        val ack = effects.filterIsInstance<SessionEffect.SendStartAccepted>().single()
        assertEquals("start-1", ack.correlationId)

        // A later clock update must not move the armed deadline.
        send(SessionInput.ClockChanged("clk-2", true))
        assertEquals(3_000_000L, state.armedDeadlineLocalUs)

        send(SessionInput.DeadlineReached("start-1", 3_010_000))
        assertEquals(PlaybackState.PLAYING, state.playback)
        assertEquals(SessionEffect.RevealAndPlayFromZero, effects.first())
        val started = effects.filterIsInstance<SessionEffect.Record>().single()
        assertEquals(RunRecordKind.STARTED, started.kind)
        assertEquals(3_010_000L, started.callbackLocalUs)
    }

    @Test
    fun `early wake re-arms instead of starting ahead of the group`() {
        ready(); send(startCommand())
        send(SessionInput.DeadlineReached("start-1", 2_990_000))
        assertEquals(PlaybackState.ARMED, state.playback)
        assertIs<SessionEffect.ArmStart>(effects.single())
    }

    @Test
    fun `missed deadline is an error, not permission to start late`() {
        ready(); send(startCommand())
        send(SessionInput.DeadlineReached("start-1", 3_000_000 + tuning.maxSchedulerLatenessUs + 1))
        assertEquals(PlaybackState.ERROR, state.playback)
        assertTrue(effects.none { it is SessionEffect.RevealAndPlayFromZero })
        assertEquals(ErrorCode.TOO_LATE, effects.filterIsInstance<SessionEffect.Record>().single().error?.code)
        assertTrue(effects.contains(SessionEffect.ShowStandby))
    }

    @Test
    fun `start validation rejects stale, late and unsynchronized commands`() {
        ready()
        send(startCommand(token = "old-token")); assertEquals(ErrorCode.STALE_PREPARATION, rejection())
        send(startCommand().copy(meta = meta.copy(messageId = "x", generation = 0))); assertEquals(ErrorCode.STALE_RUN, rejection())
        send(startCommand().copy(meta = meta.copy(messageId = "x", controllerEpoch = "old-epoch"))); assertEquals(ErrorCode.STALE_RUN, rejection())
        send(startCommand(startAtController = nowController(1_400_000))); assertEquals(ErrorCode.TOO_LATE, rejection())
        send(startCommand().copy(verdict = ClockVerdict(false, "stale"))); assertEquals(ErrorCode.CLOCK_UNCERTAIN, rejection())
        send(startCommand().copy(estimate = null)); assertEquals(ErrorCode.CLOCK_UNCERTAIN, rejection())
        send(startCommand().copy(start = startCommand().start.copy(positionUs = 5))); assertEquals(ErrorCode.INVALID_STATE, rejection())
        assertEquals(PlaybackState.READY, state.playback, "rejections leave readiness untouched")
    }

    @Test
    fun `a second distinct start while armed is rejected`() {
        ready(); send(startCommand())
        send(startCommand(id = "start-2"))
        assertEquals(ErrorCode.ALREADY_ARMED, rejection())
        assertEquals("start-1", state.armedCommandId)
    }

    @Test
    fun `lifecycle loss invalidates readiness and tokens`() {
        ready()
        send(SessionInput.LifecycleChanged(false))
        assertEquals(PlaybackState.STANDBY, state.playback)
        assertEquals(ErrorCode.NOT_ACTIVE, effects.filterIsInstance<SessionEffect.SendNotReady>().single().error.code)
        send(SessionInput.LifecycleChanged(true))
        send(startCommand())
        assertEquals(ErrorCode.INVALID_STATE, rejection(), "wake must not restore readiness")
    }

    @Test
    fun `lifecycle loss while armed disarms with an error`() {
        ready(); send(startCommand())
        send(SessionInput.LifecycleChanged(false))
        assertEquals(PlaybackState.ERROR, state.playback)
        assertEquals("start-1", effects.filterIsInstance<SessionEffect.DisarmStart>().single().armId)
        send(SessionInput.DeadlineReached("start-1", 3_000_000))
        assertTrue(effects.isEmpty(), "a stale timer callback cannot start playback")
    }

    @Test
    fun `lifecycle interruption pauses and requires explicit resume - no autoplay on wake`() {
        ready(); send(startCommand()); send(SessionInput.DeadlineReached("start-1", 3_000_000))
        send(SessionInput.LifecycleChanged(false))
        assertEquals(PlaybackState.PAUSED, state.playback)
        assertEquals(RunRecordKind.INTERRUPTED, effects.filterIsInstance<SessionEffect.Record>().single().kind)
        send(SessionInput.LifecycleChanged(true))
        assertEquals(PlaybackState.PAUSED, state.playback)
        assertTrue(effects.isEmpty())
        control(MessageType.RESUME)
        assertEquals(PlaybackState.PLAYING, state.playback)
    }

    @Test
    fun `completion records result before returning to standby`() {
        ready(); send(startCommand()); send(SessionInput.DeadlineReached("start-1", 3_000_000))
        send(SessionInput.PlaybackCompleted(60_000_000))
        assertEquals(PlaybackState.STANDBY, state.playback)
        assertEquals(RunRecordKind.COMPLETED, assertIs<SessionEffect.Record>(effects[0]).kind)
        assertEquals(SessionEffect.ShowStandby, effects[1])
    }

    @Test
    fun `pause resume stop and cancel follow explicit rules`() {
        ready(); send(startCommand())
        control(MessageType.PAUSE); assertEquals(ErrorCode.INVALID_STATE, rejection())
        control(MessageType.CANCEL_START)
        assertEquals(PlaybackState.STANDBY, state.playback)
        assertTrue(effects.any { it is SessionEffect.DisarmStart })

        ready(); send(startCommand(id = "start-3")); send(SessionInput.DeadlineReached("start-3", 3_000_000))
        control(MessageType.CANCEL_START); assertEquals(ErrorCode.INVALID_STATE, rejection())
        control(MessageType.PAUSE); assertEquals(PlaybackState.PAUSED, state.playback)
        control(MessageType.STOP); assertEquals(PlaybackState.STANDBY, state.playback)
        assertEquals(RunRecordKind.STOPPED, effects.filterIsInstance<SessionEffect.Record>().single().kind)
    }

    @Test
    fun `expired controls and controls for other runs are rejected`() {
        ready(); send(startCommand()); send(SessionInput.DeadlineReached("start-1", 3_000_000))
        send(SessionInput.ControlCommand(meta.copy(messageId = "p"), MessageType.PAUSE, Control(validUntilControllerUs = 1), nowController(4_000_000)))
        assertEquals(ErrorCode.TOO_LATE, rejection())
        send(SessionInput.ControlCommand(meta.copy(messageId = "p2", runId = "other"), MessageType.PAUSE, Control(Long.MAX_VALUE), nowController(4_000_000)))
        assertEquals(ErrorCode.STALE_RUN, rejection())
        assertEquals(PlaybackState.PLAYING, state.playback)
    }

    @Test
    fun `prepare is refused while a run is active and repeated prepare does not rebuild`() {
        ready()
        prepare(token = "tok-2")
        assertTrue(effects.none { it is SessionEffect.PreparePlayer }, "same run & media: report ready again")
        assertTrue(effects.any { it is SessionEffect.SendReady })

        send(startCommand()); send(SessionInput.DeadlineReached("start-1", 3_000_000))
        prepare(token = "tok-3", m = meta.copy(messageId = "prep-2", runId = "run-2"))
        assertEquals(ErrorCode.INVALID_STATE, rejection())
        assertEquals(PlaybackState.PLAYING, state.playback)
    }

    @Test
    fun `superseded preparation results are ignored`() {
        prepare(token = "tok-1")
        prepare(token = "tok-2", m = meta.copy(messageId = "prep-2", generation = 2))
        send(SessionInput.ClockChanged("clk", true))
        send(SessionInput.PlayerPrepared("tok-1"))
        assertEquals(PlaybackState.PREPARING, state.playback)
        send(SessionInput.PlayerPrepareFailed("tok-1", ErrorInfo(ErrorCode.PLAYBACK_FAILED)))
        assertEquals(PlaybackState.PREPARING, state.playback)
        send(SessionInput.PlayerPrepared("tok-2"))
        assertEquals(PlaybackState.READY, state.playback)
    }
}
