package com.arun.downloader.core.scheduler

import com.arun.downloader.core.model.DownloadId
import com.arun.downloader.core.model.DownloadPriority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock

class DownloadScheduler(
    initialMaxConcurrent: Int = 3,
    private val scope: CoroutineScope
) {
    private val mutex = Mutex()
    private val queue = SchedulerQueue()
    private val semaphore = Semaphore(initialMaxConcurrent)
    private val activeJobs = mutableMapOf<DownloadId, Job>()

    private val _activeCount = MutableStateFlow(0)
    val activeCount: StateFlow<Int> = _activeCount.asStateFlow()

    private var maxConcurrent: Int = initialMaxConcurrent

    suspend fun updateMaxConcurrent(newMax: Int) = mutex.withLock {
        require(newMax > 0) { "Max concurrent downloads must be greater than zero." }
        val diff = newMax - maxConcurrent
        maxConcurrent = newMax
        if (diff > 0) {
            repeat(diff) { semaphore.release() }
        }
    }

    suspend fun enqueue(
        id: DownloadId,
        priority: DownloadPriority,
        createdAtEpochMs: Long,
        executor: suspend () -> Unit
    ) {
        mutex.withLock {
            if (activeJobs.containsKey(id) || queue.contains(id)) return
            queue.enqueue(id, priority, createdAtEpochMs)
        }
        dispatchNext(executor)
    }

    suspend fun cancel(id: DownloadId) = mutex.withLock {
        queue.remove(id)
        activeJobs.remove(id)?.cancel()
        _activeCount.value = activeJobs.size
    }

    suspend fun pause(id: DownloadId) = cancel(id)

    private fun dispatchNext(executor: suspend () -> Unit) {
        scope.launch {
            semaphore.acquire()

            val nextItem = mutex.withLock {
                val item = queue.pollNext()
                if (item == null) {
                    semaphore.release()
                    null
                } else {
                    item
                }
            } ?: return@launch

            val id = nextItem.id
            val job = launch {
                try {
                    executor()
                } finally {
                    mutex.withLock {
                        activeJobs.remove(id)
                        _activeCount.value = activeJobs.size
                    }
                    semaphore.release()
                    dispatchNext(executor)
                }
            }

            mutex.withLock {
                activeJobs[id] = job
                _activeCount.value = activeJobs.size
            }
        }
    }

    suspend fun shutdown() = mutex.withLock {
        queue.clear()
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
        _activeCount.value = 0
    }
}