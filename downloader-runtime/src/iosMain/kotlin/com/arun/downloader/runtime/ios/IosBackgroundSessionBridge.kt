package com.arun.downloader.runtime.ios

import com.arun.downloader.core.engine.fileNameFromUrl
import com.arun.downloader.core.model.ChecksumValidation
import com.arun.downloader.core.model.DownloadError
import com.arun.downloader.core.model.DownloadId
import com.arun.downloader.core.model.DownloadRequest
import com.arun.downloader.core.repository.BackgroundTaskStore
import com.arun.downloader.core.repository.BackgroundTransferEngine
import com.arun.downloader.core.repository.DownloadFileSystem
import com.arun.downloader.core.repository.DownloadRepository
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okio.HashingSource
import okio.Path
import okio.Path.Companion.toPath
import okio.blackholeSink
import okio.buffer
import okio.use
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLErrorCancelled
import platform.Foundation.NSURLErrorTimedOut
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDownloadDelegateProtocol
import platform.Foundation.NSURLSessionDownloadTask
import platform.Foundation.NSURLSessionDownloadTaskResumeData
import platform.Foundation.NSURLSessionTask
import platform.Foundation.create
import platform.Foundation.setValue
import platform.Foundation.timeIntervalSince1970
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.posix.memcpy
import kotlin.coroutines.resume

