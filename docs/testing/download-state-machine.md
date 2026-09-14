# DownloadStateMachine Tests

This document explains what
[`DownloadStateMachineTest.kt`](../../downloader-core/src/commonTest/kotlin/com/arun/downloader/core/statemachine/DownloadStateMachineTest.kt)
verifies and how the `DownloadStateMachine` behaves, with concrete examples for each test.

## Under test

- `DownloadStateMachine` (`commonMain/.../statemachine/DownloadStateMachine.kt`) — holds the
  current `DownloadState` in a `StateFlow` and exposes a single `suspend fun transition(event)`
  that computes and publishes the next state, or throws `IllegalStateTransitionException` if the
  event is not valid for the current state.
- `DownloadStateEvent` — sealed set of events that drive transitions: `Enqueue`, `AcquireSlot`,
  `HeaderReceived`, `ProgressUpdate`, `Pause`, `Resume`, `Cancel`, `StartValidation`, `Complete`,
  `Fail`.
- `DownloadState` — sealed set of states: `Idle`, `Queued`, `Connecting`, `Downloading`, `Paused`,
  `Validating`, `Completed`, `Failed`, `Cancelled` (`Completed`/`Cancelled` are terminal).

## State diagram (happy path)

```
Idle -> Queued -> Connecting -> Downloading -> Validating -> Completed
          |            |             |
          v            v             v
        Paused <---------------------+
          |
          v (Resume)
      Connecting
```

`Cancel` can be sent from most non-terminal states and moves directly to `Cancelled`.
`Fail` can be sent from several states and moves to `Failed` (retryable via `Resume`/`Enqueue`).

## Test-by-test explanation

### `idleCanTransitionToQueued`
Confirms a brand-new state machine starts in `Idle`, and that sending `Enqueue` moves it to
`Queued` while carrying over the requested priority.

```kotlin
val sm = DownloadStateMachine(testId)
sm.state.value.shouldBeInstanceOf<DownloadState.Idle>()

val next = sm.transition(DownloadStateEvent.Enqueue(DownloadPriority.HIGH, 1000L))
next.shouldBeInstanceOf<DownloadState.Queued>()
(next as DownloadState.Queued).priority shouldBe DownloadPriority.HIGH
```

### `standardLifecycleCompletesSuccessfully`
Walks the full happy-path lifecycle and asserts every intermediate state emitted on the
`state` flow, using Turbine's `test { }` to collect each emission in order:

`Idle -> Queued -> Connecting -> Downloading -> Validating -> Completed`

It also checks derived data along the way, e.g. after `HeaderReceived(totalBytes = 10000L)`
the `Downloading` state has `progressPercentage == 0.0f`, and after
`ProgressUpdate(downloadedBytes = 5000L)` the percentage becomes `50.0f`.

```kotlin
sm.state.test {
    awaitItem().shouldBeInstanceOf<DownloadState.Idle>()

    sm.transition(DownloadStateEvent.Enqueue(DownloadPriority.NORMAL, 1000L))
    awaitItem().shouldBeInstanceOf<DownloadState.Queued>()

    sm.transition(DownloadStateEvent.AcquireSlot)
    awaitItem().shouldBeInstanceOf<DownloadState.Connecting>()

    sm.transition(DownloadStateEvent.HeaderReceived(totalBytes = 10000L, etag = "w/xyz", lastModified = null))
    val downloading = awaitItem() as DownloadState.Downloading
    downloading.totalBytes shouldBe 10000L
    downloading.progressPercentage shouldBe 0.0f

    sm.transition(DownloadStateEvent.ProgressUpdate(downloadedBytes = 5000L, speedBytesPerSecond = 2500L))
    val progress = awaitItem() as DownloadState.Downloading
    progress.downloadedBytes shouldBe 5000L
    progress.progressPercentage shouldBe 50.0f

    sm.transition(DownloadStateEvent.StartValidation)
    awaitItem().shouldBeInstanceOf<DownloadState.Validating>()

    sm.transition(DownloadStateEvent.Complete("/path/file.bin", 10000L, 2000L))
    val completed = awaitItem() as DownloadState.Completed
    completed.absolutePath shouldBe "/path/file.bin"
    completed.totalBytes shouldBe 10000L
}
```

### `chunkedTransferYieldsNullProgressPercentage`
Covers servers that respond without a `Content-Length` header (chunked transfer encoding).
When `HeaderReceived(totalBytes = null, ...)` is sent, `totalBytes` stays `null` through
subsequent progress updates, and `progressPercentage` is `null` instead of a divide-by-zero
or a misleading percentage — since we don't know the total size, no percentage can be computed.

