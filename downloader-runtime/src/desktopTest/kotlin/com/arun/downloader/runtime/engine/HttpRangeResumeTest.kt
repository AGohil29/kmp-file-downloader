package com.arun.downloader.runtime.engine

import app.cash.sqldelight.EnumColumnAdapter
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.arun.downloader.core.engine.ContentRange
import com.arun.downloader.core.engine.DownloadTask
import com.arun.downloader.core.model.ConflictStrategy
import com.arun.downloader.core.model.DownloadError
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

    // ---------- 416 / 412 recovery ----------

    private suspend fun freshResponse(bytes: ByteArray, etag: String? = "\"etag-new\""): NetworkResponse {
        val channel = ByteChannel(autoFlush = true)
        channel.writeFully(bytes)
        channel.close()
        return NetworkResponse(
            statusCode = 200,
            headers = mapOf("Content-Length" to bytes.size.toString()),
            bodyChannel = channel,
            contentLength = bytes.size.toLong(),
            isPartialContent = false,
            etag = etag,
            lastModified = null
        )
    }

    private suspend fun prepare(id: DownloadId, fileName: String, staged: String?, total: Long?, etag: String?): DownloadRequest {
        val stagingPart = baseStagingDir / ".downloader_staging" / "${id.raw}.part"
        fakeFileSystem.createDirectories(stagingPart.parent!!)
        if (staged != null) {
            downloadFileSystem.sink(stagingPart, append = false).buffer().use { it.write(staged.encodeToByteArray()) }
        }
        val request = DownloadRequest(
            url = "https://mock.com/$fileName",
            destinationDirectory = baseDestinationDir,
            fileName = fileName,
            priority = DownloadPriority.NORMAL,
            conflictStrategy = ConflictStrategy.OVERWRITE
        )
        repository.insertOrUpdate(id, request, "PAUSED", 1000L, 1000L)
        if (total != null || etag != null) repository.updateConnectionHeaders(id, total, etag, null, 1000L)
        return request
    }

    private fun taskFor(id: DownloadId, request: DownloadRequest, network: NetworkTransport) = DownloadTask(
        id = id,
        request = request,
        stateMachine = DownloadStateMachine(id),
        repository = repository,
        network = network,
        fileSystem = downloadFileSystem,
        stagingDirectory = baseStagingDir,
        ioDispatcher = testDispatcher
    )

    @Test
    fun http416WithCompleteStagedFileCommitsWithoutRedownload() = runTest(testDispatcher) {
        val id = DownloadId("dl_416_complete")
        val request = prepare(id, "done.txt", staged = "0123456789", total = 10L, etag = "\"e1\"")
        val ranges = mutableListOf<Long?>()

        val network = object : NetworkTransport {
            override suspend fun execute(netRequest: NetworkRequest): NetworkResponse {
                ranges += netRequest.rangeStart
                throw DownloadError.NonRetryable.RangeNotSatisfiable(totalBytes = 10L)
            }
            override fun close() {}
        }

        val task = taskFor(id, request, network)
        task.execute()
        testDispatcher.scheduler.advanceUntilIdle()

        task.state.value.shouldBeInstanceOf<DownloadState.Completed>()
        ranges shouldBe listOf<Long?>(10L)
        fakeFileSystem.read(baseDestinationDir / "done.txt") { readUtf8() } shouldBe "0123456789"
    }

    @Test
    fun http416WithMismatchedTotalRestartsFromZero() = runTest(testDispatcher) {
        val id = DownloadId("dl_416_stale")
        val request = prepare(id, "stale.txt", staged = "old-stale-bytes", total = null, etag = "\"e1\"")
        val ranges = mutableListOf<Long?>()

        val network = object : NetworkTransport {
            override suspend fun execute(netRequest: NetworkRequest): NetworkResponse {
                ranges += netRequest.rangeStart
                if (netRequest.rangeStart != null) {
                    throw DownloadError.NonRetryable.RangeNotSatisfiable(totalBytes = 5L)
                }
                netRequest.ifRange shouldBe null
                return freshResponse("fresh".encodeToByteArray())
            }
            override fun close() {}
        }

        val task = taskFor(id, request, network)
        task.execute()
        testDispatcher.scheduler.advanceUntilIdle()

        task.state.value.shouldBeInstanceOf<DownloadState.Completed>()
        ranges shouldBe listOf<Long?>(15L, null)
        fakeFileSystem.read(baseDestinationDir / "stale.txt") { readUtf8() } shouldBe "fresh"
    }

    @Test
    fun http412DropsValidatorsAndPartialFileThenRestarts() = runTest(testDispatcher) {
        val id = DownloadId("dl_412")
        val request = prepare(id, "changed.txt", staged = "partial", total = 20L, etag = "\"old\"")
        val seen = mutableListOf<Pair<Long?, String?>>()

        val network = object : NetworkTransport {
            override suspend fun execute(request: NetworkRequest): NetworkResponse {
                seen += request.rangeStart to request.ifRange
                if (request.rangeStart != null) throw DownloadError.NonRetryable.PreconditionFailed()
                return freshResponse("brand new content".encodeToByteArray(), etag = "\"new\"")
            }
            override fun close() {}
        }

        val task = taskFor(id, request, network)
        task.execute()
        testDispatcher.scheduler.advanceUntilIdle()

        task.state.value.shouldBeInstanceOf<DownloadState.Completed>()
        seen shouldBe listOf(7L to "\"old\"", null to null)
        fakeFileSystem.read(baseDestinationDir / "changed.txt") { readUtf8() } shouldBe "brand new content"
        repository.getById(id)?.etag shouldBe "\"new\""
    }

    @Test
    fun persistent416OnFreshRequestFailsWithoutLooping() = runTest(testDispatcher) {
        val id = DownloadId("dl_416_persistent")
        val request = prepare(id, "bad.txt", staged = "abc", total = null, etag = "\"e\"")
        var calls = 0

        val network = object : NetworkTransport {
            override suspend fun execute(request: NetworkRequest): NetworkResponse {
                calls++
                throw if (request.rangeStart != null) {
                    DownloadError.NonRetryable.RangeNotSatisfiable(totalBytes = null)
                } else {
                    DownloadError.NonRetryable.ServerDoesNotSupportRange("416 without range")
                }
            }
            override fun close() {}
        }

        val task = taskFor(id, request, network)
        task.execute()
        testDispatcher.scheduler.advanceUntilIdle()

        task.state.value.shouldBeInstanceOf<DownloadState.Failed>()
        calls shouldBe 2
    }

    @Test
    fun stagedFileLargerThanKnownTotalIsResetBeforeRequest() = runTest(testDispatcher) {
        val id = DownloadId("dl_oversize")
        val request = prepare(id, "over.txt", staged = "way too many bytes", total = 4L, etag = "\"e\"")
        val ranges = mutableListOf<Long?>()

        val network = object : NetworkTransport {
            override suspend fun execute(request: NetworkRequest): NetworkResponse {
                ranges += request.rangeStart
                return freshResponse("data".encodeToByteArray())
            }
            override fun close() {}
        }

        val task = taskFor(id, request, network)
        task.execute()
        testDispatcher.scheduler.advanceUntilIdle()

        task.state.value.shouldBeInstanceOf<DownloadState.Completed>()
        ranges shouldBe listOf<Long?>(null)
    }

    private suspend fun resumeWithChecksum(id: DownloadId, expectedHash: String): DownloadTask {
        val base = prepare(id, "chk.txt", staged = "0123456789", total = 20L, etag = "\"e1\"")
        val request = base.copy(
            expectedChecksum = com.arun.downloader.core.model.ChecksumValidation(
                com.arun.downloader.core.model.ChecksumValidation.Algorithm.SHA256, expectedHash
            )
        )
        val network = object : NetworkTransport {
            override suspend fun execute(netRequest: NetworkRequest): NetworkResponse {
                val channel = ByteChannel(autoFlush = true)
                channel.writeFully("ABCDEFGHIJ".encodeToByteArray())
                channel.close()
                return NetworkResponse(
                    statusCode = 206,
                    headers = mapOf("Content-Range" to "bytes 10-19/20"),
                    bodyChannel = channel,
                    contentLength = 10L,
                    isPartialContent = true,
                    etag = "\"e1\"",
                    lastModified = null
                )
            }
            override fun close() {}
        }
        val task = taskFor(id, request, network)
        task.execute()
        testDispatcher.scheduler.advanceUntilIdle()
        return task
    }

    @Test
    fun resumedDownloadWithWrongChecksumFails() = runTest(testDispatcher) {
        val task = resumeWithChecksum(DownloadId("dl_resume_bad_hash"), "0".repeat(64))
        val state = task.state.value
        state.shouldBeInstanceOf<DownloadState.Failed>()
        state.error.shouldBeInstanceOf<DownloadError.NonRetryable.ChecksumMismatch>()
        fakeFileSystem.exists(baseDestinationDir / "chk.txt") shouldBe false
    }

    @Test
    fun resumedDownloadWithCorrectChecksumCompletes() = runTest(testDispatcher) {
        val good = "0123456789ABCDEFGHIJ".encodeToByteArray().let { okio.Buffer().write(it).sha256().hex() }
        val task = resumeWithChecksum(DownloadId("dl_resume_good_hash"), good)
        task.state.value.shouldBeInstanceOf<DownloadState.Completed>()
    }

    @Test
    fun resumeWith206OmittingEtagKeepsStoredValidator() = runTest(testDispatcher) {
        val id = DownloadId("dl_resume_no_etag")
        val request = prepare(id, "keep.txt", staged = "0123456789", total = 20L, etag = "\"etag-v1\"")

        val network = object : NetworkTransport {
            override suspend fun execute(netRequest: NetworkRequest): NetworkResponse {
                val channel = ByteChannel(autoFlush = true)
                channel.writeFully("ABCDEFGHIJ".encodeToByteArray())
                channel.close()
                return NetworkResponse(
                    statusCode = 206,
                    headers = mapOf("Content-Range" to "bytes 10-19/20"),
                    bodyChannel = channel,
                    contentLength = 10L,
                    isPartialContent = true,
                    etag = null, // server omits validators on 206
                    lastModified = null
                )
            }
            override fun close() {}
        }

        val task = taskFor(id, request, network)
        task.execute()
        testDispatcher.scheduler.advanceUntilIdle()

        task.state.value.shouldBeInstanceOf<DownloadState.Completed>()
        repository.getById(id)?.etag shouldBe "\"etag-v1\""
    }

    private fun recordingNetwork(seen: MutableList<Pair<Long?, String?>>) = object : NetworkTransport {
        override suspend fun execute(request: NetworkRequest): NetworkResponse {
            seen += request.rangeStart to request.ifRange
            return freshResponse("0123456789ABCDEFGHIJ".encodeToByteArray())
        }
        override fun close() {}
    }

    @Test
    fun resumeWithoutAnyValidatorMustRestartFromZero() = runTest(testDispatcher) {
        val id = DownloadId("dl_no_validator")
        val request = prepare(id, "nov.txt", staged = "0123456789", total = 20L, etag = null)
        val seen = mutableListOf<Pair<Long?, String?>>()

        taskFor(id, request, recordingNetwork(seen)).execute()
        testDispatcher.scheduler.advanceUntilIdle()

        println("no-validator request: $seen")
        // BUG: a bare Range request is sent; a changed resource would silently corrupt the file.
        seen shouldBe listOf<Pair<Long?, String?>>(null to null)
    }

    @Test
    fun weakEtagMustNotBeSentInIfRange() = runTest(testDispatcher) {
        val id = DownloadId("dl_weak_etag")
        val request = prepare(id, "weak.txt", staged = "0123456789", total = 20L, etag = "W/\"weak-1\"")
        val seen = mutableListOf<Pair<Long?, String?>>()

        taskFor(id, request, recordingNetwork(seen)).execute()
        testDispatcher.scheduler.advanceUntilIdle()

        println("weak-etag request: $seen")
        // BUG: weak validator is sent in If-Range (RFC 7233 requires strong comparison).
        // With no other validator the only safe choice is a fresh, non-ranged request.
        seen shouldBe listOf<Pair<Long?, String?>>(null to null)
    }

    @Test
    fun weakEtagFallsBackToLastModifiedInIfRange() = runTest(testDispatcher) {
        val id = DownloadId("dl_weak_etag_lm")
        val request = prepare(id, "weaklm.txt", staged = "0123456789", total = 20L, etag = "W/\"weak-1\"")
        val lastModified = "Wed, 21 Oct 2026 07:28:00 GMT"
        repository.updateConnectionHeaders(id, 20L, "W/\"weak-1\"", lastModified, 1001L)
        val seen = mutableListOf<Pair<Long?, String?>>()

        taskFor(id, request, recordingNetwork(seen)).execute()
        testDispatcher.scheduler.advanceUntilIdle()

        println("weak-etag+LM request: $seen")
        seen.first() shouldBe (10L to lastModified)
    }
}
