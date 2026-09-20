package com.arun.downloader.storage.repository

import app.cash.sqldelight.EnumColumnAdapter
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.turbine.test
import com.arun.downloader.core.model.ConflictStrategy
import com.arun.downloader.core.model.DownloadError
import com.arun.downloader.core.model.DownloadId
import com.arun.downloader.core.model.DownloadPriority
import com.arun.downloader.core.model.DownloadRequest
import com.arun.downloader.storage.db.DownloadDatabase
import com.arun.downloader.storage.db.DownloadRecordEntity
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import kotlin.test.BeforeTest
import kotlin.test.Test

class SqlDelightDownloadRepositoryTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var database: DownloadDatabase
    private lateinit var repository: DownloadRepository

    @BeforeTest
    fun setup() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        DownloadDatabase.Schema.create(driver)
        database = DownloadDatabase(
            driver = driver,
            DownloadRecordEntityAdapter = DownloadRecordEntity.Adapter(
                priorityAdapter = EnumColumnAdapter<DownloadPriority>(),
                conflict_strategyAdapter = EnumColumnAdapter<ConflictStrategy>()
            )
        )
        repository = SqlDelightDownloadRepository(database, testDispatcher)
    }

    private fun createSampleRequest(url: String = "https://example.com/archive.zip") =
        DownloadRequest(
            url = url,
            destinationDirectory = "/downloads".toPath(),
            fileName = "archive.zip",
            priority = DownloadPriority.HIGH,
            conflictStrategy = ConflictStrategy.OVERWRITE
        )

    @Test
    fun insertAndRetrieveRecordMatchesMetadata() = runTest(testDispatcher) {
        val id = DownloadId("dl_persist_01")
        val request = createSampleRequest()

        repository.insertOrUpdate(
            id = id,
            request = request,
            initialState = "QUEUED",
            createdAtEpochMs = 1000L,
            updatedAtEpochMs = 1000L
        )

        val record = repository.getById(id)
        record.shouldNotBeNull()
        record.id shouldBe id
        record.url shouldBe request.url
        record.priority shouldBe DownloadPriority.HIGH
        record.conflictStrategy shouldBe ConflictStrategy.OVERWRITE
        record.state shouldBe "QUEUED"
        record.downloadedBytes shouldBe 0L
    }

    @Test
    fun reactiveFlowEmitsUpdatesOnProgressAndStateChanges() = runTest(testDispatcher) {
        val id = DownloadId("dl_flow_02")
        val request = createSampleRequest()

        repository.insertOrUpdate(id, request, "QUEUED", 1000L, 1000L)

        repository.observeById(id).test {
            val initial = awaitItem()
            initial.shouldNotBeNull()
            initial.state shouldBe "QUEUED"

            // Update headers
            repository.updateConnectionHeaders(id, totalBytes = 50_000L, etag = "etag-1", lastModified = null, 1100L)
            val headerState = awaitItem()
            headerState.shouldNotBeNull()
            headerState.state shouldBe "DOWNLOADING"
            headerState.totalBytes shouldBe 50_000L
            headerState.etag shouldBe "etag-1"

            // Update byte progress
            repository.updateProgress(id, downloadedBytes = 25_000L, timestampEpochMs = 1200L)
            val progressState = awaitItem()
            progressState.shouldNotBeNull()
            progressState.downloadedBytes shouldBe 25_000L

            // Complete
            repository.markCompleted(id, 1300L)
            val completedState = awaitItem()
            completedState.shouldNotBeNull()
            completedState.state shouldBe "COMPLETED"
            completedState.downloadedBytes shouldBe 50_000L

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun reconcileStaleStatesTransitionsDownloadingToPausedOnStartup() = runTest(testDispatcher) {
        val dl1 = DownloadId("dl_running_1")
        val dl2 = DownloadId("dl_running_2")
        val dl3 = DownloadId("dl_completed_3")

        repository.insertOrUpdate(dl1, createSampleRequest(), "DOWNLOADING", 1000L, 1000L)
        repository.insertOrUpdate(dl2, createSampleRequest(), "CONNECTING", 1000L, 1000L)
        repository.insertOrUpdate(dl3, createSampleRequest(), "COMPLETED", 1000L, 1000L)

        // Process dies and restarts:
        repository.reconcileStaleStates(timestampEpochMs = 2000L)

        repository.getById(dl1)?.state shouldBe "PAUSED"
        repository.getById(dl2)?.state shouldBe "PAUSED"
        repository.getById(dl3)?.state shouldBe "COMPLETED"
    }

    @Test
    fun failureRecordingPersistsErrorDetailsCorrectly() = runTest(testDispatcher) {
        val id = DownloadId("dl_fail_03")
        repository.insertOrUpdate(id, createSampleRequest(), "DOWNLOADING", 1000L, 1000L)

        val timeoutError = DownloadError.Retryable.NetworkTimeout("Gateway timeout connecting to upstream")
        repository.markFailed(id, timeoutError, timestampEpochMs = 1500L)

        val failedRecord = repository.getById(id)
        failedRecord.shouldNotBeNull()
        failedRecord.state shouldBe "FAILED"
        failedRecord.errorType shouldBe "NetworkTimeout"
        failedRecord.errorMessage shouldBe "Gateway timeout connecting to upstream"
    }

    @Test
    fun deletionRemovesRecordFromPersistence() = runTest(testDispatcher) {
        val id = DownloadId("dl_delete_04")
        repository.insertOrUpdate(id, createSampleRequest(), "QUEUED", 1000L, 1000L)

        repository.getById(id).shouldNotBeNull()
        repository.delete(id)
        repository.getById(id).shouldBeNull()
    }
}