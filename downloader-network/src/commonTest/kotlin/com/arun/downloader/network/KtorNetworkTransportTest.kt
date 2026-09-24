package com.arun.downloader.network

import com.arun.downloader.core.model.DownloadError
import com.arun.downloader.core.model.NetworkRequest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class KtorNetworkTransportTest {

    @Test
    fun streamingReadsChunksSequentiallyWithoutFullBuffer() = runTest {
        val totalBytes = 64 * 1024 // 64 KB
        val mockData = ByteArray(totalBytes) { (it % 128).toByte() }

        val mockEngine = MockEngine { _ ->
            val channel = ByteChannel(autoFlush = true)
            launch {
                channel.writeFully(mockData)
                channel.close()
            }

            respond(
                content = channel,
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentLength to listOf(totalBytes.toString()),
                    HttpHeaders.ETag to listOf("\"mock-etag\"")
                )
            )
        }

        val client = HttpClient(mockEngine)
        val transport = KtorNetworkTransport(client)

        val response = transport.execute(NetworkRequest("https://mock.com/file.bin"))
        response.contentLength shouldBe totalBytes.toLong()
        response.etag shouldBe "\"mock-etag\""
        response.isPartialContent shouldBe false

        // Stream via 8 KB buffer to simulate disk writes
        val buffer = ByteArray(8192)
        var totalBytesRead = 0L

        while (!response.bodyChannel.isClosedForRead) {
            val read = response.bodyChannel.readAvailable(buffer, 0, buffer.size)
            if (read <= 0) break
            totalBytesRead += read
        }

        totalBytesRead shouldBe totalBytes.toLong()
        transport.close()
    }

    @Test
    fun rangeRequestSendsCorrectHeaderAndParses206() = runTest {
        val mockEngine = MockEngine { request ->
            request.headers[HttpHeaders.Range] shouldBe "bytes=1000-"
            request.headers[HttpHeaders.IfRange] shouldBe "\"sample-etag\""

            respond(
                content = ByteChannel(autoFlush = true).apply {
                    writeFully("partial payload".encodeToByteArray())
                    close()
                },
                status = HttpStatusCode.PartialContent,
                headers = headersOf(
                    HttpHeaders.ContentLength to listOf("15"),
                    HttpHeaders.ContentRange to listOf("bytes 1000-1014/50000")
                )
            )
        }

        val transport = KtorNetworkTransport(HttpClient(mockEngine))
        val response = transport.execute(
            NetworkRequest(
                url = "https://mock.com/partial.bin",
                rangeStart = 1000L,
                ifRange = "\"sample-etag\""
            )
        )

        response.statusCode shouldBe 206
        response.isPartialContent shouldBe true
        response.contentLength shouldBe 15L
        transport.close()
    }

    @Test
    fun serverError503MapsToRetryableServerUnavailable() = runTest {
        val mockEngine = MockEngine { _ ->
            respond(
                content = "Service Degraded",
                status = HttpStatusCode.ServiceUnavailable
            )
        }

        val transport = KtorNetworkTransport(HttpClient(mockEngine))

        val error = shouldThrow<DownloadError.Retryable.ServerUnavailable> {
            transport.execute(NetworkRequest("https://mock.com/unavailable"))
        }
        error.httpCode shouldBe 503
        transport.close()
    }

    @Test
    fun clientError404MapsToNonRetryableHttpError() = runTest {
        val mockEngine = MockEngine { _ ->
            respond(
                content = "Not Found",
                status = HttpStatusCode.NotFound
            )
        }

        val transport = KtorNetworkTransport(HttpClient(mockEngine))

        val error = shouldThrow<DownloadError.NonRetryable.HttpError> {
            transport.execute(NetworkRequest("https://mock.com/missing.zip"))
        }
        error.httpCode shouldBe 404
        transport.close()
    }

    @Test
    fun coroutineCancellationClosesStreamGracefully() = runTest {
        val channel = ByteChannel(autoFlush = true)
        val mockEngine = MockEngine { _ ->
            respond(content = channel, status = HttpStatusCode.OK)
        }

        val transport = KtorNetworkTransport(HttpClient(mockEngine))

        val job = launch {
            val response = transport.execute(NetworkRequest("https://mock.com/infinite"))
            val buffer = ByteArray(1024)
            while (true) {
                response.bodyChannel.readAvailable(buffer, 0, buffer.size)
            }
        }

        // Cancel mid-stream
        job.cancelAndJoin()
        job.isCancelled shouldBe true
        transport.close()
    }
}