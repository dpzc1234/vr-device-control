package com.vrdevicecontrol.headset.net

import com.vrdevicecontrol.domain.ClockEstimate
import com.vrdevicecontrol.domain.ClockEstimator
import com.vrdevicecontrol.domain.ClockSample
import com.vrdevicecontrol.domain.ClockVerdict
import com.vrdevicecontrol.domain.MonotonicClock
import com.vrdevicecontrol.domain.Tuning
import com.vrdevicecontrol.protocol.ClockProbe
import com.vrdevicecontrol.protocol.ClockReply
import com.vrdevicecontrol.protocol.ClockStatus
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Estimates controller−headset offset from bursts of four-timestamp probes. Replies are handled on
 * the socket thread with the receive timestamp captured by the transport, not after UI work.
 * Estimates are retained briefly by ID so a `START_AT` can name the estimate it was based on.
 */
class ClockSynchronizer(
    private val clock: MonotonicClock,
    private val tuning: Tuning,
    private val sendProbe: (ClockProbe) -> Boolean,
) {
    private val estimator = ClockEstimator(tuning)
    private val burstLock = Mutex()
    private val pending = ConcurrentHashMap<String, Long>()
    private val samples = java.util.Collections.synchronizedList(mutableListOf<ClockSample>())
    private val retained = object : LinkedHashMap<String, ClockEstimate>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ClockEstimate>?) = size > 8
    }

    private val _latest = MutableStateFlow<ClockEstimate?>(null)
    val latest: StateFlow<ClockEstimate?> = _latest.asStateFlow()

    fun onReply(reply: ClockReply, receivedAtUs: Long) {
        val h1 = pending.remove(reply.probeId) ?: return
        if (h1 != reply.h1Us) return
        samples += ClockSample(reply.probeId, h1, reply.c2Us, reply.c3Us, receivedAtUs)
    }

    /** Runs one probe burst; returns the new estimate or null if too few probes came back. */
    suspend fun runBurst(): ClockEstimate? = burstLock.withLock {
        samples.clear()
        pending.clear()
        repeat(tuning.clockProbeCount) {
            val id = UUID.randomUUID().toString()
            val h1 = clock.nowUs()
            pending[id] = h1
            if (!sendProbe(ClockProbe(id, h1))) {
                pending.remove(id)
                return@withLock null
            }
            delay(tuning.clockProbeSpacingMs)
        }
        delay(300) // Grace for the last replies; late replies are discarded below.
        pending.clear()
        val estimate = estimator.estimate(samples.toList(), UUID.randomUUID().toString(), clock.nowUs())
        if (estimate != null) {
            synchronized(retained) { retained[estimate.id] = estimate }
            _latest.value = estimate
        }
        estimate
    }

    fun estimate(id: String): ClockEstimate? = synchronized(retained) { retained[id] }

    fun verdict(estimate: ClockEstimate? = _latest.value): ClockVerdict = estimator.verdict(estimate, clock.nowUs())

    /** Current controller time from the latest mapping, if any. */
    fun nowControllerUs(): Long? = _latest.value?.toController(clock.nowUs())

    fun status(): ClockStatus? {
        val e = _latest.value ?: return null
        val now = clock.nowUs()
        val v = estimator.verdict(e, now)
        return ClockStatus(
            estimateId = e.id,
            offsetUs = e.offsetUs,
            uncertaintyUs = e.uncertaintyUs(now, tuning.clockDriftPpm),
            rttUs = e.rttUs,
            sampleCount = e.sampleCount,
            ageMs = e.ageUs(now) / 1000,
            acceptable = v.acceptable,
            reason = v.reason,
        )
    }
}
