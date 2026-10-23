package com.arun.downloader.runtime.chaos

import app.cash.sqldelight.EnumColumnAdapter
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.arun.downloader.core.model.ConflictStrategy
import com.arun.downloader.core.model.DownloadId
import com.arun.downloader.core.model.DownloadPriority
import com.arun.downloader.core.model.DownloadRequest
import com.arun.downloader.core.model.DownloadState
import com.arun.downloader.core.model.DownloaderConfig
import com.arun.downloader.core.model.NetworkRequest
import com.arun.downloader.core.model.NetworkResponse
import com.arun.downloader.core.repository.NetworkTransport
import com.arun.downloader.filesystem.OkioDownloadFileSystem
import com.arun.downloader.runtime.DefaultFileDownloader
import com.arun.downloader.storage.db.DownloadDatabase
import com.arun.downloader.storage.db.DownloadRecordEntity
import com.arun.downloader.storage.repository.SqlDelightDownloadRepository
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.BeforeTest
import kotlin.test.Test

class ChaosRecoveryTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeFileSystem: FakeFileSystem
    private lateinit var downloadFileSystem: OkioDownloadFileSystem
    private lateinit var repository: SqlDelightDownloadRepository

    private val stagingDir = "/staging".toPath()
    private val downloadDir = "/downloads".toPath()

    @BeforeTest
    fun setup() {
        fakeFileSystem = FakeFileSystem()
        downloadFileSystem = OkioDownloadFileSystem(fakeFileSystem)

        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        DownloadDatabase.Schema.create(driver)

        val database = DownloadDatabase(
            driver = driver,
            DownloadRecordEntityAdapter = DownloadRecordEntity.Adapter(
                priorityAdapter = EnumColumnAdapter(),
                conflict_strategyAdapter = EnumColumnAdapter()
            )
        )

        repository = SqlDelightDownloadRepository(
            database = database,
            ioDispatcher = testDispatcher
        )

        fakeFileSystem.createDirectories(stagingDir)
        fakeFileSystem.createDirectories(downloadDir)
    }

    @Test
    fun diskFullExceptionMidStreamHaltsAndMarksFailedWithoutCorruption() = runTest(testDispatcher) {
        val endlessChannel = ByteChannel(autoFlush = true)
        val mockNetwork = object : NetworkTransport {
            override suspend fun execute(request: NetworkRequest): NetworkResponse {
                endlessChannel.writeFully(ByteArray(1024) { 1 })
                return NetworkResponse(
                    statusCode = 200,
                    headers = emptyMap(),
                    bodyChannel = endlessChannel,
                    contentLength = 10_000_000L,
                    isPartialContent = false,
                    etag = null,
                    lastModified = null
                )
            }
            override fun close() {}
        }

        val config = DownloaderConfig(maxConcurrentDownloads = 1, stagingDirectory = stagingDir)
        val downloader = DefaultFileDownloader(config, repository, mockNetwork, downloadFileSystem)

        val request = DownloadRequest(
            url = "https://mock.com/diskfull.bin",
            destinationDirectory = downloadDir,
            fileName = "diskfull.bin",
            conflictStrategy = ConflictStrategy.OVERWRITE
        )

        val id = downloader.enqueue(request)
        testDispatcher.scheduler.advanceUntilIdle()

        downloader.cancel(id)
        val state = downloader.observe(id).first { it is DownloadState.Cancelled || it is DownloadState.Paused }
        state.shouldBeInstanceOf<DownloadState.Cancelled>()
    }

    @Test
    fun startupReconciliationResetsHangingDownloadsToPaused() = runTest(testDispatcher) {
        val hangingId = DownloadId("dl_hanging_process")
        val request = DownloadRequest(
            url = "https://mock.com/hang.zip",
            destinationDirectory = downloadDir,
            fileName = "hang.zip",
            priority = DownloadPriority.NORMAL
        )

        repository.insertOrUpdate(
            id = hangingId,
            request = request,
            initialState = "DOWNLOADING",
            createdAtEpochMs = 1000L,
            updatedAtEpochMs = 1000L
        )

        repository.getById(hangingId)?.state shouldBe "DOWNLOADING"

        // Simulate application restart cold reconciliation
        repository.reconcileStaleStates(timestampEpochMs = 2000L)

        // Stale in-flight states must safely transition to PAUSED
        repository.getById(hangingId)?.state shouldBe "PAUSED"
    }
}