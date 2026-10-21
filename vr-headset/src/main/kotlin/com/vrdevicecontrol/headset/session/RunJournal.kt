package com.vrdevicecontrol.headset.session

import com.vrdevicecontrol.domain.MonotonicClock
import com.vrdevicecontrol.domain.RunContext
import com.vrdevicecontrol.headset.platform.utcNow
import com.vrdevicecontrol.headset.platform.writeAtomically
import com.vrdevicecontrol.protocol.ErrorCode
import com.vrdevicecontrol.protocol.ErrorInfo
import com.vrdevicecontrol.protocol.RunRecord
import com.vrdevicecontrol.protocol.RunRecordKind
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class JournalFile(
    val records: List<RunRecord> = emptyList(),
    val acknowledged: Set<String> = emptySet(),
    val nextSequence: Long = 0,
)

/**
 * Bounded durable run-result log and upload outbox. Writes happen on a single background thread so
 * the player looper never blocks on disk.
 */
class RunJournal(
    private val file: File,
    private val json: Json,
    private val clock: MonotonicClock,
    private val processInstanceId: String,
    private val capacity: Int = 400,
) {
    private val writer = Executors.newSingleThreadExecutor()
    private var data: JournalFile = runCatching { json.decodeFromString(JournalFile.serializer(), file.readText()) }
        .getOrDefault(JournalFile())

    @Synchronized
    fun append(
        run: RunContext,
        kind: RunRecordKind,
        positionUs: Long = 0,
        detail: String? = null,
        error: ErrorInfo? = null,
    ): RunRecord {
        val record = RunRecord(
            recordId = UUID.randomUUID().toString(),
            runId = run.runId,
            generation = run.generation,
            controllerEpoch = run.controllerEpoch,
            processInstanceId = processInstanceId,
            sequence = data.nextSequence,
            kind = kind,
            atUtc = utcNow(),
            monotonicUs = clock.nowUs(),
            positionUs = positionUs,
            detail = detail,
            error = error,
        )
        data = trim(data.copy(records = data.records + record, nextSequence = data.nextSequence + 1))
        persist()
        return record
    }

    @Synchronized
    fun outbox(limit: Int = 50): List<RunRecord> = data.records.filter { it.recordId !in data.acknowledged }.take(limit)

    @Synchronized
    fun acknowledge(ids: Collection<String>) {
        val known = data.records.map { it.recordId }.toSet()
        data = trim(data.copy(acknowledged = data.acknowledged + ids.filter { it in known }))
        persist()
    }

    @Synchronized
    fun recent(limit: Int = 20): List<RunRecord> = data.records.takeLast(limit)

    /**
     * On process launch, any run whose last record is not terminal was interrupted by process death.
     * Record that; never replay its start.
     */
    @Synchronized
    fun markUnfinishedInterrupted() {
        val lastByRun = data.records.groupBy { it.runId to it.generation }.mapValues { (_, v) -> v.maxBy { it.sequence } }
        lastByRun.values.filter { it.kind !in TERMINAL }.forEach { last ->
            append(
                RunContext(last.runId, last.generation, last.controllerEpoch, PLACEHOLDER_ASSET, PLACEHOLDER_PRESENTATION),
                RunRecordKind.ERROR,
                positionUs = last.positionUs,
                detail = "Headset app restarted during run (previous process ${last.processInstanceId.take(8)})",
                error = ErrorInfo(ErrorCode.INTERRUPTED, "App restarted"),
            )
        }
    }

    private fun trim(j: JournalFile): JournalFile {
        if (j.records.size <= capacity) return j
        // Drop acknowledged records first, then the oldest.
        val excess = j.records.size - capacity
        val ackedOldest = j.records.filter { it.recordId in j.acknowledged }.take(excess).map { it.recordId }.toSet()
        var kept = j.records.filterNot { it.recordId in ackedOldest }
        if (kept.size > capacity) kept = kept.takeLast(capacity)
        val ids = kept.map { it.recordId }.toSet()
        return j.copy(records = kept, acknowledged = j.acknowledged.filter { it in ids }.toSet())
    }

    private fun persist() {
        val snapshot = json.encodeToString(JournalFile.serializer(), data)
        writer.execute { file.writeAtomically(snapshot) }
    }

    private companion object {
        val TERMINAL = setOf(RunRecordKind.COMPLETED, RunRecordKind.STOPPED, RunRecordKind.ERROR)
        val PLACEHOLDER_ASSET = com.vrdevicecontrol.protocol.AssetDescriptor("", "", 0, "", "")
        val PLACEHOLDER_PRESENTATION = com.vrdevicecontrol.protocol.PresentationDescriptor(
            "", 0, com.vrdevicecontrol.protocol.Projection.FLAT, com.vrdevicecontrol.protocol.StereoLayout.MONO,
        )
    }
}
