# Scheduler, Public API & `DefaultFileDownloader`

This document covers the newest set of changes that introduce a **priority-aware concurrency
scheduler** and the **public `FileDownloader` facade** that ties the state machine, repository,
network, and file system layers together into a usable download manager with pause/resume/cancel/
retry/delete support. It explains every file added:

- `downloader-core/src/commonMain/kotlin/com/arun/downloader/core/FileDownloader.kt` (new)
- `downloader-core/src/commonMain/kotlin/com/arun/downloader/core/model/DownloaderConfig.kt` (new)
- `downloader-core/src/commonMain/kotlin/com/arun/downloader/core/model/QueuedDownloadItem.kt` (new)
- `downloader-core/src/commonMain/kotlin/com/arun/downloader/core/scheduler/SchedulerQueue.kt` (new)
- `downloader-core/src/commonMain/kotlin/com/arun/downloader/core/scheduler/DownloadScheduler.kt` (new)
- `downloader-core/src/commonTest/kotlin/com/arun/downloader/core/scheduler/DownloadSchedulerTest.kt` (new)
- `downloader-runtime/src/commonMain/kotlin/com/arun/downloader/runtime/DefaultFileDownloader.kt` (new)

This layer sits **above** `DownloadTask` (see
[`docs/engine/download-task.md`](../engine/download-task.md) and
[`docs/engine/http-range-resume.md`](../engine/http-range-resume.md)): where `DownloadTask` knows
how to run a *single* download to completion, this new code is responsible for managing *many*
downloads at once — queuing them, capping concurrency, and exposing a single stable API
(`FileDownloader`) that a UI or application layer can call without knowing about state machines,
tasks, or repositories at all.

## Module overview

```
downloader-core/
└── src/
    ├── commonMain/kotlin/com/arun/downloader/core/
    │   ├── FileDownloader.kt                 (public facade interface)
    │   ├── model/
    │   │   ├── DownloaderConfig.kt           (construction-time configuration)
    │   │   └── QueuedDownloadItem.kt         (internal queue entry)
    │   └── scheduler/
    │       ├── SchedulerQueue.kt             (priority queue, no coroutines)
    │       └── DownloadScheduler.kt          (concurrency-limited dispatcher)
    └── commonTest/kotlin/com/arun/downloader/core/scheduler/
        └── DownloadSchedulerTest.kt          (queue ordering + concurrency limit tests)

downloader-runtime/
└── src/commonMain/kotlin/com/arun/downloader/runtime/
    └── DefaultFileDownloader.kt              (concrete FileDownloader implementation)
```

`FileDownloader`, `DownloaderConfig`, `QueuedDownloadItem`, `SchedulerQueue`, and
`DownloadScheduler` all live in `downloader-core` (pure Kotlin, no platform/DB/network
dependencies), while `DefaultFileDownloader` lives in `downloader-runtime` because it wires
together `DownloadTask`, the SqlDelight-backed `DownloadRepository`, `NetworkTransport`, and
`DownloadFileSystem` — the concrete platform implementations assembled elsewhere in
`downloader-runtime`.

## `FileDownloader.kt` — the public API surface

```kotlin
interface FileDownloader {
    suspend fun enqueue(request: DownloadRequest): DownloadId
    fun observe(id: DownloadId): StateFlow<DownloadState>
    fun observeAll(): Flow<List<DownloadState>>
    suspend fun pause(id: DownloadId)
    suspend fun resume(id: DownloadId)
    suspend fun cancel(id: DownloadId)
    suspend fun retry(id: DownloadId)
    suspend fun delete(id: DownloadId, deleteFiles: Boolean = true)
    suspend fun shutdown()

    companion object
}
```

This is the single entry point applications are meant to depend on — everything below it
(`DownloadTask`, `DownloadStateMachine`, `DownloadScheduler`, `DownloadRepository`,
`NetworkTransport`, `DownloadFileSystem`) is an implementation detail. Its shape:

- **`enqueue`** — submits a new download request and returns a `DownloadId` immediately; the
  actual transfer happens asynchronously in the background.
