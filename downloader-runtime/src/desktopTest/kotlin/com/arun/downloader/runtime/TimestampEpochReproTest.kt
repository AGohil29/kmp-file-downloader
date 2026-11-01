package com.arun.downloader.runtime

import app.cash.sqldelight.EnumColumnAdapter
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.arun.downloader.core.model.ConflictStrategy
import com.arun.downloader.core.model.DownloadRequest
import com.arun.downloader.core.model.DownloaderConfig
import com.arun.downloader.core.model.NetworkRequest
import com.arun.downloader.core.model.NetworkResponse
import com.arun.downloader.core.repository.NetworkTransport
import com.arun.downloader.filesystem.OkioDownloadFileSystem
import com.arun.downloader.storage.db.DownloadDatabase
import com.arun.downloader.storage.db.DownloadRecordEntity
import com.arun.downloader.storage.repository.SqlDelightDownloadRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reproduces: DefaultFileDownloader.currentTimeEpochMs() uses
 * TimeSource.Monotonic.markNow().elapsedNow(), which is the time elapsed since the
 * mark was just created (~0 ms), not wall-clock epoch milliseconds.
 */
class TimestampEpochReproTest {

    private val testDispatcher = StandardTestDispatcher()
    private val downloaderScope = CoroutineScope(SupervisorJob() + testDispatcher)
    private lateinit var repository: SqlDelightDownloadRepository
    private lateinit var downloader: DefaultFileDownloader

    private val stagingDir = "/staging".toPath()
    private val downloadDir = "/downloads".toPath()

    @BeforeTest
    fun setup() {
        val fakeFileSystem = FakeFileSystem().apply {
            createDirectories(stagingDir)
            createDirectories(downloadDir)
        }
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        DownloadDatabase.Schema.create(driver)
        val database = DownloadDatabase(
            driver = driver,
            DownloadRecordEntityAdapter = DownloadRecordEntity.Adapter(
                priorityAdapter = EnumColumnAdapter(),
                conflict_strategyAdapter = EnumColumnAdapter()
            )
        )
        repository = SqlDelightDownloadRepository(database = database, ioDispatcher = testDispatcher)

        // Network never responds, so downloads stay in-flight and timestamps are from the facade.
        val hangingNetwork = object : NetworkTransport {
            override suspend fun execute(request: NetworkRequest): NetworkResponse = awaitCancellation()
            override fun close() {}
        }

        downloader = DefaultFileDownloader(
            config = DownloaderConfig(maxConcurrentDownloads = 1, stagingDirectory = stagingDir),
            repository = repository,
            network = hangingNetwork,
            fileSystem = OkioDownloadFileSystem(fakeFileSystem),
            scope = downloaderScope
        )
    }

    @AfterTest
    fun tearDown() {
        downloaderScope.cancel()
    }

    private fun request(name: String) = DownloadRequest(
        url = "https://mock.com/$name",
        destinationDirectory = downloadDir,
        fileName = name,
        conflictStrategy = ConflictStrategy.OVERWRITE
    )

    @Test
    fun enqueuePersistsWallClockCreatedAndUpdatedAt() = runTest(testDispatcher) {
        val before = System.currentTimeMillis()
        val id = downloader.enqueue(request("a.bin"))
        val after = System.currentTimeMillis()

        val record = repository.getById(id)!!
        println("enqueue -> createdAt=${record.createdAtEpochMs}, updatedAt=${record.updatedAtEpochMs}, wallClock=$before")

        assertTrue(
            record.createdAtEpochMs in before..after,
            "createdAtEpochMs=${record.createdAtEpochMs} should be wall-clock epoch ms in [$before, $after]"
        )
        assertTrue(
            record.updatedAtEpochMs in before..after,
            "updatedAtEpochMs=${record.updatedAtEpochMs} should be wall-clock epoch ms in [$before, $after]"
        )
    }

    @Test
    fun pauseAndCancelPersistWallClockUpdatedAt() = runTest(testDispatcher) {
        val id = downloader.enqueue(request("b.bin"))

        val beforePause = System.currentTimeMillis()
        downloader.pause(id)
        val paused = repository.getById(id)!!
        println("pause -> updatedAt=${paused.updatedAtEpochMs}, wallClock=$beforePause")
        assertTrue(
            paused.updatedAtEpochMs >= beforePause,
            "updatedAtEpochMs after pause=${paused.updatedAtEpochMs} should be >= $beforePause"
        )

        val beforeCancel = System.currentTimeMillis()
        downloader.cancel(id)
        val cancelled = repository.getById(id)!!
        assertTrue(
            cancelled.updatedAtEpochMs >= beforeCancel,
            "updatedAtEpochMs after cancel=${cancelled.updatedAtEpochMs} should be >= $beforeCancel"
        )
    }

    @Test
    fun laterEnqueueHasStrictlyLaterCreatedAtAndOrdersFirstInObserveAll() = runTest(testDispatcher) {
        val first = downloader.enqueue(request("first.bin"))
        Thread.sleep(20) // real wall-clock gap between the two enqueues
        val second = downloader.enqueue(request("second.bin"))

        val firstRecord = repository.getById(first)!!
        val secondRecord = repository.getById(second)!!
        println("first.createdAt=${firstRecord.createdAtEpochMs}, second.createdAt=${secondRecord.createdAtEpochMs}")

        assertTrue(
            secondRecord.createdAtEpochMs > firstRecord.createdAtEpochMs,
            "second createdAt (${secondRecord.createdAtEpochMs}) must be > first (${firstRecord.createdAtEpochMs})"
        )

        // observeAll is ORDER BY created_at_epoch_ms DESC -> newest first.
        val ordered = repository.observeAll().first().map { it.id }
        assertEquals(listOf(second, first), ordered, "observeAll should list newest download first")
    }
}

