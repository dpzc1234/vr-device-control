package com.vrdevicecontrol.domain

import kotlin.random.Random

/** Bounded exponential reconnect backoff with jitter, so a fleet does not reconnect in lockstep. */
class Backoff(
    private val minMs: Long,
    private val maxMs: Long,
    private val random: Random = Random.Default,
) {
    private var attempt = 0

    fun nextDelayMs(): Long {
        val ceiling = (minMs shl attempt.coerceAtMost(20)).coerceAtMost(maxMs)
        attempt++
        // "Equal jitter": at least half the ceiling, so retries never collapse to zero delay.
        return ceiling / 2 + random.nextLong(ceiling / 2 + 1)
    }

    fun reset() {
        attempt = 0
    }
}
