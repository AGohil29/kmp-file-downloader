package com.arun.downloader.runtime.ios

import com.arun.downloader.core.model.DownloadError
import com.arun.downloader.core.model.DownloadId
import com.arun.downloader.core.model.DownloadPriority
import com.arun.downloader.core.repository.DownloadFileSystem
import com.arun.downloader.core.repository.DownloadRepository
import com.arun.downloader.core.statemachine.DownloadStateEvent
import com.arun.downloader.core.statemachine.DownloadStateMachine
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okio.Path.Companion.toPath
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDownloadDelegateProtocol
import platform.Foundation.NSURLSessionDownloadTask
import platform.Foundation.NSURLSessionTask
import platform.Foundation.setValue
import platform.Foundation.timeIntervalSince1970
import platform.darwin.NSObject

@OptIn(ExperimentalForeignApi::class)
class IosBackgroundSessionBridge(
    private val sessionId: String = "com.arun.downloader.background.session",
    private val repository: DownloadRepository,
    private val fileSystem: DownloadFileSystem,
) : NSObject(), NSURLSessionDownloadDelegateProtocol {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Maps iOS taskIdentifier to our domain DownloadId
    private val activeTasks = mutableMapOf<ULong, DownloadId>()
    private val stateMachines = mutableMapOf<DownloadId, DownloadStateMachine>()
    private val completionHandlers = mutableMapOf<String, () -> Unit>()

    private val session: NSURLSession by lazy {
        val config =
            NSURLSessionConfiguration.backgroundSessionConfigurationWithIdentifier(sessionId)
                .apply {
                    sessionSendsLaunchEvents = true
                    discretionary = false // Download immediately, don't defer for AC power/unmetered Wi-Fi unless configured
                }
        NSURLSession.sessionWithConfiguration(config, this, null)
    }

    fun startDownload(
        id: DownloadId,
        url: String,
        headers: Map<String, String>,
        priority: DownloadPriority = DownloadPriority.NORMAL,
        stateMachine: DownloadStateMachine,
    ) {
        stateMachines[id] = stateMachine

        val nsUrl = NSURL(string = url) ?: run {
            scope.launch {
                val error = DownloadError.NonRetryable.MalformedUrl(url)
                stateMachine.transition(DownloadStateEvent.Fail(error, canRetry = false))
                repository.markFailed(id, error, getEpochMs())
            }
            return
        }

        val request = NSMutableURLRequest.requestWithURL(nsUrl).apply {
            headers.forEach { (key, value) ->
                setValue(value, forHTTPHeaderField = key)
            }
        }

        val task = session.downloadTaskWithRequest(request)
        val taskId = task.taskIdentifier
        activeTasks[taskId] = id

        scope.launch {
            val now = getEpochMs()
            stateMachine.transition(DownloadStateEvent.Enqueue(priority, now))
            stateMachine.transition(DownloadStateEvent.AcquireSlot)
            repository.updateState(id, "CONNECTING", now)
        }

        task.resume()
    }

    fun pauseDownload(id: DownloadId, onPaused: ((ByteArray?) -> Unit)? = null) {
        val entry = activeTasks.entries.firstOrNull { it.value == id } ?: return
        val taskId = entry.key

        session.getTasksWithCompletionHandler { _, _, downloadTasks ->
            downloadTasks?.forEach { t ->
                val dt = t as? NSURLSessionDownloadTask
                if (dt?.taskIdentifier == taskId) {
                    dt.cancelByProducingResumeData { _ ->
                        scope.launch {
                            stateMachines[id]?.transition(DownloadStateEvent.Pause)
                            repository.updateState(id, "PAUSED", getEpochMs())
                        }
                        onPaused?.invoke(null)
                    }
                }
            }
        }
    }

    fun cancelDownload(id: DownloadId) {
        val entry = activeTasks.entries.firstOrNull { it.value == id } ?: return
        val taskId = entry.key

        session.getTasksWithCompletionHandler { _, _, downloadTasks ->
            downloadTasks?.forEach { t ->
                val dt = t as? NSURLSessionDownloadTask
                if (dt?.taskIdentifier == taskId) {
                    dt.cancel()
                    scope.launch {
                        stateMachines[id]?.transition(DownloadStateEvent.Cancel)
                        repository.updateState(id, "CANCELLED", getEpochMs())
                        cleanupTask(taskId)
                    }
                }
            }
        }
    }

    // MARK: - NSURLSessionDownloadDelegate Callbacks
    override fun URLSession(
        session: NSURLSession,
        downloadTask: NSURLSessionDownloadTask,
        didWriteData: Long,
        totalBytesWritten: Long,
        totalBytesExpectedToWrite: Long
    ) {
        val downloadId = activeTasks[downloadTask.taskIdentifier] ?: return

        scope.launch {
            val sm = stateMachines[downloadId] ?: return@launch
            sm.transition(
                DownloadStateEvent.ProgressUpdate(
                    downloadedBytes = totalBytesWritten,
                    speedBytesPerSecond = 0L
                )
            )
            repository.updateProgress(downloadId, totalBytesWritten, getEpochMs())
        }
    }

    override fun URLSession(
        session: NSURLSession,
        downloadTask: NSURLSessionDownloadTask,
        didFinishDownloadingToURL: NSURL
    ) {
        val downloadId = activeTasks[downloadTask.taskIdentifier] ?: return
        val tempPathStr = didFinishDownloadingToURL.path ?: return

        scope.launch {
            val record = repository.getById(downloadId) ?: return@launch
            val sm = stateMachines[downloadId]

            try {
                sm?.transition(DownloadStateEvent.StartValidation)

                val tempOkioPath = tempPathStr.toPath()
                val targetDirectory = record.destinationDirectory
                fileSystem.createDirectories(targetDirectory)

                val finalFileName = record.fileName ?: fileSystem.sanitizeFileName(record.url.substringAfterLast('/'))
                val finalDestination = fileSystem.resolveUniquePath(
                    directory = targetDirectory,
                    baseName = finalFileName,
                    conflictStrategy = record.conflictStrategy
                )

                // Atomic move from iOS sandbox temp directory to target directory via Okio
                fileSystem.atomicMove(tempOkioPath, finalDestination)

                val now = getEpochMs()
                val totalBytes = fileSystem.size(finalDestination)
                sm?.transition(
                    DownloadStateEvent.Complete(
                        finalPath = finalDestination.toString(),
                        totalBytes = totalBytes,
                        timestampEpochMs = now
                    )
                )
                repository.markCompleted(downloadId, finalDestination, now)
            } catch (e: Throwable) {
                val error = DownloadError.NonRetryable.Unknown(e.message ?: "Move error", e)
                sm?.transition(DownloadStateEvent.Fail(error, canRetry = false))
                repository.markFailed(downloadId, error, getEpochMs())
            } finally {
                cleanupTask(downloadTask.taskIdentifier)
            }
        }
    }

    override fun URLSession(
        session: NSURLSession,
        task: NSURLSessionTask,
        didCompleteWithError: NSError?
    ) {
        val downloadId = activeTasks[task.taskIdentifier] ?: return
        if (didCompleteWithError != null) {
            val message = didCompleteWithError.localizedDescription
            scope.launch {
                val error = DownloadError.Retryable.ConnectionInterrupted(message)
                stateMachines[downloadId]?.transition(DownloadStateEvent.Fail(error, canRetry = true))
                repository.markFailed(downloadId, error, getEpochMs())
                cleanupTask(task.taskIdentifier)
            }
        }
    }

    override fun URLSessionDidFinishEventsForBackgroundURLSession(session: NSURLSession) {
        val handler = completionHandlers.remove(sessionId)
        handler?.invoke()
    }

    fun registerBackgroundCompletionHandler(identifier: String, handler: () -> Unit) {
        completionHandlers[identifier] = handler
    }

    private fun cleanupTask(taskId: ULong) {
        val id = activeTasks.remove(taskId)
        if (id != null) {
            stateMachines.remove(id)
        }
    }

    private fun getEpochMs(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()
}