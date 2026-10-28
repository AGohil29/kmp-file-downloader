package com.arun.downloader.runtime

import com.arun.downloader.core.FileDownloader
import com.arun.downloader.core.engine.DownloadTask
import com.arun.downloader.core.engine.fileNameFromUrl
import com.arun.downloader.core.model.DownloadError
import com.arun.downloader.core.model.DownloadId
import com.arun.downloader.core.model.DownloadRecord
import com.arun.downloader.core.model.DownloadRequest
import com.arun.downloader.core.model.DownloadState
import com.arun.downloader.core.model.DownloaderConfig
import com.arun.downloader.core.repository.DownloadFileSystem
import com.arun.downloader.core.repository.DownloadRepository
import com.arun.downloader.core.repository.NetworkTransport
import com.arun.downloader.core.scheduler.DownloadScheduler
import com.arun.downloader.core.statemachine.DownloadStateMachine
import com.arun.downloader.core.time.EpochClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DefaultFileDownloader(
    private val config: DownloaderConfig,
    private val repository: DownloadRepository,
    private val network: NetworkTransport,
    private val fileSystem: DownloadFileSystem,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : FileDownloader {

    private val scheduler = DownloadScheduler(config.maxConcurrentDownloads, scope)
    private val taskMutex = Mutex()
    private val activeTasks = MutableStateFlow<Map<DownloadId, DownloadTask>>(emptyMap())
    private val fallbackStateFlows = mutableMapOf<DownloadId, StateFlow<DownloadState>>()

    override suspend fun enqueue(request: DownloadRequest): DownloadId {
        val id = DownloadId.generate()
        val now = currentTimeEpochMs()

        repository.insertOrUpdate(
            id = id,
            request = request,
            initialState = "QUEUED",
            createdAtEpochMs = now,
            updatedAtEpochMs = now
        )

        val task = createDownloadTask(id, request)
        taskMutex.withLock {
            activeTasks.value = activeTasks.value + (id to task)
        }

        scheduler.enqueue(id, request.priority, now) {
            try {
                task.execute()
            } finally {
                taskMutex.withLock {
                    activeTasks.value = activeTasks.value - id
                }
            }
        }

        return id
    }

    override fun observe(id: DownloadId): StateFlow<DownloadState> {
        val activeTask = activeTasks.value[id]
        if (activeTask != null) {
            return activeTask.state
        }

        return fallbackStateFlows.getOrPut(id) {
            // Never block the caller (often the UI thread) waiting for database dispatch.
            repository.observeById(id)
                .map { record -> record?.let { mapRecordToState(it) } ?: DownloadState.Idle(id) }
                .stateIn(
                    scope = scope,
                    started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000L),
                    initialValue = DownloadState.Idle(id)
                )
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeAll(): Flow<List<DownloadState>> {
        val liveStates = activeTasks.flatMapLatest { tasks ->
            if (tasks.isEmpty()) {
                flowOf(emptyMap<DownloadId, DownloadState>())
            } else {
                combine(tasks.values.map { it.state }) { states -> states.associateBy { it.id } }
            }
        }
        return combine(repository.observeAll(), liveStates) { records, live ->
            records.map { record ->
                val persisted = mapRecordToState(record)
                val current = live[record.id]
                // Persistence owns lifecycle and byte counts. Only overlay transient speed;
                // a stale live state must never resurrect a paused/completed/deleted record.
                if (persisted is DownloadState.Downloading && current is DownloadState.Downloading) {
                    persisted.copy(speedBytesPerSecond = current.speedBytesPerSecond)
                } else {
                    persisted
                }
            }
        }
    }

    override suspend fun pause(id: DownloadId) {
        scheduler.pause(id)
        repository.updateState(id, "PAUSED", currentTimeEpochMs())
        taskMutex.withLock {
            activeTasks.value = activeTasks.value - id
        }
    }

    override suspend fun resume(id: DownloadId) {
        val record = repository.getById(id) ?: return
        if (record.state == "COMPLETED" || record.state == "DOWNLOADING") return

        val now = currentTimeEpochMs()
        repository.updateState(id, "QUEUED", now)

        // Rebuild the *full* original request: dropping headers (auth) or the checksum here would
        // make resumed/retried downloads fail auth or silently skip integrity validation.
        val request = DownloadRequest(
            url = record.url,
            destinationDirectory = record.destinationDirectory,
            fileName = record.fileName ?: fileNameFromUrl(record.url).ifBlank { null },
            priority = record.priority,
            headers = record.headers,
            conflictStrategy = record.conflictStrategy,
            expectedChecksum = record.expectedChecksum,
            group = record.group
        )

        val task = createDownloadTask(id, request)
        taskMutex.withLock {
            activeTasks.value = activeTasks.value + (id to task)
        }

        scheduler.enqueue(id, record.priority, now) {
            try {
                task.execute()
            } finally {
                taskMutex.withLock {
                    activeTasks.value = activeTasks.value - id
                }
            }
        }
    }

    override suspend fun cancel(id: DownloadId) {
        // scheduler.cancel joins the running job, so the task has released its file handles
        // before the staging file is purged.
        scheduler.cancel(id)
        taskMutex.withLock {
            activeTasks.value = activeTasks.value - id
        }
        fileSystem.delete(stagingPartFile(id))
        repository.updateState(id, "CANCELLED", currentTimeEpochMs())
    }

    override suspend fun retry(id: DownloadId) {
        resume(id)
    }

    override suspend fun delete(id: DownloadId, deleteFiles: Boolean) {
        cancel(id)
        val record = repository.getById(id)
        if (deleteFiles && record != null) {
            record.finalPath?.let { fileSystem.delete(it) }
        }
        repository.delete(id)
    }

    private fun stagingPartFile(id: DownloadId) =
        config.stagingDirectory / ".downloader_staging" / "${id.raw}.part"

    override suspend fun pauseGroup(groupName: String) {
        val records = repository.getByGroup(groupName)
        records.forEach { record ->
            pause(record.id)
        }
    }

    override suspend fun resumeGroup(groupName: String) {
        val records = repository.getByGroup(groupName)
        records.forEach { record ->
            resume(record.id)
        }
    }

    override suspend fun cancelGroup(groupName: String) {
        val records = repository.getByGroup(groupName)
        records.forEach { record ->
            cancel(record.id)
        }
    }

    override suspend fun shutdown() {
        scheduler.shutdown()
        taskMutex.withLock {
            activeTasks.value = emptyMap()
        }
        network.close()
        scope.cancel()
    }

    private fun createDownloadTask(id: DownloadId, request: DownloadRequest): DownloadTask {
        val initialState = DownloadState.Idle(id)
        val stateMachine = DownloadStateMachine(id, initialState)
        return DownloadTask(
            id = id,
            request = request,
            stateMachine = stateMachine,
            repository = repository,
            network = network,
            fileSystem = fileSystem,
            stagingDirectory = config.stagingDirectory,
            progressUpdateIntervalMs = config.progressUpdateIntervalMs
        )
    }

    private fun mapRecordToState(record: DownloadRecord): DownloadState {
        val id = record.id
        // Prefer the persisted committed path; fall back to the requested name only for legacy
        // records completed before final_path was tracked.
        val resolvedPath = record.finalPath?.toString()
            ?: (record.destinationDirectory / (record.fileName ?: fileNameFromUrl(record.url))).toString()

        return when (record.state) {
            "QUEUED" -> DownloadState.Queued(id, record.priority, record.createdAtEpochMs)
            "CONNECTING" -> DownloadState.Connecting(id)
            "DOWNLOADING" -> DownloadState.Downloading(
                id = id,
                downloadedBytes = record.downloadedBytes,
                totalBytes = record.totalBytes,
                speedBytesPerSecond = 0L,
                etag = record.etag,
                lastModified = record.lastModified
            )
            "PAUSED" -> DownloadState.Paused(id, record.downloadedBytes, record.totalBytes)
            "VALIDATING" -> DownloadState.Validating(id)
            "COMPLETED" -> DownloadState.Completed(
                id = id,
                absolutePath = resolvedPath,
                totalBytes = record.downloadedBytes,
                completedAtEpochMs = record.updatedAtEpochMs
            )
            "FAILED" -> {
                val error = restoreError(record.errorType, record.errorMessage)
                DownloadState.Failed(
                    id = id,
                    error = error,
                    canRetry = error is DownloadError.Retryable,
                    downloadedBytes = record.downloadedBytes
                )
            }
            "CANCELLED" -> DownloadState.Cancelled(id)
            else -> DownloadState.Idle(id)
        }
    }

    /**
     * Rebuilds a [DownloadError] from the persisted `error_type` (class simple name) and message.
     * Only type + message are stored, so structured fields are recovered best-effort from the
     * message; unrecognised types fall back to [DownloadError.NonRetryable.Unknown].
     */
    private fun restoreError(type: String?, message: String?): DownloadError {
        val numbers = Regex("-?\\d+").findAll(message.orEmpty()).map { it.value }.toList()
        val code = numbers.firstOrNull()?.toIntOrNull() ?: 0
        fun msg(default: String) = message ?: default
        return when (type) {
            "NetworkTimeout" -> DownloadError.Retryable.NetworkTimeout(msg("Network timed out"))
            "ConnectionInterrupted" ->
                DownloadError.Retryable.ConnectionInterrupted(msg("Connection was interrupted"))
            "ServerUnavailable" -> DownloadError.Retryable.ServerUnavailable(code, msg("Server unavailable: $code"))
            "RateLimited" -> DownloadError.Retryable.RateLimited(null, msg("Rate limit reached"))
            "HttpError" -> DownloadError.NonRetryable.HttpError(code, msg("HTTP error $code"))
            "InsufficientDiskSpace" -> DownloadError.NonRetryable.InsufficientDiskSpace(
                requiredBytes = numbers.getOrNull(0)?.toLongOrNull() ?: 0L,
                availableBytes = numbers.getOrNull(1)?.toLongOrNull() ?: 0L,
                message = msg("Insufficient disk space")
            )
            "FileSystemPermissionDenied" -> DownloadError.NonRetryable.FileSystemPermissionDenied(
                path = message?.substringAfter("accessing ", "").orEmpty(),
                message = msg("Permission denied")
            )
            "MalformedUrl" -> DownloadError.NonRetryable.MalformedUrl(
                url = message?.substringAfter("Invalid URL: ", "").orEmpty(),
                message = msg("Invalid URL")
            )
            "FileAlreadyExists" -> DownloadError.NonRetryable.FileAlreadyExists(
                path = message?.substringAfter("destination: ", "").orEmpty(),
                message = msg("File already exists")
            )
            "ServerDoesNotSupportRange" -> DownloadError.NonRetryable.ServerDoesNotSupportRange(
                msg("Server rejected byte range request with non-resumable status.")
            )
            "RangeNotSatisfiable" -> DownloadError.NonRetryable.RangeNotSatisfiable(
                totalBytes = null,
                message = msg("Requested byte range not satisfiable (HTTP 416).")
            )
            "PreconditionFailed" -> DownloadError.NonRetryable.PreconditionFailed(
                msg("Precondition failed (HTTP 412).")
            )
            "ChecksumMismatch" -> DownloadError.NonRetryable.ChecksumMismatch(
                expected = message?.substringAfter("Expected: ", "")?.substringBefore(",").orEmpty(),
                actual = message?.substringAfter("actual: ", "").orEmpty(),
                message = msg("Integrity check failed")
            )
            "PathTraversalDetected" -> DownloadError.NonRetryable.PathTraversalDetected(
                unsafePath = message?.substringAfter("detected: ", "").orEmpty(),
                message = msg("Unsafe filename or path detected")
            )
            else -> DownloadError.NonRetryable.Unknown(msg("Failed"))
        }
    }

    private fun currentTimeEpochMs(): Long = EpochClock.nowEpochMs()
}