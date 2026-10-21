package com.vrdevicecontrol.domain

/**
 * Monotonic time source in integer microseconds. On Android this wraps
 * `SystemClock.elapsedRealtimeNanos()`, which keeps counting during deep sleep. Never use wall
 * clock time for synchronization.
 */
fun interface MonotonicClock {
    fun nowUs(): Long
}

/**
 * Engineering starting points from the plan. They are configurable and must be tuned from
 * hardware measurement; they are not performance claims.
 */
data class Tuning(
    val heartbeatIntervalMs: Long = 2_000,
    val staleAfterMs: Long = 6_000,
    val reconnectMinMs: Long = 500,
    val reconnectMaxMs: Long = 15_000,
    val clockProbeCount: Int = 12,
    val clockProbeSpacingMs: Long = 100,
    val clockMinValidSamples: Int = 5,
    val clockMaxSampleRttUs: Long = 250_000,
    val clockMaxEstimateAgeUs: Long = 10_000_000,
    val clockMaxUncertaintyUs: Long = 20_000,
    /** Assumed worst-case oscillator drift between devices, used to age an estimate. */
    val clockDriftPpm: Long = 100,
    val startLeadUs: Long = 2_000_000,
    val minAcceptLeadUs: Long = 500_000,
    val maxSchedulerLatenessUs: Long = 40_000,
    val prepareAcceptWindowUs: Long = 5_000_000,
    val controlAcceptWindowUs: Long = 3_000_000,
    val maxConcurrentFleetTransfers: Int = 2,
    val maxConcurrentDeviceTransfers: Int = 1,
    val storageReserveBytes: Long = 2L * 1024 * 1024 * 1024,
)
