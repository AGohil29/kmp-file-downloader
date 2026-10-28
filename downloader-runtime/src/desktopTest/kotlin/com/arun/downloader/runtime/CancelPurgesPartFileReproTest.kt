package com.arun.downloader.runtime

import app.cash.sqldelight.EnumColumnAdapter
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
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
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

/**
 * Reproduces: FileDownloader.cancel() is documented to purge temporary staging files, but
 * DefaultFileDownloader.cancel() only stops the job and marks the record CANCELLED. The `.part`
 * file is removed solely by delete().
 */
class CancelPurgesPartFileReproTest {

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

    private fun partFile(id: DownloadId) = stagingDir / ".downloader_staging" / "${id.raw}.part"

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

    private suspend fun awaitUntil(message: String, condition: suspend () -> Boolean) =
        withTimeout(5_000.milliseconds) {
            while (!condition()) delay(10.milliseconds)
        }

    /** Streams a partial body and then stalls, leaving a non-empty .part file on disk. */
    private class StallingNetwork : NetworkTransport {
        override suspend fun execute(request: NetworkRequest): NetworkResponse {
            val channel = ByteChannel(autoFlush = true)
            channel.writeFully(ByteArray(8192) { 1 })
            return NetworkResponse(
                statusCode = 200,
                headers = emptyMap(),
                bodyChannel = channel,
                contentLength = 1_000_000L,
                isPartialContent = false,
                etag = null,
                lastModified = null
            )
        }
        override fun close() {}
    }

    private fun request() = DownloadRequest(
        url = "https://mock.com/big.bin",
        destinationDirectory = downloadDir,
        fileName = "big.bin"
    )

    @Test
    fun cancelMustPurgePartFileOfActiveDownload() = runBlocking {
        val downloader = newDownloader(StallingNetwork())
        val id = downloader.enqueue(request())
        awaitUntil("part file written") { fakeFs.exists(partFile(id)) && fakeFs.metadata(partFile(id)).size!! > 0L }

        downloader.cancel(id)

        println("after cancel: state=${repository.getById(id)?.state}, part exists=${fakeFs.exists(partFile(id))}")
        assertEquals("CANCELLED", repository.getById(id)?.state)
        assertEquals(false, fakeFs.exists(partFile(id)), "BUG: cancel() left the .part file on disk")
    }

    @Test
    fun cancelMustPurgePartFileOfPausedDownload() = runBlocking {
        val downloader = newDownloader(StallingNetwork())
        val id = downloader.enqueue(request())
        awaitUntil("part file written") { fakeFs.exists(partFile(id)) && fakeFs.metadata(partFile(id)).size!! > 0L }
        downloader.pause(id)
        assertEquals(true, fakeFs.exists(partFile(id)), "sanity: pause keeps the partial file")

        downloader.cancel(id)

        assertEquals(false, fakeFs.exists(partFile(id)), "BUG: cancel() of a paused download left the .part file")
    }

    @Test
    fun deleteRemovesPartFile() = runBlocking {
        val downloader = newDownloader(StallingNetwork())
        val id = downloader.enqueue(request())
        awaitUntil("part file written") { fakeFs.exists(partFile(id)) && fakeFs.metadata(partFile(id)).size!! > 0L }

        downloader.delete(id)

        assertEquals(false, fakeFs.exists(partFile(id)))
    }
}

