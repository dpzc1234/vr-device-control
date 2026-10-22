package com.vrdevicecontrol.controller.access

import android.content.Context

/** App-private SharedPreferences; excluded from backup and device transfer (data_extraction_rules.xml). */
class PrefsAccessStore(context: Context) : AccessStore {
    private val prefs = context.getSharedPreferences("access", Context.MODE_PRIVATE)

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun getLong(key: String): Long = prefs.getLong(key, 0)

    override fun edit(values: Map<String, Any?>) {
        val editor = prefs.edit()
        values.forEach { (k, v) ->
            when (v) {
                null -> editor.remove(k)
                is String -> editor.putString(k, v)
                is Long -> editor.putLong(k, v)
                else -> error("Unsupported value for $k")
            }
        }
        // Synchronous: a lockout or new PIN must be on disk before we report the result.
        editor.commit()
    }
}
