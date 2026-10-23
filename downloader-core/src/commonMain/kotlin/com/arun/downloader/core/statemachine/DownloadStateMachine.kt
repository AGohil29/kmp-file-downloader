package com.arun.downloader.core.statemachine

import com.arun.downloader.core.model.DownloadId
import com.arun.downloader.core.model.DownloadState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DownloadStateMachine(
    val id: DownloadId,
    initialState: DownloadState = DownloadState.Idle(id)
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<DownloadState> = _state.asStateFlow()

    suspend fun transition(event: DownloadStateEvent): DownloadState = mutex.withLock {
        val current = _state.value
        val next = calculateNextState(current, event)
        _state.value = next
        next
    }

    private fun calculateNextState(current: DownloadState, event: DownloadStateEvent): DownloadState {
        return when (current) {
            is DownloadState.Idle -> when (event) {
                is DownloadStateEvent.Enqueue -> DownloadState.Queued(id, event.priority, event.timestampEpochMs)
                is DownloadStateEvent.Cancel -> DownloadState.Cancelled(id)
                else -> throw IllegalStateTransitionException(id, current, event)
            }

            is DownloadState.Queued -> when (event) {
                is DownloadStateEvent.AcquireSlot -> DownloadState.Connecting(id)
                is DownloadStateEvent.Pause -> DownloadState.Paused(id, downloadedBytes = 0L, totalBytes = null)
                is DownloadStateEvent.Cancel -> DownloadState.Cancelled(id)
                is DownloadStateEvent.Fail -> DownloadState.Failed(id, event.error, event.canRetry, downloadedBytes = 0L)
                else -> throw IllegalStateTransitionException(id, current, event)
            }

            is DownloadState.Connecting -> when (event) {
                is DownloadStateEvent.HeaderReceived -> DownloadState.Downloading(
                    id = id,
                    downloadedBytes = 0L,
                    totalBytes = event.totalBytes,
                    speedBytesPerSecond = 0L,
                    etag = event.etag,
                    lastModified = event.lastModified
                )
                is DownloadStateEvent.Pause -> DownloadState.Paused(id, downloadedBytes = 0L, totalBytes = null)
                is DownloadStateEvent.Cancel -> DownloadState.Cancelled(id)
                is DownloadStateEvent.Fail -> DownloadState.Failed(id, event.error, event.canRetry, downloadedBytes = 0L)
                else -> throw IllegalStateTransitionException(id, current, event)
            }

            is DownloadState.Downloading -> when (event) {
                is DownloadStateEvent.ProgressUpdate -> current.copy(
                    downloadedBytes = event.downloadedBytes,
                    speedBytesPerSecond = event.speedBytesPerSecond
                )
                is DownloadStateEvent.Pause -> DownloadState.Paused(
                    id = id,
                    downloadedBytes = current.downloadedBytes,
                    totalBytes = current.totalBytes
                )
                is DownloadStateEvent.StartValidation -> DownloadState.Validating(id)
                is DownloadStateEvent.Cancel -> DownloadState.Cancelled(id)
                is DownloadStateEvent.Fail -> DownloadState.Failed(
                    id = id,
                    error = event.error,
                    canRetry = event.canRetry,
                    downloadedBytes = current.downloadedBytes
                )
                else -> throw IllegalStateTransitionException(id, current, event)
            }

            is DownloadState.Paused -> when (event) {
                is DownloadStateEvent.Resume -> DownloadState.Connecting(id)
                is DownloadStateEvent.Cancel -> DownloadState.Cancelled(id)
                else -> throw IllegalStateTransitionException(id, current, event)
            }

            is DownloadState.Validating -> when (event) {
                is DownloadStateEvent.Complete -> DownloadState.Completed(
                    id = id,
                    absolutePath = event.finalPath,
                    totalBytes = event.totalBytes,
                    completedAtEpochMs = event.timestampEpochMs
                )
                is DownloadStateEvent.Fail -> DownloadState.Failed(
                    id = id,
                    error = event.error,
                    canRetry = event.canRetry,
                    downloadedBytes = 0L
                )
                is DownloadStateEvent.Cancel -> DownloadState.Cancelled(id)
                else -> throw IllegalStateTransitionException(id, current, event)
            }

            is DownloadState.Failed -> when (event) {
                is DownloadStateEvent.Resume,
                is DownloadStateEvent.Enqueue -> DownloadState.Connecting(id)
                is DownloadStateEvent.Cancel -> DownloadState.Cancelled(id)
                else -> throw IllegalStateTransitionException(id, current, event)
            }

            // Terminal states: cannot transition further
            is DownloadState.Completed,
            is DownloadState.Cancelled -> throw IllegalStateTransitionException(id, current, event)
        }
    }
}