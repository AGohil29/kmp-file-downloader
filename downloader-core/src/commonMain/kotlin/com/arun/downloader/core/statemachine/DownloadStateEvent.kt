package com.arun.downloader.core.statemachine

import com.arun.downloader.core.model.DownloadError
import com.arun.downloader.core.model.DownloadPriority

sealed interface DownloadStateEvent {
    data class Enqueue(val priority: DownloadPriority, val timestampEpochMs: Long) : DownloadStateEvent
    data object AcquireSlot : DownloadStateEvent
    data class HeaderReceived(
        val totalBytes: Long?,
        val etag: String?,
        val lastModified: String?
    ) : DownloadStateEvent
    data class ProgressUpdate(
        val downloadedBytes: Long,
        val speedBytesPerSecond: Long
    ) : DownloadStateEvent
    data object Pause : DownloadStateEvent
    data object Resume : DownloadStateEvent
    data object Cancel : DownloadStateEvent
    data object StartValidation : DownloadStateEvent
    data class Complete(
        val finalPath: String,
        val totalBytes: Long,
        val timestampEpochMs: Long
    ) : DownloadStateEvent
    data class Fail(val error: DownloadError, val canRetry: Boolean) : DownloadStateEvent
}