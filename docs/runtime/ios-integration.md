# iOS Background Download Integration

This document outlines the setup required to run background transfers on iOS using `downloader-runtime`.

---

## 1. Capabilities & Plist Configuration

To ensure iOS and Apple's root daemon (`nsurlsessiond`) can wake or relaunch your application when a background download finishes, configure the required background modes.

In your iOS app's **`Info.plist`** (or Target $\rightarrow$ **Signing & Capabilities** $\rightarrow$ **+ Capability** $\rightarrow$ **Background Modes**):

```xml
<key>UIBackgroundModes</key>
<array>
    <string>fetch</string>
    <string>processing</string>
</array>
```

---

## 2. Background transfer engine

By default `DefaultFileDownloader` downloads in-process through `NetworkTransport` (Ktor/Darwin).
That stops when iOS suspends the app. To keep transfers running, pass a `BackgroundTransferEngine`:

```kotlin
val bridge = IosBackgroundSessionBridge(
    repository = repository,          // SqlDelightDownloadRepository
    taskStore = repository,           // it also implements BackgroundTaskStore
    fileSystem = fileSystem,
    stagingDirectory = paths.stagingDirectory
)
IosAppDelegateHelper.attachBridge(bridge)

val downloader = DefaultFileDownloader(
    config = config, repository = repository, network = transport,
    fileSystem = fileSystem, backgroundEngine = bridge
)
```

When `backgroundEngine` is set, `enqueue`/`resume`/`pause`/`cancel` are delegated to it. State and
progress are written to the repository, so `observe`/`observeAll` read from the database.

### Behaviour of `IosBackgroundSessionBridge`

| Concern            | Behaviour                                                                                                                                                                                         |
|--------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Session            | Background `NSURLSession`, recreated with the same identifier on launch (`attach()`) to receive missed events.                                                                                    |
| Task identity      | `taskDescription` holds the `DownloadId`; `taskIdentifier` and resume data are persisted (`background_task_id`, `resume_data` columns, migration `3.sqm`), so the mapping survives process death. |
| Pause              | Resume data from `cancelByProducingResumeData` is stored; the next `resume()` starts `downloadTaskWithResumeData`.                                                                                |
| Failure            | Resume data supplied by iOS on error is stored, so `retry()` continues. Timeouts map to `NetworkTimeout`, others to `ConnectionInterrupted`.                                                      |
| Completion         | The system temp file is moved synchronously in the delegate callback, then HTTP status and checksum are checked and the file is committed to its destination.                                     |
| Completion handler | Invoked on the main queue after pending database writes finish.                                                                                                                                   |

Not recorded on iOS: ETag / Last-Modified (resume relies on iOS resume data).

### AppDelegate

Forward `handleEventsForBackgroundURLSession` to `IosAppDelegateHelper.shared`. A handler received
before the bridge is attached is queued and registered on `attachBridge`.

Tests: `IosBackgroundWiringReproTest` (source-level checks of wiring, resume data and persistence).
