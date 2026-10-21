package com.vrdevicecontrol.headset.content

import android.util.Log
import com.vrdevicecontrol.domain.Backoff
import com.vrdevicecontrol.domain.Tuning
import com.vrdevicecontrol.headset.net.ControllerTarget
import com.vrdevicecontrol.headset.platform.utcNow
import com.vrdevicecontrol.headset.platform.writeAtomically
import com.vrdevicecontrol.protocol.AssetDescriptor
import com.vrdevicecontrol.protocol.AssetResult
import com.vrdevicecontrol.protocol.EnsureAsset
import com.vrdevicecontrol.protocol.Endpoints
import com.vrdevicecontrol.protocol.ErrorCode
import com.vrdevicecontrol.protocol.ErrorInfo
import com.vrdevicecontrol.protocol.InventoryItem
import com.vrdevicecontrol.protocol.RemoveAsset
import com.vrdevicecontrol.protocol.TransferProgress
import com.vrdevicecontrol.protocol.TransferState
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/** Checkpoint for a hash-bound `.part` file; only bytes flushed to disk are counted. */
@Serializable
private data class Checkpoint(val sha256: String, val totalBytes: Long, val committedBytes: Long)

class TransferFailure(val error: ErrorInfo) : Exception(error.detail)

/**
 * App-managed media library on the headset. Downloads land in `partial/<sha>.part`, are verified
 * by full SHA-256 and size, and only then are renamed into `media/<sha>.mp4` and published to the
 * inventory. Only files this repository created are ever deleted.
 */
