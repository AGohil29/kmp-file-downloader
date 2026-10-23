# Performance Benchmark Tests

**File:** `downloader-runtime/src/desktopTest/kotlin/com/arun/downloader/runtime/benchmark/DownloaderPerformanceBenchmarkTest.kt`

## Purpose

Coarse smoke benchmarks that catch large performance regressions in the streaming pipeline and
in the scheduler queue. They are not precise micro-benchmarks: they use `measureTime` inside a
normal unit test, run on the desktop JVM target, and print a result line to stdout.

The setup matches the other runtime tests (see [parity](cross-platform-parity.md) and
[chaos](chaos-recovery.md)): Okio `FakeFileSystem`, in-memory SQLite,
`SqlDelightDownloadRepository` and a `StandardTestDispatcher`.

## Test cases

### `benchmarkStreamingThroughput10MbFile`

Measures how fast `DefaultFileDownloader` moves data from the network channel to the file.

1. A fake `NetworkTransport` returns a 200 response with `contentLength` of 10 MB. A coroutine
   in `backgroundScope` writes the payload into a Ktor `ByteChannel` in 64 KB blocks and then
   closes it.
2. `DownloaderConfig` uses `maxConcurrentDownloads = 1` and `progressUpdateIntervalMs = 1000`,
   so progress persistence does not dominate the timing.
3. `measureTime` wraps `enqueue(...)` plus `advanceUntilIdle()`, i.e. the whole download.
4. Throughput is `MB / seconds` and printed as
   `BENCHMARK [Streaming Throughput]: ...`.
5. Asserts throughput is **greater than 5 MB/s**.

Caveats: the `FakeFileSystem` is in memory, so this measures the downloader's own overhead (channel
reads, writes, state updates), not real disk or network speed. The test uses virtual-time
scheduling, so results depend on the machine and the 5 MB/s threshold is a loose floor. If the
measured duration rounds to 0 ms, the throughput falls back to the size in MB and the test passes.

### `benchmarkSchedulerQueueEnqueue1000Items`

Times 1,000 `SchedulerQueue.enqueue` calls (every third item `HIGH` priority, the rest `NORMAL`,
increasing `createdAtEpochMs`), prints the elapsed time as
`BENCHMARK [Scheduler Queue]: ...`, and asserts `queue.size() == 1000`.

Only the size is asserted; there is no time limit, so it will not fail on slowness. The printed
unit label is `s` where the value is in microseconds (a `µs` typo in the test's output string).

## Running

```bash
./gradlew :downloader-runtime:desktopTest --tests "com.arun.downloader.runtime.benchmark.DownloaderPerformanceBenchmarkTest" -i
```

Use `-i` to see the `BENCHMARK` lines in the Gradle output.

## Related docs

- [Cross-platform parity test](cross-platform-parity.md)
- [Chaos recovery tests](chaos-recovery.md)
- [Scheduler and FileDownloader](../scheduler/download-scheduler-and-file-downloader.md)

