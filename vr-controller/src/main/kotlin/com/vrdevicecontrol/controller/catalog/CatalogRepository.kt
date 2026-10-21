package com.vrdevicecontrol.controller.catalog

import android.content.ContentResolver
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.vrdevicecontrol.controller.diagnostics.EventLog
import com.vrdevicecontrol.controller.platform.JsonStore
import com.vrdevicecontrol.controller.platform.utcNow
import com.vrdevicecontrol.protocol.AssetDescriptor
import com.vrdevicecontrol.protocol.EyeOrder
import com.vrdevicecontrol.protocol.PresentationDescriptor
import com.vrdevicecontrol.protocol.Projection
import com.vrdevicecontrol.protocol.StereoLayout
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.coroutines.coroutineContext

@Serializable
data class CatalogEntry(
    val asset: AssetDescriptor,
    val presentation: PresentationDescriptor,
    /** False until the operator has checked the suggested projection/stereo layout. */
    val presentationConfirmed: Boolean,
    val originalName: String,
    val importedAtUtc: String,
    val hasThumbnail: Boolean,
)

@Serializable
private data class CatalogData(val entries: List<CatalogEntry> = emptyList())

data class ImportProgress(val name: String, val copiedBytes: Long, val totalBytes: Long?, val phase: String)

/**
 * Tablet video library. Imports stream the user-selected document into app-owned storage while
 * hashing it, so no persisted URI grant or removable drive is needed during operation. Bytes are
 * immutable and deduplicated by SHA-256; presentation settings are revisioned separately.
 */
