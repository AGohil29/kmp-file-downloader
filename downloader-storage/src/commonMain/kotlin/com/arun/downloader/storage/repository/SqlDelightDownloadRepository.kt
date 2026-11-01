package com.arun.downloader.storage.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.arun.downloader.core.model.ChecksumValidation
import com.arun.downloader.core.model.DownloadError
import com.arun.downloader.core.model.DownloadId
import com.arun.downloader.core.model.DownloadRecord
import com.arun.downloader.core.model.DownloadRequest
import com.arun.downloader.core.repository.BackgroundTaskInfo
import com.arun.downloader.core.repository.BackgroundTaskStore
import com.arun.downloader.core.repository.DownloadRepository
import com.arun.downloader.storage.db.DownloadDatabase
import com.arun.downloader.storage.db.DownloadRecordEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okio.Path
import okio.Path.Companion.toPath

class SqlDelightDownloadRepository(
    private val database: DownloadDatabase,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default
) : DownloadRepository, BackgroundTaskStore {

    private val queries = database.downloadRecordQueries

    override suspend fun insertOrUpdate(
        id: DownloadId,
        request: DownloadRequest,
        initialState: String,
        createdAtEpochMs: Long,
        updatedAtEpochMs: Long
    ): Unit = withContext(ioDispatcher) {
        queries.insertOrReplaceRecord(
            id = id.raw,
            url = request.url,
            destinationDirectory = request.destinationDirectory.toString(),
            fileName = request.fileName,
            state = initialState,
            priority = request.priority,
            downloadedBytes = 0L,
            totalBytes = null,
            etag = null,
            lastModified = null,
            conflictStrategy = request.conflictStrategy,
            errorType = null,
            errorMessage = null,
            createdAtEpochMs = createdAtEpochMs,
            updatedAtEpochMs = updatedAtEpochMs,
            downloadGroup = request.group,
            requestHeaders = HeaderCodec.encode(request.headers),
            checksumAlgorithm = request.expectedChecksum?.algorithm?.name,
            checksumHash = request.expectedChecksum?.expectedHash
        )
    }

    override suspend fun getById(id: DownloadId): DownloadRecord? = withContext(ioDispatcher) {
        queries.selectById(id.raw).executeAsOneOrNull()?.toDomain()
    }

    override suspend fun getByGroup(groupName: String): List<DownloadRecord> = withContext(ioDispatcher) {
        queries.selectByGroup(groupName).executeAsList().map { it.toDomain() }
    }

    override fun observeById(id: DownloadId): Flow<DownloadRecord?> {
        return queries.selectById(id.raw)
            .asFlow()
            .mapToOneOrNull(ioDispatcher)
            .map { it?.toDomain() }
    }

    override fun observeAll(): Flow<List<DownloadRecord>> {
        return queries.selectAll()
            .asFlow()
            .mapToList(ioDispatcher)
            .map { list -> list.map { it.toDomain() } }
    }

    override suspend fun updateState(id: DownloadId, state: String, timestampEpochMs: Long): Unit =
        withContext(ioDispatcher) {
            queries.updateState(state = state, updatedAtEpochMs = timestampEpochMs, id = id.raw)
        }

    override suspend fun updateConnectionHeaders(
        id: DownloadId,
        totalBytes: Long?,
        etag: String?,
        lastModified: String?,
        timestampEpochMs: Long
    ): Unit = withContext(ioDispatcher) {
        queries.updateConnectionHeaders(
            totalBytes = totalBytes,
            etag = etag,
            lastModified = lastModified,
            updatedAtEpochMs = timestampEpochMs,
            id = id.raw
        )
    }

    override suspend fun updateProgress(id: DownloadId, downloadedBytes: Long, timestampEpochMs: Long): Unit =
        withContext(ioDispatcher) {
            queries.updateProgress(
                downloadedBytes = downloadedBytes,
                updatedAtEpochMs = timestampEpochMs,
                id = id.raw
            )
        }

    override suspend fun markCompleted(id: DownloadId, finalPath: Path, timestampEpochMs: Long): Unit =
        withContext(ioDispatcher) {
            queries.markCompleted(finalPath = finalPath.toString(), updatedAtEpochMs = timestampEpochMs, id = id.raw)
        }

    override suspend fun markFailed(id: DownloadId, error: DownloadError, timestampEpochMs: Long): Unit =
        withContext(ioDispatcher) {
            queries.markFailed(
                errorType = error::class.simpleName ?: "Unknown",
                errorMessage = error.message,
                updatedAtEpochMs = timestampEpochMs,
                id = id.raw
            )
        }

    override suspend fun reconcileStaleStates(timestampEpochMs: Long): Unit = withContext(ioDispatcher) {
        queries.reconcileStaleStatesOnStartup(reconciledAtEpochMs = timestampEpochMs)
    }

    override suspend fun saveBackgroundTask(
        id: DownloadId,
        taskIdentifier: Long?,
        resumeData: ByteArray?
    ): Unit = withContext(ioDispatcher) {
        queries.updateBackgroundTask(
            backgroundTaskId = taskIdentifier,
            resumeData = resumeData,
            id = id.raw
        )
    }

    override suspend fun getBackgroundTask(id: DownloadId): BackgroundTaskInfo? = withContext(ioDispatcher) {
        queries.selectBackgroundTask(id.raw).executeAsOneOrNull()?.let {
            BackgroundTaskInfo(taskIdentifier = it.background_task_id, resumeData = it.resume_data)
        }
    }

    override suspend fun findByBackgroundTask(taskIdentifier: Long): DownloadId? = withContext(ioDispatcher) {
        queries.selectIdByBackgroundTask(taskIdentifier).executeAsOneOrNull()?.let { DownloadId(it) }
    }

    override suspend fun delete(id: DownloadId): Unit = withContext(ioDispatcher) {
        queries.deleteById(id.raw)
    }

    override suspend fun clear(): Unit = withContext(ioDispatcher) {
        queries.clearAll()
    }

    private fun DownloadRecordEntity.toDomain(): DownloadRecord = DownloadRecord(
        id = DownloadId(id),
        url = url,
        destinationDirectory = destination_directory.toPath(),
        fileName = file_name,
        state = state,
        priority = priority,
        downloadedBytes = downloaded_bytes,
        totalBytes = total_bytes,
        etag = etag,
        lastModified = last_modified,
        conflictStrategy = conflict_strategy,
        errorType = error_type,
        errorMessage = error_message,
        createdAtEpochMs = created_at_epoch_ms,
        updatedAtEpochMs = updated_at_epoch_ms,
        group = download_group,
        finalPath = final_path?.toPath(),
        headers = HeaderCodec.decode(request_headers),
        expectedChecksum = decodeChecksum(checksum_algorithm, checksum_hash)
    )

    private fun decodeChecksum(algorithm: String?, hash: String?): ChecksumValidation? {
        if (algorithm == null || hash == null) return null
        val algo = ChecksumValidation.Algorithm.entries.firstOrNull { it.name == algorithm } ?: return null
        return ChecksumValidation(algo, hash)
    }
}