package com.arun.downloader.runtime

import app.cash.sqldelight.EnumColumnAdapter
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.arun.downloader.core.model.ChecksumValidation
import com.arun.downloader.core.model.DownloadError
import com.arun.downloader.core.model.DownloadId
import com.arun.downloader.core.model.DownloadRequest
import com.arun.downloader.core.model.DownloaderConfig
import com.arun.downloader.core.model.NetworkRequest
import com.arun.downloader.core.model.NetworkResponse
import com.arun.downloader.core.repository.NetworkTransport
import com.arun.downloader.filesystem.OkioDownloadFileSystem
import com.arun.downloader.storage.db.DownloadDatabase
import com.arun.downloader.storage.db.DownloadRecordEntity
import com.arun.downloader.storage.repository.SqlDelightDownloadRepository
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

/**
 * Reproduces: resume() / retry() rebuild the DownloadRequest from the persisted record, which
 * stores neither `headers` nor `expectedChecksum`. Consequently:
 *  - the resumed/retried HTTP request is sent without auth headers (e.g. Authorization);
 *  - a corrupted payload is committed as COMPLETED because checksum validation is skipped.
 *
 * Scenario: the first attempt fails with a retryable error, then the caller retries/resumes.
 */
class ResumeRetryRequestLossReproTest {

    private val stagingDir = "/staging".toPath()
    private val downloadDir = "/downloads".toPath()
    private val authHeaders = mapOf("Authorization" to "Bearer secret-token")

    private val goodPayload = "GOOD PAYLOAD".encodeToByteArray()
    private val corruptPayload = "EVIL PAYLOAD".encodeToByteArray() // same length, different bytes
    private val goodSha256 = okio.ByteString.of(*goodPayload).sha256().hex()

    private lateinit var fakeFs: FakeFileSystem
    private lateinit var repository: SqlDelightDownloadRepository
    private val scopes = mutableListOf<CoroutineScope>()

    /** Records every request; first call fails (retryable), later calls serve [laterPayload]. */
    private inner class RecordingNetwork(private val laterPayload: ByteArray) : NetworkTransport {
        val requests = CopyOnWriteArrayList<NetworkRequest>()
        override suspend fun execute(request: NetworkRequest): NetworkResponse {
            requests += request
            if (requests.size == 1) throw DownloadError.Retryable.NetworkTimeout()
            return NetworkResponse(
                statusCode = 200,
                headers = mapOf("Content-Length" to laterPayload.size.toString()),
                bodyChannel = ByteReadChannel(laterPayload),
                contentLength = laterPayload.size.toLong(),
                isPartialContent = false,
                etag = null,
                lastModified = null
            )
        }
        override fun close() {}
    }

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

    private suspend fun awaitState(id: DownloadId, vararg states: String): String =
        withTimeout(5_000.milliseconds) {
            var s = repository.getById(id)?.state
            while (s !in states) {
                delay(10.milliseconds)
                s = repository.getById(id)?.state
            }
            s!!
        }

    private fun request() = DownloadRequest(
        url = "https://mock.com/secure.bin",
        destinationDirectory = downloadDir,
        fileName = "secure.bin",
        headers = authHeaders,
        expectedChecksum = ChecksumValidation(ChecksumValidation.Algorithm.SHA256, goodSha256)
    )

    private suspend fun failFirstAttempt(downloader: DefaultFileDownloader, network: RecordingNetwork): DownloadId {
        val id = downloader.enqueue(request())
        awaitState(id, "FAILED")
        assertEquals(authHeaders, network.requests[0].headers, "sanity: first attempt carries auth headers")
        return id
    }

    @Test
    fun retryMustResendAuthHeaders() = runBlocking {
        val network = RecordingNetwork(goodPayload)
        val downloader = newDownloader(network)
        val id = failFirstAttempt(downloader, network)

        downloader.retry(id)
        awaitState(id, "COMPLETED", "FAILED")

        println("retry request headers=${network.requests[1].headers}")
        assertEquals(authHeaders, network.requests[1].headers, "BUG: retry() dropped request headers")
    }

