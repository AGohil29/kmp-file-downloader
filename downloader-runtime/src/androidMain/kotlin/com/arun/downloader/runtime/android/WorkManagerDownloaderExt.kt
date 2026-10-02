package com.arun.downloader.runtime.android

import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.arun.downloader.core.model.DownloadRequest
import java.util.UUID

fun WorkManager.enqueueBackgroundDownload(
    request: DownloadRequest,
    requiresUnmeteredNetwork: Boolean = false,
    requiresCharging: Boolean = false
): UUID {
    val constraints = Constraints.Builder().apply {
        setRequiredNetworkType(
            if (requiresUnmeteredNetwork) NetworkType.UNMETERED else NetworkType.CONNECTED
        )
        setRequiresCharging(requiresCharging)
    }.build()

    val workRequest = OneTimeWorkRequestBuilder<DownloadWorkManagerWorker>()
        .setConstraints(constraints)
        .setInputData(
            workDataOf(
                DownloadWorkManagerWorker.KEY_URL to request.url,
                DownloadWorkManagerWorker.KEY_DEST_DIR to request.destinationDirectory.toString(),
                DownloadWorkManagerWorker.KEY_FILE_NAME to request.fileName
            )
        )
        .build()

    enqueue(workRequest)
    return workRequest.id
}