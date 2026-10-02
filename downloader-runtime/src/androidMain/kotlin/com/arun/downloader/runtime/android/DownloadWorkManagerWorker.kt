package com.arun.downloader.runtime.android

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.arun.downloader.core.FileDownloader
import com.arun.downloader.core.model.DownloadRequest
import com.arun.downloader.core.model.DownloadState
import kotlinx.coroutines.flow.first
import okio.Path.Companion.toPath

class DownloadWorkManagerWorker(
    appContext: Context,
    params: WorkerParameters,
    private val downloaderProvider: (Context) -> FileDownloader = { defaultDownloaderFactory(it) }
) : CoroutineWorker(appContext, params) {

    // Default 2-parameter constructor required by Android WorkManager's default WorkerFactory
    constructor(appContext: Context, params: WorkerParameters) : this(
        appContext = appContext,
        params = params,
        downloaderProvider = { defaultDownloaderFactory(it) }
    )

    override suspend fun doWork(): Result {
        val url = inputData.getString(KEY_URL) ?: return Result.failure()
        val destDir = inputData.getString(KEY_DEST_DIR) ?: applicationContext.filesDir.absolutePath
        val fileName = inputData.getString(KEY_FILE_NAME)

        val notificationManager = DownloadNotificationManager(applicationContext)
        val notification = notificationManager.buildProgressNotification(
            title = fileName ?: "Background Download",
            downloadedBytes = 0L,
            totalBytes = null,
            progressPercent = null,
            downloadId = url
        )

        // Set foreground info complying with Android 14+ dataSync requirements
        val foregroundInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                DownloadForegroundService.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(DownloadForegroundService.NOTIFICATION_ID, notification)
        }

        try {
            setForeground(foregroundInfo)
        } catch (_: Exception) {
            // Foreground promotion may fail if background execution constraints trigger; proceed normally
        }

        val downloader = downloaderProvider(applicationContext)

        return try {
            val request = DownloadRequest(
                url = url,
                destinationDirectory = destDir.toPath(),
                fileName = fileName
            )
            val id = downloader.enqueue(request)

            // Await terminal state using Flow.first to terminate cleanly
            val terminalState = downloader.observe(id).first { state ->
                state is DownloadState.Completed ||
                        state is DownloadState.Failed ||
                        state is DownloadState.Cancelled
            }

            when (terminalState) {
                is DownloadState.Completed -> {
                    Result.success(
                        workDataOf(
                            KEY_OUTPUT_PATH to terminalState.absolutePath,
                            KEY_TOTAL_BYTES to terminalState.totalBytes
                        )
                    )
                }
                is DownloadState.Failed -> {
                    if (terminalState.canRetry && runAttemptCount < MAX_RETRIES) {
                        Result.retry()
                    } else {
                        Result.failure()
                    }
                }
                is DownloadState.Cancelled -> Result.failure()
                else -> Result.failure()
            }
        } catch (e: Exception) {
            if (runAttemptCount < MAX_RETRIES) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    companion object {
        const val KEY_URL = "key_url"
        const val KEY_DEST_DIR = "key_dest_dir"
        const val KEY_FILE_NAME = "key_file_name"
        const val KEY_OUTPUT_PATH = "key_output_path"
        const val KEY_TOTAL_BYTES = "key_total_bytes"
        private const val MAX_RETRIES = 3

        @Volatile
        var downloaderFactoryOverride: ((Context) -> FileDownloader)? = null

        private fun defaultDownloaderFactory(context: Context): FileDownloader {
            return downloaderFactoryOverride?.invoke(context)
                ?: DownloadForegroundService.downloaderInstance
                ?: throw IllegalStateException(
                    "FileDownloader instance not initialized. Set DownloadForegroundService.downloaderInstance " +
                            "or DownloadWorkManagerWorker.downloaderFactoryOverride before enqueuing WorkManager tasks."
                )
        }
    }
}