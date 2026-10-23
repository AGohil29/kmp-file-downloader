# Download progress regression

## Phases A–C: reproduce and isolate

Both samples use `KtorNetworkTransport`. Ktor 3.1's no-block
`HttpStatement.execute()` buffers the entire body before returning. Consequently,
`DownloadTask` stays Connecting during the network transfer and only reports byte
progress during the subsequent, much faster copy to disk.

`firstBytesArriveBeforeServerFinishesBody` supplies an initial chunk and withholds
the tail until the consumer reads. It failed with a timeout before the fix and
passes afterward. Reading an already-buffered body in chunks is not sufficient
evidence of streaming.

## Phase D: streaming and ownership

- Use `HttpStatement.body<HttpResponse>()` to obtain streaming headers and body.
- The consumer must call `NetworkResponse.close()` in `finally`.
- Close the exposed channel, raw Ktor body, and response job. Ktor's default
  `bodyAsChannel()` transformation can introduce a forwarding coroutine; closing
  only that wrapper is insufficient for an upstream reader waiting for bytes.
- `DownloadTask` releases responses even when opening the disk sink fails.
- Read to EOF with cancellation checks; network read failures must not silently
  produce completed, truncated files.

## Phase E: observation and samples

- `observeAll()` preserves persisted lifecycle/byte counts and overlays live task
  speeds instead of always displaying zero.
- Historical `observe(id)` loads asynchronously rather than blocking on SQL.
- Both samples show indeterminate progress while connecting and when the total
  size is unknown. Known totals use a clamped determinate indicator.
- Android retains the downloader in a ViewModel across activity recreation.
- Desktop constructs its downloader outside composition, once per process.
- UI actions use composition-bound coroutine scopes; collectors are keyed by
  downloader instance.

## Phase F: additional failures exposed by validation

- Completing an unknown-length response attempted to assign a null total to the
  non-null downloaded byte count. SQL now preserves the actual transferred count.
- Cancellation returned before task cleanup, allowing Paused to overwrite
  Cancelled. Cancellation and shutdown now wait for cleanup outside the scheduler
  mutex.
- Queue dispatch reused another download's executor. Executors are now associated
  with their own IDs, and dispatch honors the current concurrency limit.

## Automated verification

`StreamingProgressIntegrationTest` runs a gated loopback server with real CIO and
OkHttp clients, SQLite, disk writes, and `observeAll()` collectors. Coverage:

- Multiple intermediate byte counts and nonzero speeds before the body finishes.
- Known and unknown content lengths on both engines.
- Completion, exact downloaded bytes, and history observed by a new collector.
- Cancellation remains Cancelled after cleanup.
- Pause/resume sends the expected Range header and produces an intact final file.
- Concurrent downloads expose independent progress and save separate files.
- A raw TCP server closes an incomplete response; the download fails without
  committing the destination file.

Network tests additionally cover unread response cleanup, real mid-read
cancellation, early HTTP errors, and range/header mapping. Engine tests verify
cleanup after cancellation and a file-open failure. Scheduler tests cover queued
executor identity, priority order, cleanup ordering, and increased concurrency.

Validated on 2026-10-13:

- 49 JVM tests across core, network, storage, and runtime: passed.
- 21 Android-target core/network unit tests (host JVM): passed.
- Desktop Kotlin compilation and Android debug APK assembly: passed.
- Android debug APK installed and activity launch returned success on a connected
  device. A system screen covered the activity, so rendered progress was not
  visually verified.

Gradle tasks: `:downloader-core:desktopTest`, `:downloader-network:desktopTest`,
`:downloader-storage:desktopTest`, `:downloader-runtime:desktopTest`,
`:downloader-core:testDebugUnitTest`, `:downloader-network:testDebugUnitTest`,
`:samples:sample-desktop:compileKotlinDesktop`, and
`:samples:sample-android:assembleDebug`.

## Remaining manual checks

On each visible sample UI, download from a slow endpoint and check intermediate
progress, pause/resume, cancellation, and unknown-length presentation. Rotate
Android during a transfer to confirm retained ownership visually. Heap profiling
of a large real download and visual desktop/UI automation were not performed;
the gated streaming test guards against full-response buffering but is not a heap
benchmark.
