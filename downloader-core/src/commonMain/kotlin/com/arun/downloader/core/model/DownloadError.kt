package com.arun.downloader.core.model

sealed class DownloadError(
    override val message: String,
    override val cause: Throwable? = null
) : Exception(message, cause) {

    sealed class Retryable(
        message: String,
        cause: Throwable? = null
    ) : DownloadError(message, cause) {

        data class NetworkTimeout(
            override val message: String = "Network timed out",
            override val cause: Throwable? = null
        ) : Retryable(message, cause)

        data class ConnectionInterrupted(
            override val message: String = "Connection was interrupted",
            override val cause: Throwable? = null
        ) : Retryable(message, cause)

        data class ServerUnavailable(
            val httpCode: Int,
            override val message: String = "Server unavailable: $httpCode",
            override val cause: Throwable? = null
        ) : Retryable(message, cause)

        data class RateLimited(
            val retryAfterMs: Long?,
            override val message: String = "Rate limit reached",
            override val cause: Throwable? = null
        ) : Retryable(message, cause)
    }

    sealed class NonRetryable(
        message: String,
        cause: Throwable? = null
    ) : DownloadError(message, cause) {
        data class HttpError(
            val httpCode: Int,
            override val message: String = "HTTP error $httpCode",
            override val cause: Throwable? = null
        ) : NonRetryable(message, cause)

        data class InsufficientDiskSpace(
            val requiredBytes: Long,
            val availableBytes: Long,
            override val message: String = "Required $requiredBytes bytes, but only $availableBytes bytes available.",
            override val cause: Throwable? = null
        ) : NonRetryable(message, cause)

        data class FileSystemPermissionDenied(
            val path: String,
            override val message: String = "Permission denied accessing $path",
            override val cause: Throwable? = null
        ) : NonRetryable(message, cause)

        data class MalformedUrl(
            val url: String,
            override val message: String = "Invalid URL: $url",
            override val cause: Throwable? = null
        ) : NonRetryable(message, cause)

        data class FileAlreadyExists(
            val path: String,
            override val message: String = "File already exists at destination: $path",
            override val cause: Throwable? = null
        ) : NonRetryable(message, cause)

        data class ServerDoesNotSupportRange(
            override val message: String = "Server rejected byte range request with non-resumable status.",
            override val cause: Throwable? = null
        ) : NonRetryable(message, cause)

        data class ChecksumMismatch(
            val expected: String,
            val actual: String,
            override val message: String = "Integrity check failed. Expected: $expected, actual: $actual",
            override val cause: Throwable? = null
        ) : NonRetryable(message, cause)

        data class PathTraversalDetected(
            val unsafePath: String,
            override val message: String = "Unsafe filename or path detected: $unsafePath",
            override val cause: Throwable? = null
        ) : NonRetryable(message, cause)

        data class Unknown(
            override val message: String = "Unknown error occurred",
            override val cause: Throwable? = null
        ) : NonRetryable(message, cause)
    }
}