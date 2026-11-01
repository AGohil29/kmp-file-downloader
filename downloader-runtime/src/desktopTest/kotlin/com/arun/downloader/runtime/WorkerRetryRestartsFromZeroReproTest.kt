package com.arun.downloader.runtime

import app.cash.sqldelight.EnumColumnAdapter
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.arun.downloader.core.model.DownloadError
import com.arun.downloader.core.model.DownloadId
import com.arun.downloader.core.model.DownloadRequest
import com.arun.downloader.core.model.DownloadState
import com.arun.downloader.core.model.DownloaderConfig
import com.arun.downloader.core.model.NetworkRequest
import com.arun.downloader.core.model.NetworkResponse
import com.arun.downloader.core.repository.NetworkTransport
import com.arun.downloader.filesystem.OkioDownloadFileSystem
import com.arun.downloader.storage.db.DownloadDatabase
import com.arun.downloader.storage.db.DownloadRecordEntity
import com.arun.downloader.storage.repository.SqlDelightDownloadRepository
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.milliseconds

/**
 * Reproduces: `DownloadWorkManagerWorker.doWork()` calls `downloader.enqueue(request)` on every
 * WorkManager attempt. `enqueue` always mints a new `DownloadId`, which owns a new
 * `<id>.part` staging file and a new DB record, so a `Result.retry()` attempt never sees the
 * partial data of the previous attempt and restarts from byte 0.
 *
 * The worker needs Android classes, so this test replays exactly what it does per attempt:
 * `enqueue(request)` then await a terminal state, using the real `DefaultFileDownloader`.
 */
class WorkerRetryRestartsFromZeroReproTest {

    private val stagingDir = "/staging".toPath()
    private val downloadDir = "/downloads".toPath()
    private val payload = "0123456789ABCDEFGHIJ".encodeToByteArray()

    private lateinit var fakeFs: FakeFileSystem
    private lateinit var repository: SqlDelightDownloadRepository
    private val scopes = mutableListOf<CoroutineScope>()
    private val seenRequests = mutableListOf<NetworkRequest>()

    @BeforeTest
    fun setup() {
        fakeFs = FakeFileSystem().apply {
            createDirectories(stagingDir)
            createDirectories(downloadDir)
        }
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        DownloadDatabase.Schema.create(driver)
        repository = SqlDelightDownloadRepository(
            database = DownloadDatabase(
                driver = driver,
                DownloadRecordEntityAdapter = DownloadRecordEntity.Adapter(
                    priorityAdapter = EnumColumnAdapter(),
                    conflict_strategyAdapter = EnumColumnAdapter()
                )
            )
        )
    }

    @AfterTest
    fun tearDown() {
        scopes.forEach { it.cancel() }
    }

    /** Attempt 1 streams 10 bytes then drops; later attempts honour Range like a real server. */
    private fun flakyServer() = object : NetworkTransport {
        override suspend fun execute(request: NetworkRequest): NetworkResponse {
            seenRequests += request
            val start = request.rangeStart?.toInt()
            if (seenRequests.size == 1) {
                val ch = ByteChannel()
                CoroutineScope(Dispatchers.Default).launch {
                    ch.writeFully(payload.copyOfRange(0, 10))
                    ch.flush()
                    delay(50.milliseconds)
                    ch.cancel(DownloadError.Retryable.ConnectionInterrupted())
                }
                return NetworkResponse(200, emptyMap(), ch, payload.size.toLong(), false, "\"v1\"", null)
            }
            return if (start != null) {
                val tail = payload.copyOfRange(start, payload.size)
                NetworkResponse(
                    206,
                    mapOf("Content-Range" to "bytes $start-${payload.size - 1}/${payload.size}"),
                    ByteReadChannel(tail), tail.size.toLong(), true, "\"v1\"", null
                )
            } else {
                NetworkResponse(200, emptyMap(), ByteReadChannel(payload), payload.size.toLong(), false, "\"v1\"", null)
            }
        }

        override fun close() {}
    }

    private fun newDownloader(network: NetworkTransport): DefaultFileDownloader {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        return DefaultFileDownloader(
            config = DownloaderConfig(maxConcurrentDownloads = 1, stagingDirectory = stagingDir),
            repository = repository,
            network = network,
            fileSystem = OkioDownloadFileSystem(fakeFs),
            scope = scope
        )
    }

    /** What DownloadWorkManagerWorker.doWork() does for one attempt (stable id = work id). */
    private suspend fun workerAttempt(downloader: DefaultFileDownloader, request: DownloadRequest): Pair<DownloadId, DownloadState> {
        val id = downloader.enqueue(request, DownloadId("wm_test-work-id"))
        val terminal = withTimeout(5_000.milliseconds) {
            downloader.observe(id).first {
                it is DownloadState.Completed || it is DownloadState.Failed || it is DownloadState.Cancelled
            }
        }
        return id to terminal
    }

    private fun partFiles() =
        fakeFs.listRecursively(stagingDir).filter { it.name.endsWith(".part") }.toList()

    @Test
    fun retryAttemptReusesDownloadIdAndResumesFromPartialFile() = runBlocking<Unit> {
        val downloader = newDownloader(flakyServer())
        val request = DownloadRequest(
            url = "https://x/file.bin", fileName = "file.bin", destinationDirectory = downloadDir
        )

        val (id1, state1) = workerAttempt(downloader, request)
        val failed = state1 as DownloadState.Failed
        assertEquals(true, failed.canRetry, "attempt 1 should be retryable -> worker returns Result.retry()")
        println("attempt1 id=${id1.raw} parts=${partFiles()}")

        // WorkManager runs the retry attempt: same inputs, same worker code.
        val (id2, state2) = workerAttempt(downloader, request)
        println("attempt2 id=${id2.raw} state=${state2::class.simpleName} parts=${partFiles()} requests=${seenRequests.map { it.rangeStart }}")

        assertEquals(id1, id2, "BUG: retry attempt created a new DownloadId")
        assertEquals(DownloadState.Completed::class, state2::class)
        assertEquals(0, partFiles().size, "BUG: orphaned .part file left behind; found ${partFiles()}")
        assertEquals(
            payload.decodeToString(),
            fakeFs.read("/downloads/file.bin".toPath()) { readUtf8() },
            "resumed file must be the full, correctly concatenated payload"
        )
        assertEquals(null, seenRequests[0].rangeStart)
        assertNotNull(seenRequests.getOrNull(1)?.rangeStart, "BUG: retry attempt sent no Range header (restarted from zero)")
        assertEquals(10L, seenRequests[1].rangeStart)
        assertEquals(1, downloader.observeAll().first().size, "BUG: retry attempt created a second download record")
    }
}


