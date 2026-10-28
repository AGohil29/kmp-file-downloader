package com.arun.downloader.runtime

import app.cash.sqldelight.EnumColumnAdapter
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.arun.downloader.core.model.ChecksumValidation
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
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds

/**
 * Reproduces: after an app restart (a fresh DefaultFileDownloader over the same database) every
 * FAILED record is rebuilt as `NonRetryable.Unknown(message)` with `canRetry = true`, although
 * `error_type` is persisted. The concrete error class (and its retryability) is lost.
 */
class FailedStateRestoreReproTest {

    private val stagingDir = "/staging".toPath()
    private val downloadDir = "/downloads".toPath()

    private lateinit var fakeFs: FakeFileSystem
    private lateinit var repository: SqlDelightDownloadRepository
    private val scopes = mutableListOf<CoroutineScope>()

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

    private fun failingNetwork(error: DownloadError) = object : NetworkTransport {
        override suspend fun execute(request: NetworkRequest): NetworkResponse = throw error
        override fun close() {}
    }

    private fun okNetwork(payload: ByteArray) = object : NetworkTransport {
        override suspend fun execute(request: NetworkRequest) = NetworkResponse(
            statusCode = 200,
            headers = emptyMap(),
            bodyChannel = ByteReadChannel(payload),
            contentLength = payload.size.toLong(),
            isPartialContent = false,
            etag = null,
            lastModified = null
        )
        override fun close() {}
    }

    private suspend fun awaitFailed(id: DownloadId) = withTimeout(5_000.milliseconds) {
        while (repository.getById(id)?.state != "FAILED") delay(10.milliseconds)
    }

    /** Runs a failing download, then returns (liveState, stateAfterRestart). */
    private suspend fun failAndRestart(
        network: NetworkTransport,
        request: DownloadRequest
    ): Pair<DownloadState.Failed, DownloadState.Failed> {
        val first = newDownloader(network)
        val id = first.enqueue(request)
        awaitFailed(id)
        val live = first.observeAll().first().single { it.id == id } as DownloadState.Failed

        // "Restart": brand-new downloader, same persisted database.
        val second = newDownloader(network)
        val restored = second.observeAll().first().single { it.id == id } as DownloadState.Failed
        println("live=${live.error::class.simpleName} canRetry=${live.canRetry} | " +
            "restored=${restored.error::class.simpleName} canRetry=${restored.canRetry}")
        return live to restored
    }

    private fun request() = DownloadRequest(url = "https://x/file.bin", destinationDirectory = downloadDir)

    @Test
    fun httpErrorTypeSurvivesRestart() = runBlocking<Unit> {
        val (live, restored) = failAndRestart(failingNetwork(DownloadError.NonRetryable.HttpError(404)), request())
        assertIs<DownloadError.NonRetryable.HttpError>(restored.error, "BUG: restored as ${restored.error::class.simpleName}")
        assertEquals(404, (restored.error as DownloadError.NonRetryable.HttpError).httpCode)
    }

    @Test
    fun checksumMismatchSurvivesRestartAndIsNotRetryable() = runBlocking<Unit> {
        val payload = "EVIL".encodeToByteArray()
        val req = request().copy(
            expectedChecksum = ChecksumValidation(ChecksumValidation.Algorithm.SHA256, "0".repeat(64))
        )
        val (live, restored) = failAndRestart(okNetwork(payload), req)
        assertIs<DownloadError.NonRetryable.ChecksumMismatch>(restored.error, "BUG: restored as ${restored.error::class.simpleName}")
    }

    @Test
    fun retryableErrorSurvivesRestart() = runBlocking<Unit> {
        val (live, restored) = failAndRestart(failingNetwork(DownloadError.Retryable.NetworkTimeout()), request())
        assertIs<DownloadError.Retryable.NetworkTimeout>(restored.error, "BUG: restored as ${restored.error::class.simpleName}")
    }

    @Test
    fun canRetryFlagMatchesErrorKindAfterRestart() = runBlocking<Unit> {
        val (live, restored) = failAndRestart(
            failingNetwork(DownloadError.NonRetryable.MalformedUrl("bad")), request()
        )
        assertEquals(false, restored.canRetry, "BUG: canRetry is hard-coded to true for a non-retryable error")
    }
}

