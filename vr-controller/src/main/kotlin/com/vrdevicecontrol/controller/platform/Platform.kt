package com.vrdevicecontrol.controller.platform

import android.os.SystemClock
import com.vrdevicecontrol.domain.MonotonicClock
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

object ElapsedRealtimeClock : MonotonicClock {
    override fun nowUs(): Long = SystemClock.elapsedRealtimeNanos() / 1_000
}

fun utcNow(): String = Instant.now().toString()

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

/**
 * A small persisted value: in-memory [StateFlow] plus atomic JSON snapshots on a single writer
 * thread. Use [saveNow] where intent must be durable before acting (e.g. before dispatching a start).
 */
class JsonStore<T>(
    private val file: File,
    private val serializer: KSerializer<T>,
    private val json: Json,
    default: () -> T,
) {
    private val writer = Executors.newSingleThreadExecutor()
    private val _value = MutableStateFlow(
        runCatching { json.decodeFromString(serializer, file.readText()) }.getOrElse { default() },
    )
    val value: StateFlow<T> = _value.asStateFlow()
    val current: T get() = _value.value

    @Synchronized
    fun update(block: (T) -> T): T {
        val next = block(_value.value)
        if (next != _value.value) {
            _value.value = next
            val text = json.encodeToString(serializer, next)
            writer.execute { file.writeAtomically(text) }
        }
        return next
    }

    @Synchronized
    fun saveNow(block: (T) -> T): T {
        val next = block(_value.value)
        _value.value = next
        file.writeAtomically(json.encodeToString(serializer, next))
        return next
    }
}