    @Test
    fun resumeMustResendAuthHeaders() = runBlocking {
        val network = RecordingNetwork(goodPayload)
        val downloader = newDownloader(network)
        val id = failFirstAttempt(downloader, network)

        downloader.resume(id)
        awaitState(id, "COMPLETED", "FAILED")

        println("resume request headers=${network.requests[1].headers}")
        assertEquals(authHeaders, network.requests[1].headers, "BUG: resume() dropped request headers")
    }

    @Test
    fun retryMustStillValidateChecksum() = runBlocking {
        val network = RecordingNetwork(corruptPayload)
        val downloader = newDownloader(network)
        val id = failFirstAttempt(downloader, network)

        downloader.retry(id)
        val finalState = awaitState(id, "COMPLETED", "FAILED")

        println("retry with corrupt payload -> state=$finalState, file exists=${fakeFs.exists(downloadDir / "secure.bin")}")
        assertEquals("FAILED", finalState, "BUG: retry() skipped checksum validation and committed a corrupt file")
    }

    @Test
    fun rangedResumeMustValidateChecksumOverWholeFile() = runBlocking {
        // Second attempt answers 206 with the genuine tail; the on-disk prefix is corrupt.
        val tail = goodPayload.copyOfRange(4, goodPayload.size)
        val requests = CopyOnWriteArrayList<NetworkRequest>()
        val network = object : NetworkTransport {
            override suspend fun execute(request: NetworkRequest): NetworkResponse {
                requests += request
                if (requests.size == 1) throw DownloadError.Retryable.NetworkTimeout()
                return NetworkResponse(
                    statusCode = 206,
                    headers = mapOf("Content-Range" to "bytes 4-${goodPayload.size - 1}/${goodPayload.size}"),
                    bodyChannel = ByteReadChannel(tail),
                    contentLength = tail.size.toLong(),
                    isPartialContent = true,
                    etag = null,
                    lastModified = null
                )
            }
            override fun close() {}
        }
        val downloader = newDownloader(network)
        val id = downloader.enqueue(request())
        awaitState(id, "FAILED")

        // Simulate a corrupt 4-byte prefix left behind by the interrupted attempt.
        val part = stagingDir / ".downloader_staging" / "${id.raw}.part"
        fakeFs.createDirectories(part.parent!!)
        fakeFs.write(part) { write("EVIL".encodeToByteArray()) }

        downloader.resume(id)
        val finalState = awaitState(id, "COMPLETED", "FAILED")

        assertEquals(4L, requests[1].rangeStart, "sanity: second attempt is a ranged resume")
        assertEquals(authHeaders, requests[1].headers)
        assertEquals("FAILED", finalState, "BUG: ranged resume skipped checksum validation")
        assertEquals("ChecksumMismatch", repository.getById(id)!!.errorType)
    }

    @Test
    fun rangedResumeWithGenuinePrefixCompletes() = runBlocking {
        val tail = goodPayload.copyOfRange(4, goodPayload.size)
        var calls = 0
        val network = object : NetworkTransport {
            override suspend fun execute(request: NetworkRequest): NetworkResponse {
                if (++calls == 1) throw DownloadError.Retryable.NetworkTimeout()
                return NetworkResponse(
                    statusCode = 206,
                    headers = mapOf("Content-Range" to "bytes 4-${goodPayload.size - 1}/${goodPayload.size}"),
                    bodyChannel = ByteReadChannel(tail),
                    contentLength = tail.size.toLong(),
                    isPartialContent = true,
                    etag = null,
                    lastModified = null
                )
            }
            override fun close() {}
        }
        val downloader = newDownloader(network)
        val id = downloader.enqueue(request())
        awaitState(id, "FAILED")

        val part = stagingDir / ".downloader_staging" / "${id.raw}.part"
        fakeFs.createDirectories(part.parent!!)
        fakeFs.write(part) { write(goodPayload.copyOfRange(0, 4)) }

        downloader.resume(id)
        assertEquals("COMPLETED", awaitState(id, "COMPLETED", "FAILED"))
        assertEquals(goodPayload.decodeToString(), fakeFs.read(downloadDir / "secure.bin") { readUtf8() })
    }
}


