package com.vrdevicecontrol.headset.session

import android.os.Handler
import android.os.SystemClock
import com.vrdevicecontrol.domain.MonotonicClock

/**
 * Executes an accepted start at a frozen local monotonic deadline on the player looper.
 *
 * `Handler` delays use uptime, which pauses in deep sleep, while the deadline is in elapsed-realtime
 * microseconds. So the scheduler only ever posts *relative* waits and re-checks the monotonic clock
 * on every wake. The last [FINE_WINDOW_US] is covered by a short re-post and, finally, a bounded
 * spin of at most [MAX_SPIN_US]; there is no unbounded busy-wait, alarm or wake lock.
 */
class StartScheduler(
    private val handler: Handler,
    private val clock: MonotonicClock,
) {
    private var armedId: String? = null
    private var callback: Runnable? = null

    fun arm(armId: String, deadlineUs: Long, onDue: (armId: String, nowUs: Long) -> Unit) {
        disarm(armedId ?: armId)
        armedId = armId
        schedule(armId, deadlineUs, onDue)
    }

    fun disarm(armId: String) {
        if (armedId != armId) return
        callback?.let(handler::removeCallbacks)
        callback = null
        armedId = null
    }

    private fun schedule(armId: String, deadlineUs: Long, onDue: (String, Long) -> Unit) {
        val runnable = Runnable {
            if (armedId != armId) return@Runnable
            var now = clock.nowUs()
            val remaining = deadlineUs - now
            when {
                remaining > FINE_WINDOW_US -> schedule(armId, deadlineUs, onDue)
                remaining > MAX_SPIN_US -> schedule(armId, deadlineUs, onDue)
                else -> {
                    while (now < deadlineUs) now = clock.nowUs() // ≤ MAX_SPIN_US
                    armedId = null
                    callback = null
                    onDue(armId, now)
                }
            }
        }
        callback = runnable
        val remainingUs = deadlineUs - clock.nowUs()
        val delayMs = when {
            remainingUs > FINE_WINDOW_US -> (remainingUs - FINE_WINDOW_US) / 1_000
            remainingUs > MAX_SPIN_US -> (remainingUs - MAX_SPIN_US) / 1_000
            else -> 0
        }
        if (delayMs <= 0) handler.post(runnable) else handler.postAtTime(runnable, SystemClock.uptimeMillis() + delayMs)
    }

    private companion object {
        const val FINE_WINDOW_US = 15_000L
        const val MAX_SPIN_US = 2_000L
    }
}
