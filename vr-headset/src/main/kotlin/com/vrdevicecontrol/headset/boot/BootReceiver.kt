package com.vrdevicecontrol.headset.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Launches the player after a headset reboot when [StartOnBoot] is enabled. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!StartOnBoot.isEnabled(context)) return
        Log.i(TAG, "BOOT_COMPLETED — launching HeadsetActivity")
        BootLaunchGuard.startAfterBoot(context)
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
