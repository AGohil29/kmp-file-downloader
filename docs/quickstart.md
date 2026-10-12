# 5-Minute Quickstart Guide

`kmp-file-downloader` is a cross-platform file downloading engine for Kotlin Multiplatform
(Android, iOS, Desktop). It supports pause/resume (HTTP range), priorities, concurrency limits,
conflict strategies, checksum validation, persistence across restarts and download groups.

---

## 1. Installation

The library is currently consumed as Gradle modules of this repository (there is no published
Maven artifact yet). Include the repository in your build, or copy the modules, and depend on the
runtime module from `commonMain`:

```kotlin
// build.gradle.kts
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":downloader-runtime"))
            // Types you use directly (DownloadRequest, DownloadState, FileDownloader, ...)
            implementation(project(":downloader-core"))
        }
    }
}
```

If you wire the pieces yourself (step 2) you also need `:downloader-storage`,
`:downloader-network` and `:downloader-filesystem`. Once artifacts are published, replace the
`project(...)` lines with the published coordinates and `<version>`.

Module roles: see [architecture overview](architecture/overview.md).

---

## 2. Platform instantiation

`FileDownloader.create(...)` and per-platform helpers such as `AndroidFileDownloader` or
`DesktopFileDownloader` **do not exist yet**. You build a `DefaultFileDownloader` from four parts:

| Part        | Class                                                                              | Platform-specific bit               |
|-------------|------------------------------------------------------------------------------------|-------------------------------------|
| Config      | `DownloaderConfig(maxConcurrentDownloads, stagingDirectory, ...)`                  | none                                |
| Storage     | `SqlDelightDownloadRepository(createDownloadDatabase(DatabaseDriverFactory(...)))` | `DatabaseDriverFactory`             |
| Network     | `KtorNetworkTransport(HttpClient(engine))`                                         | Ktor engine (OkHttp / Darwin / CIO) |
| File system | `OkioDownloadFileSystem()`                                                         | none (Okio `FileSystem.SYSTEM`)     |

### Desktop (macOS / Windows / Linux)

```kotlin
val downloadDir = "/path/to/downloads".toPath()

val database = createDownloadDatabase(
    DatabaseDriverFactory(dbFile = File(System.getProperty("user.home"), ".myapp/downloads.db"))
)

val downloader: FileDownloader = DefaultFileDownloader(
    config = DownloaderConfig(maxConcurrentDownloads = 3, stagingDirectory = downloadDir),
    repository = SqlDelightDownloadRepository(database),
    network = KtorNetworkTransport(HttpClient(CIO)),   // or any Ktor JVM engine
    fileSystem = OkioDownloadFileSystem()
)
```

`DatabaseDriverFactory()` with no file uses an in-memory database (history is lost on exit).
A complete app is in [samples/desktop-sample.md](samples/desktop-sample.md).

### Android

```kotlin
val database = createDownloadDatabase(DatabaseDriverFactory(context))   // "downloader_metadata.db"

val downloader: FileDownloader = DefaultFileDownloader(
    config = DownloaderConfig(
        stagingDirectory = context.filesDir.absolutePath.toPath()
    ),
    repository = SqlDelightDownloadRepository(database),
    network = KtorNetworkTransport(HttpClient(OkHttp)),
    fileSystem = OkioDownloadFileSystem()
)
```

Keep a single instance (e.g. in your DI graph or `Application`). For background execution and
notifications see [Android notifications and WorkManager](android/android-notifications-and-workmanager.md).

### iOS

```kotlin
val database = createDownloadDatabase(DatabaseDriverFactory())          // "downloader_metadata.db"

val downloader: FileDownloader = DefaultFileDownloader(
    config = DownloaderConfig(stagingDirectory = documentsDirectoryPath.toPath()),
    repository = SqlDelightDownloadRepository(database),
    network = KtorNetworkTransport(HttpClient(Darwin)),
    fileSystem = OkioDownloadFileSystem()
)
```

See [iOS integration](runtime/ios-integration.md) for exposing this to Swift.

> The Ktor engine artifacts (`ktor-client-okhttp`, `ktor-client-darwin`, `ktor-client-cio`) must be
> added to the matching platform source sets of your app.

---

## 3. Enqueue and observe

```kotlin
class DownloadViewModel(private val downloader: FileDownloader) : ViewModel() {

    fun downloadAsset(destinationDir: okio.Path) {
        viewModelScope.launch {
            val request = DownloadRequest(
                url = "https://speed.cloudflare.com/__down?bytes=104857600",
                destinationDirectory = destinationDir,
                fileName = "payload_100mb.bin",
                priority = DownloadPriority.HIGH,
                conflictStrategy = ConflictStrategy.RENAME_NEW_INCREMENTAL,
                group = "app_assets"
            )

            val downloadId = downloader.enqueue(request)

            // Reactively observe state in Compose / UI
            downloader.observe(downloadId).collect { state ->
                when (state) {
                    is DownloadState.Downloading -> {
                        println("Progress: ${state.downloadedBytes} bytes | ${state.speedBytesPerSecond / 1024} KB/s")
                    }
                    is DownloadState.Completed -> {
                        println("File saved to: ${state.absolutePath}")
                    }
                    is DownloadState.Failed -> {
                        println("Error: ${state.error.message} (retry possible: ${state.canRetry})")
                    }
                    is DownloadState.Paused -> {
                        println("Paused at ${state.downloadedBytes} bytes")
                    }
                    else -> Unit
                }
            }
        }
    }

    fun pauseAllAssets() {
        viewModelScope.launch {
            downloader.pauseGroup("app_assets")
        }
    }
}
```

What the pieces mean:

- `enqueue` returns a `DownloadId` immediately; the transfer runs in the background.
- `observe(id)` is a `StateFlow<DownloadState>`. The states are `Idle`, `Queued`, `Connecting`,
  `Downloading`, `Paused`, `Validating`, `Completed`, `Failed` and `Cancelled`. `Completed`,
  `Failed` and `Cancelled` are terminal, so the `collect` above keeps running until you cancel
  the coroutine. Use `.first { it is DownloadState.Completed }` if you only need the result.
- `Downloading.progressPercentage` is available when the server reports a total size.
- `group` is optional. Downloads sharing a group name can be controlled together with
  `pauseGroup`, `resumeGroup` and `cancelGroup`.
- `ConflictStrategy` decides what happens if the destination file already exists
  (e.g. `OVERWRITE`, `RENAME_NEW_INCREMENTAL`).

---

## 4. Controlling downloads

```kotlin
downloader.pause(id)                    // keeps the partial .part file
downloader.resume(id)                   // continues with an HTTP Range request
downloader.retry(id)                    // for Failed downloads (alias of resume)
downloader.cancel(id)
downloader.delete(id, deleteFiles = true)

downloader.resumeGroup("app_assets")
downloader.cancelGroup("app_assets")

downloader.observeAll()                 // Flow<List<DownloadState>> for a download list UI
downloader.shutdown()                   // on app termination
```

After a process restart, downloads that were queued or in flight come back as `Paused` and can
be resumed.

---

## Next steps

- [Architecture overview](architecture/overview.md)
- [Scheduler, public API and `DefaultFileDownloader`](scheduler/download-scheduler-and-file-downloader.md)
- [HTTP range / resume](engine/http-range-resume.md)
- [Testing docs](testing/download-state-machine.md)