- **`observe`/`observeAll`** — reactive read APIs (`StateFlow`/`Flow`) so UI layers can render
  progress without polling.
- **`pause`/`resume`/`cancel`/`retry`** — lifecycle controls. `retry` is currently just an alias
  for `resume` (see `DefaultFileDownloader` below).
- **`delete`** — removes the download record and, optionally, any files it produced (both the
  in-progress staging `.part` file and a completed destination file).
- **`shutdown`** — stops all scheduled/active work and releases resources (used on app
  termination).
- The empty **`companion object`** is a deliberate placeholder for a future platform-specific
  factory function (e.g. `FileDownloader.create(...)`) that will assemble a
  `DefaultFileDownloader` with real platform dependencies (SqlDelight driver, Ktor engine, Okio
  file system) — it is not yet implemented, and no such factory exists in this change.

## `DownloaderConfig.kt`

```kotlin
data class DownloaderConfig(
    val maxConcurrentDownloads: Int = 3,
    val stagingDirectory: Path,
    val connectTimeoutMs: Long = 15_000L,
    val readTimeoutMs: Long = 30_000L,
    val progressUpdateIntervalMs: Long = 100L
)
```

A single, immutable configuration object passed into `DefaultFileDownloader` at construction
time. It centralizes the knobs that were previously scattered as constructor defaults on
`DownloadTask` and `NetworkClientFactory` (see
[`docs/networking/downloader-network-module.md`](../networking/downloader-network-module.md)):

- `maxConcurrentDownloads` seeds the `DownloadScheduler`'s semaphore permit count.
- `stagingDirectory` is the root directory `DownloadTask` uses for its `.downloader_staging`
  subfolder (also reused by `DefaultFileDownloader.delete` to locate a download's `.part` file).
- `connectTimeoutMs`/`readTimeoutMs` mirror `NetworkClientFactory.createDefault`'s parameters,
  intended to be threaded through when the network client is constructed by the (not-yet-written)
  `FileDownloader` factory.
- `progressUpdateIntervalMs` is forwarded directly into each `DownloadTask` it creates.

## `QueuedDownloadItem.kt`

```kotlin
data class QueuedDownloadItem(
    val id: DownloadId,
    val priority: DownloadPriority,
    val createdAtEpochMs: Long
)
```

A minimal internal record representing "a download waiting for a scheduler slot." It carries just
enough information (`priority` + `createdAtEpochMs`) for `SchedulerQueue` to order pending work —
it deliberately does **not** carry the full `DownloadRequest`, keeping the scheduler decoupled
from request/network/file-system concerns entirely.

## `SchedulerQueue.kt` — priority ordering (no coroutines)

A plain, synchronous priority queue with no threading/coroutine concerns of its own — all
thread-safety is the caller's responsibility (provided by `DownloadScheduler`'s `Mutex`, below).

```kotlin
class SchedulerQueue {
    private val items = mutableListOf<QueuedDownloadItem>()

    private val comparator = Comparator<QueuedDownloadItem> { a, b ->
        val priorityComparison = b.priority.weight.compareTo(a.priority.weight)
        if (priorityComparison != 0) priorityComparison else a.createdAtEpochMs.compareTo(b.createdAtEpochMs)
    }

    fun enqueue(id: DownloadId, priority: DownloadPriority, createdAtEpochMs: Long) { ... }
    fun remove(id: DownloadId): Boolean
    fun pollNext(): QueuedDownloadItem?
    fun peek(): QueuedDownloadItem?
    fun contains(id: DownloadId): Boolean
    fun size(): Int
    fun clear()
}
```

- **Ordering rule**: higher `DownloadPriority.weight` (`HIGH` = 30, `NORMAL` = 20, `LOW` = 10, see
  `DownloadPriority.kt`) is dequeued first; ties are broken **FIFO** by `createdAtEpochMs`
  (earlier timestamp first). The comparator computes `b.weight.compareTo(a.weight)` (reversed) for
  priority so that higher weights sort earlier in the list, then falls back to ascending
  timestamp order.
