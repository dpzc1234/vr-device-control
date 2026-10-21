package com.vrdevicecontrol.domain

import com.vrdevicecontrol.protocol.AssetDescriptor
import com.vrdevicecontrol.protocol.ErrorCode
import com.vrdevicecontrol.protocol.ErrorInfo
import com.vrdevicecontrol.protocol.PlaybackState
import com.vrdevicecontrol.protocol.PresentationDescriptor
import com.vrdevicecontrol.protocol.Projection
import com.vrdevicecontrol.protocol.RunRecordKind
import com.vrdevicecontrol.protocol.StereoLayout
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RunRulesTest {
    private val rules = RunRules()
    private val asset = AssetDescriptor("a", "f".repeat(64), 10, "Sample", "video/mp4", durationUs = 30_000_000)
    private val presentation = PresentationDescriptor("a", 1, Projection.FLAT, StereoLayout.MONO)

    private fun draft(vararg ids: String) =
        Run("run", 1, "epoch", asset, presentation, ids.map { Participant(it) }, createdAtUtc = "t")

    private fun live(id: String, online: Boolean = true, playback: PlaybackState = PlaybackState.READY, clock: Boolean = true) =
        id to DeviceLive(id, online, playback, appActive = true, clockAcceptable = clock, hasAsset = true)

    private fun Run.allReady() = participants.fold(this) { r, p -> r.update(p.deviceId) { it.copy(prepare = PrepareStatus.READY) } }

    @Test
    fun `participants freeze at preparation`() {
        val run = draft("h1", "h2").withSelection(listOf("h1")).copy(frozen = true)
        assertEquals(listOf("h1"), run.participants.map { it.deviceId })
        assertFailsWith<IllegalStateException> { run.withSelection(listOf("h1", "h3")) }
    }

    @Test
    fun `start requires every selected participant to be ready, online and synchronized`() {
        val run = draft("h1", "h2").copy(frozen = true).allReady()
        val devices = mapOf(live("h1"), live("h2"))
        assertTrue(rules.canStart(run, devices))
        assertEquals(RunPhase.READY, rules.phase(run, devices, 0))

        val offline = mapOf(live("h1"), live("h2", online = false))
        assertFalse(rules.canStart(run, offline))
        assertEquals("Out of contact", rules.startBlockers(run, offline)["h2"])
        assertEquals(RunPhase.NEEDS_ATTENTION, rules.phase(run, offline, 0))

        val badClock = mapOf(live("h1"), live("h2", clock = false))
        assertFalse(rules.canStart(run, badClock))

        val notReady = run.update("h2") { it.copy(prepare = PrepareStatus.NOT_READY, prepareError = ErrorInfo(ErrorCode.UNSUPPORTED_MEDIA)) }
        assertEquals("Video format not supported", rules.startBlockers(notReady, devices)["h2"])
    }

    @Test
    fun `starting then uncertain when no reports arrive - acknowledgements are not playback`() {
        val startAt = 10_000_000L
        val run = draft("h1", "h2").copy(frozen = true).allReady().copy(startAtControllerUs = startAt)
            .update("h1") { it.copy(startAck = StartAck.ACCEPTED) }
            .update("h2") { it.copy(startAck = StartAck.ACCEPTED) }
        val devices = mapOf(live("h1", online = false), live("h2", online = false))
        assertEquals(RunPhase.STARTING, rules.phase(run, devices, startAt))
        assertEquals(RunPhase.UNCERTAIN, rules.phase(run, devices, startAt + RunRules.START_SETTLE_US))
        assertFalse(rules.canStart(run, devices), "no repeat start once scheduled")
    }

    @Test
    fun `observed reports drive active and finished, late records never overwrite results`() {
        val startAt = 10_000_000L
        var run = draft("h1", "h2").copy(frozen = true).allReady().copy(startAtControllerUs = startAt)
        run = run.applyRecord("h1", "run", 1, RunRecordKind.STARTED, "t1", null)
        assertEquals(RunPhase.ACTIVE, rules.phase(run, emptyMap(), startAt + 5_000_000))

        run = run.applyRecord("h1", "run", 1, RunRecordKind.COMPLETED, "t2", null)
        run = run.applyRecord("h1", "run", 1, RunRecordKind.STARTED, "t1", null) // delayed duplicate
        assertEquals(Observed.COMPLETED, run.participant("h1")!!.observed)

        run = run.applyRecord("h2", "other-run", 1, RunRecordKind.COMPLETED, "t", null)
        assertEquals(Observed.NONE, run.participant("h2")!!.observed, "records from other runs are ignored")
        assertEquals(RunPhase.UNCERTAIN, rules.phase(run, emptyMap(), startAt + 60_000_000), "offline participant stays unknown")

        run = run.applyRecord("h2", "run", 1, RunRecordKind.ERROR, "t3", ErrorInfo(ErrorCode.PLAYBACK_FAILED))
        assertEquals(RunPhase.FINISHED, rules.phase(run, emptyMap(), startAt + 60_000_000))
    }

    @Test
    fun `estimated progress is clamped to duration`() {
        val run = draft("h1").copy(startAtControllerUs = 1_000_000)
        assertEquals(null, rules.estimatedPositionUs(run, 0))
        assertEquals(5_000_000L, rules.estimatedPositionUs(run, 6_000_000))
        assertEquals(30_000_000L, rules.estimatedPositionUs(run, 99_000_000))
    }
}

