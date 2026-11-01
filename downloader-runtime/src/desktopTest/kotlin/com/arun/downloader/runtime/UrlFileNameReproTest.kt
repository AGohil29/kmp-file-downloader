package com.arun.downloader.runtime

import app.cash.sqldelight.EnumColumnAdapter
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

/**
 * Reproduces: when no explicit fileName is given, the name is derived with
 * `url.substringAfterLast('/')`, so query strings / fragments leak into the file name
 * (`https://x/file.zip?token=abc` -> `file.zip_token=abc`).
 */
class UrlFileNameReproTest {

    private val stagingDir = "/staging".toPath()
    private val downloadDir = "/downloads".toPath()
    private val payload = "payload".encodeToByteArray()

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

    private class OkNetwork(private val payload: ByteArray, private val failFirst: Boolean = false) : NetworkTransport {
        private var calls = 0
        override suspend fun execute(request: NetworkRequest): NetworkResponse {
            if (failFirst && ++calls == 1) throw DownloadError.Retryable.NetworkTimeout()
            return NetworkResponse(
                statusCode = 200,
                headers = emptyMap(),
                bodyChannel = ByteReadChannel(payload),
                contentLength = payload.size.toLong(),
                isPartialContent = false,
                etag = null,
                lastModified = null
            )
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

    private suspend fun awaitState(id: DownloadId, vararg states: String) = withTimeout(5_000.milliseconds) {
        while (repository.getById(id)?.state !in states) delay(10.milliseconds)
    }

    private fun downloadedNames(): List<String> = fakeFs.list(downloadDir).map { it.name }

    private suspend fun download(url: String): List<String> {
        val downloader = newDownloader(OkNetwork(payload))
        val id = downloader.enqueue(DownloadRequest(url = url, destinationDirectory = downloadDir))
        awaitState(id, "COMPLETED", "FAILED")
        println("$url -> ${downloadedNames()}")
        return downloadedNames()
    }

    @Test
    fun queryStringMustNotBecomePartOfFileName() = runBlocking {
        assertEquals(listOf("file.zip"), download("https://x/file.zip?token=abc"), "BUG: query leaked into name")
    }

    @Test
    fun fragmentMustNotBecomePartOfFileName() = runBlocking {
        assertEquals(listOf("file.zip"), download("https://x/dir/file.zip#section"), "BUG: fragment leaked into name")
    }

    @Test
    fun queryContainingSlashesMustNotChangeFileName() = runBlocking {
        assertEquals(
            listOf("file.zip"),
            download("https://x/file.zip?redirect=https://y/other/path"),
            "BUG: name taken from the query's last path segment"
        )
    }

    @Test
    fun percentEncodedNameIsDecoded() = runBlocking {
        assertEquals(listOf("my file.zip"), download("https://x/my%20file.zip"), "BUG: percent-encoding kept")
    }

    @Test
    fun resumedDownloadKeepsSameCleanFileName() = runBlocking {
        val downloader = newDownloader(OkNetwork(payload, failFirst = true))
        val id = downloader.enqueue(DownloadRequest(url = "https://x/file.zip?token=abc", destinationDirectory = downloadDir))
        awaitState(id, "FAILED")

        downloader.resume(id)
        awaitState(id, "COMPLETED", "FAILED")

        println("after resume -> ${downloadedNames()}")
        assertEquals(listOf("file.zip"), downloadedNames(), "BUG: resume() used the raw URL tail as fileName")
    }
}