- `enqueue` re-sorts the entire backing list on every insertion (`items.sortWith(comparator)`).
  This is O(n log n) per enqueue rather than a proper heap, which is a deliberate simplicity
  trade-off appropriate for a download queue (expected to hold at most dozens/hundreds of items,
  not a hot-path data structure).
- `pollNext()` removes and returns the front of the list (highest priority, earliest timestamp);
  `peek()` inspects it without removing.
- `remove(id)` supports cancelling a download that hasn't started yet (still sitting in the
  queue) — used by `DownloadScheduler.cancel`.

## `DownloadScheduler.kt` — concurrency-limited dispatch

```kotlin
class DownloadScheduler(
    initialMaxConcurrent: Int = 3,
    private val scope: CoroutineScope
) {
    private val mutex = Mutex()
    private val queue = SchedulerQueue()
    private val semaphore = Semaphore(initialMaxConcurrent)
    private val activeJobs = mutableMapOf<DownloadId, Job>()
    val activeCount: StateFlow<Int>
    ...
}
```

This is the coroutine-aware layer that actually runs queued work, bounded by a
`kotlinx.coroutines.sync.Semaphore` sized to `maxConcurrent` permits.

### `enqueue(id, priority, createdAtEpochMs, executor)`

```kotlin
suspend fun enqueue(id, priority, createdAtEpochMs, executor: suspend () -> Unit) {
    mutex.withLock {
        if (activeJobs.containsKey(id) || queue.contains(id)) return
        queue.enqueue(id, priority, createdAtEpochMs)
    }
    dispatchNext(executor)
}
```

- Guards against **duplicate enqueue**: if the same `id` is already running or already waiting in
  the queue, the call is a no-op.
- Adds the item to `SchedulerQueue` under the mutex, then calls `dispatchNext` to attempt to start
  work immediately (which will block on the semaphore if all permits are currently held).

### `dispatchNext(executor)` — the core dispatch loop

```kotlin
private fun dispatchNext(executor: suspend () -> Unit) {
    scope.launch {
        semaphore.acquire()

        val nextItem = mutex.withLock {
            val item = queue.pollNext()
            if (item == null) { semaphore.release(); null } else item
        } ?: return@launch

        val id = nextItem.id
        val job = launch {
            try {
                executor()
            } finally {
                mutex.withLock {
                    activeJobs.remove(id)
                    _activeCount.value = activeJobs.size
                }
                semaphore.release()
                dispatchNext(executor)
            }
        }

        mutex.withLock {
            activeJobs[id] = job
            _activeCount.value = activeJobs.size
        }
    }
}
```

Each call launches a coroutine that:

1. **Acquires a semaphore permit first** — this is what enforces the concurrency cap; if
   `maxConcurrent` downloads are already running, this suspends until one finishes.
2. Once a permit is held, pulls the next-highest-priority item off `queue`. If the queue is empty
   (e.g. all pending items were already dispatched by other concurrent calls to `dispatchNext`),
   it releases the permit it just took and exits — avoiding a permit leak.
3. Launches a **child coroutine** to actually run `executor()` (the real download work, injected
   by the caller — in `DefaultFileDownloader` this is `task.execute()`), tracking it in
   `activeJobs` (so it can be cancelled later by id) and publishing the new `activeCount`.
4. In the child job's `finally` block — guaranteed to run whether `executor()` completes normally,
   throws, or is cancelled — it removes the job from `activeJobs`, releases the semaphore permit,
   and **recursively calls `dispatchNext` again** to immediately try to pull the next queued item
   into the now-freed slot. This recursive "pump" pattern means the scheduler doesn't need a
   dedicated polling loop; each completed download hands off directly to the next one.

### `cancel(id)` / `pause(id)`

```kotlin
suspend fun cancel(id: DownloadId) = mutex.withLock {
    queue.remove(id)
    activeJobs.remove(id)?.cancel()
    _activeCount.value = activeJobs.size
}

suspend fun pause(id: DownloadId) = cancel(id)
```

