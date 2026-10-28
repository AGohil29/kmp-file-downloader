package com.arun.downloader.runtime

import app.cash.sqldelight.EnumColumnAdapter
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.arun.downloader.core.model.DownloadId
import com.arun.downloader.core.model.DownloadRequest
import com.arun.downloader.core.model.DownloadState
import com.arun.downloader.core.model.DownloaderConfig
import com.arun.downloader.filesystem.OkioDownloadFileSystem
import com.arun.downloader.network.KtorNetworkTransport
import com.arun.downloader.storage.db.DownloadDatabase
import com.arun.downloader.storage.db.DownloadRecordEntity
import com.arun.downloader.storage.repository.SqlDelightDownloadRepository
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toOkioPath
import java.net.InetSocketAddress
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class StreamingProgressIntegrationTest {
    @Test fun cioReportsProgressWithContentLength() = verifyProgress(HttpClient(CIO), true)
    @Test fun cioReportsProgressWithoutContentLength() = verifyProgress(HttpClient(CIO), false)
    @Test fun okHttpReportsProgressWithContentLength() = verifyProgress(HttpClient(OkHttp), true)
    @Test fun okHttpReportsProgressWithoutContentLength() = verifyProgress(HttpClient(OkHttp), false)

    private fun verifyProgress(client: HttpClient, knownLength: Boolean) = runBlocking {
        val fixture = Fixture(client, knownLength)
        try {
            withTimeout(15.seconds) {
                fixture.downloader.enqueue(fixture.request)
                fixture.awaitState { it is DownloadState.Downloading }

                repeat(2) { index ->
                    // DownloadTask throttles using real time; withhold the next chunk until
                    // the previous chunk has propagated all the way to the public list flow.
                    delay(150)
                    fixture.releaseChunk.release()
                    val state = fixture.awaitState {
                        it is DownloadState.Downloading &&
                            it.downloadedBytes == (index + 1) * fixture.chunk.size.toLong() &&
                            it.speedBytesPerSecond > 0
                    } as DownloadState.Downloading
                    assertEquals(if (knownLength) fixture.payload.size.toLong() else null, state.totalBytes)
                    assertFalse(fixture.serverFinished.get(), "Progress must precede the end of the HTTP body")
                }

                fixture.releaseChunk.release()
                val completed = fixture.awaitState { it is DownloadState.Completed } as DownloadState.Completed
                assertEquals(fixture.payload.size.toLong(), completed.totalBytes)
                assertContentEquals(fixture.payload, fixture.directory.resolve("file.bin").readBytes())
                // A new collector must still see history after the live task disappears.
                delay(50)
                assertTrue(fixture.downloader.observeAll().first().single() is DownloadState.Completed)
            }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun cancellingStreamingDownloadKeepsCancelledState() = runBlocking {
        val fixture = Fixture(HttpClient(CIO), true)
        try {
            withTimeout(15.seconds) {
                val id = fixture.downloader.enqueue(fixture.request)
                fixture.awaitState { it is DownloadState.Downloading }
                delay(150)
                fixture.releaseChunk.release()
                fixture.awaitState { it is DownloadState.Downloading && it.downloadedBytes > 0 }
                fixture.downloader.cancel(id)
                delay(200) // Allow task cancellation cleanup to finish.
                assertTrue(fixture.downloader.observeAll().first().single() is DownloadState.Cancelled)
                assertFalse(fixture.directory.resolve("file.bin").exists())
            }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun pauseThenResumeUsesRangeAndCompletes() = runBlocking {
        val fixture = Fixture(HttpClient(OkHttp), true)
        try {
            withTimeout(15.seconds) {
                val id = fixture.downloader.enqueue(fixture.request)
                fixture.awaitState { it is DownloadState.Downloading }
                delay(150)
                fixture.releaseChunk.release()
                fixture.awaitState { it is DownloadState.Downloading && it.downloadedBytes > 0 }
                fixture.downloader.pause(id)
                val paused = fixture.awaitState { it is DownloadState.Paused } as DownloadState.Paused
                assertEquals(fixture.chunk.size.toLong(), paused.downloadedBytes)
                fixture.downloader.resume(id)
                fixture.awaitState { it is DownloadState.Completed }
                assertEquals("bytes=${fixture.chunk.size}-", fixture.lastRange.get())
                assertContentEquals(fixture.payload, fixture.directory.resolve("file.bin").readBytes())
            }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun concurrentDownloadsReportIndependentProgress() = runBlocking {
        val fixture = Fixture(HttpClient(CIO), true)
        try {
            withTimeout(15.seconds) {
                val first = fixture.downloader.enqueue(fixture.request)
                val second = fixture.downloader.enqueue(
                    fixture.request.copy(url = fixture.request.url + "?second", fileName = "second.bin")
                )
                fixture.awaitState(first) { it is DownloadState.Downloading }
                fixture.awaitState(second) { it is DownloadState.Downloading }
                delay(150)
                fixture.releaseChunk.release()
                fixture.awaitState(first) { it is DownloadState.Downloading && it.downloadedBytes == 4096L }
                val waiting = fixture.awaitState(second) { it is DownloadState.Downloading } as DownloadState.Downloading
                assertEquals(0L, waiting.downloadedBytes)
                fixture.releaseSecondChunk.release()
                fixture.awaitState(second) { it is DownloadState.Downloading && it.downloadedBytes == 4096L }
                fixture.releaseChunk.release(2)
                fixture.releaseSecondChunk.release(2)
                fixture.downloader.observeAll().first { states ->
                    states.size == 2 && states.all { it is DownloadState.Completed }
                }
                assertContentEquals(fixture.payload, fixture.directory.resolve("file.bin").readBytes())
                assertContentEquals(fixture.payload, fixture.directory.resolve("second.bin").readBytes())
            }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun truncatedResponseFailsWithoutCommittingFile() = runBlocking {
        val fixture = Fixture(HttpClient(CIO), true)
        val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val executor = Executors.newSingleThreadExecutor()
        val writer = executor.submit {
            socket.accept().use { connection ->
                connection.soTimeout = 5_000
                val reader = connection.getInputStream().bufferedReader()
                while (!reader.readLine().isNullOrEmpty()) { }
                connection.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Length: 10000\r\nConnection: close\r\n\r\n".toByteArray())
                    write(ByteArray(100))
                    flush()
                }
                // Close the TCP connection while 9,900 advertised bytes are still missing.
            }
        }
        try {
            withTimeout(15.seconds) {
                fixture.downloader.enqueue(fixture.request.copy(url = "http://127.0.0.1:${socket.localPort}/file"))
                fixture.awaitState { it is DownloadState.Failed }
                assertFalse(fixture.directory.resolve("file.bin").exists())
            }
        } finally {
            socket.close()
            writer.cancel(true)
            executor.shutdownNow()
            fixture.close()
        }
    }

    private class Fixture(client: HttpClient, knownLength: Boolean) {
        val directory = Files.createTempDirectory("downloader-progress-test").toFile()
        val chunk = ByteArray(4096) { (it % 127).toByte() }
        val payload = chunk + chunk + chunk
        val releaseChunk = Semaphore(0)
        val releaseSecondChunk = Semaphore(0)
        val lastRange = AtomicReference<String?>(null)
        val serverFinished = AtomicBoolean(false)
        private val executor = Executors.newCachedThreadPool()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = this@Fixture.executor
            createContext("/file") { exchange ->
                try {
                    val range = exchange.requestHeaders.getFirst("Range")
                    if (range != null) {
                        lastRange.set(range)
                        val offset = range.removePrefix("bytes=").removeSuffix("-").toInt()
                        exchange.responseHeaders.add("Content-Range", "bytes $offset-${payload.lastIndex}/${payload.size}")
                        exchange.sendResponseHeaders(206, (payload.size - offset).toLong())
                        exchange.responseBody.use { it.write(payload, offset, payload.size - offset) }
                        return@createContext
                    }
                    exchange.responseHeaders.add("ETag", "\"fixture-v1\"")
                    exchange.sendResponseHeaders(200, if (knownLength) payload.size.toLong() else 0)
                    val gate = if (exchange.requestURI.query == "second") releaseSecondChunk else releaseChunk
                    exchange.responseBody.use { body ->
                        repeat(3) {
                            gate.acquire()
                            body.write(chunk)
                            body.flush()
                        }
                    }
                    serverFinished.set(true)
                } catch (_: Exception) {
                    // Expected when the client cancels or fixture cleanup interrupts a writer.
                } finally {
                    exchange.close()
                }
            }
            start()
        }
        private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        private val database = run {
            DownloadDatabase.Schema.create(driver)
            DownloadDatabase(
                driver,
                DownloadRecordEntity.Adapter(EnumColumnAdapter(), EnumColumnAdapter())
            )
        }
        val downloader = DefaultFileDownloader(
            config = DownloaderConfig(stagingDirectory = directory.toOkioPath()),
            repository = SqlDelightDownloadRepository(database, Dispatchers.IO),
            network = KtorNetworkTransport(client),
            fileSystem = OkioDownloadFileSystem()
        )
        val request = DownloadRequest(
            url = "http://127.0.0.1:${server.address.port}/file",
            destinationDirectory = directory.toOkioPath(),
            fileName = "file.bin"
        )

        suspend fun awaitState(id: DownloadId? = null, predicate: (DownloadState) -> Boolean): DownloadState =
            downloader.observeAll().first { states ->
                println("${System.currentTimeMillis()} observeAll: $states")
                states.firstOrNull { id == null || it.id == id }?.let(predicate) == true
            }.first { id == null || it.id == id }

        suspend fun close() {
            downloader.shutdown()
            releaseChunk.release(3)
            releaseSecondChunk.release(3)
            server.stop(0)
            executor.shutdownNow()
            driver.close()
            directory.deleteRecursively()
        }
    }
}



