package com.arun.downloader.core.model

data class QueuedDownloadItem(
    val id: DownloadId,
    val priority: DownloadPriority,
    val createdAtEpochMs: Long
)