- `cancel` handles both cases uniformly: if the download hasn't started yet, it's simply removed
  from the pending queue (never runs); if it's already running, its tracked `Job` is cancelled
  (which propagates a `CancellationException` into the running `executor()` — see
  `DownloadTask`'s `NonCancellable` cleanup handling in
  [`docs/engine/download-task.md`](../engine/download-task.md)).
- **`pause` is currently implemented as a plain alias for `cancel`** at the scheduler level — the
  scheduler itself has no concept of "paused vs. cancelled," it only knows "stop this job and/or
  remove it from the queue." The distinction between pause and cancel is applied one layer up, in
  `DefaultFileDownloader` (see below), which persists a different terminal repository state
  (`"PAUSED"` vs `"CANCELLED"`) depending on which public method the caller invoked.

### `updateMaxConcurrent(newMax)` and `shutdown()`

- `updateMaxConcurrent` allows changing the concurrency cap at runtime. Since
  `kotlinx.coroutines.sync.Semaphore` has no API to shrink its permit count, this only handles
  **increasing** the limit (by releasing `diff` extra permits when `newMax > maxConcurrent`);
  decreasing the limit is accepted (the tracked `maxConcurrent` field is updated) but takes effect
  gradually as in-flight downloads finish and stop releasing surplus permits, rather than
  immediately cancelling running downloads to shrink capacity.
- `shutdown()` clears the pending queue and cancels every currently active `Job`, resetting
  `activeCount` to zero — used when the whole downloader is being torn down.

## `DownloadSchedulerTest.kt`

Two tests validate the scheduler's two core responsibilities independently:

- **`schedulerQueuePrioritizesHighPriorityAndFifoTieBreaks`** — tests `SchedulerQueue` directly
  (no coroutines): enqueues a `LOW`, `NORMAL`, `HIGH`, and a second `NORMAL` item with an
  *earlier* timestamp than the first `NORMAL` item, then asserts `pollNext()` returns them in the
  order `HIGH → NORMAL (earlier timestamp) → NORMAL (later timestamp) → LOW` — confirming both the
  priority ordering and the FIFO tie-break rule.
- **`schedulerEnforcesMaxConcurrencyLimits`** — uses `kotlinx.coroutines.test.runTest` with a
  `DownloadScheduler(initialMaxConcurrent = 2, scope = this)`. Enqueues 5 fake tasks that each
  increment/decrement a shared `concurrentCount` counter around a `100ms` `delay`, tracking the
  highest concurrent value observed. After `advanceUntilIdle()`, asserts
  `maxObservedConcurrent shouldBe 2` (never exceeded the cap) and `scheduler.activeCount.value
  shouldBe 0` (everything drained cleanly by the end) — confirming the semaphore-gated dispatch
  loop correctly limits concurrency and eventually processes every queued item.

## `DefaultFileDownloader.kt` — wiring it all together

The concrete `FileDownloader` implementation in `downloader-runtime`, constructed with a
`DownloaderConfig` plus the three port interfaces (`DownloadRepository`, `NetworkTransport`,
`DownloadFileSystem`):

```kotlin
class DefaultFileDownloader(
    private val config: DownloaderConfig,
    private val repository: DownloadRepository,
    private val network: NetworkTransport,
    private val fileSystem: DownloadFileSystem,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : FileDownloader
```

It owns a `DownloadScheduler` (sized from `config.maxConcurrentDownloads`) and a
`taskMutex`-guarded `activeTasks: MutableMap<DownloadId, DownloadTask>` map tracking currently
live `DownloadTask` instances (needed so `observe()` can return the *live* `StateFlow` for
in-flight downloads rather than a reconstructed one).

### `enqueue(request)`

1. Generates a new `DownloadId`, persists an initial `"QUEUED"` record via
   `repository.insertOrUpdate`.
2. Builds a `DownloadTask` (via the shared `createDownloadTask` helper) and registers it in
   `activeTasks`.