/**
 * [BackgroundTransferEngine] backed by a background `NSURLSession`, so downloads keep running
 * (and finish) while the app is suspended or terminated.
 *
 * - Task identity: each task carries the [DownloadId] in `taskDescription` (persisted by iOS) and its
 *   `taskIdentifier` is stored through [taskStore], so the mapping survives process death.
 * - Pause: the resume data from `cancelByProducingResumeData` is persisted and used by the next [start].
 * - Completion: the system temp file is moved synchronously inside the delegate callback (iOS deletes
 *   it when the callback returns) and then committed to its final destination.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class, ExperimentalCoroutinesApi::class)
class IosBackgroundSessionBridge(
    private val sessionId: String = "com.arun.downloader.background.session",
    private val repository: DownloadRepository,
    private val taskStore: BackgroundTaskStore,
    private val fileSystem: DownloadFileSystem,
    private val stagingDirectory: Path,
) : BackgroundTransferEngine {

    // All mutable state below is only touched from this serial dispatcher.
    private val serial = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + serial)

    private val idsByTask = mutableMapOf<Long, DownloadId>()
    private val connected = mutableSetOf<DownloadId>()
    private val completionHandlers = mutableMapOf<String, () -> Unit>()
    private var inFlight = 0
    private var finishEventsPending = false

    private val session: NSURLSession by lazy {
        val config =
            NSURLSessionConfiguration.backgroundSessionConfigurationWithIdentifier(sessionId)
                .apply {
                    sessionSendsLaunchEvents = true
                    discretionary = false
                }
        NSURLSession.sessionWithConfiguration(config, SessionDelegate(this), null)
    }

    // MARK: - BackgroundTransferEngine

    override fun attach() {
        // Recreating the session with the same identifier re-delivers events of tasks that
        // finished/progressed while the process was not running.
        session.configuration.identifier
    }

    override suspend fun start(id: DownloadId, request: DownloadRequest): Unit = withContext(serial) {
        val info = taskStore.getBackgroundTask(id)
        val liveTaskId = info?.taskIdentifier
        if (liveTaskId != null && findDownloadTask(liveTaskId) != null) return@withContext // already running

        repository.updateState(id, "CONNECTING", nowMs())

        val resumeData = info?.resumeData
        val task: NSURLSessionDownloadTask = if (resumeData != null && resumeData.isNotEmpty()) {
            session.downloadTaskWithResumeData(resumeData.toNSData())
        } else {
            val nsUrl = NSURL.URLWithString(request.url)
            if (nsUrl == null) {
                val error = DownloadError.NonRetryable.MalformedUrl(request.url)
                repository.markFailed(id, error, nowMs())
                return@withContext
            }
            val nsRequest = NSMutableURLRequest.requestWithURL(nsUrl).apply {
                request.headers.forEach { (key, value) -> setValue(value, forHTTPHeaderField = key) }
            }
            session.downloadTaskWithRequest(nsRequest)
        }
        task.taskDescription = id.raw

        val taskId = task.taskIdentifier.toLong()
        idsByTask[taskId] = id
        connected.remove(id)
        // Persist before resuming so a callback/relaunch can always map the task back to its id.
        taskStore.saveBackgroundTask(id, taskId, null)
        task.resume()
    }

    override suspend fun pause(id: DownloadId): Unit = withContext(serial) {
        val info = taskStore.getBackgroundTask(id)
        val task = info?.taskIdentifier?.let { findDownloadTask(it) }

        val resumeData: ByteArray? = if (task != null) {
            suspendCancellableCoroutine { cont ->
                task.cancelByProducingResumeData { data -> cont.resume(data?.toByteArray()) }
            }
        } else {
            info?.resumeData
        }

        info?.taskIdentifier?.let { idsByTask.remove(it) }
        connected.remove(id)
        taskStore.saveBackgroundTask(id, null, resumeData)
        repository.updateState(id, "PAUSED", nowMs())
    }

    override suspend fun cancel(id: DownloadId): Unit = withContext(serial) {
        val info = taskStore.getBackgroundTask(id)
        info?.taskIdentifier?.let { taskId ->
            findDownloadTask(taskId)?.cancel()
            idsByTask.remove(taskId)
        }
        connected.remove(id)
        taskStore.saveBackgroundTask(id, null, null)
        repository.updateState(id, "CANCELLED", nowMs())
    }

    override fun close() {
        scope.cancel()
    }

    // MARK: - Delegate callbacks (forwarded from SessionDelegate)

    internal fun onProgress(
        downloadTask: NSURLSessionDownloadTask,
        totalBytesWritten: Long,
        totalBytesExpectedToWrite: Long
    ) {
        val taskId = downloadTask.taskIdentifier.toLong()
        val description = downloadTask.taskDescription
        track {
            val id = resolveId(taskId, description) ?: return@track
            val now = nowMs()
            if (connected.add(id)) {
                repository.updateConnectionHeaders(
                    id = id,
                    totalBytes = totalBytesExpectedToWrite.takeIf { it > 0 },
                    etag = null,
                    lastModified = null,
                    timestampEpochMs = now
                )
            }
            repository.updateProgress(id, totalBytesWritten, now)
        }
    }

    internal fun onFinishedDownloading(
        downloadTask: NSURLSessionDownloadTask,
        didFinishDownloadingToURL: NSURL
    ) {
        val taskId = downloadTask.taskIdentifier.toLong()
        val description = downloadTask.taskDescription
        val httpStatus = (downloadTask.response as? NSHTTPURLResponse)?.statusCode?.toInt()
        val systemTemp = didFinishDownloadingToURL.path ?: return

        // iOS deletes the temp file as soon as this method returns, so move it synchronously.
        val stagedPath = stageSystemTempFile(systemTemp, taskId)

        track {
            val id = resolveId(taskId, description)
            if (id == null || stagedPath == null) {
                stagedPath?.let { fileSystem.delete(it) }
                return@track
            }
            try {
                finalizeDownload(id, stagedPath, httpStatus)
            } finally {
                forget(taskId, id)
            }
        }
    }

    internal fun onTaskCompleted(
        task: NSURLSessionTask,
        didCompleteWithError: NSError?
    ) {
        val error = didCompleteWithError ?: return
        // Pause/cancel surface as NSURLErrorCancelled; their state is written by pause()/cancel().
        if (error.code == NSURLErrorCancelled) return

        val taskId = task.taskIdentifier.toLong()
        val description = task.taskDescription
        val message = error.localizedDescription
        val code = error.code
        val resumeData = (error.userInfo[NSURLSessionDownloadTaskResumeData] as? NSData)?.toByteArray()

        track {
            val id = resolveId(taskId, description) ?: return@track
            val failure = if (code == NSURLErrorTimedOut) {
                DownloadError.Retryable.NetworkTimeout(message)
            } else {
                DownloadError.Retryable.ConnectionInterrupted(message)
            }
            idsByTask.remove(taskId)
            connected.remove(id)
            // Keep resume data (when iOS provides it) so retry() continues instead of restarting.
            taskStore.saveBackgroundTask(id, null, resumeData)
            repository.markFailed(id, failure, nowMs())
        }
    }

    internal fun onFinishedBackgroundEvents() {
        scope.launch {
            finishEventsPending = true
            flushFinishEventsIfIdle()
        }
    }

    fun registerBackgroundCompletionHandler(identifier: String, handler: () -> Unit) {
        scope.launch { completionHandlers[identifier] = handler }
    }

    // MARK: - Internals

    private suspend fun finalizeDownload(id: DownloadId, stagedPath: Path, httpStatus: Int?) {
        val record = repository.getById(id)
        if (record == null) {
            fileSystem.delete(stagedPath)
            return
        }
        try {
            if (httpStatus != null && httpStatus >= 400) {
                throw DownloadError.NonRetryable.HttpError(httpStatus, "HTTP error $httpStatus")
            }

            record.expectedChecksum?.let { checksum ->
                val actual = hashFile(stagedPath, checksum.algorithm).hex().lowercase()
                val expected = checksum.expectedHash.lowercase()
                if (actual != expected) throw DownloadError.NonRetryable.ChecksumMismatch(expected, actual)
            }

            fileSystem.createDirectories(record.destinationDirectory)
            val finalName = record.fileName ?: fileSystem.sanitizeFileName(fileNameFromUrl(record.url))
            val finalPath = fileSystem.resolveUniquePath(
                directory = record.destinationDirectory,
                baseName = finalName,
                conflictStrategy = record.conflictStrategy
            )
            fileSystem.atomicMove(stagedPath, finalPath)

            val size = fileSystem.size(finalPath)
            repository.updateProgress(id, size, nowMs())
            repository.markCompleted(id, finalPath, nowMs())
        } catch (e: Throwable) {
            fileSystem.delete(stagedPath)
            val error = e as? DownloadError ?: DownloadError.NonRetryable.Unknown(e.message ?: "Move error", e)
            repository.markFailed(id, error, nowMs())
        }
    }

    private suspend fun forget(taskId: Long, id: DownloadId) {
        idsByTask.remove(taskId)
        connected.remove(id)
        taskStore.saveBackgroundTask(id, null, null)
    }

    /** Resolves the download for a task: memory -> `taskDescription` (survives relaunch) -> database. */
    private suspend fun resolveId(taskId: Long, description: String?): DownloadId? {
        idsByTask[taskId]?.let { return it }
        val resolved = description?.takeIf { it.isNotBlank() }?.let { DownloadId(it) }
            ?: taskStore.findByBackgroundTask(taskId)
        if (resolved != null) idsByTask[taskId] = resolved
        return resolved
    }

    private suspend fun findDownloadTask(taskId: Long): NSURLSessionDownloadTask? =
        suspendCancellableCoroutine { cont ->
            session.getTasksWithCompletionHandler { _, _, downloadTasks ->
                val match = downloadTasks?.firstOrNull {
                    (it as? NSURLSessionTask)?.taskIdentifier?.toLong() == taskId
                } as? NSURLSessionDownloadTask
                cont.resume(match)
            }
        }

    /** Runs [block] on the serial scope and defers the system completion handler until it finishes. */
    private fun track(block: suspend () -> Unit) {
        scope.launch {
            inFlight++
            try {
                block()
            } finally {
                inFlight--
                flushFinishEventsIfIdle()
            }
        }
    }

    private fun flushFinishEventsIfIdle() {
        if (!finishEventsPending || inFlight > 0) return
        finishEventsPending = false
        val handler = completionHandlers.remove(sessionId) ?: return
        // The system requires the completion handler to run on the main queue.
        dispatch_async(dispatch_get_main_queue()) { handler() }
    }

    private fun stageSystemTempFile(systemTemp: String, taskId: Long): Path? {
        val dir = (stagingDirectory / ".downloader_staging").toString()
        val target = "$dir/bg_$taskId.tmp"
        val fm = NSFileManager.defaultManager
        fm.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
        fm.removeItemAtPath(target, error = null)
        return if (fm.moveItemAtPath(systemTemp, toPath = target, error = null)) target.toPath() else null
    }

    private fun hashFile(path: Path, algorithm: ChecksumValidation.Algorithm): okio.ByteString {
        val raw = fileSystem.source(path)
        val hashing = when (algorithm) {
            ChecksumValidation.Algorithm.MD5 -> HashingSource.md5(raw)
            ChecksumValidation.Algorithm.SHA1 -> HashingSource.sha1(raw)
            ChecksumValidation.Algorithm.SHA256 -> HashingSource.sha256(raw)
        }
        hashing.buffer().use { it.readAll(blackholeSink()) }
        return hashing.hash
    }

    private fun nowMs(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()
}

