package com.vrdevicecontrol.headset.platform

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.StatFs
import android.os.SystemClock
import com.vrdevicecontrol.domain.MonotonicClock
import java.io.File
import java.io.FileOutputStream
import java.time.Instant

/** `elapsedRealtimeNanos` keeps counting through deep sleep; uptime-based timers do not. */
object ElapsedRealtimeClock : MonotonicClock {
    override fun nowUs(): Long = SystemClock.elapsedRealtimeNanos() / 1_000
}

/** UTC only for human-readable audit timestamps. */
fun utcNow(): String = Instant.now().toString()

data class PowerAndStorage(
    val batteryPercent: Int?,
    val charging: Boolean?,
    val freeBytes: Long,
    val totalBytes: Long,
)

fun readPowerAndStorage(context: Context, dir: File): PowerAndStorage {
    val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    val stat = StatFs(dir.absolutePath)
    return PowerAndStorage(
        batteryPercent = if (level >= 0 && scale > 0) level * 100 / scale else null,
        charging = if (status < 0) null else status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
        freeBytes = stat.availableBytes,
        totalBytes = stat.totalBytes,
    )
}

/** Write-then-rename so readers never observe a torn file. */
fun File.writeAtomically(text: String) {
    val tmp = File(parentFile, "$name.tmp")
    FileOutputStream(tmp).use { out ->
        out.write(text.toByteArray(Charsets.UTF_8))
        out.fd.sync()
    }
    if (!tmp.renameTo(this)) {
        delete()
        check(tmp.renameTo(this)) { "Could not replace $this" }
    }
}
