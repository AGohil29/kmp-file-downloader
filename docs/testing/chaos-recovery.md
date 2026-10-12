# Chaos Recovery Tests

**File:** `downloader-runtime/src/desktopTest/kotlin/com/arun/downloader/runtime/chaos/ChaosRecoveryTest.kt`

## Purpose

These tests check that the downloader stays consistent when things go wrong: a transfer that is
interrupted mid-stream, and an app process that dies while downloads are in flight. They use the
same in-memory setup as the [parity test](cross-platform-parity.md): Okio `FakeFileSystem`,
in-memory SQLite (`JdbcSqliteDriver` + `DownloadDatabase.Schema`), `SqlDelightDownloadRepository`
and a `StandardTestDispatcher`. The network is replaced by a custom `NetworkTransport`.

## Test cases

### `diskFullExceptionMidStreamHaltsAndMarksFailedWithoutCorruption`

Simulates a long transfer that never finishes:

1. The fake `NetworkTransport` returns a 200 response with `contentLength = 10_000_000` and a
   `ByteChannel` that has only 1 KiB written and is never closed, so the download stays in flight.
2. The download is enqueued and the scheduler runs until idle.
3. `downloader.cancel(id)` is called while the stream is still open.
4. The test waits for `Cancelled` (or `Paused`) on `observe(id)` and asserts the state is
   `DownloadState.Cancelled`.

What it proves: an in-flight task can be stopped cleanly and reaches a terminal `Cancelled`
state instead of hanging.

> Note: despite the test name, it does not inject a real disk-full error. It exercises
> cancellation of an open stream. A true disk-full case would need a filesystem that throws on
> write.

### `startupReconciliationResetsHangingDownloadsToPaused`

Simulates the app being killed mid-download:

1. A record for `dl_hanging_process` is inserted directly into the repository with state
   `DOWNLOADING`. No task is actually running for it.
2. The test asserts that the stored state is `DOWNLOADING`.
3. `repository.reconcileStaleStates(timestampEpochMs = 2000L)` is called, as it would be on a
   cold start.
4. The stored state must now be `PAUSED`.

What it proves: after a restart, records left in an in-flight state are reset to `PAUSED`, so
they can be resumed instead of appearing stuck as `DOWNLOADING`.

## Running

```bash
./gradlew :downloader-runtime:desktopTest --tests "com.arun.downloader.runtime.chaos.ChaosRecoveryTest"
```

## Related docs

- [Cross-platform parity test](cross-platform-parity.md)
- [Download state machine tests](download-state-machine.md)
- [Runtime module](../runtime/downloader-runtime-module.md)

