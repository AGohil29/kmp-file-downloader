package com.arun.downloader.core.model

sealed interface DownloadError {
    val message: String
    val cause: Throwable?

    sealed interface Retryable : DownloadError {
        data class NetworkTimeout(
            override val message: String,
            override val cause: Throwable? = null
        ) : Retryable

        data class ConnectionInterrupted(
            override val message: String,
            override val cause: Throwable? = null
        ) : Retryable

        data class ServerUnavailable(
            val httpCode: Int,
            override val message: String,
            override val cause: Throwable? = null
        ) : Retryable

        data class RateLimited(
            val retryAfterMs: Long?,
            override val message: String,
            override val cause: Throwable? = null
        ) : Retryable
    }

    sealed interface NonRetryable : DownloadError {
        data class HttpError(
            val httpCode: Int,
            override val message: String,
            override val cause: Throwable? = null
        ) : NonRetryable

        data class InsufficientDiskSpace(
            val requiredBytes: Long,
            val availableBytes: Long,
            override val message: String = "Required $requiredBytes bytes, but only $availableBytes bytes available.",
            override val cause: Throwable? = null
        ) : NonRetryable

        data class FileSystemPermissionDenied(
            val path: String,
            override val message: String = "Permission denied accessing $path",
            override val cause: Throwable? = null
        ) : NonRetryable

        data class MalformedUrl(
            val url: String,
            override val message: String = "Invalid URL: $url",
            override val cause: Throwable? = null
        ) : NonRetryable

        data class FileAlreadyExists(
            val path: String,
            override val message: String = "File already exists at destination: $path",
            override val cause: Throwable? = null
        ) : NonRetryable

        data class ServerDoesNotSupportRange(
            override val message: String = "Server rejected byte range request with non-resumable status.",
            override val cause: Throwable? = null
        ) : NonRetryable

        data class ChecksumMismatch(
            val expected: String,
            val actual: String,
            override val message: String = "Integrity check failed. Expected: $expected, actual: $actual",
            override val cause: Throwable? = null
        ) : NonRetryable

        data class PathTraversalDetected(
            val unsafePath: String,
            override val message: String = "Unsafe filename or path detected: $unsafePath",
            override val cause: Throwable? = null
        ) : NonRetryable

        data class Unknown(
            override val message: String,
            override val cause: Throwable? = null
        ) : NonRetryable
    }
}