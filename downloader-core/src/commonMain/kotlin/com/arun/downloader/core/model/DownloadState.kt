package com.arun.downloader.core.model

sealed interface DownloadState {
    val id: DownloadId

    data class Idle(override val id: DownloadId) : DownloadState

    data class Queued(
        override val id: DownloadId,
        val priority: DownloadPriority,
        val createdAtEpochMs: Long
    ) : DownloadState

    data class Connecting(override val id: DownloadId) : DownloadState

    data class Downloading(
        override val id: DownloadId,
        val downloadedBytes: Long,
        val totalBytes: Long?,
        val speedBytesPerSecond: Long,
        val etag: String?,
        val lastModified: String?
    ) : DownloadState {
        init {
            require(downloadedBytes >= 0L) { "downloadedBytes must not be negative." }
            totalBytes?.let {
                require(it >= 0L) { "totalBytes must not be negative." }
            }
            require(speedBytesPerSecond >= 0L) { "speedBytesPerSecond must not be negative." }
        }

        val progressPercentage: Float?
            get() = totalBytes?.let { total ->
                if (total > 0L) ((downloadedBytes.toDouble() / total.toDouble()) * 100.0).toFloat().coerceIn(0f, 100f)
                else null
            }
    }

    data class Paused(
        override val id: DownloadId,
        val downloadedBytes: Long,
        val totalBytes: Long?
    ) : DownloadState

    data class Validating(override val id: DownloadId) : DownloadState

    data class Completed(
        override val id: DownloadId,
        val absolutePath: String,
        val totalBytes: Long,
        val completedAtEpochMs: Long
    ) : DownloadState

    data class Failed(
        override val id: DownloadId,
        val error: DownloadError,
        val canRetry: Boolean,
        val downloadedBytes: Long
    ) : DownloadState

    data class Cancelled(override val id: DownloadId) : DownloadState
}