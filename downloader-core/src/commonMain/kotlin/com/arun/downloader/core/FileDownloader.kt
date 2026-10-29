package com.arun.downloader.core

import com.arun.downloader.core.model.DownloadId
import com.arun.downloader.core.model.DownloadRequest
import com.arun.downloader.core.model.DownloadState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The primary public entry point for the KMP File Downloader library.
 *
 * Provides thread-safe, coroutine-native download management across Android, iOS, and Desktop.
 * Implementations manage persistent queueing, resumable transfers, network backoff,
 * and background OS handoffs.
 */
interface FileDownloader {
    /**
     * Enqueues a new file download request into the persistent scheduler queue.
     *
     * @param request The [DownloadRequest] specifying the source URL, target path, priority, and options.
     * @return A unique [DownloadId] representing the queued download task.
     */
    suspend fun enqueue(request: DownloadRequest): DownloadId

    /**
     * Idempotent enqueue under a caller-supplied [id] (e.g. derived from a WorkManager work id).
     *
     * - No record for [id]: behaves like [enqueue] but uses [id].
     * - Record exists and is `COMPLETED`, or its task is already running/queued: no-op.
     * - Otherwise (paused, failed, cancelled, ...): resumes the existing download, reusing its
     *   staging `.part` file so the transfer continues via HTTP Range instead of restarting.
     *
     * Safe to call again on every retry attempt of an external scheduler.
     */
    suspend fun enqueue(request: DownloadRequest, id: DownloadId): DownloadId

    /**
     * Observes the reactive state of a specific download task.
     *
     * @param id The [DownloadId] returned when enqueuing.
     * @return A [StateFlow] emitting immediate and ongoing updates ([DownloadState.Downloading], [DownloadState.Completed], etc.).
     */
    fun observe(id: DownloadId): StateFlow<DownloadState>

    /**
     * Observes all active, queued, paused, and historical downloads in the database.
     *
     * @return A cold [Flow] emitting the updated list of all known [DownloadState] instances.
     */
    fun observeAll(): Flow<List<DownloadState>>

    /**
     * Pauses an active or connecting download.
     *
     * Retains partial staging files (`.part`) on disk for subsequent HTTP Range resumption.
     *
     * @param id The unique identifier of the download.
     */
    suspend fun pause(id: DownloadId)

    /**
     * Resumes a paused, queued, or recoverable failed download from its last recorded byte offset.
     *
     * @param id The unique identifier of the download.
     */
    suspend fun resume(id: DownloadId)

    /**
     * Cancels an active or queued download and purges any temporary staging files from disk.
     *
     * @param id The unique identifier of the download.
     */
    suspend fun cancel(id: DownloadId)

    /**
     * Retries a failed download using its previously configured request parameters.
     *
     * @param id The unique identifier of the download.
     */
    suspend fun retry(id: DownloadId)

    /**
     * Permanently deletes download metadata from persistent storage and optionally deletes the output file.
     *
     * @param id The unique identifier of the download.
     * @param deleteFiles When `true`, removes both temporary staging files and any completed final target file.
     */
    suspend fun delete(id: DownloadId, deleteFiles: Boolean = true)

    /**
     * Pauses all active downloads belonging to a specific group tag.
     *
     * @param groupName The group identifier specified in [DownloadRequest.group].
     */
    suspend fun pauseGroup(groupName: String)

    /**
     * Resumes all paused or queued downloads belonging to a specific group tag.
     *
     * @param groupName The group identifier specified in [DownloadRequest.group].
     */
    suspend fun resumeGroup(groupName: String)

    /**
     * Cancels all active, paused, or queued downloads belonging to a specific group tag.
     *
     * @param groupName The group identifier specified in [DownloadRequest.group].
     */
    suspend fun cancelGroup(groupName: String)

    /**
     * Gracefully shuts down the downloader engine, cancelling active workers, flushing in-memory
     * write buffers, and closing network channels.
     */
    suspend fun shutdown()

    companion object
}