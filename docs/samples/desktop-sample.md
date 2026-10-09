# Desktop Sample (`samples:sample-desktop`)

A Compose Multiplatform (Desktop/JVM) app that wires every library module together and gives a UI
to enqueue, pause, resume, retry and delete downloads. It is the quickest way to manually verify
the engine end to end with real network and disk I/O.

Source: `samples/sample-desktop/src/desktopMain/kotlin/com/arun/downloader/sample/desktop/Main.kt`

## Module setup

`settings.gradle.kts` includes the sample:

```kotlin
include(":samples:sample-desktop")
```

`samples/sample-desktop/build.gradle.kts`:

- Plugins: `kotlin-multiplatform`, `compose-multiplatform`, `compose-compiler` (from the version
  catalog, `gradle/libs.versions.toml`).
- Single `jvm("desktop")` target.
- `desktopMain` depends on `downloader-core`, `-filesystem`, `-storage`, `-network`, `-runtime`,
  plus `okio`, `compose.desktop.currentOs`, `kotlinx-coroutines-swing`,
  `sqldelight-driver-sqlite` and `ktor-client-cio`.
- `compose.desktop.application.mainClass = "com.arun.downloader.sample.desktop.MainKt"`.
- Native distributions: Dmg, Msi, Deb (`packageName = "KmpFileDownloaderSample"`).

`:samples` itself is only a container directory with no build file.

### Run

```
./gradlew :samples:sample-desktop:run
```

## Wiring (`main()`)

| Step        | Implementation                                                                                                                                                                                                                                   |
|-------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Directories | `DesktopPathResolver.getDefaultDownloadDirectory()` / `getDefaultDatabaseDirectory()`                                                                                                                                                            |
| Storage     | `JdbcSqliteDriver("jdbc:sqlite:<dbDir>/downloader_sample.db")`, `DownloadDatabase.Schema.create` (ignored if it already exists), enum column adapters for `priority` and `conflict_strategy`, `SqlDelightDownloadRepository` on `Dispatchers.IO` |
| Network     | Ktor `HttpClient(CIO)` with `HttpTimeout`, wrapped in `KtorNetworkTransport`                                                                                                                                                                     |
| Filesystem  | `OkioDownloadFileSystem`                                                                                                                                                                                                                         |
| Config      | `DownloaderConfig(maxConcurrentDownloads = 3, stagingDirectory = downloadDir)`                                                                                                                                                                   |
| Facade      | `DefaultFileDownloader(config, repository, network, fileSystem)`                                                                                                                                                                                 |
| Shutdown    | `DesktopShutdownHook.register { downloader.shutdown() }`                                                                                                                                                                                         |

### Timeouts

Large files need special timeout handling:

- `requestTimeoutMillis = null` – disables the overall request timeout, otherwise long downloads
  would be aborted.
- `connectTimeoutMillis = 30_000` – connection must be established within 30 s.
- `socketTimeoutMillis = 30_000` – max gap between received bytes.

## UI

### `App`

- **Enqueue card**: URL field, file name field, destination label and a *Start Download* button.
  The request is built with `DownloadPriority.HIGH` and
  `ConflictStrategy.RENAME_NEW_INCREMENTAL` (a repeated file name becomes `name (1).ext`, etc.).
- **Queue list**: a `LazyColumn` fed by `downloader.observeAll()`, collected in a
  `LaunchedEffect`.

### `DownloadItemCard`

| State                 | Display                                         | Actions                    |
|-----------------------|-------------------------------------------------|----------------------------|
| `Queued`/`Connecting` | "Waiting in queue..."                           | Pause (Connecting), Delete |
| `Downloading`         | progress bar, bytes done / total, speed in KB/s | Pause, Delete              |
| `Paused`              | bytes downloaded                                | Resume, Delete             |
| `Completed`           | saved path                                      | Delete                     |
| `Failed`              | error message                                   | Retry, Delete              |

Delete calls `downloader.delete(id, deleteFiles = true)`.

## Test URLs

The default URL is a Cloudflare speed-test endpoint where the size is a query parameter:

```
https://speed.cloudflare.com/__down?bytes=52428800   # 50 MB (default)
https://speed.cloudflare.com/__down?bytes=10485760   # 10 MB
https://speed.cloudflare.com/__down?bytes=104857600  # 100 MB
```

Change the `bytes` value to get a larger file when you want progress and speed to stay visible
longer. Other public hosts (e.g. `download.thinkbroadband.com/100MB.zip`,
`ash-speed.hetzner.com/100MB.bin`) can be pasted into the URL field. These hosts come and go, so
if one fails with a connection error, try another.

Notes:

- Cloudflare's generated stream may not support HTTP `Range`, so *Resume* may restart from zero
  there. Use a static file host to test real resume; see
  [`http-range-resume.md`](../engine/http-range-resume.md).
- Progress shows "Unknown" total when the server sends no `Content-Length`.

## Gradle sync troubleshooting

Command-line Gradle configures and compiles the sample fine. If IDE sync hangs after adding it:

1. Set **Settings → Build Tools → Gradle → Gradle JVM** to JDK 21. Gradle 8.11.1 does not
   support running on JDK 24, and `gradle/gradle-daemon-jvm.properties` requests 21.
2. Raise memory in `gradle.properties`, e.g. `org.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=1g`.
3. The first sync downloads Compose/Skiko artifacts, which are large, so allow it time.
4. Invalidate caches and re-import if it is still stuck.

