package com.vrdevicecontrol.controller.diagnostics

import android.util.Log
import com.vrdevicecontrol.controller.platform.utcNow
import com.vrdevicecontrol.domain.MonotonicClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable

@Serializable
data class LogEvent(
    val atUtc: String,
    val monotonicUs: Long,
    val type: String,
    val deviceId: String? = null,
    val runId: String? = null,
    val detail: String? = null,
)

/** Bounded structured event log for the diagnostics screen and export. Never holds credentials. */
class EventLog(private val clock: MonotonicClock, private val capacity: Int = 2_000) {
    private val _events = MutableStateFlow<List<LogEvent>>(emptyList())
    val events: StateFlow<List<LogEvent>> = _events.asStateFlow()

    fun log(type: String, deviceId: String? = null, runId: String? = null, detail: String? = null) {
        val event = LogEvent(utcNow(), clock.nowUs(), type, deviceId, runId, detail)
        Log.i("Controller", "$type device=${deviceId?.take(8)} run=${runId?.take(8)} $detail")
        _events.update { (it + event).takeLast(capacity) }
    }
}
