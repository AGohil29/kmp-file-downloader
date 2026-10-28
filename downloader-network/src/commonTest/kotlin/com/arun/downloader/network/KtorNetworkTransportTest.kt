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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

class KtorNetworkTransportTest {

    @Test
    fun firstBytesArriveBeforeServerFinishesBody() = runTest {
        val releaseTail = CompletableDeferred<Unit>()
        val channel = ByteChannel(autoFlush = true)
        val producer = launch(Dispatchers.Default) {
            channel.writeFully("first".encodeToByteArray())
            releaseTail.await()
            channel.writeFully("last".encodeToByteArray())
            channel.close()
        }
        val transport = KtorNetworkTransport(HttpClient(MockEngine {
            respond(channel, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "9"))
        }))

        try {
            // Real-time timeout: the server is deliberately unable to finish until we read.
            withContext(Dispatchers.Default) {
                withTimeout(5.seconds) {
                    val response = transport.execute(NetworkRequest("https://mock.com/gated"))
                    try {
                        response.contentLength shouldBe 9L
                        val buffer = ByteArray(5)
                        response.bodyChannel.readAvailable(buffer) shouldBe 5
                        buffer.decodeToString() shouldBe "first"
                        releaseTail.isCompleted shouldBe false
                        releaseTail.complete(Unit)
                        response.bodyChannel.readAvailable(buffer) shouldBe 4
                        buffer.decodeToString(0, 4) shouldBe "last"
                    } finally {
                        response.close()
                    }
                }
            }
        } finally {
            releaseTail.complete(Unit)
            channel.cancel(null)
            producer.cancelAndJoin()
            transport.close()
        }
    }

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
        try {
            response.contentLength shouldBe totalBytes.toLong()
            response.etag shouldBe "\"mock-etag\""
            response.isPartialContent shouldBe false
            val buffer = ByteArray(8192)
            var totalBytesRead = 0L
            while (true) {
                val read = response.bodyChannel.readAvailable(buffer, 0, buffer.size)
                if (read == -1) break
                totalBytesRead += read
            }
            totalBytesRead shouldBe totalBytes.toLong()
        } finally {
            response.close()
            transport.close()
        }
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

        try {
            response.statusCode shouldBe 206
            response.isPartialContent shouldBe true
            response.contentLength shouldBe 15L
        } finally {
            response.close()
            transport.close()
        }
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
    fun coroutineCancellationClosesStreamGracefully() = runTest(timeout = 10.seconds) {
        val channel = ByteChannel(autoFlush = true)
        channel.writeFully(byteArrayOf(1))
        val consumed = CompletableDeferred<Unit>()
        val mockEngine = MockEngine { _ ->
            respond(content = channel, status = HttpStatusCode.OK)
        }

        val transport = KtorNetworkTransport(HttpClient(mockEngine))

        val job = launch {
            val response = transport.execute(NetworkRequest("https://mock.com/infinite"))
            try {
                val buffer = ByteArray(1024)
                response.bodyChannel.readAvailable(buffer) shouldBe 1
                consumed.complete(Unit)
                while (response.bodyChannel.readAvailable(buffer) != -1) { }
            } finally {
                response.close()
            }
        }

        try {
            consumed.await()
            job.cancelAndJoin()
            job.isCancelled shouldBe true
            channel.isClosedForWrite shouldBe true
        } finally {
            job.cancelAndJoin()
            transport.close()
        }
    }

    @Test
    fun closingUnreadResponseClosesBody() = runTest(timeout = 10.seconds) {
        val channel = ByteChannel(autoFlush = true)
        val transport = KtorNetworkTransport(HttpClient(MockEngine {
            respond(channel, HttpStatusCode.OK)
        }))
        try {
            val response = transport.execute(NetworkRequest("https://mock.com/unread"))
            response.close()
            channel.isClosedForWrite shouldBe true
        } finally {
            channel.cancel(null)
            transport.close()
        }
    }

    @Test
    fun httpErrorIsReportedWithoutWaitingForBody() = runTest(timeout = 10.seconds) {
        val channel = ByteChannel(autoFlush = true)
        val transport = KtorNetworkTransport(HttpClient(MockEngine {
            respond(channel, HttpStatusCode.ServiceUnavailable)
        }))
        try {
            shouldThrow<DownloadError.Retryable.ServerUnavailable> {
                transport.execute(NetworkRequest("https://mock.com/error-with-open-body"))
            }
        } finally {
            channel.cancel(null)
            transport.close()
        }
    }

    @Test
    fun http416OnRangedRequestIsTypedWithTotalFromContentRange() = runTest {
        val transport = KtorNetworkTransport(HttpClient(MockEngine {
            respond("", HttpStatusCode.RequestedRangeNotSatisfiable, headersOf(HttpHeaders.ContentRange, "bytes */1234"))
        }))
        try {
            val error = shouldThrow<DownloadError.NonRetryable.RangeNotSatisfiable> {
                transport.execute(NetworkRequest("https://mock.com/f", rangeStart = 1234L))
            }
            error.totalBytes shouldBe 1234L
        } finally {
            transport.close()
        }
    }

    @Test
    fun http416WithoutRangeHeaderIsServerDoesNotSupportRange() = runTest {
        val transport = KtorNetworkTransport(HttpClient(MockEngine {
            respond("", HttpStatusCode.RequestedRangeNotSatisfiable)
        }))
        try {
            shouldThrow<DownloadError.NonRetryable.ServerDoesNotSupportRange> {
                transport.execute(NetworkRequest("https://mock.com/f"))
            }
        } finally {
            transport.close()
        }
    }

    @Test
    fun http412OnRangedRequestIsPreconditionFailed() = runTest {
        val transport = KtorNetworkTransport(HttpClient(MockEngine {
            respond("", HttpStatusCode.PreconditionFailed)
        }))
        try {
            shouldThrow<DownloadError.NonRetryable.PreconditionFailed> {
                transport.execute(NetworkRequest("https://mock.com/f", rangeStart = 5L, ifRange = "\"e\""))
            }
        } finally {
            transport.close()
        }
    }

    @Test
    fun rangedRequestMustDisableContentEncoding() = runTest {
        var acceptEncoding: String? = "<unset>"
        val transport = KtorNetworkTransport(HttpClient(MockEngine { req ->
            acceptEncoding = req.headers[HttpHeaders.AcceptEncoding]
            respond("", HttpStatusCode.PartialContent, headersOf(HttpHeaders.ContentRange, "bytes 5-9/10"))
        }))
        try {
            transport.execute(NetworkRequest("https://mock.com/f", rangeStart = 5L, ifRange = "\"e\"")).close()
            println("ranged Accept-Encoding=$acceptEncoding")
            acceptEncoding shouldBe "identity" // BUG: not set, server may compress and break byte offsets
        } finally {
            transport.close()
        }
    }
}