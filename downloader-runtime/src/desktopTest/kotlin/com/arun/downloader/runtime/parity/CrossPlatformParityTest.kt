package com.arun.downloader.runtime.parity

import app.cash.sqldelight.EnumColumnAdapter
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.arun.downloader.core.model.ConflictStrategy
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

class CrossPlatformParityTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeFileSystem: FakeFileSystem
    private lateinit var downloadFileSystem: OkioDownloadFileSystem
    private lateinit var repository: SqlDelightDownloadRepository
    private lateinit var downloader: DefaultFileDownloader

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

        val mockNetwork = object : NetworkTransport {
            override suspend fun execute(request: NetworkRequest): NetworkResponse {
                val payload = "Parity validation test content".encodeToByteArray()
                val channel = ByteChannel(autoFlush = true)
                channel.writeFully(payload)
                channel.close()

                return NetworkResponse(
                    statusCode = 200,
                    headers = mapOf("Content-Length" to payload.size.toString()),
                    bodyChannel = channel,
                    contentLength = payload.size.toLong(),
                    isPartialContent = false,
                    etag = "\"parity-etag\"",
                    lastModified = null
                )
            }

            override fun close() {}
        }

        val config = DownloaderConfig(
            maxConcurrentDownloads = 2,
            stagingDirectory = stagingDir
        )

        downloader = DefaultFileDownloader(
            config = config,
            repository = repository,
            network = mockNetwork,
            fileSystem = downloadFileSystem
        )
    }

    @Test
    fun universalApiFacadeProducesCompletedStateAcrossAllTargets() = runTest(testDispatcher) {
        val request = DownloadRequest(
            url = "https://mock.com/parity.txt",
            destinationDirectory = downloadDir,
            fileName = "parity.txt",
            conflictStrategy = ConflictStrategy.OVERWRITE
        )

        val id = downloader.enqueue(request)
        testDispatcher.scheduler.advanceUntilIdle()

        val finalState = downloader.observe(id).first { it is DownloadState.Completed }
        finalState.shouldBeInstanceOf<DownloadState.Completed>()
        finalState.absolutePath shouldBe (downloadDir / "parity.txt").toString()

        fakeFileSystem.exists(downloadDir / "parity.txt") shouldBe true
    }
}