class CatalogRepository(
    private val resolver: ContentResolver,
    filesDir: File,
    json: Json,
    private val log: EventLog,
) {
    private val mediaDir = File(filesDir, "media").apply { mkdirs() }
    private val thumbDir = File(filesDir, "thumbs").apply { mkdirs() }
    private val store = JsonStore(File(filesDir, "catalog.json"), CatalogData.serializer(), json) { CatalogData() }

    val entries: StateFlow<List<CatalogEntry>> get() = _entries
    private val _entries = MutableStateFlow(store.current.entries)

    private val _import = MutableStateFlow<ImportProgress?>(null)
    val importProgress: StateFlow<ImportProgress?> = _import.asStateFlow()

    fun entry(assetId: String) = _entries.value.firstOrNull { it.asset.assetId == assetId }
    fun entryBySha(sha: String) = _entries.value.firstOrNull { it.asset.sha256 == sha }

    /** Thread-safe: resolves by validated hash, never by a client-supplied path. */
    fun fileFor(sha256: String): File? {
        if (!SHA.matches(sha256)) return null
        entryBySha(sha256) ?: return null
        return File(mediaDir, "$sha256.media").takeIf { it.isFile }
    }

    fun thumbnail(assetId: String): File? = File(thumbDir, "$assetId.jpg").takeIf { it.isFile }

    suspend fun import(uri: Uri): Result<CatalogEntry> = withContext(Dispatchers.IO) {
        runCatching {
            val (name, size) = queryNameAndSize(uri)
            _import.value = ImportProgress(name, 0, size, "Copying")
            val tmp = File(mediaDir, "import-${UUID.randomUUID()}.tmp")
            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            try {
                resolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "Cannot open $name" }
                    FileOutputStream(tmp).use { out ->
                        val buffer = ByteArray(1 shl 20)
                        var lastReport = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            digest.update(buffer, 0, n)
                            copied += n
                            if (copied - lastReport > 8L shl 20) {
                                lastReport = copied
                                _import.value = ImportProgress(name, copied, size, "Copying")
                            }
                        }
                        out.fd.sync()
                    }
                }
                if (size != null && size != copied) error("Copied $copied of $size bytes")
                val sha = digest.digest().joinToString("") { "%02x".format(it) }
                entryBySha(sha)?.let { existing ->
                    tmp.delete()
                    log.log("IMPORT_DUPLICATE", detail = existing.asset.title)
                    return@runCatching existing
                }
                _import.value = ImportProgress(name, copied, size, "Reading video details")
                val final = File(mediaDir, "$sha.media")
                check(tmp.renameTo(final)) { "Could not store imported file" }
                val assetId = UUID.randomUUID().toString()
                val probe = probe(final)
                val asset = AssetDescriptor(
                    assetId = assetId,
                    sha256 = sha,
                    sizeBytes = copied,
                    title = name.substringBeforeLast('.').replace('_', ' ').trim().ifEmpty { name },
                    mimeType = probe.container,
                    durationUs = probe.durationUs,
                    width = probe.width,
                    height = probe.height,
                    videoCodec = probe.videoMime,
                    audioCodec = probe.audioMime,
                    bitrate = probe.bitrate,
                    frameRate = probe.frameRate,
                )
                val thumb = writeThumbnail(final, assetId, probe.durationUs)
                val entry = CatalogEntry(
                    asset = asset,
                    presentation = suggestPresentation(assetId, name, probe.width, probe.height),
                    presentationConfirmed = false,
                    originalName = name,
                    importedAtUtc = utcNow(),
                    hasThumbnail = thumb,
                )
                // Publish only after the copy is complete and verified to be on disk.
                mutate { it + entry }
                log.log("IMPORTED", detail = "${asset.title} ${asset.width}x${asset.height} ${asset.videoCodec} sha=${sha.take(12)}")
                entry
            } finally {
                tmp.delete()
                _import.value = null
            }
        }
    }

    fun updatePresentation(assetId: String, projection: Projection, stereo: StereoLayout, eyeOrder: EyeOrder, flatWidthMeters: Float) {
        mutate { list -> list.map { e ->
            if (e.asset.assetId != assetId) return@map e
            val p = e.presentation
            val changed = p.projection != projection || p.stereo != stereo || p.eyeOrder != eyeOrder || p.flatWidthMeters != flatWidthMeters
            e.copy(
                presentation = if (changed) {
                    p.copy(revision = p.revision + 1, projection = projection, stereo = stereo, eyeOrder = eyeOrder, flatWidthMeters = flatWidthMeters)
                } else p,
                presentationConfirmed = true,
            )
        } }
        log.log("PRESENTATION_EDITED", detail = "$assetId $projection $stereo $eyeOrder")
    }

    fun rename(assetId: String, title: String) {
        val clean = title.trim().take(80).ifEmpty { return }
        mutate { list -> list.map { if (it.asset.assetId == assetId) it.copy(asset = it.asset.copy(title = clean)) else it } }
    }

    /** Removes the tablet copy only. Headset copies are removed by explicit per-device operations. */
    fun delete(assetId: String) {
        val e = entry(assetId) ?: return
        mutate { list -> list.filterNot { it.asset.assetId == assetId } }
        File(mediaDir, "${e.asset.sha256}.media").delete()
        File(thumbDir, "$assetId.jpg").delete()
        log.log("DELETED_FROM_TABLET", detail = e.asset.title)
    }

    /** Catalog edits come from the actor and from imports on IO; each is an atomic read-modify-write. */
    @Synchronized
    private fun mutate(block: (List<CatalogEntry>) -> List<CatalogEntry>) {
        val list = block(_entries.value)
        _entries.value = list
        store.update { CatalogData(list) }
    }

    private fun queryNameAndSize(uri: Uri): Pair<String, Long?> {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val name = c.getString(0) ?: "video"
                val size = if (c.isNull(1)) null else c.getLong(1)
                return name to size
            }
        }
        return (uri.lastPathSegment ?: "video") to null
    }

    private data class Probe(
        val container: String,
        val durationUs: Long,
        val width: Int,
        val height: Int,
        val videoMime: String?,
        val audioMime: String?,
        val bitrate: Long?,
        val frameRate: Float?,
    )

    /** Advisory metadata from Android media APIs; on-headset preparation is the real test. */
    private fun probe(file: File): Probe {
        val retriever = MediaMetadataRetriever()
        var container = "video/mp4"
        var durationUs = 0L
        var width = 0
        var height = 0
        var bitrate: Long? = null
        try {
            retriever.setDataSource(file.absolutePath)
            container = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: container
            durationUs = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0) * 1000
            width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rotation == 90 || rotation == 270) { val t = width; width = height; height = t }
        } catch (_: Exception) {
        } finally {
            runCatching { retriever.release() }
        }
        var videoMime: String? = null
        var audioMime: String? = null
        var frameRate: Float? = null
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && videoMime == null) {
                    videoMime = mime
                    if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                        frameRate = runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }
                            .getOrElse { runCatching { format.getFloat(MediaFormat.KEY_FRAME_RATE) }.getOrNull() }
                    }
                    if (width == 0) width = format.getInteger(MediaFormat.KEY_WIDTH)
                    if (height == 0) height = format.getInteger(MediaFormat.KEY_HEIGHT)
                } else if (mime.startsWith("audio/") && audioMime == null) {
                    audioMime = mime
                }
            }
        } catch (_: Exception) {
        } finally {
            extractor.release()
        }
        return Probe(container, durationUs, width, height, videoMime, audioMime, bitrate, frameRate)
    }

    private fun writeThumbnail(file: File, assetId: String, durationUs: Long): Boolean {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val at = (durationUs / 10).coerceIn(0, 5_000_000)
            val frame = retriever.getScaledFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 640, 360) ?: return false
            FileOutputStream(File(thumbDir, "$assetId.jpg")).use { frame.compress(Bitmap.CompressFormat.JPEG, 82, it) }
            frame.recycle()
            true
        } catch (_: Exception) {
            false // Large 8K streams may not decode on the tablet; the UI shows a placeholder.
        } finally {
            runCatching { retriever.release() }
        }
    }

    companion object {
        private val SHA = Regex("[0-9a-f]{64}")

        /**
         * A suggestion only: the entry stays unconfirmed until the operator checks it, because
         * unusual projections must not be assumed.
         */
        fun suggestPresentation(assetId: String, name: String, width: Int, height: Int): PresentationDescriptor {
            val n = name.lowercase()
            val aspect = if (height > 0) width.toFloat() / height else 0f
            val stereo = when {
                Regex("(^|[^a-z])(tb|ou|topbottom|top_bottom|overunder)([^a-z]|$)").containsMatchIn(n) -> StereoLayout.TOP_BOTTOM
                Regex("(^|[^a-z])(sbs|lr|sidebyside|side_by_side)([^a-z]|$)").containsMatchIn(n) -> StereoLayout.SIDE_BY_SIDE
                aspect in 0.95f..1.05f -> StereoLayout.TOP_BOTTOM
                else -> StereoLayout.MONO
            }
            val projection = when {
                "180" in n -> Projection.EQUIRECT_180
                "360" in n -> Projection.EQUIRECT_360
                stereo == StereoLayout.TOP_BOTTOM && aspect in 0.95f..1.05f -> Projection.EQUIRECT_360
                aspect in 1.95f..2.05f && stereo == StereoLayout.MONO -> Projection.EQUIRECT_360
                else -> Projection.FLAT
            }
            return PresentationDescriptor(assetId, 1, projection, stereo, EyeOrder.LEFT_FIRST)
        }
    }
}
