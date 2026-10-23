# Cross-Platform Parity Test

**File:** `downloader-runtime/src/desktopTest/kotlin/com/arun/downloader/runtime/parity/CrossPlatformParityTest.kt`

## Purpose

The downloader exposes one public API (`FileDownloader`, implemented by `DefaultFileDownloader`)
that is shared by Android, iOS and Desktop. This test verifies that the shared facade, wired
with the real storage and filesystem layers, drives a download from `enqueue` to
`DownloadState.Completed` without any platform-specific code. Because all logic lives in
`commonMain`, a pass on the desktop (JVM) target is a strong signal the same flow works on the
other targets.

## What is wired together

| Layer      | Implementation used in the test                                                                                                                     |
|------------|-----------------------------------------------------------------------------------------------------------------------------------------------------|
| Network    | Anonymous `NetworkTransport` returning a fixed 200 response (`"Parity validation test content"`, ETag `"parity-etag"`) through a Ktor `ByteChannel` |
| Filesystem | `OkioDownloadFileSystem` on top of Okio `FakeFileSystem` (in-memory; no disk access)                                                                |
| Storage    | `SqlDelightDownloadRepository` on an in-memory `JdbcSqliteDriver` with `DownloadDatabase.Schema` created per test                                   |
| Runtime    | `DefaultFileDownloader` with `DownloaderConfig(maxConcurrentDownloads = 2, stagingDirectory = /staging)`                                            |
| Dispatcher | `StandardTestDispatcher` for deterministic coroutine execution                                                                                      |

`setup()` (`@BeforeTest`) rebuilds all of this for every test, so tests are isolated.

## Test case

`universalApiFacadeProducesCompletedStateAcrossAllTargets`

1. Enqueues a `DownloadRequest` for `https://mock.com/parity.txt` into `/downloads` with
   `ConflictStrategy.OVERWRITE`.
2. Calls `testDispatcher.scheduler.advanceUntilIdle()` so the scheduler and task run.
3. Collects `downloader.observe(id)` until a `DownloadState.Completed` is emitted.
4. Asserts:
   - the state is `Completed`;
   - `absolutePath` equals `/downloads/parity.txt`;
   - the file exists in the fake filesystem (staging file was moved to its final location).

## Running

```bash
./gradlew :downloader-runtime:desktopTest --tests "com.arun.downloader.runtime.parity.CrossPlatformParityTest"
```

## Related docs

- [Download state machine tests](download-state-machine.md)
- [Runtime module](../runtime/downloader-runtime-module.md)
- [Scheduler and FileDownloader](../scheduler/download-scheduler-and-file-downloader.md)

