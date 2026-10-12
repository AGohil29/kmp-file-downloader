package com.arun.downloader.runtime

import com.arun.downloader.core.FileDownloader
import com.arun.downloader.core.engine.DownloadTask
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.TimeSource

class DefaultFileDownloader(
    private val config: DownloaderConfig,
    private val repository: DownloadRepository,
    private val network: NetworkTransport,
    private val fileSystem: DownloadFileSystem,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : FileDownloader {

    private val scheduler = DownloadScheduler(config.maxConcurrentDownloads, scope)
    private val taskMutex = Mutex()
    private val activeTasks = mutableMapOf<DownloadId, DownloadTask>()
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
            activeTasks[id] = task
        }

        scheduler.enqueue(id, request.priority, now) {
            try {
                task.execute()
            } finally {
                taskMutex.withLock {
                    activeTasks.remove(id)
                }
            }
        }

        return id
    }

    override fun observe(id: DownloadId): StateFlow<DownloadState> {
        val activeTask = activeTasks[id]
        if (activeTask != null) {
            return activeTask.state
        }

        return fallbackStateFlows.getOrPut(id) {
            // Fetch current snapshot synchronously or fallback to Idle
            val initialRecord = runBlocking { repository.getById(id) }
            val initialState = initialRecord?.let { mapRecordToState(it) } ?: DownloadState.Idle(id)

            repository.observeById(id)
                .map { record -> record?.let { mapRecordToState(it) } ?: DownloadState.Idle(id) }
                .stateIn(
                    scope = scope,
                    started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000L),
                    initialValue = initialState
                )
        }
    }

    override fun observeAll(): Flow<List<DownloadState>> {
        return repository.observeAll().map { records ->
            records.map { mapRecordToState(it) }
        }
    }

    override suspend fun pause(id: DownloadId) {
        scheduler.pause(id)
        repository.updateState(id, "PAUSED", currentTimeEpochMs())
        taskMutex.withLock {
            activeTasks.remove(id)
        }
    }

    override suspend fun resume(id: DownloadId) {
        val record = repository.getById(id) ?: return
        if (record.state == "COMPLETED" || record.state == "DOWNLOADING") return

        val now = currentTimeEpochMs()
        repository.updateState(id, "QUEUED", now)

        val request = DownloadRequest(
            url = record.url,
            destinationDirectory = record.destinationDirectory,
            fileName = record.fileName ?: record.url.substringAfterLast('/'),
            priority = record.priority,
            conflictStrategy = record.conflictStrategy
        )

        val task = createDownloadTask(id, request)
        taskMutex.withLock {
            activeTasks[id] = task
        }

        scheduler.enqueue(id, record.priority, now) {
            try {
                task.execute()
            } finally {
                taskMutex.withLock {
                    activeTasks.remove(id)
                }
            }
        }
    }

    override suspend fun cancel(id: DownloadId) {
        scheduler.cancel(id)
        taskMutex.withLock {
            activeTasks.remove(id)
        }
        repository.updateState(id, "CANCELLED", currentTimeEpochMs())
    }

    override suspend fun retry(id: DownloadId) {
        resume(id)
    }

    override suspend fun delete(id: DownloadId, deleteFiles: Boolean) {
        cancel(id)
        val record = repository.getById(id)
        if (deleteFiles && record != null) {
            val partFile = config.stagingDirectory / ".downloader_staging" / "${id.raw}.part"
            fileSystem.delete(partFile)

            val resolvedFileName = record.fileName ?: record.url.substringAfterLast('/')
            val destinationFile = record.destinationDirectory / resolvedFileName
            fileSystem.delete(destinationFile)
        }
        repository.delete(id)
    }

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
            activeTasks.clear()
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
        val resolvedFileName = record.fileName ?: record.url.substringAfterLast('/')
        val resolvedPath = (record.destinationDirectory / resolvedFileName).toString()

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
            "FAILED" -> DownloadState.Failed(
                id = id,
                error = DownloadError.NonRetryable.Unknown(record.errorMessage ?: "Failed"),
                canRetry = true,
                downloadedBytes = record.downloadedBytes
            )
            "CANCELLED" -> DownloadState.Cancelled(id)
            else -> DownloadState.Idle(id)
        }
    }

    private fun currentTimeEpochMs(): Long =
        TimeSource.Monotonic.markNow().elapsedNow().inWholeMilliseconds
}