class LocalContentRepository(
    filesDir: File,
    private val http: OkHttpClient,
    private val json: Json,
    private val tuning: Tuning,
    private val scope: CoroutineScope,
    private val checkpointBytes: Long = 16L * 1024 * 1024,
    private val retryBaseMs: Long = 1_000,
) {
    private val mediaDir = File(filesDir, "media").apply { mkdirs() }
    private val partialDir = File(mediaDir, "partial").apply { mkdirs() }
    private val inventoryFile = File(mediaDir, "inventory.json")
    private val downloadLock = Mutex()
    private val jobs = mutableMapOf<String, Job>()

    private val _inventory = MutableStateFlow<List<InventoryItem>>(emptyList())
    val inventory: StateFlow<List<InventoryItem>> = _inventory.asStateFlow()

    private val _transfers = MutableStateFlow<Map<String, TransferProgress>>(emptyMap())
    val transfers: StateFlow<Map<String, TransferProgress>> = _transfers.asStateFlow()

    /** Assets in use by a session (PREPARING through PAUSED) cannot be removed. */
    private val pinned = MutableStateFlow<Set<String>>(emptySet())

    /** True while a run is armed or playing: downloads wait so they cannot disturb playback. */
    val deferred = MutableStateFlow(false)

    var onProgress: (TransferProgress) -> Unit = {}

    val storageDir: File get() = mediaDir

    fun verifiedFile(sha256: String): File? {
        if (_inventory.value.none { it.sha256 == sha256 }) return null
        return finalFile(sha256).takeIf { it.isFile }
    }

    fun pin(sha256: String) = pinned.update { it + sha256 }
    fun unpin(sha256: String) = pinned.update { it - sha256 }

    /**
     * Launch-time reconciliation: drop inventory rows whose file vanished, re-verify final files
     * without a row (crash between rename and publish), and discard partials without checkpoints.
     */
    suspend fun reconcile() = withContext(Dispatchers.IO) {
        val listed = loadInventory().filter { finalFile(it.sha256).isFile }
        val known = listed.map { it.sha256 }.toMutableSet()
        val recovered = mutableListOf<InventoryItem>()
        mediaDir.listFiles { f -> f.isFile && f.name.endsWith(".mp4") }.orEmpty().forEach { f ->
            val sha = f.name.removeSuffix(".mp4")
            if (sha in known || !SHA_PATTERN.matches(sha)) return@forEach
            if (sha256Of(f) == sha) {
                recovered += InventoryItem(sha, f.length(), utcNow())
                known += sha
            } else {
                Log.w(TAG, "Discarding unverified media file ${f.name}")
                f.delete()
            }
        }
        partialDir.listFiles().orEmpty().forEach { f ->
            if (f.name.endsWith(".part") && !checkpointFile(f.name.removeSuffix(".part")).isFile) f.delete()
        }
        publish(listed + recovered)
    }

    fun ensure(request: EnsureAsset, target: () -> ControllerTarget?) {
        val asset = request.asset
        if (verifiedFile(asset.sha256) != null) {
            report(TransferProgress(request.operationId, asset.sha256, TransferState.AVAILABLE, asset.sizeBytes, asset.sizeBytes))
            return
        }
        synchronized(jobs) {
            if (jobs[asset.sha256]?.isActive == true) return
            report(TransferProgress(request.operationId, asset.sha256, TransferState.QUEUED, committedBytes(asset.sha256), asset.sizeBytes))
            jobs[asset.sha256] = scope.launch(Dispatchers.IO) { runTransfer(request, target) }
        }
    }

    fun cancel(sha256: String) {
        synchronized(jobs) { jobs.remove(sha256)?.cancel() }
        partFile(sha256).delete()
        checkpointFile(sha256).delete()
        _transfers.update { it - sha256 }
    }

    fun remove(request: RemoveAsset): AssetResult {
        val sha = request.sha256
        if (sha in pinned.value) {
            return AssetResult(request.operationId, sha, false, ErrorInfo(ErrorCode.ASSET_IN_USE, "Video is in use by a run"))
        }
        cancel(sha)
        val removed = _inventory.value.filterNot { it.sha256 == sha }
        publish(removed)
        finalFile(sha).delete()
        return AssetResult(request.operationId, sha, true)
    }

    private suspend fun runTransfer(request: EnsureAsset, target: () -> ControllerTarget?) {
        val asset = request.asset
        val backoff = Backoff(retryBaseMs, 30 * retryBaseMs)
        var attempts = 0
        downloadLock.withLock {
            while (true) {
                if (deferred.value) {
                    report(progress(request, TransferState.PAUSED, committedBytes(asset.sha256)))
                    deferred.first { !it }
                }
                try {
                    download(request, target() ?: throw IOException("Controller not connected"))
                    verifyAndPublish(request)
                    return@withLock
                } catch (e: TransferFailure) {
                    report(progress(request, TransferState.FAILED, committedBytes(asset.sha256), e.error))
                    return@withLock
                } catch (e: DeferredForRun) {
                    continue
                } catch (e: IOException) {
                    attempts++
                    Log.w(TAG, "Transfer of ${asset.sha256.take(12)} failed (attempt $attempts)", e)
                    if (attempts >= MAX_ATTEMPTS) {
                        report(progress(request, TransferState.FAILED, committedBytes(asset.sha256), ErrorInfo(ErrorCode.TRANSFER_FAILED, e.message)))
                        return@withLock
                    }
                    report(progress(request, TransferState.PAUSED, committedBytes(asset.sha256), ErrorInfo(ErrorCode.TRANSFER_FAILED, "Retrying: ${e.message}")))
                    delay(backoff.nextDelayMs())
                }
            }
        }
        synchronized(jobs) { jobs.remove(asset.sha256) }
    }

    private class DeferredForRun : Exception()

    private suspend fun download(request: EnsureAsset, target: ControllerTarget) {
        val asset = request.asset
        val part = partFile(asset.sha256)
        var offset = reconcilePartial(asset)
        val needed = asset.sizeBytes - offset
        if (needed > mediaDir.usableSpace - tuning.storageReserveBytes) {
            throw TransferFailure(ErrorInfo(ErrorCode.STORAGE_FULL, "Needs ${needed / MB} MB, ${mediaDir.usableSpace / MB} MB free"))
        }
        if (offset == asset.sizeBytes) return

        val etag = "\"${asset.sha256}\""
        val call = http.newCall(
            Request.Builder()
                .url(target.baseUrl + Endpoints.asset(asset.sha256))
                .header("Authorization", target.authorization)
                .header("Range", "bytes=$offset-")
                .header("If-Range", etag)
                .build(),
        )
        call.execute().use { response ->
            when (response.code) {
                206 -> {
                    val range = response.header("Content-Range") ?: throw IOException("206 without Content-Range")
                    val match = CONTENT_RANGE.matchEntire(range) ?: throw IOException("Bad Content-Range $range")
                    val (start, _, total) = match.destructured
                    if (start.toLong() != offset || total.toLong() != asset.sizeBytes) {
                        throw IOException("Unexpected range $range for offset $offset")
                    }
                }
                200 -> {
                    // Full content instead of a range: restart deliberately rather than appending.
                    offset = 0
                    RandomAccessFile(part, "rw").use { it.setLength(0) }
                    writeCheckpoint(asset, 0)
                }
                416 -> {
                    // Server says our range is unsatisfiable; local length must already be complete.
                    if (part.length() == asset.sizeBytes) return
                    RandomAccessFile(part, "rw").use { it.setLength(0) }
                    writeCheckpoint(asset, 0)
                    throw IOException("Range not satisfiable; restarting")
                }
                401, 403 -> throw TransferFailure(ErrorInfo(ErrorCode.UNAUTHORIZED, "Controller refused the download"))
                404 -> throw TransferFailure(ErrorInfo(ErrorCode.ASSET_MISSING, "Video no longer on tablet"))
                else -> throw IOException("HTTP ${response.code}")
            }
            val body = response.body ?: throw IOException("Empty body")
            FileOutputStream(part, offset > 0).use { out ->
                val input = body.byteStream()
                val buffer = ByteArray(BUFFER)
                var committed = offset
                var sinceSync = 0L
                var lastReport = 0L
                report(progress(request, TransferState.DOWNLOADING, committed))
                while (true) {
                    currentCoroutineContext().ensureActive()
                    if (deferred.value) {
                        out.flush(); out.fd.sync(); writeCheckpoint(asset, committed)
                        throw DeferredForRun()
                    }
                    val n = try {
                        input.read(buffer)
                    } catch (e: IOException) {
                        // Keep what already arrived: make it durable, then let the retry resume from here.
                        out.flush(); out.fd.sync(); writeCheckpoint(asset, committed)
                        throw e
                    }
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    committed += n
                    sinceSync += n
                    if (committed > asset.sizeBytes) throw TransferFailure(ErrorInfo(ErrorCode.HASH_MISMATCH, "More bytes than expected"))
                    if (sinceSync >= checkpointBytes) {
                        out.flush(); out.fd.sync()
                        writeCheckpoint(asset, committed) // Only after the bytes are durable.
                        sinceSync = 0
                    }
                    if (committed - lastReport >= REPORT_BYTES) {
                        lastReport = committed
                        report(progress(request, TransferState.DOWNLOADING, committed))
                    }
                }
                out.flush(); out.fd.sync()
                writeCheckpoint(asset, committed)
                if (committed != asset.sizeBytes) throw IOException("Stream ended at $committed of ${asset.sizeBytes}")
            }
        }
    }

    private fun verifyAndPublish(request: EnsureAsset) {
        val asset = request.asset
        val part = partFile(asset.sha256)
        report(progress(request, TransferState.VERIFYING, part.length()))
        val actual = sha256Of(part)
        if (part.length() != asset.sizeBytes || actual != asset.sha256) {
            part.delete(); checkpointFile(asset.sha256).delete()
            throw TransferFailure(ErrorInfo(ErrorCode.HASH_MISMATCH, "Checksum mismatch; download discarded"))
        }
        val final = finalFile(asset.sha256)
        check(part.renameTo(final)) { "Could not promote verified file" }
        checkpointFile(asset.sha256).delete()
        publish(_inventory.value.filterNot { it.sha256 == asset.sha256 } + InventoryItem(asset.sha256, asset.sizeBytes, utcNow()))
        report(progress(request, TransferState.AVAILABLE, asset.sizeBytes))
    }

    /** Truncates a partial file to its last durable checkpoint; length alone is never trusted. */
    private fun reconcilePartial(asset: AssetDescriptor): Long {
        val part = partFile(asset.sha256)
        val cp = readCheckpoint(asset.sha256)
        if (!part.isFile || cp == null || cp.totalBytes != asset.sizeBytes) {
            part.delete()
            part.createNewFile()
            writeCheckpoint(asset, 0)
            return 0
        }
        val durable = minOf(cp.committedBytes, part.length())
        if (part.length() != durable) RandomAccessFile(part, "rw").use { it.setLength(durable) }
        return durable
    }

    private fun committedBytes(sha: String) = readCheckpoint(sha)?.committedBytes ?: 0

    private fun progress(request: EnsureAsset, state: TransferState, committed: Long, error: ErrorInfo? = null) =
        TransferProgress(request.operationId, request.asset.sha256, state, committed, request.asset.sizeBytes, error)

    private fun report(progress: TransferProgress) {
        _transfers.update { it + (progress.sha256 to progress) }
        onProgress(progress)
    }

    private fun publish(items: List<InventoryItem>) {
        val sorted = items.distinctBy { it.sha256 }.sortedBy { it.sha256 }
        inventoryFile.writeAtomically(json.encodeToString(ListSerializer(InventoryItem.serializer()), sorted))
        _inventory.value = sorted
    }

    private fun loadInventory(): List<InventoryItem> =
        runCatching { json.decodeFromString(ListSerializer(InventoryItem.serializer()), inventoryFile.readText()) }
            .getOrDefault(emptyList())

    private fun writeCheckpoint(asset: AssetDescriptor, committed: Long) =
        checkpointFile(asset.sha256).writeAtomically(json.encodeToString(Checkpoint.serializer(), Checkpoint(asset.sha256, asset.sizeBytes, committed)))

    private fun readCheckpoint(sha: String): Checkpoint? =
        runCatching { json.decodeFromString(Checkpoint.serializer(), checkpointFile(sha).readText()) }.getOrNull()

    private fun finalFile(sha: String) = File(mediaDir, "$sha.mp4")
    private fun partFile(sha: String) = File(partialDir, "$sha.part")
    private fun checkpointFile(sha: String) = File(partialDir, "$sha.json")

    companion object {
        private const val TAG = "LocalContent"
        private const val MB = 1024L * 1024
        private const val BUFFER = 256 * 1024
        private const val REPORT_BYTES = 8L * MB
        private const val MAX_ATTEMPTS = 6
        private val SHA_PATTERN = Regex("[0-9a-f]{64}")
        private val CONTENT_RANGE = Regex("bytes (\\d+)-(\\d+)/(\\d+)")

        fun sha256Of(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(BUFFER)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
