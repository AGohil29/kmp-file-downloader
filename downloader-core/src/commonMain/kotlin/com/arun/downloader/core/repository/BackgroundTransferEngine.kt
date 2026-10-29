package com.arun.downloader.core.repository

import com.arun.downloader.core.model.DownloadId
import com.arun.downloader.core.model.DownloadRequest

/**
 * Platform transfer engine that keeps downloading while the app is suspended or terminated
 * (e.g. an iOS background `NSURLSession`). When supplied to the downloader, transfers are handed
 * to the engine instead of the in-process [NetworkTransport]; the engine owns the lifecycle and
 * reports progress/terminal states through [DownloadRepository].
 */
interface BackgroundTransferEngine {
    /** Re-attaches to transfers that outlived the previous process. Call once at startup. */
    fun attach()

    /** Starts the transfer, continuing from persisted resume data when available. */
    suspend fun start(id: DownloadId, request: DownloadRequest)

    /** Stops the transfer, persisting resume data and the PAUSED state. */
    suspend fun pause(id: DownloadId)

    /** Stops the transfer and discards resume data. */
    suspend fun cancel(id: DownloadId)

    fun close() {}
}

class BackgroundTaskInfo(
    val taskIdentifier: Long?,
    val resumeData: ByteArray?
)

/**
 * Durable storage for the OS-level task identity and resume data of a [BackgroundTransferEngine],
 * so that mappings survive process death.
 */
interface BackgroundTaskStore {
    suspend fun saveBackgroundTask(id: DownloadId, taskIdentifier: Long?, resumeData: ByteArray?)

    suspend fun getBackgroundTask(id: DownloadId): BackgroundTaskInfo?

    suspend fun findByBackgroundTask(taskIdentifier: Long): DownloadId?
}

