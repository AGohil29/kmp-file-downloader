# `DownloadTask` Engine

`DownloadTask` (`downloader-core/src/commonMain/kotlin/com/arun/downloader/core/engine/DownloadTask.kt`)
is the orchestrator that drives a **single download** from `Idle` through to `Completed`/
`Failed`/`Cancelled`, coordinating the state machine and the three port interfaces
(`DownloadRepository`, `NetworkTransport`, `DownloadFileSystem`). This document explains its
current design, and the two bugs found and fixed while getting `DownloadTaskTest` (in
`downloader-runtime`) to pass.

## Construction

```kotlin
class DownloadTask(
    val id: DownloadId,
    val request: DownloadRequest,
    private val stateMachine: DownloadStateMachine,
    private val repository: DownloadRepository,
    private val network: NetworkTransport,
    private val fileSystem: DownloadFileSystem,
    private val stagingDirectory: Path,
    private val progressUpdateIntervalMs: Long = 100L,
    private val bufferSize: Int = 8192,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default
)
```

Every dependency is an interface (or a value type), so tests construct a `DownloadTask` with a
real `DownloadStateMachine`, a real `SqlDelightDownloadRepository` backed by an in-memory SQLite
DB, a fake `NetworkTransport`, and a `FakeFileSystem` — no real network/disk I/O is needed to
exercise the full engine logic.

`state: StateFlow<DownloadState>` simply proxies `stateMachine.state`, so callers (e.g. UI layers)
observe progress without talking to the state machine directly.

## `execute()` — the happy path

```kotlin
suspend fun execute() = withContext(ioDispatcher) {
    ...
    try {
        stateMachine.transition(DownloadStateEvent.Enqueue(request.priority, getEpochMs()))
        stateMachine.transition(DownloadStateEvent.AcquireSlot)
        repository.updateState(id, "CONNECTING", getEpochMs())

        val netResponse = network.execute(NetworkRequest(url = request.url, headers = request.headers))

        stateMachine.transition(DownloadStateEvent.HeaderReceived(...))
        repository.updateConnectionHeaders(...)

        // open staging sink (optionally wrapped in a HashingSink for checksum validation)
        pumpBytes(channel = netResponse.bodyChannel, sink = bufferedSink, totalBytes = totalBytes)
        bufferedSink.flush()

        stateMachine.transition(DownloadStateEvent.StartValidation)
        // verify byte count, then checksum if requested — throws DownloadError on mismatch

        fileSystem.atomicMove(stagingPartFile, finalDestinationPath)
        stateMachine.transition(DownloadStateEvent.Complete(...))
        repository.markCompleted(id, completionTime)
    } catch (cancellation: CancellationException) { ... }
    catch (e: Throwable) { ... }
    finally { closeQuietly(bufferedSink, rawSink) }
}
```

1. Writes stream to a **staging** file (`stagingDirectory/.downloader_staging/<id>.part`), never
   directly to the destination — so a half-written file is never visible at the final path.
2. Validates the downloaded byte count against `Content-Length` (if known), and optionally the
   checksum (`MD5`/`SHA1`/`SHA256`) via an `okio.HashingSink` wrapped around the staging sink.
3. Only after validation succeeds does it perform an **atomic move** from staging to the final
   destination — this two-phase (write-then-commit) design means a crash mid-download never
   corrupts or partially overwrites a previously completed file.
4. Progress (`ProgressUpdate` events + `repository.updateProgress`) is throttled to at most once
   every `progressUpdateIntervalMs` (default 100ms) to avoid flooding the state flow / DB with
   writes for every chunk.

## Bug #1 (fixed): missing `Enqueue` transition before `AcquireSlot`

`DownloadStateMachine` only allows `AcquireSlot` from the `Queued` state — not from `Idle`
(see [`docs/testing/download-state-machine.md`](../testing/download-state-machine.md)). The
original `execute()` skipped straight to `AcquireSlot`:

```kotlin
// BEFORE — throws IllegalStateTransitionException immediately, since AcquireSlot
// is not valid from Idle.
stateMachine.transition(DownloadStateEvent.AcquireSlot)
```

Worse, this exception is thrown *inside* the `try` block, so it was caught by
`catch (e: Throwable)`, which itself tried to fire a `Fail` transition — but `Fail` is *also*
invalid from `Idle`, so a **second** `IllegalStateTransitionException` was thrown from inside the
catch block, masking the original error entirely. The test failure surfaced as:

```
IllegalStateTransitionException: Cannot execute event 'Fail' from state 'Idle'.
```

...which is misleading — the real problem was the *first* transition attempt, not `Fail`.

**Fix**: explicitly enqueue before acquiring a slot, mirroring the real lifecycle
(`Idle -> Queued -> Connecting`):

