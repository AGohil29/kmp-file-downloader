package com.arun.downloader.core.scheduler

import com.arun.downloader.core.model.DownloadId
import com.arun.downloader.core.model.DownloadPriority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class DownloadScheduler(
    initialMaxConcurrent: Int = 3,
    private val scope: CoroutineScope
) {
    private val mutex = Mutex()
    private val queue = SchedulerQueue()
    private val pendingExecutors = mutableMapOf<DownloadId, suspend () -> Unit>()
    private val activeJobs = mutableMapOf<DownloadId, Job>()

    private val _activeCount = MutableStateFlow(0)
    val activeCount: StateFlow<Int> = _activeCount.asStateFlow()

    private var maxConcurrent: Int = initialMaxConcurrent
    private var closed = false

    init {
        require(initialMaxConcurrent > 0) { "Max concurrent downloads must be greater than zero." }
    }

    suspend fun updateMaxConcurrent(newMax: Int) = mutex.withLock {
        require(newMax > 0) { "Max concurrent downloads must be greater than zero." }
        maxConcurrent = newMax
        dispatchLocked()
    }

    suspend fun enqueue(
        id: DownloadId,
        priority: DownloadPriority,
        createdAtEpochMs: Long,
        executor: suspend () -> Unit
    ) {
        mutex.withLock {
            check(!closed) { "Scheduler has been shut down." }
            if (activeJobs.containsKey(id) || queue.contains(id)) return
            pendingExecutors[id] = executor
            queue.enqueue(id, priority, createdAtEpochMs)
            dispatchLocked()
        }
    }

    suspend fun cancel(id: DownloadId) {
        val job = mutex.withLock {
            queue.remove(id)
            pendingExecutors.remove(id)
            activeJobs[id]?.also { it.cancel() }
        }
        // Never join under the mutex: the task's finally block needs it to release its slot.
        job?.join()
    }

    suspend fun pause(id: DownloadId) = cancel(id)

    /** Must be called under [mutex]; register each job before another dispatcher can see it. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun dispatchLocked() {
        while (!closed && activeJobs.size < maxConcurrent) {
            val id = queue.pollNext()?.id ?: break
            val executor = pendingExecutors.remove(id) ?: continue
            // ATOMIC guarantees finally runs even when cancelled before the first dispatch.
            val job = scope.launch(start = CoroutineStart.ATOMIC) {
                try {
                    executor()
                } finally {
                    withContext(NonCancellable) {
                        mutex.withLock {
                            activeJobs.remove(id)
                            _activeCount.value = activeJobs.size
                            dispatchLocked()
                        }
                    }
                }
            }
            activeJobs[id] = job
            _activeCount.value = activeJobs.size
        }
    }

    suspend fun shutdown() {
        val jobs = mutex.withLock {
            closed = true
            queue.clear()
            pendingExecutors.clear()
            activeJobs.values.toList().also { jobs -> jobs.forEach { it.cancel() } }
        }
        jobs.joinAll()
    }
}