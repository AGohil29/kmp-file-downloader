package com.arun.downloader.core.statemachine

import com.arun.downloader.core.model.DownloadId
import com.arun.downloader.core.model.DownloadState

class IllegalStateTransitionException(
    val id: DownloadId,
    val from: DownloadState,
    val event: DownloadStateEvent
) : IllegalStateException(
    "Illegal transition for download '${id.raw}': Cannot execute event '${event::class.simpleName}' from state '${from::class.simpleName}'."
)