package com.vrdevicecontrol.headset.boot

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.vrdevicecontrol.headset.HeadsetActivity

/**
 * Best-effort recovery for Horizon OS boot races where a system panel (commonly the Store) is
 * placed in front shortly after BOOT_COMPLETED. A regular APK cannot force-stop system apps, so
 * this only brings our own task back to the foreground during a short boot-only window.
 */
object BootLaunchGuard {
    private val handler = Handler(Looper.getMainLooper())
    private var generation = 0
    @Volatile private var resumed = false
    @Volatile private var taskId: Int? = null

    fun startAfterBoot(context: Context) {
        val app = context.applicationContext
        val current = ++generation
        bringPlayerForward(app, "initial boot launch")
        RETRY_DELAYS_MS.forEach { delay ->
            handler.postDelayed({
                if (generation == current && !resumed) {
                    bringPlayerForward(app, "boot focus retry after ${delay}ms")
                }
            }, delay)
        }
    }

    fun onActivityResumed(task: Int) {
        taskId = task
        resumed = true
    }

    fun onActivityPaused(task: Int) {
        if (taskId == task) resumed = false
    }

    private fun bringPlayerForward(context: Context, reason: String) {
        val existingTask = taskId
        val moved = existingTask != null && runCatching {
            context.getSystemService(ActivityManager::class.java).moveTaskToFront(existingTask, 0)
            true
        }.getOrDefault(false)
        if (moved) {
            Log.i(TAG, "Moved player task to foreground: $reason")
            return
        }

        runCatching {
            context.startActivity(
                Intent(context, HeadsetActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                ),
            )
        }.onSuccess {
            Log.i(TAG, "Requested player foreground: $reason")
        }.onFailure {
            Log.w(TAG, "Could not bring player to foreground: $reason", it)
        }
    }

    private const val TAG = "BootLaunchGuard"
    private val RETRY_DELAYS_MS = longArrayOf(3_000L, 8_000L, 15_000L)
}