class CommandDeduplicatorTest {
    @Test
    fun `duplicates return cached results and changed payloads conflict`() {
        val dedup = CommandDeduplicator<String>(capacity = 2)
        assertEquals(CommandDeduplicator.Lookup.New, dedup.lookup("m1", "fp"))
        dedup.record("m1", "fp", "accepted")
        assertEquals(CommandDeduplicator.Lookup.Duplicate("accepted"), dedup.lookup("m1", "fp"))
        assertEquals(CommandDeduplicator.Lookup.Conflict, dedup.lookup("m1", "other"))
        dedup.record("m2", "fp", "x"); dedup.record("m3", "fp", "y")
        assertEquals(CommandDeduplicator.Lookup.New, dedup.lookup("m1", "fp"), "bounded journal evicts oldest")
    }
}

class TransferPlannerTest {
    private val tuning = Tuning(storageReserveBytes = 100)
    private val planner = TransferPlanner(tuning)

    @Test
    fun `honors fleet and device limits and defers offline or running headsets`() {
        val queue = listOf(
            QueuedTransfer("o1", "h1", "s1", 10),
            QueuedTransfer("o2", "h1", "s2", 10),
            QueuedTransfer("o3", "h2", "s1", 10),
            QueuedTransfer("o4", "h3", "s1", 10),
            QueuedTransfer("o5", "h4", "s1", 10),
            QueuedTransfer("o6", "h5", "s1", 10_000),
        )
        val devices = mapOf(
            "h1" to TransferDeviceView("h1", true, false, 1_000),
            "h2" to TransferDeviceView("h2", true, true, 1_000),
            "h3" to TransferDeviceView("h3", false, false, 1_000),
            "h4" to TransferDeviceView("h4", true, false, 1_000),
            "h5" to TransferDeviceView("h5", true, false, 1_000),
        )
        val plan = planner.plan(queue, emptySet(), devices)
        assertEquals(listOf("o1", "o5"), plan.dispatch.map { it.operationId })
        assertEquals("Queued behind another transfer", plan.blocked["o2"])
        assertEquals("Deferred while headset is in a run", plan.blocked["o3"])
        assertEquals("Waiting for headset to reconnect", plan.blocked["o4"])
        assertEquals("Not enough free space on headset", plan.blocked["o6"])
    }
}

class BackoffTest {
    @Test
    fun `delays grow, stay bounded and reset`() {
        val backoff = Backoff(500, 15_000, Random(42))
        val delays = (0 until 10).map { backoff.nextDelayMs() }
        assertTrue(delays.all { it in 250..15_000 })
        assertTrue(delays.last() >= 7_500)
        backoff.reset()
        assertTrue(backoff.nextDelayMs() <= 500)
    }
}
