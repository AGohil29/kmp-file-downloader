package com.arun.downloader.core.statemachine

import app.cash.turbine.test
import com.arun.downloader.core.model.DownloadError
import com.arun.downloader.core.model.DownloadId
import com.arun.downloader.core.model.DownloadPriority
import com.arun.downloader.core.model.DownloadState
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class DownloadStateMachineTest {
    private val testId = DownloadId("dl_test_1001")

    @Test
    fun idleCanTransitionToQueued() = runTest {
        val sm = DownloadStateMachine(testId)
        sm.state.value.shouldBeInstanceOf<DownloadState.Idle>()

        val next = sm.transition(DownloadStateEvent.Enqueue(DownloadPriority.HIGH, 1000L))
        next.shouldBeInstanceOf<DownloadState.Queued>()
        (next as DownloadState.Queued).priority shouldBe DownloadPriority.HIGH
    }

    @Test
    fun standardLifecycleCompletesSuccessfully() = runTest {
        val sm = DownloadStateMachine(testId)

        sm.state.test {
            awaitItem().shouldBeInstanceOf<DownloadState.Idle>()

            // Enqueue
            sm.transition(DownloadStateEvent.Enqueue(DownloadPriority.NORMAL, 1000L))
            awaitItem().shouldBeInstanceOf<DownloadState.Queued>()

            // Connect
            sm.transition(DownloadStateEvent.AcquireSlot)
            awaitItem().shouldBeInstanceOf<DownloadState.Connecting>()

            // Receive Headers (10,000 bytes)
            sm.transition(DownloadStateEvent.HeaderReceived(totalBytes = 10000L, etag = "w/xyz", lastModified = null))
            val downloading = awaitItem()
            downloading.shouldBeInstanceOf<DownloadState.Downloading>()
            (downloading as DownloadState.Downloading).totalBytes shouldBe 10000L
            downloading.progressPercentage shouldBe 0.0f

            // Progress (5,000 bytes)
            sm.transition(DownloadStateEvent.ProgressUpdate(downloadedBytes = 5000L, speedBytesPerSecond = 2500L))
            val progress = awaitItem() as DownloadState.Downloading
            progress.downloadedBytes shouldBe 5000L
            progress.progressPercentage shouldBe 50.0f

            // Validation
            sm.transition(DownloadStateEvent.StartValidation)
            awaitItem().shouldBeInstanceOf<DownloadState.Validating>()

            // Complete
            sm.transition(DownloadStateEvent.Complete("/path/file.bin", 10000L, 2000L))
            val completed = awaitItem() as DownloadState.Completed
            completed.absolutePath shouldBe "/path/file.bin"
            completed.totalBytes shouldBe 10000L
        }
    }

    @Test
    fun chunkedTransferYieldsNullProgressPercentage() = runTest {
        val sm = DownloadStateMachine(testId)
        sm.transition(DownloadStateEvent.Enqueue(DownloadPriority.NORMAL, 1000L))
        sm.transition(DownloadStateEvent.AcquireSlot)
        sm.transition(DownloadStateEvent.HeaderReceived(totalBytes = null, etag = null, lastModified = null))

        val downloading = sm.transition(
            DownloadStateEvent.ProgressUpdate(downloadedBytes = 4096L, speedBytesPerSecond = 1024L)
        ) as DownloadState.Downloading

        downloading.totalBytes.shouldBeNull()
        downloading.progressPercentage.shouldBeNull()
    }

    @Test
    fun illegalTransitionsThrowTypedException() = runTest {
        val sm = DownloadStateMachine(testId)

        // Cannot complete directly from Idle
        shouldThrow<IllegalStateTransitionException> {
            sm.transition(DownloadStateEvent.Complete("/path/file.bin", 1000L, 1000L))
        }

        sm.transition(DownloadStateEvent.Enqueue(DownloadPriority.NORMAL, 1000L))

        // Cannot send progress directly when Queued without Connecting/Downloading
        shouldThrow<IllegalStateTransitionException> {
            sm.transition(DownloadStateEvent.ProgressUpdate(100L, 50L))
        }
    }

    @Test
    fun terminalStatesRejectAllSubsequentTransitions() = runTest {
        val sm = DownloadStateMachine(testId)

        sm.transition(DownloadStateEvent.Enqueue(DownloadPriority.NORMAL, 1000L))
        sm.transition(DownloadStateEvent.Cancel)
        sm.state.value.shouldBeInstanceOf<DownloadState.Cancelled>()

        // Cancelled is terminal
        shouldThrow<IllegalStateTransitionException> {
            sm.transition(DownloadStateEvent.Resume)
        }

        shouldThrow<IllegalStateTransitionException> {
            sm.transition(DownloadStateEvent.Enqueue(DownloadPriority.NORMAL, 2000L))
        }
    }

    @Test
    fun pauseAndResumeLifecycleIsDeterministic() = runTest {
        val sm = DownloadStateMachine(testId)
        sm.transition(DownloadStateEvent.Enqueue(DownloadPriority.NORMAL, 1000L))
        sm.transition(DownloadStateEvent.AcquireSlot)
        sm.transition(DownloadStateEvent.HeaderReceived(totalBytes = 2000L, etag = "etag-1", lastModified = null))
        sm.transition(DownloadStateEvent.ProgressUpdate(downloadedBytes = 500L, speedBytesPerSecond = 100L))

        val paused = sm.transition(DownloadStateEvent.Pause)
        paused.shouldBeInstanceOf<DownloadState.Paused>()
        (paused as DownloadState.Paused).downloadedBytes shouldBe 500L
        paused.totalBytes shouldBe 2000L

        val resumed = sm.transition(DownloadStateEvent.Resume)
        resumed.shouldBeInstanceOf<DownloadState.Connecting>()
    }

    @Test
    fun failedDownloadAllowsExplicitRetry() = runTest {
        val sm = DownloadStateMachine(testId)
        sm.transition(DownloadStateEvent.Enqueue(DownloadPriority.NORMAL, 1000L))
        sm.transition(DownloadStateEvent.AcquireSlot)

        val failed = sm.transition(
            DownloadStateEvent.Fail(
                error = DownloadError.Retryable.NetworkTimeout("Gateway timeout"),
                canRetry = true
            )
        )
        failed.shouldBeInstanceOf<DownloadState.Failed>()
        (failed as DownloadState.Failed).canRetry shouldBe true

        val retrying = sm.transition(DownloadStateEvent.Resume)
        retrying.shouldBeInstanceOf<DownloadState.Connecting>()
    }
}