/** Objective-C facing delegate; Kotlin classes cannot mix ObjC and Kotlin supertypes. */
private class SessionDelegate(
    private val bridge: IosBackgroundSessionBridge
) : NSObject(), NSURLSessionDownloadDelegateProtocol {

    override fun URLSession(
        session: NSURLSession,
        downloadTask: NSURLSessionDownloadTask,
        didWriteData: Long,
        totalBytesWritten: Long,
        totalBytesExpectedToWrite: Long
    ) = bridge.onProgress(downloadTask, totalBytesWritten, totalBytesExpectedToWrite)

    override fun URLSession(
        session: NSURLSession,
        downloadTask: NSURLSessionDownloadTask,
        didFinishDownloadingToURL: NSURL
    ) = bridge.onFinishedDownloading(downloadTask, didFinishDownloadingToURL)

    override fun URLSession(
        session: NSURLSession,
        task: NSURLSessionTask,
        didCompleteWithError: NSError?
    ) = bridge.onTaskCompleted(task, didCompleteWithError)

    override fun URLSessionDidFinishEventsForBackgroundURLSession(session: NSURLSession) =
        bridge.onFinishedBackgroundEvents()
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    val bytes = ByteArray(size)
    if (size > 0) {
        bytes.usePinned { memcpy(it.addressOf(0), this.bytes, this.length) }
    }
    return bytes
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }
