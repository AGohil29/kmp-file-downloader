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

- `maxConcurrentDownloads` seeds the `DownloadScheduler`'s concurrency limit (must be > 0).
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
    private val pendingExecutors = mutableMapOf<DownloadId, suspend () -> Unit>()
    private val activeJobs = mutableMapOf<DownloadId, Job>()
    val activeCount: StateFlow<Int>
    ...
}
```

The coroutine-aware layer that runs queued work. Concurrency is bounded by comparing
`activeJobs.size` with `maxConcurrent` under the mutex; there is no semaphore. Each queued id keeps
**its own executor** in `pendingExecutors`, so a freed slot always runs the executor of the item
actually dequeued (previously a different download's executor could be reused).
`initialMaxConcurrent` and `updateMaxConcurrent` must be greater than zero.

### `enqueue(id, priority, createdAtEpochMs, executor)`

- Throws `IllegalStateException` if the scheduler has been shut down.
- No-op if the `id` is already active or queued.
- Stores the executor, adds the item to `SchedulerQueue`, then calls `dispatchLocked()`, all under
  the mutex.

### `dispatchLocked()` — the core dispatch loop

Must be called with `mutex` held. While the scheduler is not closed and
`activeJobs.size < maxConcurrent`, it polls the highest-priority item, takes its executor, and
launches a job with `CoroutineStart.ATOMIC` (so the `finally` block runs even if the job is
cancelled before its first dispatch). The job is registered in `activeJobs` before any other
dispatcher can observe it, and `activeCount` is updated.

When a job ends (success, failure or cancellation), its `finally` block runs in `NonCancellable`,
removes the job from `activeJobs`, updates `activeCount` and calls `dispatchLocked()` again to fill
the freed slot. No polling loop is needed.

### `cancel(id)` / `pause(id)`

```kotlin
suspend fun cancel(id: DownloadId) {
    val job = mutex.withLock {
        queue.remove(id)
        pendingExecutors.remove(id)
        activeJobs[id]?.also { it.cancel() }
    }
    // Never join under the mutex: the task's finally block needs it to release its slot.
    job?.join()
}

suspend fun pause(id: DownloadId) = cancel(id)
```

- A queued download is removed and never runs; a running one is cancelled.
- **`cancel` now waits for the job to finish cleanup** (outside the mutex). Previously it returned
  immediately, so a late `Paused` write could overwrite `Cancelled`.
- `pause` is still an alias for `cancel` at the scheduler level; the pause/cancel distinction is
  applied in `DefaultFileDownloader` (persisted `"PAUSED"` vs `"CANCELLED"`).

### `updateMaxConcurrent(newMax)` and `shutdown()`

- `updateMaxConcurrent` sets the new limit and calls `dispatchLocked()`. Raising the limit starts
  queued work immediately; lowering it takes effect as running downloads finish (running downloads
  are not cancelled).
- `shutdown()` marks the scheduler closed, clears the queue and pending executors, cancels all
  active jobs and **waits for them to complete** (`joinAll`).


## `DownloadSchedulerTest.kt`

Tests validate the scheduler's core responsibilities independently. Besides the two below,
newer tests cover queued-executor identity (each id runs its own executor), priority order,
cleanup ordering on cancel, and starting queued work when the limit is increased.

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
  shouldBe 0` (everything drained cleanly by the end) — confirming the dispatch
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
`activeTasks: MutableStateFlow<Map<DownloadId, DownloadTask>>` (updated under `taskMutex`)
tracking currently live `DownloadTask` instances. Being a flow lets `observeAll()` react to tasks
starting and finishing, and lets `observe()` return the *live* `StateFlow` for in-flight downloads.

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

### `enqueue(request, id)` — idempotent enqueue

`enqueue(request, id: DownloadId)` lets an external scheduler (e.g. a WorkManager worker) supply a
stable id. `enqueue(request)` simply delegates with `DownloadId.generate()`.

- No record for `id` → inserted and scheduled like a normal enqueue.
- Record `COMPLETED`, or a non-terminal live task exists → no-op.
- Otherwise (paused/failed/cancelled, or a task that just published its terminal state) → the
  old job is joined via `scheduler.cancel(id)` if needed, then `resume(id)` re-schedules it, reusing
  the staging `.part` file so the transfer continues with an HTTP Range request.

See [Android WorkManager](../android/android-notifications-and-workmanager.md).