3. Hands the task off to `scheduler.enqueue(id, priority, now) { task.execute() }`, wrapping
   `task.execute()` so that once it finishes (success, failure, or cancellation) the task is
   removed from `activeTasks` in a `finally` block — preventing the map from growing unbounded
   with stale entries.
4. Returns the `DownloadId` immediately — the actual transfer runs asynchronously on `scope`.

### `observe(id)` — live vs. cold reconstruction

```kotlin
override fun observe(id: DownloadId): StateFlow<DownloadState> {
    val activeTask = activeTasks[id]
    if (activeTask != null) return activeTask.state

    val fallbackFlow = MutableStateFlow<DownloadState>(DownloadState.Idle(id))
    scope.launch {
        repository.observeById(id).collect { record ->
            if (record != null) fallbackFlow.value = mapRecordToState(record)
        }
    }
    return fallbackFlow.asStateFlow()
}
```

- If the download is currently active (in `activeTasks`), its real `DownloadTask.state`
  `StateFlow` is returned directly — this is the live, low-latency path with every `ProgressUpdate`
  reflected immediately.
- Otherwise (app was restarted, or the download isn't running right now — e.g. it's `PAUSED`,
  `COMPLETED`, or `FAILED`), a **fallback flow** is created, seeded with `DownloadState.Idle(id)`,
  and a background collector subscribes to `repository.observeById(id)` (a reactive DB query) to
  keep it updated by translating each `DownloadRecord` into a `DownloadState` via
  `mapRecordToState`. This lets `observe()` work uniformly for both "downloading right now" and
  "just reading historical/paused state from the database" callers.

### `observeAll()`

Simply maps every `DownloadRecord` emitted by `repository.observeAll()` through
`mapRecordToState`, giving a reactive `Flow<List<DownloadState>>` snapshot of every known download
— suitable for driving a downloads list screen.

### `pause(id)` vs `cancel(id)`

```kotlin
override suspend fun pause(id: DownloadId) {
    scheduler.pause(id)
    repository.updateState(id, "PAUSED", currentTimeEpochMs())
    taskMutex.withLock { activeTasks.remove(id) }
}

override suspend fun cancel(id: DownloadId) {
    scheduler.cancel(id)
    taskMutex.withLock { activeTasks.remove(id) }
    repository.updateState(id, "CANCELLED", currentTimeEpochMs())
}
```

Both call into the scheduler's identical `cancel`/`pause` (recall: `pause` is an alias for
`cancel` at that layer) to stop/dequeue the job, but persist **different terminal states** to the
repository — this is exactly the layer referenced above where pause/cancel semantics actually
diverge. A paused download's staging `.part` file is left on disk (so `resume()` can pick up where
it left off via the HTTP range logic in `DownloadTask`), whereas cancellation is expected to be
followed by `delete()` if cleanup is desired.

### `resume(id)`

```kotlin
override suspend fun resume(id: DownloadId) {
    val record = repository.getById(id) ?: return
    if (record.state == "COMPLETED" || record.state == "DOWNLOADING") return

    val now = currentTimeEpochMs()
    repository.updateState(id, "QUEUED", now)

    val request = DownloadRequest(
        url = record.url,
        destinationDirectory = record.destinationDirectory,
        fileName = record.fileName ?: record.url.substringAfterLast('/'),
        priority = record.priority,
        conflictStrategy = record.conflictStrategy
    )

    val task = createDownloadTask(id, request)
    taskMutex.withLock { activeTasks[id] = task }
    scheduler.enqueue(id, record.priority, now) {
        try { task.execute() } finally { taskMutex.withLock { activeTasks.remove(id) } }
    }
}
```

- Reconstructs a `DownloadRequest` from the **persisted `DownloadRecord`** (not from any
  in-memory state — this is what allows resuming a download after an app restart, since nothing
  about the original request needs to survive in memory).
- Guards against resuming a download that's already `COMPLETED` or currently `DOWNLOADING` (a
  no-op safety check to avoid double-starting a task).
