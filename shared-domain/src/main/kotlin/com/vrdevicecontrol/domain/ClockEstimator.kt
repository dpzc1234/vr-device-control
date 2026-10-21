package com.vrdevicecontrol.domain

import kotlin.math.abs

/**
 * One four-timestamp exchange initiated by the headset.
 * `h1` headset send, `c2` controller receive, `c3` controller send, `h4` headset receive.
 */
data class ClockSample(
    val probeId: String,
    val h1: Long,
    val c2: Long,
    val c3: Long,
    val h4: Long,
) {
    /** offset = controller − headset. */
    val offsetUs: Long get() = ((c2 - h1) + (c3 - h4)) / 2
    val rttUs: Long get() = (h4 - h1) - (c3 - c2)
    val isValid: Boolean get() = h4 >= h1 && c3 >= c2 && rttUs >= 0
}

/**
 * Mapping from headset monotonic time to controller monotonic time. A heuristic under asymmetric
 * paths, not proof of accuracy.
 */
data class ClockEstimate(
    val id: String,
    val offsetUs: Long,
    /** Half the best round trip plus offset dispersion among the samples used. */
    val baseUncertaintyUs: Long,
    val rttUs: Long,
    val sampleCount: Int,
    val createdAtLocalUs: Long,
) {
    fun toController(localUs: Long): Long = localUs + offsetUs
    fun toLocal(controllerUs: Long): Long = controllerUs - offsetUs
    fun ageUs(nowLocalUs: Long): Long = nowLocalUs - createdAtLocalUs
    fun uncertaintyUs(nowLocalUs: Long, driftPpm: Long): Long =
        baseUncertaintyUs + ageUs(nowLocalUs).coerceAtLeast(0) * driftPpm / 1_000_000
}

data class ClockVerdict(val acceptable: Boolean, val reason: String? = null)

class ClockEstimator(private val tuning: Tuning = Tuning()) {

    /**
     * Uses the median offset of the lowest-RTT half of the valid samples. Returns null when there
     * are too few valid samples to say anything useful.
     */
    fun estimate(samples: List<ClockSample>, id: String, nowLocalUs: Long): ClockEstimate? {
        val valid = samples.filter { it.isValid && it.rttUs <= tuning.clockMaxSampleRttUs }
        if (valid.size < tuning.clockMinValidSamples) return null
        val best = valid.sortedBy { it.rttUs }.take(maxOf(3, valid.size / 2))
        val offsets = best.map { it.offsetUs }.sorted()
        val median = if (offsets.size % 2 == 1) {
            offsets[offsets.size / 2]
        } else {
            (offsets[offsets.size / 2 - 1] + offsets[offsets.size / 2]) / 2
        }
        val dispersion = best.maxOf { abs(it.offsetUs - median) }
        val bestRtt = best.first().rttUs
        return ClockEstimate(
            id = id,
            offsetUs = median,
            baseUncertaintyUs = bestRtt / 2 + dispersion,
            rttUs = bestRtt,
            sampleCount = best.size,
            createdAtLocalUs = nowLocalUs,
        )
    }

    fun verdict(estimate: ClockEstimate?, nowLocalUs: Long): ClockVerdict {
        if (estimate == null) return ClockVerdict(false, "No clock estimate yet")
        val age = estimate.ageUs(nowLocalUs)
        if (age < 0) return ClockVerdict(false, "Estimate from the future")
        if (age > tuning.clockMaxEstimateAgeUs) return ClockVerdict(false, "Clock estimate is stale")
        val uncertainty = estimate.uncertaintyUs(nowLocalUs, tuning.clockDriftPpm)
        if (uncertainty > tuning.clockMaxUncertaintyUs) {
            return ClockVerdict(false, "Clock uncertainty ${uncertainty / 1000} ms exceeds ${tuning.clockMaxUncertaintyUs / 1000} ms")
        }
        return ClockVerdict(true)
    }
}
