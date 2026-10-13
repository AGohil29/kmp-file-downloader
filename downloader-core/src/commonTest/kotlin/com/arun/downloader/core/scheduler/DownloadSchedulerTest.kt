package com.arun.downloader.core.scheduler

import com.arun.downloader.core.model.DownloadId
import com.arun.downloader.core.model.DownloadPriority
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadSchedulerTest {

    @Test
    fun schedulerQueuePrioritizesHighPriorityAndFifoTieBreaks() {
        val queue = SchedulerQueue()
        val idLow = DownloadId("low")
        val idNormal = DownloadId("normal")
        val idHigh = DownloadId("high")
        val idNormalEarlier = DownloadId("normal_early")

        queue.enqueue(idLow, DownloadPriority.LOW, 100L)
        queue.enqueue(idNormal, DownloadPriority.NORMAL, 200L)
        queue.enqueue(idHigh, DownloadPriority.HIGH, 300L)
        queue.enqueue(idNormalEarlier, DownloadPriority.NORMAL, 100L) // Earlier timestamp than idNormal

        queue.pollNext()?.id shouldBe idHigh          // HIGH takes precedence
        queue.pollNext()?.id shouldBe idNormalEarlier // Earlier NORMAL takes precedence over later NORMAL
        queue.pollNext()?.id shouldBe idNormal
        queue.pollNext()?.id shouldBe idLow
    }

    @Test
    fun schedulerEnforcesMaxConcurrencyLimits() = runTest {
        val scheduler = DownloadScheduler(initialMaxConcurrent = 2, scope = this)

        var concurrentCount = 0
        var maxObservedConcurrent = 0

        val taskExecutor: suspend () -> Unit = {
            concurrentCount++
            maxObservedConcurrent = maxOf(maxObservedConcurrent, concurrentCount)
            delay(100L.milliseconds)
            concurrentCount--
        }

        repeat(5) { i ->
            scheduler.enqueue(
                id = DownloadId("dl_$i"),
                priority = DownloadPriority.NORMAL,
                createdAtEpochMs = i.toLong(),
                executor = taskExecutor
            )
        }

        advanceUntilIdle()

        maxObservedConcurrent shouldBe 2
        scheduler.activeCount.value shouldBe 0
    }

    @Test
    fun queuedIdsRunTheirOwnExecutorInPriorityOrder() = runTest {
        val scheduler = DownloadScheduler(1, this)
        val gate = CompletableDeferred<Unit>()
        val executed = mutableListOf<String>()
        scheduler.enqueue(DownloadId("first"), DownloadPriority.NORMAL, 0) {
            executed += "first"
            gate.await()
        }
        runCurrent()
        scheduler.enqueue(DownloadId("low"), DownloadPriority.LOW, 1) { executed += "low" }
        scheduler.enqueue(DownloadId("high"), DownloadPriority.HIGH, 2) { executed += "high" }
        gate.complete(Unit)
        advanceUntilIdle()
        executed shouldBe listOf("first", "high", "low")
    }

    @Test
    fun cancelWaitsForNonCancellableCleanup() = runTest {
        val scheduler = DownloadScheduler(1, this)
        val id = DownloadId("cancel")
        var cleanedUp = false
        scheduler.enqueue(id, DownloadPriority.NORMAL, 0) {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    delay(100)
                    cleanedUp = true
                }
            }
        }
        runCurrent()
        scheduler.cancel(id)
        cleanedUp shouldBe true
        scheduler.activeCount.value shouldBe 0
    }

    @Test
    fun increasingConcurrencyStartsWaitingTasks() = runTest {
        val scheduler = DownloadScheduler(1, this)
        val gate = CompletableDeferred<Unit>()
        var started = 0
        repeat(3) { index ->
            scheduler.enqueue(DownloadId("task_$index"), DownloadPriority.NORMAL, index.toLong()) {
                started++
                gate.await()
            }
        }
        runCurrent()
        started shouldBe 1
        scheduler.updateMaxConcurrent(3)
        runCurrent()
        started shouldBe 3
        gate.complete(Unit)
        advanceUntilIdle()
        scheduler.shutdown()
    }
}