```kotlin
// AFTER
stateMachine.transition(DownloadStateEvent.Enqueue(request.priority, getEpochMs()))
stateMachine.transition(DownloadStateEvent.AcquireSlot)
```

**Lesson**: when a state machine's error-handling path performs its own transition (`Fail`), make
sure that transition is reachable from *every* state the code can realistically be in when the
`catch` block runs — otherwise a bug in the happy path can hide behind a secondary, unrelated
exception in the error path.

## Bug #2 (fixed): cancellation cleanup silently dropped

## Cancellation and `NonCancellable` cleanup

When a caller cancels the coroutine running `execute()` (e.g., user pauses mid-download), the
`catch (cancellation: CancellationException)` branch is meant to persist the partial progress as
a `Paused` state:

```kotlin
// BEFORE
} catch (cancellation: CancellationException) {
    closeQuietly(bufferedSink, rawSink)
    stateMachine.transition(DownloadStateEvent.Pause)          // suspend fun
    repository.updateState(id, "PAUSED", getEpochMs())          // suspend fun, uses withContext(ioDispatcher)
    throw cancellation
}
```

This looked correct, but `DownloadTaskTest.cancellationPreservesPartialFileAndEntersPausedState`
kept asserting `repository.getById(id)?.state shouldBe "PAUSED"` and getting `"DOWNLOADING"`
instead — the DB write never happened, even though `task.state.value` (the in-memory state
machine) correctly showed `Paused`.

**Root cause**: once a coroutine's `Job` is cancelled, any subsequent suspending call that
re-dispatches onto a `CoroutineDispatcher` via `withContext(...)` — which is exactly what
`SqlDelightDownloadRepository.updateState()` does internally — throws `CancellationException`
immediately instead of running its body, *even inside a `catch` block*, because the coroutine
context is already marked cancelled. `DownloadStateMachine.transition()`'s `Mutex.withLock` often
gets lucky (uncontended lock, no real suspension), which is why the in-memory state update
appeared to "work" while the DB write silently never executed.

**Fix**: wrap the cancellation cleanup in `kotlinx.coroutines.NonCancellable`, which is the
standard pattern for "I need to await a suspend function during cleanup after my own job was
cancelled":

```kotlin
// AFTER
} catch (cancellation: CancellationException) {
    closeQuietly(bufferedSink, rawSink)
    withContext(NonCancellable) {
        stateMachine.transition(DownloadStateEvent.Pause)
        repository.updateState(id, "PAUSED", getEpochMs())
    }
    throw cancellation
}
```

**Lesson**: any suspending cleanup logic inside a `catch (e: CancellationException)` (or a
`finally` block) that itself calls further suspend functions must run inside
`withContext(NonCancellable) { ... }`, otherwise those calls will throw (or, if they don't check
cancellation at all internal suspension points, silently skip work) because the enclosing job is
already in a "cancelling" state.

## Failure path (`catch (e: Throwable)`)

Any other exception — a `DownloadError` thrown deliberately (checksum mismatch, byte-count
mismatch) or an unexpected `Throwable` from `network`/`fileSystem` — is normalized into a
`DownloadError` and drives a `Fail` transition:

```kotlin
} catch (e: Throwable) {
    closeQuietly(bufferedSink, rawSink)
    val downloadError = e as? DownloadError ?: DownloadError.NonRetryable.Unknown(e.message ?: "Unknown error", e)
    val canRetry = downloadError is DownloadError.Retryable
    stateMachine.transition(DownloadStateEvent.Fail(downloadError, canRetry))
    repository.markFailed(id, downloadError, getEpochMs())
}
```

This path is reached, for example, by `DownloadTaskTest.checksumMismatchDeletesOrFailsGracefully`,
which deliberately supplies a wrong `expectedChecksum` and asserts the task ends in
`DownloadState.Failed` with a `ChecksumMismatch` error, and that the (invalid) destination file
was never created.

Note this catch block does **not** need the `NonCancellable` treatment that Bug #2 required,
because it only runs when the job is *not* cancelled (an ordinary exception was thrown, the job
is still active) — `withContext(ioDispatcher)` inside `repository.markFailed` works normally in
that case.

## Platform clock (`expect`/`actual`)

`getEpochMs()` delegates to `internal expect fun getSystemClockEpochMs(): Long`, implemented per
target:

| Source set                    | Implementation                                      |
|-------------------------------|-----------------------------------------------------|
| `androidMain` / `desktopMain` | `System.currentTimeMillis()`                        |
| `iosMain`                     | `clock_gettime_nsec_np(CLOCK_REALTIME) / 1_000_000` |

This keeps `DownloadTask` fully in `commonMain` while still getting a monotonic wall-clock value
per platform, without pulling in `kotlinx-datetime` for a single timestamp read.