- Otherwise flips the record back to `"QUEUED"` and re-enqueues a fresh `DownloadTask` through the
  scheduler, identical to `enqueue()`. Because `DownloadTask.execute()` itself checks for an
  existing staging `.part` file and issues an HTTP range request when one is found (see
  [`docs/engine/http-range-resume.md`](../engine/http-range-resume.md)), this naturally continues
  a previously-paused transfer rather than restarting it from scratch.

### `retry(id)`

```kotlin
override suspend fun retry(id: DownloadId) {
    resume(id)
}
```

Currently a direct alias for `resume` — a `FAILED` download is retried the same way a `PAUSED`
one is resumed (both go through the same "reconstruct request from record, re-enqueue" path). No
separate retry-specific logic (e.g. exponential backoff, retry-count limits) exists yet.

### `delete(id, deleteFiles)`

```kotlin
override suspend fun delete(id: DownloadId, deleteFiles: Boolean) {
    cancel(id)
    val record = repository.getById(id)
    if (deleteFiles && record != null) {
        val partFile = config.stagingDirectory / ".downloader_staging" / "${id.raw}.part"
        fileSystem.delete(partFile)

        val resolvedFileName = record.fileName ?: record.url.substringAfterLast('/')
        val destinationFile = record.destinationDirectory / resolvedFileName
        fileSystem.delete(destinationFile)
    }
    repository.delete(id)
}
```

1. First cancels any in-flight/queued work for the id (reusing `cancel`'s scheduler + in-memory
   cleanup, plus marking the record `"CANCELLED"`, before it's deleted outright).
2. If `deleteFiles` is true (the default), removes **both** possible on-disk artifacts: the
   staging `.part` file (present if the download was ever paused/partial) and the final
   destination file (present if it had already completed) — computed using the same
   `.downloader_staging/<id>.part` convention `DownloadTask` uses internally.
3. Finally removes the database record entirely via `repository.delete(id)`.

### `shutdown()`

```kotlin
override suspend fun shutdown() {
    scheduler.shutdown()
    taskMutex.withLock { activeTasks.clear() }
    network.close()
    scope.cancel()
}
```

Tears down everything in order: stops/cancels all scheduler work, clears the local task-tracking
map, closes the shared `NetworkTransport` (releasing the underlying HTTP client/connection pool —
see `KtorNetworkTransport.close()` in
[`docs/networking/downloader-network-module.md`](../networking/downloader-network-module.md)),
and finally cancels the downloader's own `CoroutineScope` so any remaining background collectors
(e.g. from `observe()`'s fallback flows) stop.

### `createDownloadTask` / `mapRecordToState` (private helpers)

- `createDownloadTask(id, request)` builds a fresh `DownloadStateMachine` seeded with
  `DownloadState.Queued(...)` and constructs a `DownloadTask` from `config`/`repository`/
  `network`/`fileSystem`, keeping this wiring in one place for both `enqueue` and `resume`.
- `mapRecordToState(record)` is the single translation function from the persisted string-based
  `DownloadRecord.state` (`"QUEUED"`, `"CONNECTING"`, `"DOWNLOADING"`, `"PAUSED"`, `"VALIDATING"`,
  `"COMPLETED"`, `"FAILED"`, `"CANCELLED"`) into the corresponding typed `DownloadState` sealed
  class instance, reconstructing whatever fields are available on the record (bytes, totals,
  etag/lastModified, error message) — any unrecognized state string falls back to
  `DownloadState.Idle(id)`.

> **Note (pre-existing, not part of this change's scope):** `currentTimeEpochMs()` in this file is
> implemented as `TimeSource.Monotonic.markNow().elapsedNow().inWholeMilliseconds`, which returns
> the elapsed time since the mark was just taken (effectively ~0), **not** a wall-clock epoch
> timestamp — unlike `DownloadTask`'s own `getSystemClockEpochMs()` expect/actual (see
> [`docs/engine/download-task.md`](../engine/download-task.md)), which correctly reads the
> platform's real clock. This looks like a latent bug worth fixing in a follow-up, but is called
> out here for visibility rather than fixed as part of documenting these changes.
