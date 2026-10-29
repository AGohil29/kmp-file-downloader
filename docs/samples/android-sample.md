# Android Sample (`samples:sample-android`)

A Compose Android app that wires the library modules together with the Android SQLite driver and
the Ktor OkHttp engine. It uses the shared `DownloadScreen` from `samples:sample-common`, so the UI
matches the [desktop sample](desktop-sample.md): pick one of the preset sample URLs (videos, an
image and a PDF — see [Sample URLs](desktop-sample.md#sample-urls)) or type your own, enqueue one
or *Enqueue All Samples*, then pause, resume, retry and delete downloads from a live list.

Source: `samples/sample-android/src/main/java/com/arun/downloader/sample/android/MainActivity.kt`

## Module setup

`settings.gradle.kts` includes the sample:

```kotlin
include(":samples:sample-android")
```

`samples/sample-android/build.gradle.kts`:

- Plugins: `kotlin-android`, `android-application`, `compose-compiler` (version catalog).
- `namespace`/`applicationId` `com.arun.downloader.sample.android`, `minSdk = 26`,
  `compileSdk`/`targetSdk = 36`, Java/JVM target 17, Compose enabled.
- Depends on `downloader-core`, `-filesystem`, `-storage`, `-network`, `-runtime`, plus
  `sqldelight-driver-android`, `androidx-core-ktx`, `androidx-work-runtime-ktx`, `okio` and
  `ktor-client-okhttp`.

### Build and install

```
./gradlew :samples:sample-android:assembleDebug
```

## Wiring

| Step       | Implementation                                                                                                                                            |
|------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------|
| Storage    | `AndroidSqliteDriver(DownloadDatabase.Schema, context, "downloader_sample.db")`, enum column adapters, `SqlDelightDownloadRepository` on `Dispatchers.IO` |
| Network    | `KtorNetworkTransport(HttpClient(OkHttp))`                                                                                                                |
| Filesystem | `OkioDownloadFileSystem`                                                                                                                                  |
| Config     | `DownloaderConfig(maxConcurrentDownloads = 3, stagingDirectory = filesDir)`                                                                               |
| Facade     | `DefaultFileDownloader(config, repository, network, fileSystem)`                                                                                          |

## Lifecycle

- The downloader is owned by `SampleDownloaderViewModel` (an `AndroidViewModel`), not by the
  activity. It survives configuration changes such as rotation, so an in-flight transfer keeps
  running and the UI simply re-attaches to the same `FileDownloader`.
- `MainActivity` requests `POST_NOTIFICATIONS` on Android 13+ and obtains the ViewModel via
  `ViewModelProvider`.
- UI actions run in a `rememberCoroutineScope()`, and the `observeAll()` collector is keyed by the
  downloader instance.

## Progress display

- `Connecting`/`Validating` and downloads with an unknown total show an indeterminate bar.
- Known totals show a determinate bar, clamped to 0–100%.
- Byte counts and speed update while data is still arriving; see
  [`progress-streaming.md`](../testing/progress-streaming.md).

## Background downloads via WorkManager

The **Background** button on the download screen calls `WorkManager.enqueueBackgroundDownload(...)`,
which runs `DownloadWorkManagerWorker`. `MainActivity` sets
`DownloadWorkManagerWorker.downloaderFactoryOverride` to the screen's downloader so the worker shares
its queue and database. `DownloadScreen` exposes this through the optional `onEnqueueBackground`
callback (other platforms leave it unset and the button is hidden). If work runs after a process
restart before the activity opens, the override is not set yet; set it in an `Application` class.

## Testing

The Android-target `downloader-core` and `downloader-network` unit tests run on the host JVM:

```
./gradlew :downloader-core:testDebugUnitTest :downloader-network:testDebugUnitTest
```

See [`progress-streaming.md`](../testing/progress-streaming.md) for the manual checks still
outstanding on a device (slow endpoint, pause/resume, rotation during a transfer).