### `observe(id)` — live vs. cold reconstruction

- If the download is active (in `activeTasks`), its real `DownloadTask.state` is returned directly.
- Otherwise a cached `StateFlow` is built from `repository.observeById(id)` via
  `stateIn(..., SharingStarted.WhileSubscribed(5_000))`, mapping each record through
  `mapRecordToState`. The initial value is `DownloadState.Idle(id)`; the real state arrives
  asynchronously. The earlier `runBlocking` snapshot was removed so callers (often the UI thread)
  are never blocked on the database.

### `observeAll()`

Combines `repository.observeAll()` with the live states of active tasks
(`activeTasks.flatMapLatest { combine(task.state) }`). Persistence owns lifecycle state and byte
counts; the only value overlaid from the live task is the transient `speedBytesPerSecond`, and only
when both the persisted and live states are `Downloading`. A stale live state can therefore never
resurrect a paused, completed or deleted record, and speeds no longer show as zero.


### `pause(id)` vs `cancel(id)`

```kotlin
override suspend fun pause(id: DownloadId) {
    scheduler.pause(id)
    repository.updateState(id, "PAUSED", currentTimeEpochMs())
    taskMutex.withLock { activeTasks.value = activeTasks.value - id }
}

override suspend fun cancel(id: DownloadId) {
    scheduler.cancel(id)
    taskMutex.withLock { activeTasks.value = activeTasks.value - id }
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
        headers = record.headers,
        conflictStrategy = record.conflictStrategy,
        expectedChecksum = record.expectedChecksum,
        group = record.group
    )

    val task = createDownloadTask(id, request)
    taskMutex.withLock { activeTasks.value = activeTasks.value + (id to task) }
    scheduler.enqueue(id, record.priority, now) {
        try { task.execute() } finally { taskMutex.withLock { activeTasks.value = activeTasks.value - id } }
    }
}
```

