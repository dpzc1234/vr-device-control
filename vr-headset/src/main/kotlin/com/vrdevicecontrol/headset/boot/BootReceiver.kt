package com.vrdevicecontrol.headset.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.vrdevicecontrol.headset.HeadsetActivity

/** Launches the player after a headset reboot when [StartOnBoot] is enabled. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!StartOnBoot.isEnabled(context)) return
        Log.i(TAG, "BOOT_COMPLETED — launching HeadsetActivity")
        context.startActivity(
            Intent(context, HeadsetActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP,
            ),
        )
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
