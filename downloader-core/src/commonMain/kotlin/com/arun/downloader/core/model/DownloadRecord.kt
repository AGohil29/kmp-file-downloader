package com.arun.downloader.core.model

import com.arun.downloader.core.model.ConflictStrategy
import com.arun.downloader.core.model.DownloadId
import com.arun.downloader.core.model.DownloadPriority
import okio.Path

data class DownloadRecord(
    val id: DownloadId,
    val url: String,
    val destinationDirectory: Path,
    val fileName: String?,
    val state: String,
    val priority: DownloadPriority,
    val downloadedBytes: Long,
    val totalBytes: Long?,
    val etag: String?,
    val lastModified: String?,
    val conflictStrategy: ConflictStrategy,
    val errorType: String?,
    val errorMessage: String?,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long
)