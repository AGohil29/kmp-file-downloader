package com.arun.downloader.core.scheduler

import com.arun.downloader.core.model.DownloadId
import com.arun.downloader.core.model.DownloadPriority
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
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
}