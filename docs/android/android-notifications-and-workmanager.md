# Android Notifications, Foreground Service & WorkManager Integration

## Build configuration

### `downloader-runtime/build.gradle.kts`
- Adds `androidTarget { ... jvmTarget = "17" }` so the module compiles for Android with JVM 17.
- New `androidMain` dependencies: `androidx-core-ktx` (notifications, `ServiceCompat`),
  `androidx-work-runtime-ktx` (WorkManager), `sqldelight-driver-android` (SQLite on Android) and
  `ktor-client-okhttp` (HTTP engine).
- New `android { }` block: `namespace = "com.arun.downloader.runtime"`, `compileSdk = 36`,
  `minSdk = 24`, Java 17 source/target compatibility.

### `gradle/libs.versions.toml`
The catalog alias `androidx-workmanager` was renamed to `androidx-work-runtime-ktx` (same module,
`androidx.work:work-runtime-ktx`) so it matches the name looked up by
`libs.findLibrary("androidx-work-runtime-ktx")` in the build script.

## `AndroidManifest.xml`
- **Permissions**: `INTERNET`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_DATA_SYNC` (required on Android 14+ for a `dataSync` foreground service) and
  `POST_NOTIFICATIONS` (Android 13+; the host app must still request it at runtime).
- **`DownloadForegroundService`** declared non-exported with `foregroundServiceType="dataSync"`.
- **`DownloadActionReceiver`** declared non-exported, listening for
  `com.arun.downloader.action.PAUSE` and `com.arun.downloader.action.CANCEL`.
- A commented-out `androidx.startup.InitializationProvider` block (for disabling WorkManager's
  default auto-initialization so the host app can supply a custom configuration/factory). It is
  currently inactive, so WorkManager initializes with its defaults.

## `DownloadNotificationManager.kt`
Wraps Android's `NotificationManager`.
- On construction creates the notification channel `download_channel_v1` ("File Downloads",
  `IMPORTANCE_LOW`, no badge) on API 26+.
- `buildProgressNotification(title, downloadedBytes, totalBytes, progressPercent, downloadId)`:
  ongoing, only-alert-once notification. With known size it shows a determinate progress bar and
  text like `1.20 MB / 5.00 MB (24%)`; otherwise an indeterminate bar with `Downloaded <size>`.
  It attaches **Pause** and **Cancel** actions as `PendingIntent.getBroadcast` targeting
  `DownloadActionReceiver`, carrying the download id in `EXTRA_DOWNLOAD_ID`. Request codes are
  derived from `downloadId.hashCode()` (cancel XORed with `0x5555`) so each download gets distinct
  intents; `FLAG_IMMUTABLE` is used for API 31+ compliance.
- `buildCompletionNotification` / `buildFailureNotification`: auto-cancel notifications with
  "Download Complete" / "Download Failed" text.
- `notify(id, notification)` and `cancel(id)` delegate to the system manager.
- `formatBytes` renders B/KB/MB/GB for display.
- Constants: `CHANNEL_ID`, `ACTION_PAUSE`, `ACTION_CANCEL`, `EXTRA_DOWNLOAD_ID`.

## `DownloadForegroundService.kt`
A `Service` that keeps the process alive and shows progress while downloads run.
- `onStartCommand` immediately calls `startForeground` with an initial "Downloading file..."
  notification (using `ServiceCompat.startForeground` with
  `FOREGROUND_SERVICE_TYPE_DATA_SYNC` on API 34+), then starts `observeDownloader()` and returns
  `START_NOT_STICKY`.
- `observeDownloader()` collects `downloader.observeAll()` on a `SupervisorJob + Dispatchers.Main.immediate`
  scope. It filters states to `Downloading`/`Connecting`/`Queued`. If none remain, it calls
  `stopForeground(STOP_FOREGROUND_REMOVE)` and `stopSelf()`. Otherwise it picks a primary download
  (first `Downloading`, else first active) and updates the single foreground notification
  (id `9999`) with percent/bytes, or a "Connecting..." message.
- `onDestroy` cancels the service scope; `onBind` returns `null` (started service only).
- Companion: `NOTIFICATION_ID = 9999`, a `@Volatile var downloaderInstance: FileDownloader?` used as
  a process-wide handle to the downloader, `start(context, downloader)` (stores the instance and
  calls `ContextCompat.startForegroundService`) and `stop(context)`.

## `DownloadActionReceiver.kt`
A `BroadcastReceiver` for the notification buttons. It reads `EXTRA_DOWNLOAD_ID`, builds a
`DownloadId`, and on `ACTION_PAUSE` / `ACTION_CANCEL` launches a coroutine on
`Dispatchers.Default`. **The actual calls to `downloaderInstance?.pause(id)` /
`cancel(id)` are currently commented out**, so tapping the buttons does nothing yet.

## `DownloadWorkManagerWorker.kt`
A `CoroutineWorker` that runs one download as WorkManager background work.
- Input keys: `KEY_URL` (required), `KEY_DEST_DIR` (defaults to `filesDir`), `KEY_FILE_NAME`.
  Output keys on success: `KEY_OUTPUT_PATH`, `KEY_TOTAL_BYTES`.
- `doWork()`:
  1. Fails immediately if no URL.
  2. Builds a progress notification and calls `setForeground(ForegroundInfo(...))` (with the
     `dataSync` type on API 29+); failures to promote are swallowed.
  3. Obtains a `FileDownloader` via `downloaderProvider`, calls
     `enqueue(request, DownloadId("wm_<workId>"))`, and waits
     with `observe(id).first { Completed | Failed | Cancelled }`.
     **Retries resume instead of restarting:** WorkManager keeps the same work id across
     `Result.retry()` attempts, so the id-based `enqueue` is idempotent. If a record for that id
     exists it is resumed (reusing its `.part` file via HTTP Range), a `COMPLETED` one is left as
     is, and a still-running task is not duplicated. Previously every attempt called
     `enqueue(request)`, which minted a new `DownloadId`, a new record and a new `.part` file, so
     each retry restarted from byte 0 and orphaned the old partial file. Covered by
     `WorkerRetryRestartsFromZeroReproTest`.
  4. Maps the terminal state: `Completed` → `Result.success` with output path/size; `Failed` with
     `canRetry` and `runAttemptCount < 3` → `Result.retry()`, otherwise `Result.failure()`;
     `Cancelled` → `Result.failure()`. Any thrown exception retries up to 3 attempts.
- The downloader is resolved by `defaultDownloaderFactory`: first
  `downloaderFactoryOverride`, then `DownloadForegroundService.downloaderInstance`, otherwise it
  throws `IllegalStateException` asking the app to initialize one. The host app must therefore set
  one of these before any work runs.
- A secondary two-arg constructor exists because WorkManager's default `WorkerFactory` requires
  `(Context, WorkerParameters)`.

## `WorkManagerDownloaderExt.kt`
`WorkManager.enqueueBackgroundDownload(request, requiresUnmeteredNetwork = false, requiresCharging = false): UUID`
- Builds `Constraints` (network `UNMETERED` or `CONNECTED`, optional charging requirement).
- Creates a `OneTimeWorkRequest<DownloadWorkManagerWorker>` with input data from the
  `DownloadRequest` (url, destination directory string, file name), enqueues it, and returns the
  work id for tracking/cancellation.
- Only url, destination and file name are forwarded; priority, headers, checksum and conflict
  strategy of the `DownloadRequest` are not passed to the worker.

## How the pieces fit together

```
App ── enqueueBackgroundDownload ──> WorkManager ──> DownloadWorkManagerWorker
                                                         │  (foreground info + notification)
                                                         ▼
                                                  FileDownloader.enqueue/observe

App ── DownloadForegroundService.start(ctx, downloader) ──> observeAll() ──> progress notification
Notification [Pause|Cancel] ──> DownloadActionReceiver ──> (downloader.pause/cancel – TODO)
```

## Known gaps / notes
- `DownloadActionReceiver` does not yet invoke `pause`/`cancel` (lines are commented out).
- `DownloadNotificationManager` has unused/stray imports (`android.R.attr.action`,
  `kotlin.jvm.java`) that should be removed.
- The foreground service uses a single notification id (`9999`) for the "primary" download, and the
  worker also uses it, so concurrent work shares one notification slot.
- Completion/failure notification builders exist but are not yet posted by any component.
- No Android unit/instrumented tests were added in this commit.
