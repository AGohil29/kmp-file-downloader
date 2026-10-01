package com.arun.downloader.core

import com.arun.downloader.core.model.DownloadId
import com.arun.downloader.core.model.DownloadRequest
import com.arun.downloader.core.model.DownloadState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface FileDownloader {
    suspend fun enqueue(request: DownloadRequest): DownloadId
    fun observe(id: DownloadId): StateFlow<DownloadState>
    fun observeAll(): Flow<List<DownloadState>>
    suspend fun pause(id: DownloadId)
    suspend fun resume(id: DownloadId)
    suspend fun cancel(id: DownloadId)
    suspend fun retry(id: DownloadId)
    suspend fun delete(id: DownloadId, deleteFiles: Boolean = true)
    suspend fun shutdown()

    companion object
}