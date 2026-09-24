package com.arun.downloader.core.repository

import com.arun.downloader.core.model.DownloadError
import com.arun.downloader.core.model.DownloadId
import com.arun.downloader.core.model.DownloadRecord
import com.arun.downloader.core.model.DownloadRequest
import kotlinx.coroutines.flow.Flow

interface DownloadRepository {
    suspend fun insertOrUpdate(
        id: DownloadId,
        request: DownloadRequest,
        initialState: String = "QUEUED",
        createdAtEpochMs: Long,
        updatedAtEpochMs: Long
    )

    suspend fun getById(id: DownloadId): DownloadRecord?

    fun observeById(id: DownloadId): Flow<DownloadRecord?>

    fun observeAll(): Flow<List<DownloadRecord>>

    suspend fun updateState(id: DownloadId, state: String, timestampEpochMs: Long)

    suspend fun updateConnectionHeaders(
        id: DownloadId,
        totalBytes: Long?,
        etag: String?,
        lastModified: String?,
        timestampEpochMs: Long
    )

    suspend fun updateProgress(id: DownloadId, downloadedBytes: Long, timestampEpochMs: Long)

    suspend fun markCompleted(id: DownloadId, timestampEpochMs: Long)

    suspend fun markFailed(id: DownloadId, error: DownloadError, timestampEpochMs: Long)

    suspend fun reconcileStaleStates(timestampEpochMs: Long)

    suspend fun delete(id: DownloadId)

    suspend fun clear()
}