- Reconstructs the **full** `DownloadRequest` from the **persisted `DownloadRecord`** (not from any
  in-memory state — this is what allows resuming a download after an app restart, since nothing
  about the original request needs to survive in memory). Every request field is restored:
  `headers`, `expectedChecksum` and `group` included (see
  [Persisted request data](#persisted-request-data-headers--checksum) below).
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

### Persisted request data (headers & checksum)

**Bug fixed.** `resume()`/`retry()` used to rebuild the request with only `url`,
`destinationDirectory`, `fileName`, `priority` and `conflictStrategy`. `headers` and
`expectedChecksum` were never persisted, so they silently fell back to their defaults
(`emptyMap()` / `null`):

- a resumed/retried download was sent **without auth headers** (e.g. `Authorization`) and could
  fail authentication;
- **checksum validation was skipped**, so a corrupt payload could be committed as `COMPLETED`.

**Fix.**

- `DownloadRecord` gained `headers: Map<String, String> = emptyMap()` and
  `expectedChecksum: ChecksumValidation? = null`.
- `DownloadRecordEntity` gained three nullable columns, written by `insertOrReplaceRecord` and
  mapped back in `toDomain()`:

  | Column               | Content                                                                                                                    |
  |----------------------|----------------------------------------------------------------------------------------------------------------------------|
  | `request_headers`    | headers encoded by `HeaderCodec` as `k1=v1&k2=v2` (`%`, `=`, `&` and control chars are percent-encoded); `NULL` when empty |
  | `checksum_algorithm` | `ChecksumValidation.Algorithm.name` (`MD5`/`SHA1`/`SHA256`)                                                                |
  | `checksum_hash`      | expected hex digest                                                                                                        |

- **Migration:** `2.sqm` (schema v2 → v3) adds the columns for drivers that use
  `DownloadDatabase.Schema.migrate` (Android, iOS). The desktop `DatabaseDriverFactory`
  `migrateLegacySchema` adds any missing column on an existing file. Rows written before the
  migration read back as "no headers / no checksum".
- `resume()` passes `headers`, `expectedChecksum` and `group` into the rebuilt request.

> **Security note:** headers are stored in plain text in the downloads database so a download can
> resume after a process restart. If they contain long-lived secrets, protect the database file
> (e.g. SQLCipher / platform encryption) or use short-lived tokens.

Regression tests: `ResumeRetryRequestLossReproTest` (`downloader-runtime/src/desktopTest`) checks
that retry/resume resend the original headers and that a corrupt payload ends as `FAILED`, and
`SqlDelightDownloadRepositoryTest.headersAndChecksumRoundTrip` checks the storage round trip.

### `delete(id, deleteFiles)`

```kotlin
override suspend fun delete(id: DownloadId, deleteFiles: Boolean) {
    cancel(id)
    val record = repository.getById(id)
    if (deleteFiles && record != null) {
        record.finalPath?.let { fileSystem.delete(it) }
    }
    repository.delete(id)
}
```

1. First cancels any in-flight/queued work for the id (reusing `cancel`'s scheduler + in-memory
   cleanup, plus marking the record `"CANCELLED"`, before it's deleted outright). `cancel` itself
   now deletes the staging `.part` file once the scheduler has joined the job (so file handles
   are released) and then persists `"CANCELLED"`; a cancelled download no longer leaves partial
   data behind. Covered by `CancelPurgesPartFileReproTest`.
2. If `deleteFiles` is true (the default), removes the committed file recorded in
   `record.finalPath` (present if the download had completed). The staging `.part` file is already
   gone because of step 1.
3. Finally removes the database record entirely via `repository.delete(id)`.

### Download groups

A download can be tagged with an optional group name so related downloads (e.g. all files of one
album or one update package) can be controlled together.

**Model changes**

- `DownloadRequest.group: String? = null` — set it when enqueuing. `null` means "ungrouped".
- `DownloadRecord.group: String? = null` — the persisted value, read back from storage.
- `DownloadRepository.getByGroup(groupName): List<DownloadRecord>` — new repository query.

**Storage changes (`downloader-storage`)**

- New nullable column `download_group TEXT` on `DownloadRecordEntity`, plus the index
  `idx_download_record_group` for fast lookup. `insertOrReplaceRecord` writes it from
  `request.group`, and `toDomain()` maps it back to `DownloadRecord.group`.
- New queries: `selectByGroup` (ordered by priority DESC, then creation time ASC) and
  `updateStateByGroup` (bulk state update for records in `QUEUED`, `CONNECTING`, `DOWNLOADING` or
  `PAUSED`).
- **Schema change:** this adds a column to an existing table. The desktop
  `DatabaseDriverFactory` now runs `migrateLegacySchema` on an existing database file: it reads
  `PRAGMA table_info(DownloadRecordEntity)`, creates the schema if the table is missing, and
  otherwise adds `download_group` plus `idx_download_record_group` if absent. Fresh installs and
  in-memory test databases are unaffected; other platforms still need their own migration.
- `markCompleted` now sets `downloaded_bytes = COALESCE(total_bytes, downloaded_bytes)`, so
  unknown-length downloads keep their actual transferred byte count instead of writing null.

**Runtime behaviour (`DefaultFileDownloader`)**

```kotlin
override suspend fun pauseGroup(groupName: String) {
    repository.getByGroup(groupName).forEach { pause(it.id) }
}
// resumeGroup -> resume(it.id), cancelGroup -> cancel(it.id)
```

Each group operation loads the group's records and delegates to the existing single-download
`pause`/`resume`/`cancel`, so per-download semantics (scheduler removal, state machine
transitions, resumable `.part` files) are identical. Group operations are not atomic: each
download is handled in turn, and an unknown group name is a no-op.

**Startup reconciliation change**

`reconcileStaleStates` now also resets `QUEUED` records to `PAUSED` (previously only `CONNECTING`
and `DOWNLOADING`). After a process restart the in-memory scheduler queue is empty, so a
persisted `QUEUED` record would otherwise never run.

### `shutdown()`

```kotlin
override suspend fun shutdown() {
    scheduler.shutdown()
    taskMutex.withLock { activeTasks.value = emptyMap() }
    network.close()
    scope.cancel()
}
```

Tears down everything in order: stops/cancels all scheduler work, clears the local task-tracking
map, closes the shared `NetworkTransport` (releasing the underlying HTTP client/connection pool —
see `KtorNetworkTransport.close()` in
[`docs/networking/downloader-network-module.md`](../networking/downloader-network-module.md)),
and finally cancels the downloader's own `CoroutineScope` so any remaining background collectors
(e.g. from `observe()`'s fallback flows) stop. `scheduler.shutdown()` now waits for active jobs to
finish cleanup before the rest of the teardown runs.

### `createDownloadTask` / `mapRecordToState` (private helpers)

- `createDownloadTask(id, request)` builds a fresh `DownloadStateMachine` seeded with
  `DownloadState.Queued(...)` and constructs a `DownloadTask` from `config`/`repository`/
  `network`/`fileSystem`, keeping this wiring in one place for both `enqueue` and `resume`.
- `mapRecordToState(record)` is the single translation function from the persisted string-based
  `DownloadRecord.state` (`"QUEUED"`, `"CONNECTING"`, `"DOWNLOADING"`, `"PAUSED"`, `"VALIDATING"`,
  `"COMPLETED"`, `"FAILED"`, `"CANCELLED"`) into the corresponding typed `DownloadState` sealed
  class instance, reconstructing whatever fields are available on the record (bytes, totals,
  etag/lastModified, error message) — any unrecognized state string falls back to
  `DownloadState.Idle(id)`. For `Completed`, the path is `record.finalPath`; only legacy records
  without it fall back to `destinationDirectory / (fileName ?: fileNameFromUrl(url))`.
- For `FAILED` records, the error is rebuilt by the private `restoreError(errorType, errorMessage)`
  from the persisted `error_type` (the `DownloadError` subclass simple name) and `error_message`,
  and `canRetry` is `error is DownloadError.Retryable` — the same rule `DownloadTask` applies live.
  Previously every failure was rebuilt as `NonRetryable.Unknown` with `canRetry = true`, so the
  concrete type and retryability were lost after a restart. Unknown/missing types still fall back
  to `NonRetryable.Unknown(message ?: "Failed")`.
  - Only type and message are persisted, so structured fields (`httpCode`, `expected`/`actual`
    checksum, paths, disk-space numbers) are parsed best-effort from the default message formats.
    `RateLimited.retryAfterMs` and `RangeNotSatisfiable.totalBytes` are restored as `null`.
  - Covered by `FailedStateRestoreReproTest` (`downloader-runtime` `desktopTest`).

### Background transfer engine (`backgroundEngine`)

`DefaultFileDownloader` accepts an optional `BackgroundTransferEngine` (core). When set (iOS:
`IosBackgroundSessionBridge`), `enqueue`, `resume`, `pause` and `cancel` delegate to it instead of the
scheduler/`DownloadTask`/`NetworkTransport`; the engine writes state to the repository and `observe`
falls back to persisted records. `enqueue` with an existing id only resumes if the record is not
`QUEUED`/`CONNECTING`/`DOWNLOADING`/`COMPLETED`. Storage gained `background_task_id` and
`resume_data` columns (migration `3.sqm`) behind `BackgroundTaskStore`. See
`docs/runtime/ios-integration.md`.

### File names from URLs (`fileNameFromUrl`)

`substringAfterLast('/')` kept query strings, fragments and percent-escapes (`a.zip?token=1`,
`my%20file.zip`). `core.engine.fileNameFromUrl(url)` (used by `DownloadTask`,
`DefaultFileDownloader` and `IosBackgroundSessionBridge`) drops `?query`/`#fragment`, takes the
last path segment and percent-decodes it (UTF-8). A bare host or trailing `/` yields `""`, which
`sanitizeFileName` turns into a generated fallback name. Tests: `UrlFileNameTest`,
`UrlFileNameReproTest`.

### Timestamps (`EpochClock`) — bug fixed

`currentTimeEpochMs()` used to be
`TimeSource.Monotonic.markNow().elapsedNow().inWholeMilliseconds`, which measures the time since a
mark taken on the same line (≈ 0) instead of a wall-clock time. Every timestamp written by
`DefaultFileDownloader` (`createdAtEpochMs`, `updatedAtEpochMs`, `Queued` enqueue time, scheduler
ordering) was therefore about `0`.

It now delegates to the shared `com.arun.downloader.core.time.EpochClock`:

```kotlin
object EpochClock {
    fun nowEpochMs(): Long = getSystemClockEpochMs()
}

private fun currentTimeEpochMs(): Long = EpochClock.nowEpochMs()
```

`EpochClock` (in `downloader-core`) wraps the same platform `getSystemClockEpochMs()`
expect/actual that `DownloadTask` uses (see
[`docs/engine/download-task.md`](../engine/download-task.md#platform-clock-expectactual)), so the
engine and runtime share one wall-clock source. Do not use a monotonic `TimeSource` for persisted
timestamps. Covered by `downloader-runtime/src/desktopTest/.../TimestampEpochReproTest.kt`.
