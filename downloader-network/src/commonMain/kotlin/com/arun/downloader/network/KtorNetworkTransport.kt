package com.arun.downloader.network

import com.arun.downloader.core.model.DownloadError
import com.arun.downloader.network.model.NetworkRequest
import com.arun.downloader.network.model.NetworkResponse
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.headers
import io.ktor.client.request.prepareRequest
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import okio.IOException
import kotlin.coroutines.cancellation.CancellationException

class KtorNetworkTransport(
    private val client: HttpClient
) : NetworkTransport {
    override suspend fun execute(request: NetworkRequest): NetworkResponse {
        try {
            val statement = client.prepareRequest(request.url) {
                method = HttpMethod.Get

                headers {
                    request.headers.forEach { (key, value) ->
                        append(key, value)
                    }

                    // Apply HTTP Range headers if specified
                    if (request.rangeStart != null) {
                        val end = request.rangeEnd?.toString() ?: ""
                        append(HttpHeaders.Range, "bytes=${request.rangeStart}-$end")
                        request.ifRange?.let { append(HttpHeaders.IfRange, it) }
                    }
                }
            }

            val httpResponse = statement.execute()
            val status = httpResponse.status.value

            // Error translation based on status code
            if (status >= 400) {
                mapAndThrowHttpError(status, httpResponse.headers[HttpHeaders.RetryAfter])
            }

            val rawHeaders = mutableMapOf<String, String>()
            httpResponse.headers.names().forEach { name ->
                httpResponse.headers[name]?.let { value -> rawHeaders[name] = value }
            }
            val contentLength = httpResponse.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            val etag = httpResponse.headers[HttpHeaders.ETag]
            val lastModified = httpResponse.headers[HttpHeaders.LastModified]
            val isPartial = status == HttpStatusCode.PartialContent.value

            return NetworkResponse(
                statusCode = status,
                headers = rawHeaders,
                bodyChannel = httpResponse.bodyAsChannel(),
                contentLength = contentLength,
                isPartialContent = isPartial,
                etag = etag,
                lastModified = lastModified
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: DownloadError) {
            throw e
        } catch (e: Exception) {
            throw mapNetworkException(e)
        }
    }

    override fun close() {
        client.close()
    }

    private fun mapAndThrowHttpError(statusCode: Int, retryAfterHeader: String?): Nothing {
        when (statusCode) {
            HttpStatusCode.TooManyRequests.value -> {
                val retryAfterMs = retryAfterHeader?.toLongOrNull()?.times(1000L)
                throw DownloadError.Retryable.RateLimited(
                    retryAfterMs = retryAfterMs,
                    message = "Server rate limit hit (HTTP 429)."
                )
            }
            HttpStatusCode.RequestTimeout.value,
            HttpStatusCode.GatewayTimeout.value -> {
                throw DownloadError.Retryable.NetworkTimeout("HTTP timeout ($statusCode).")
            }
            HttpStatusCode.BadGateway.value,
            HttpStatusCode.ServiceUnavailable.value,
            HttpStatusCode.InternalServerError.value -> {
                throw DownloadError.Retryable.ServerUnavailable(
                    httpCode = statusCode,
                    message = "Server temporary failure (HTTP $statusCode)."
                )
            }
            HttpStatusCode.RequestedRangeNotSatisfiable.value -> {
                throw DownloadError.NonRetryable.ServerDoesNotSupportRange(
                    message = "Requested byte range not satisfiable (HTTP 416)."
                )
            }
            else -> {
                throw DownloadError.NonRetryable.HttpError(
                    httpCode = statusCode,
                    message = "HTTP request failed with status $statusCode."
                )
            }
        }
    }

    private fun mapNetworkException(e: Exception): DownloadError {
        val message = e.message ?: "Network error"
        return when (e) {
            is HttpRequestTimeoutException ->
                DownloadError.Retryable.NetworkTimeout(message, e)
            is IOException ->
                DownloadError.Retryable.ConnectionInterrupted(message, e)
            else -> {
                // Catches platform-specific network/socket exceptions cleanly
                if (e::class.simpleName?.contains("Timeout", ignoreCase = true) == true) {
                    DownloadError.Retryable.NetworkTimeout(message, e)
                } else {
                    DownloadError.NonRetryable.Unknown(message, e)
                }
            }
        }
    }

}