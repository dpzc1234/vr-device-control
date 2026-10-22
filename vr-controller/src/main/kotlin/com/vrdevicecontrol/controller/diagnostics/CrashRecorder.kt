package com.vrdevicecontrol.controller.diagnostics

import android.content.Context
import android.os.Build
import com.vrdevicecontrol.controller.BuildConfig
import com.vrdevicecontrol.controller.platform.utcNow
import java.io.File

/**
 * Keeps the last uncaught crash on disk so testers without adb can see and export it from the
 * Diagnostics screen after relaunching. The previous handler still runs, so Android behaves as usual.
 */
object CrashRecorder {
    private lateinit var file: File

    fun install(context: Context) {
        file = File(context.filesDir, "last-crash.txt")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                file.writeText(
                    "${utcNow()} · thread ${thread.name}\n" +
                        "App ${BuildConfig.VERSION_NAME} · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n\n" +
                        error.stackTraceToString(),
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun read(): String? = runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull()

    fun clear() {
        runCatching { file.delete() }
    }
}
