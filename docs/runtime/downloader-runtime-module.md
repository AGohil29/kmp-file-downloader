# `downloader-runtime` Module

This document explains the `downloader-runtime` module: why it exists, how it replaced three
separate platform-specific modules, and what `DownloadTaskTest` verifies.

## Consolidation: three modules → one

Previously there were three near-empty platform-specific runtime shells:

```
downloader-runtime-android/   (androidMain only)
downloader-runtime-desktop/   (jvmMain only — unused; convention is desktopMain)
downloader-runtime-ios/       (iosMain only)
```

Each had an identical `build.gradle.kts`:

```kotlin
plugins {
    id("kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":downloader-core"))
        }
    }
}
```

The `kmp-library` convention plugin (`build-logic/src/main/kotlin/kmp-library.gradle.kts`)
already configures **all** platform targets (`androidTarget()`, `jvm("desktop")`, `iosX64()`,
`iosArm64()`, `iosSimulatorArm64()`) for *any* module that applies it — so splitting the runtime
into per-platform modules provided no isolation benefit and only added maintenance overhead
(three build files, three `settings.gradle.kts` entries, no shared test surface). Every other
module in the repo (`downloader-core`, `downloader-filesystem`, `downloader-storage`,
`downloader-network`) already follows the "one module, multiple source sets" pattern.

**Change made**: the three modules were removed and replaced with a single `downloader-runtime`
module containing `androidMain`, `desktopMain`, and `iosMain` source sets (matching the naming
convention used elsewhere), and `settings.gradle.kts` was updated accordingly:

```diff
- include(":downloader-runtime-desktop")
- include(":downloader-runtime-android")
- include(":downloader-runtime-ios")
+ include(":downloader-runtime")
```

## Current role: integration test harness

The module's `build.gradle.kts` now depends on **all** adapter modules, not just
`downloader-core`:

```kotlin
kotlin {
    applyDefaultHierarchyTemplate()
    sourceSets {
        commonMain.dependencies {
            implementation(project(":downloader-core"))
            implementation(project(":downloader-storage"))
            implementation(project(":downloader-network"))
            implementation(project(":downloader-filesystem"))
            implementation(libs.findLibrary("ktor-client-core").get())
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.findLibrary("kotlinx-coroutines-test").get())
            implementation(libs.findLibrary("turbine").get())
            implementation(libs.findLibrary("kotest-assertions").get())
            implementation(libs.findLibrary("okio-fakefilesystem").get())
        }
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
                implementation(libs.findLibrary("sqldelight-driver-sqlite").get())
            }
        }
        val androidUnitTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
            }
        }
    }
}
```

This makes `downloader-runtime` the one place in the codebase where **all four modules are wired
together** and exercised as a whole — it hosts
[`DownloadTaskTest`](../../downloader-runtime/src/desktopTest/kotlin/com/arun/downloader/runtime/engine/DownloadTaskTest.kt),
which constructs a real `DownloadTask` (from `downloader-core`) using:

- a real `DownloadStateMachine`
- a real `SqlDelightDownloadRepository` (from `downloader-storage`) backed by an in-memory SQLite
  DB (`JdbcSqliteDriver.IN_MEMORY`)
- an `OkioDownloadFileSystem` (from `downloader-filesystem`) backed by `okio.fakefilesystem.FakeFileSystem`
  (in-memory, no real disk I/O)
- a hand-written fake `NetworkTransport` that returns canned `NetworkResponse`s over an in-memory
  `io.ktor.utils.io.ByteChannel` (no real network I/O)

This gives full confidence in the engine's orchestration logic without any of the flakiness or
speed cost of real network/disk access, while still testing the *real* implementations of the
storage and filesystem layers (only the network boundary is faked, since HTTP is already unit
tested independently in `downloader-network`).

### Important: `kotlin("test-junit")`, not `useJUnitPlatform()`

Every module's `desktopTest`/`androidUnitTest` uses the JUnit **4** engine
(`implementation(kotlin("test-junit"))`). Do **not** add
`tasks.withType<Test> { useJUnitPlatform() }` to a module's `build.gradle.kts` unless its test
dependency is also switched to `kotlin("test-junit5")` (or a JUnit5/Jupiter engine is added) —
otherwise Gradle's test worker fails immediately with:

```
PreconditionViolationException: Cannot create Launcher without at least one TestEngine
```

This exact mismatch existed transiently in `downloader-runtime/build.gradle.kts` and was removed;
see [`docs/engine/download-task.md`](../engine/download-task.md) for the full debugging story
(this was one of two stacked issues that made `DownloadTaskTest` fail).

## Test cases in `DownloadTaskTest`

| Test                                                        | What it verifies                                                                                                                                           |
|-------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `successfulDownloadExecutesTwoPhaseCommitAndMarksCompleted` | Full happy path: `Idle -> ... -> Completed`, staging `.part` file is cleaned up, destination file exists with correct bytes, DB row reaches `"COMPLETED"`. |
| `checksumMismatchDeletesOrFailsGracefully`                  | A wrong `expectedChecksum` causes `Failed(ChecksumMismatch)`, and the destination file is never created.                                                   |
| `cancellationPreservesPartialFileAndEntersPausedState`      | Cancelling the job mid-stream results in `Paused` (in-memory state *and* persisted DB state `"PAUSED"`), without crashing or losing the partial file.      |

## Module dependency diagram

```mermaid
graph TD
    subgraph downloader-runtime [downloader-runtime (test-only wiring)]
        DTT[DownloadTaskTest]
    end
    DTT --> DownloadTask
    DTT --> SqlDelightDownloadRepository
    DTT --> OkioDownloadFileSystem
    DTT --> FakeNetworkTransport[fake NetworkTransport]

    subgraph downloader-core
        DownloadTask
        DownloadStateMachine
    end
    subgraph downloader-storage
        SqlDelightDownloadRepository
    end
    subgraph downloader-filesystem
        OkioDownloadFileSystem
    end

    DownloadTask --> DownloadStateMachine
```

## Running the tests

```bash
./gradlew :downloader-runtime:desktopTest --tests "*DownloadTaskTest*"
# or, all platform-independent tests:
./gradlew :downloader-runtime:allTests
```
