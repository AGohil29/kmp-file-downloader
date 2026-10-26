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

| Step        | Implementation                                                                                                                                                                                                                                                                        |
|-------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Directories | `DesktopPathResolver.getDefaultDownloadDirectory()` / `getDefaultDatabaseDirectory()`                                                                                                                                                                                                 |
| Storage     | `createDownloadDatabase(DatabaseDriverFactory(<dbDir>/downloader_sample.db))` — creates the schema for a new file and upgrades an existing one (e.g. adds `final_path`, `request_headers`, `checksum_algorithm`, `checksum_hash`); `SqlDelightDownloadRepository` on `Dispatchers.IO` |
| Network     | Ktor `HttpClient(CIO)` with `HttpTimeout`, wrapped in `KtorNetworkTransport`                                                                                                                                                                                                          |
| Filesystem  | `OkioDownloadFileSystem`                                                                                                                                                                                                                                                              |
| Config      | `DownloaderConfig(maxConcurrentDownloads = 3, stagingDirectory = downloadDir)`                                                                                                                                                                                                        |
| Facade      | `DefaultFileDownloader(config, repository, network, fileSystem)`                                                                                                                                                                                                                      |
| Shutdown    | `DesktopShutdownHook.register { downloader.shutdown() }`                                                                                                                                                                                                                              |

### Timeouts

Large files need special timeout handling:

- `requestTimeoutMillis = null` – disables the overall request timeout, otherwise long downloads
  would be aborted.
- `connectTimeoutMillis = 30_000` – connection must be established within 30 s.
- `socketTimeoutMillis = 30_000` – max gap between received bytes.

## UI

The UI is the shared `DownloadScreen` composable from `samples:sample-common`
(`samples/sample-common/src/commonMain/kotlin/com/arun/downloader/sample/common/DownloadScreen.kt`),
also used by the Android and iOS samples.

### `DownloadScreen`

- **Enqueue card**:
  - a **Samples** row (`LazyRow`) of preset buttons — tapping one fills the URL and file name
    fields (see [Sample URLs](#sample-urls));
  - URL field, file name field and destination label;
  - **Enqueue All Samples** — enqueues every preset at once;
  - **Enqueue** — enqueues the current URL/file name.

  Requests are built with `DownloadPriority.HIGH`, `ConflictStrategy.RENAME_NEW_INCREMENTAL`
  (a repeated file name becomes `name (1).ext`, etc.) and group `"sample_app"`.
- **Bulk toolbar**: *Pause All*, *Resume All*, *Cancel All* act on the `"sample_app"` group.
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

Delete calls `downloader.delete(id, deleteFiles = true)`. The completed path and the file deleted
are the persisted `finalPath`, so a renamed download (`name (1).ext`) is shown and removed
correctly and the user's original `name.ext` is left alone.

## Sample URLs

The presets are defined in `SAMPLE_DOWNLOADS` in `DownloadScreen.kt`; the form defaults to
*Video 1*:

| Preset  | URL                                                                                                  | File name            |
|---------|------------------------------------------------------------------------------------------------------|----------------------|
| Video 1 | `https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4`                 | `Sample_Video_1.mp4` |
| Video 2 | `https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4`               | `Sample_Video_2.mp4` |
| Video 3 | `https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/SubaruOutbackOnStreetAndDirt.mp4` | `Sample_Video_3.mp4` |
| Video 4 | `https://sample-videos.com/video321/mp4/720/big_buck_bunny_720p_30mb.mp4`                            | `Sample_Video_4.mp4` |
| Image 1 | `https://picsum.photos/200/300`                                                                      | `Sample_Image_1.jpg` |
| PDF 1   | `https://sample-videos.com/pdf/Sample-pdf-5mb.pdf`                                                   | `Sample_Pdf_1.pdf`   |

Any other URL can still be typed in, e.g. a Cloudflare speed-test endpoint where the size is a
query parameter:

```
https://speed.cloudflare.com/__down?bytes=52428800   # 50 MB
https://speed.cloudflare.com/__down?bytes=104857600  # 100 MB
```

Public hosts come and go, so if one fails with a connection error, try another.

Notes:

- The Google `gtv-videos-bucket` files are static and support HTTP `Range`, so they are good for
  testing real pause/resume. `picsum.photos` redirects to a random image and is tiny, so it
  completes almost instantly. Cloudflare's generated stream may not support `Range`, so *Resume*
  may restart from zero there; see [`http-range-resume.md`](../engine/http-range-resume.md).
- Progress shows "Unknown" total and an indeterminate bar when the server sends no
  `Content-Length`; known totals use a determinate bar clamped to 0–100%.
- Byte counts and speed update while the transfer is running (see
  [`progress-streaming.md`](../testing/progress-streaming.md)).

## Gradle sync troubleshooting

Command-line Gradle configures and compiles the sample fine. If IDE sync hangs after adding it:

1. Set **Settings → Build Tools → Gradle → Gradle JVM** to JDK 21. Gradle 8.11.1 does not
   support running on JDK 24, and `gradle/gradle-daemon-jvm.properties` requests 21.
2. Raise memory in `gradle.properties`, e.g. `org.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=1g`.
3. The first sync downloads Compose/Skiko artifacts, which are large, so allow it time.
4. Invalidate caches and re-import if it is still stuck.

