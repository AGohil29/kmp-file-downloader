package com.arun.downloader.runtime.engine

import app.cash.sqldelight.EnumColumnAdapter
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.arun.downloader.core.engine.ContentRange
import com.arun.downloader.core.engine.DownloadTask
import com.arun.downloader.core.model.ConflictStrategy
import com.arun.downloader.core.model.DownloadId
import com.arun.downloader.core.model.DownloadPriority
import com.arun.downloader.core.model.DownloadRequest
import com.arun.downloader.core.model.DownloadState
import com.arun.downloader.core.model.NetworkRequest
import com.arun.downloader.core.model.NetworkResponse
import com.arun.downloader.core.repository.NetworkTransport
import com.arun.downloader.core.statemachine.DownloadStateMachine
import com.arun.downloader.filesystem.OkioDownloadFileSystem
import com.arun.downloader.storage.db.DownloadDatabase
import com.arun.downloader.storage.db.DownloadRecordEntity
import com.arun.downloader.storage.repository.SqlDelightDownloadRepository
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.buffer
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.BeforeTest
import kotlin.test.Test

class HttpRangeResumeTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeFileSystem: FakeFileSystem
    private lateinit var downloadFileSystem: OkioDownloadFileSystem
    private lateinit var repository: SqlDelightDownloadRepository

    private val baseStagingDir = "/staging".toPath()
    private val baseDestinationDir = "/downloads".toPath()

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
        repository = SqlDelightDownloadRepository(database, testDispatcher)

        fakeFileSystem.createDirectories(baseStagingDir)
        fakeFileSystem.createDirectories(baseDestinationDir)
    }

    @Test
    fun contentRangeParserParsesStandardAndWildcardHeaders() {
        val standard = ContentRange.parseOrNull("bytes 500-999/1000")
        standard?.rangeStart shouldBe 500L
        standard?.rangeEnd shouldBe 999L
        standard?.totalBytes shouldBe 1000L

        val wildcard = ContentRange.parseOrNull("bytes 0-499/*")
        wildcard?.rangeStart shouldBe 0L
        wildcard?.rangeEnd shouldBe 499L
        wildcard?.totalBytes shouldBe null

        ContentRange.parseOrNull("invalid bytes header") shouldBe null
        ContentRange.parseOrNull("bytes 1000-500/1000") shouldBe null // Start > End
    }

    @Test
    fun resumeWith206AppendsRemainingBytesAndCompletes() = runTest(testDispatcher) {
        val id = DownloadId("dl_resume_206")
        val stagingPart = baseStagingDir / ".downloader_staging" / "${id.raw}.part"
        fakeFileSystem.createDirectories(stagingPart.parent!!)

        // Existing partial file on disk: first 10 bytes ("0123456789")
        val initialBytes = "0123456789".encodeToByteArray()
        downloadFileSystem.sink(stagingPart, append = false).buffer().use {
            it.write(initialBytes)
        }

        // Remaining 10 bytes: "ABCDEFGHIJ" (Total = 20 bytes)
        val remainingBytes = "ABCDEFGHIJ".encodeToByteArray()

        val request = DownloadRequest(
            url = "https://mock.com/file.txt",
            destinationDirectory = baseDestinationDir,
            fileName = "file.txt",
            priority = DownloadPriority.NORMAL,
            conflictStrategy = ConflictStrategy.OVERWRITE
        )

        repository.insertOrUpdate(id, request, "PAUSED", 1000L, 1000L)
        repository.updateConnectionHeaders(id, 20L, "\"etag-v1\"", null, 1000L)

        // Mock network responding with 206 Partial Content
        val mockNetwork = object : NetworkTransport {
            override suspend fun execute(netRequest: NetworkRequest): NetworkResponse {
                netRequest.rangeStart shouldBe 10L
                netRequest.ifRange shouldBe "\"etag-v1\""

                val channel = ByteChannel(autoFlush = true)
                channel.writeFully(remainingBytes)
                channel.close()

                return NetworkResponse(
                    statusCode = 206,
                    headers = mapOf(
                        "Content-Length" to "10",
                        "Content-Range" to "bytes 10-19/20",
                        "ETag" to "\"etag-v1\""
                    ),
                    bodyChannel = channel,
                    contentLength = 10L,
                    isPartialContent = true,
                    etag = "\"etag-v1\"",
                    lastModified = null
                )
            }
            override fun close() {}
        }

        val task = DownloadTask(
            id = id,
            request = request,
            stateMachine = DownloadStateMachine(id),
            repository = repository,
            network = mockNetwork,
            fileSystem = downloadFileSystem,
            stagingDirectory = baseStagingDir,
            ioDispatcher = testDispatcher
        )

        task.execute()
        testDispatcher.scheduler.advanceUntilIdle()

        // Verify task completed
        task.state.value.shouldBeInstanceOf<DownloadState.Completed>()

        // Verify file content matches combined bytes (20 bytes total)
        val finalPath = baseDestinationDir / "file.txt"
        fakeFileSystem.exists(finalPath) shouldBe true
        val finalContent = fakeFileSystem.read(finalPath) { readUtf8() }
        finalContent shouldBe "0123456789ABCDEFGHIJ"
    }

    @Test
    fun serverReturns200OnRangeRequestTruncatesPartialFileAndRestarts() = runTest(testDispatcher) {
        val id = DownloadId("dl_resume_fallback_200")
        val stagingPart = baseStagingDir / ".downloader_staging" / "${id.raw}.part"
        fakeFileSystem.createDirectories(stagingPart.parent!!)

        // Existing partial file contains stale bytes
        downloadFileSystem.sink(stagingPart, append = false).buffer().use {
            it.write("stale data to be wiped".encodeToByteArray())
        }

        val fullFreshBytes = "completely fresh file from start".encodeToByteArray()

        val request = DownloadRequest(
            url = "https://mock.com/fallback.txt",
            destinationDirectory = baseDestinationDir,
            fileName = "fallback.txt",
            priority = DownloadPriority.NORMAL,
            conflictStrategy = ConflictStrategy.OVERWRITE
        )

        repository.insertOrUpdate(id, request, "PAUSED", 1000L, 1000L)

        // Mock server ignoring Range and returning full 200 OK
        val mockNetwork = object : NetworkTransport {
            override suspend fun execute(netRequest: NetworkRequest): NetworkResponse {
                val channel = ByteChannel(autoFlush = true)
                channel.writeFully(fullFreshBytes)
                channel.close()

                return NetworkResponse(
                    statusCode = 200,
                    headers = mapOf("Content-Length" to fullFreshBytes.size.toString()),
                    bodyChannel = channel,
                    contentLength = fullFreshBytes.size.toLong(),
                    isPartialContent = false,
                    etag = "\"etag-v2-new\"",
                    lastModified = null
                )
            }
            override fun close() {}
        }

        val task = DownloadTask(
            id = id,
            request = request,
            stateMachine = DownloadStateMachine(id),
            repository = repository,
            network = mockNetwork,
            fileSystem = downloadFileSystem,
            stagingDirectory = baseStagingDir,
            ioDispatcher = testDispatcher
        )

        task.execute()
        testDispatcher.scheduler.advanceUntilIdle()

        task.state.value.shouldBeInstanceOf<DownloadState.Completed>()

        // Verify output file only contains fresh bytes (stale data wiped)
        val finalPath = baseDestinationDir / "fallback.txt"
        val finalContent = fakeFileSystem.read(finalPath) { readUtf8() }
        finalContent shouldBe "completely fresh file from start"
    }
}