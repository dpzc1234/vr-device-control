package com.vrdevicecontrol.headset.content

import com.vrdevicecontrol.domain.Tuning
import com.vrdevicecontrol.headset.net.ControllerTarget
import com.vrdevicecontrol.protocol.AssetDescriptor
import com.vrdevicecontrol.protocol.EnsureAsset
import com.vrdevicecontrol.protocol.ErrorCode
import com.vrdevicecontrol.protocol.ProtocolCodec
import com.vrdevicecontrol.protocol.RemoveAsset
import com.vrdevicecontrol.protocol.TransferProgress
import com.vrdevicecontrol.protocol.TransferState
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Collections
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer

/**
 * Exercises the resumable download path against a real local HTTP server: range resume after an
 * interrupted stream, a server that ignores Range, corrupt bytes, low space and pinned deletion.
 */
class LocalContentRepositoryTest {
    private enum class Mode { NORMAL, IGNORE_RANGE, CORRUPT }

    private val bytes = Random(7).nextBytes(600 * 1024)
    private val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private val asset = AssetDescriptor("asset", sha, bytes.size.toLong(), "Sample", "video/mp4")

    private lateinit var dir: File
    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private var mode = Mode.NORMAL
    /** Drop the connection halfway through the body of the next response only. */
    @Volatile private var cutNext = false
    private val ranges: MutableList<String?> = Collections.synchronizedList(mutableListOf())

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("content-test").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.getHeader("Authorization") != "Bearer dev.secret") return MockResponse().setResponseCode(401)
                    val range = request.getHeader("Range")
                    ranges += range
                    val start = range?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
                    val payload = when (mode) {
                        Mode.CORRUPT -> bytes.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
                        else -> bytes
                    }
                    val partial = mode != Mode.IGNORE_RANGE && start > 0
                    val body = if (partial) payload.copyOfRange(start, payload.size) else payload
                    val response = MockResponse().setResponseCode(if (partial) 206 else 200).setBody(Buffer().write(body))
                    if (partial) response.addHeader("Content-Range", "bytes $start-${payload.size - 1}/${payload.size}")
                    // Abrupt end, like Wi-Fi dropping mid-transfer.
                    if (cutNext) { cutNext = false; response.setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY) }
                    return response
                }
            }
            start()
        }
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
        scope.cancel()
        dir.deleteRecursively()
    }

    private fun repo(tuning: Tuning = Tuning(storageReserveBytes = 0)) = LocalContentRepository(
        dir, OkHttpClient(), ProtocolCodec.DefaultJson, tuning, scope, checkpointBytes = 64 * 1024, retryBaseMs = 50,
    )

    private val target = { ControllerTarget(server.url("/").toString().trimEnd('/'), "Bearer dev.secret") }

    private fun awaitTerminal(repo: LocalContentRepository): TransferProgress = runBlocking {
        withTimeout(20_000) {
            repo.transfers.first { m -> m[sha]?.state in setOf(TransferState.AVAILABLE, TransferState.FAILED) }[sha]!!
        }
    }

    @Test
    fun `downloads, verifies and publishes atomically`() {
        val repo = repo()
        runBlocking { repo.reconcile() }
        assertNull(repo.verifiedFile(sha))
        repo.ensure(EnsureAsset("op1", asset), target)
        assertEquals(TransferState.AVAILABLE, awaitTerminal(repo).state)
        val file = assertNotNull(repo.verifiedFile(sha))
        assertContentEquals(bytes, file.readBytes())
        assertEquals(listOf(sha), repo.inventory.value.map { it.sha256 })
        assertFalse(File(dir, "media/partial/$sha.part").exists(), "temporary file removed after promotion")
    }

    @Test
    fun `interrupted stream resumes with a byte range from the durable offset`() {
        cutNext = true
        val repo = repo()
        repo.ensure(EnsureAsset("op1", asset), target)
        assertEquals(TransferState.AVAILABLE, awaitTerminal(repo).state)
        assertContentEquals(bytes, repo.verifiedFile(sha)!!.readBytes())
        assertEquals("bytes=0-", ranges.first())
        val resumedFrom = ranges.drop(1).first()!!.removePrefix("bytes=").removeSuffix("-").toLong()
        assertTrue(resumedFrom >= 128 * 1024, "resumed from $resumedFrom instead of restarting")
    }

    @Test
    fun `a 200 reply to a range request restarts instead of appending`() {
        cutNext = true
        mode = Mode.IGNORE_RANGE
        val repo = repo()
        repo.ensure(EnsureAsset("op1", asset), target)
        assertEquals(TransferState.AVAILABLE, awaitTerminal(repo).state)
        assertContentEquals(bytes, repo.verifiedFile(sha)!!.readBytes())
    }

    @Test
    fun `corrupt bytes never become playable`() {
        mode = Mode.CORRUPT
        val repo = repo()
        repo.ensure(EnsureAsset("op1", asset), target)
        val result = awaitTerminal(repo)
        assertEquals(TransferState.FAILED, result.state)
        assertEquals(ErrorCode.HASH_MISMATCH, result.error?.code)
        assertNull(repo.verifiedFile(sha))
        assertTrue(repo.inventory.value.isEmpty())
    }

    @Test
    fun `insufficient space fails visibly without downloading`() {
        val repo = repo(Tuning(storageReserveBytes = Long.MAX_VALUE / 2))
        repo.ensure(EnsureAsset("op1", asset), target)
        val result = awaitTerminal(repo)
        assertEquals(ErrorCode.STORAGE_FULL, result.error?.code)
        assertTrue(ranges.isEmpty(), "no request should be made")
    }

    @Test
    fun `pinned content cannot be removed and unrelated files are never touched`() {
        val unrelated = File(dir, "media/not-ours.txt").apply { parentFile.mkdirs(); writeText("keep") }
        val repo = repo()
        repo.ensure(EnsureAsset("op1", asset), target)
        awaitTerminal(repo)
        repo.pin(sha)
        val refused = repo.remove(RemoveAsset("r1", sha))
        assertFalse(refused.ok)
        assertEquals(ErrorCode.ASSET_IN_USE, refused.error?.code)
        assertNotNull(repo.verifiedFile(sha))
        repo.unpin(sha)
        assertTrue(repo.remove(RemoveAsset("r2", sha)).ok)
        assertNull(repo.verifiedFile(sha))
        assertTrue(unrelated.exists())
    }

    @Test
    fun `launch reconciliation re-verifies an unpublished final file and drops corrupt ones`() {
        val mediaDir = File(dir, "media").apply { mkdirs() }
        File(mediaDir, "$sha.mp4").writeBytes(bytes) // Crash after rename, before inventory publish.
        val bogus = "0".repeat(64)
        File(mediaDir, "$bogus.mp4").writeBytes(byteArrayOf(1, 2, 3))
        val repo = repo()
        runBlocking { repo.reconcile() }
        assertNotNull(repo.verifiedFile(sha))
        assertNull(repo.verifiedFile(bogus))
        assertFalse(File(mediaDir, "$bogus.mp4").exists())
    }
}