```kotlin
sm.transition(DownloadStateEvent.HeaderReceived(totalBytes = null, etag = null, lastModified = null))
val downloading = sm.transition(
    DownloadStateEvent.ProgressUpdate(downloadedBytes = 4096L, speedBytesPerSecond = 1024L)
) as DownloadState.Downloading

downloading.totalBytes.shouldBeNull()
downloading.progressPercentage.shouldBeNull()
```

### `illegalTransitionsThrowTypedException`
Verifies the machine rejects events that don't make sense for the current state by throwing
`IllegalStateTransitionException`, rather than silently ignoring them or corrupting state.

- Sending `Complete` while still `Idle` is invalid (nothing has started downloading yet).
- Sending `ProgressUpdate` while `Queued` is invalid (no connection/download in progress yet).

```kotlin
shouldThrow<IllegalStateTransitionException> {
    sm.transition(DownloadStateEvent.Complete("/path/file.bin", 1000L, 1000L))
}

sm.transition(DownloadStateEvent.Enqueue(DownloadPriority.NORMAL, 1000L))

shouldThrow<IllegalStateTransitionException> {
    sm.transition(DownloadStateEvent.ProgressUpdate(100L, 50L))
}
```

### `terminalStatesRejectAllSubsequentTransitions`
Confirms that once a download reaches a terminal state (`Cancelled` here, same rule applies to
`Completed`), no further events are accepted — every subsequent `transition` call throws
`IllegalStateTransitionException`, even events that would be valid from other states
(e.g. `Resume`, `Enqueue`).

```kotlin
sm.transition(DownloadStateEvent.Enqueue(DownloadPriority.NORMAL, 1000L))
sm.transition(DownloadStateEvent.Cancel)
sm.state.value.shouldBeInstanceOf<DownloadState.Cancelled>()

shouldThrow<IllegalStateTransitionException> { sm.transition(DownloadStateEvent.Resume) }
shouldThrow<IllegalStateTransitionException> {
    sm.transition(DownloadStateEvent.Enqueue(DownloadPriority.NORMAL, 2000L))
}
```

### `pauseAndResumeLifecycleIsDeterministic`
Ensures pausing mid-download preserves the bytes already downloaded and the known total size,
and that resuming a paused download re-enters `Connecting` (so the connection/headers are
re-established before downloading continues).

```kotlin
sm.transition(DownloadStateEvent.HeaderReceived(totalBytes = 2000L, etag = "etag-1", lastModified = null))
sm.transition(DownloadStateEvent.ProgressUpdate(downloadedBytes = 500L, speedBytesPerSecond = 100L))

val paused = sm.transition(DownloadStateEvent.Pause) as DownloadState.Paused
paused.downloadedBytes shouldBe 500L
paused.totalBytes shouldBe 2000L

val resumed = sm.transition(DownloadStateEvent.Resume)
resumed.shouldBeInstanceOf<DownloadState.Connecting>()
```

### `failedDownloadAllowsExplicitRetry`
Simulates a network failure while `Connecting` and checks that the resulting `Failed` state
carries the error and `canRetry` flag, and that sending `Resume` on a retryable failure moves
the machine back to `Connecting` to try again.

```kotlin
val failed = sm.transition(
    DownloadStateEvent.Fail(error = DownloadError.Retryable.NetworkTimeout("Gateway timeout"), canRetry = true)
) as DownloadState.Failed
failed.canRetry shouldBe true

val retrying = sm.transition(DownloadStateEvent.Resume)
retrying.shouldBeInstanceOf<DownloadState.Connecting>()
```

## Tools used in these tests

- **kotlin.test** (`@Test`) — test entry points, common across KMP targets.
- **kotlinx-coroutines-test** (`runTest`) — runs `suspend` code (like `transition`) in tests.
- **Turbine** (`flow.test { awaitItem() }`) — collects `StateFlow` emissions one at a time to
  assert the exact sequence of states produced by a series of transitions.
- **Kotest matchers** (`shouldBe`, `shouldBeInstanceOf`, `shouldBeNull`, `shouldThrow`) — fluent,
  type-safe assertions.

## Running the tests

```bash
./gradlew :downloader-core:allTests
# or, for a single target, e.g.:
./gradlew :downloader-core:jvmTest --tests "com.arun.downloader.core.statemachine.DownloadStateMachineTest"
```
