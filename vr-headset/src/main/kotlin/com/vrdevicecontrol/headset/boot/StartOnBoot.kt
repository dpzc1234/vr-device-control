package com.vrdevicecontrol.headset.boot

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Persists the operator's "start on boot" preference and mirrors it onto the [BootReceiver]
 * component so the system delivers (or withholds) [android.content.Intent.ACTION_BOOT_COMPLETED].
 * Default is on: a freshly installed headset launches the player after reboot once the app has
 * been opened at least once (Android's stopped-state rule).
 */
object StartOnBoot {
    private const val PREFS = "settings"
    private const val KEY = "startOnBoot"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, enabled).apply()
        applyComponent(context, enabled)
    }

    /** Re-apply the stored preference to the receiver component (e.g. after an app update). */
    fun syncComponent(context: Context) = applyComponent(context, isEnabled(context))

    private fun applyComponent(context: Context, enabled: Boolean) {
        val pm = context.packageManager
        val component = ComponentName(context, BootReceiver::class.java)
        val state = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        pm.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
    }
}
