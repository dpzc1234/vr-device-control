package com.vrdevicecontrol.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClockEstimatorTest {
    private val tuning = Tuning()
    private val estimator = ClockEstimator(tuning)

    /** Builds a sample for a controller clock that runs [offset] µs ahead of the headset. */
    private fun sample(i: Int, h1: Long, offset: Long, upUs: Long, downUs: Long, serviceUs: Long = 50): ClockSample {
        val c2 = h1 + upUs + offset
        val c3 = c2 + serviceUs
        val h4 = c3 - offset + downUs
        return ClockSample("p$i", h1, c2, c3, h4)
    }

    @Test
    fun `recovers a known offset across very different device uptimes`() {
        val offset = 987_654_321_000L // controller booted ~11 days before the headset
        val samples = (0 until 12).map { sample(it, 5_000_000L + it * 100_000L, offset, 2_000, 2_000) }
        val est = assertNotNull(estimator.estimate(samples, "e1", nowLocalUs = 6_300_000))
        assertEquals(offset, est.offsetUs)
        assertEquals(2_000, est.baseUncertaintyUs)
        assertEquals(1_000_000L + offset, est.toController(1_000_000L))
        assertEquals(1_000_000L, est.toLocal(est.toController(1_000_000L)))
    }

    @Test
    fun `negative offsets are handled`() {
        val samples = (0 until 12).map { sample(it, 9_000_000_000L + it * 100_000L, -8_999_000_000L, 1_500, 1_500) }
        assertEquals(-8_999_000_000L, estimator.estimate(samples, "e", 9_002_000_000L)!!.offsetUs)
    }

    @Test
    fun `asymmetric paths bias the offset by half the asymmetry and are reflected in uncertainty`() {
        val samples = (0 until 12).map { sample(it, it * 100_000L, 0, upUs = 6_000, downUs = 2_000) }
        val est = estimator.estimate(samples, "e", 2_000_000)!!
        assertEquals(2_000, est.offsetUs) // (6000 − 2000) / 2 error, inherent to the method
        assertTrue(est.baseUncertaintyUs >= 2_000, "uncertainty must cover the possible asymmetry error")
    }

    @Test
    fun `low rtt samples win over congested outliers`() {
        val good = (0 until 6).map { sample(it, it * 100_000L, 10_000, 1_000, 1_000) }
        val congested = (6 until 12).map { sample(it, it * 100_000L, 10_000, 80_000, 1_000) }
        val est = estimator.estimate(good + congested, "e", 2_000_000)!!
        assertEquals(10_000, est.offsetUs)
        assertEquals(2_000, est.rttUs)
    }

    @Test
    fun `invalid and negative delay samples are rejected`() {
        val invalid = listOf(
            ClockSample("a", h1 = 100, c2 = 50, c3 = 40, h4 = 200), // controller replied before receiving
            ClockSample("b", h1 = 300, c2 = 0, c3 = 0, h4 = 200), // headset received before sending
            ClockSample("c", h1 = 0, c2 = 0, c3 = 1_000, h4 = 10), // negative rtt
        )
        invalid.forEach { assertFalse(it.isValid, it.probeId) }
        val few = (0 until 3).map { sample(it, it * 100_000L, 0, 1_000, 1_000) }
        assertNull(estimator.estimate(invalid + few, "e", 1_000_000), "too few valid samples")
    }

    @Test
    fun `verdict ages estimates and enforces thresholds`() {
        val samples = (0 until 12).map { sample(it, it * 100_000L, 0, 3_000, 3_000) }
        val est = estimator.estimate(samples, "e", 1_200_000)!!
        assertTrue(estimator.verdict(est, 1_200_000).acceptable)
        assertFalse(estimator.verdict(est, 1_200_000 + tuning.clockMaxEstimateAgeUs + 1).acceptable, "stale")
        assertFalse(estimator.verdict(null, 0).acceptable)
        assertFalse(estimator.verdict(est, 0).acceptable, "estimate from the future")

        val noisy = (0 until 12).map { sample(it, it * 100_000L, 0, 30_000, 30_000) }
        val wide = estimator.estimate(noisy, "w", 1_200_000)!!
        assertFalse(estimator.verdict(wide, 1_200_000).acceptable, "30 ms half-RTT exceeds 20 ms budget")
    }
}
