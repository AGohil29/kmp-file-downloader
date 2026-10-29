package com.arun.downloader.runtime

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Reproduces three iOS issues. iOS code cannot be compiled by the desktop test target, so these
 * tests inspect the Kotlin sources (the same way the ios-integration docs describe the wiring).
 * Each test asserts the *desired* behaviour and therefore FAILS until the issue is fixed.
 *
 * 1. `DefaultFileDownloader` only talks to an injected `NetworkTransport` (Ktor/Darwin, in-process),
 *    and nothing on iOS ever instantiates `IosBackgroundSessionBridge`, so downloads die when the
 *    app is suspended.
 * 2. `pauseDownload` discards the resume data produced by `cancelByProducingResumeData`, so a
 *    paused iOS download can never continue from where it stopped.
 * 3. `activeTasks` / `stateMachines` live only in memory; after the process is killed and relaunched
 *    by the system for background events, `taskIdentifier -> DownloadId` is lost and every
 *    delegate callback is dropped by `activeTasks[...] ?: return`.
 */
class IosBackgroundWiringReproTest {

    private val moduleRoot: Path = FileSystem.SYSTEM.canonicalize(".".toPath())
    private val repoRoot: Path = generateSequence(moduleRoot) { it.parent }
        .first { FileSystem.SYSTEM.exists(it / "settings.gradle.kts") }

    private fun read(path: Path): String = FileSystem.SYSTEM.read(path) { readUtf8() }

    private fun kotlinSources(dir: Path): List<Path> =
        if (!FileSystem.SYSTEM.exists(dir)) emptyList()
        else FileSystem.SYSTEM.listRecursively(dir).filter { it.name.endsWith(".kt") }.toList()

    private val bridgeFile get() =
        moduleRoot / "src/iosMain/kotlin/com/arun/downloader/runtime/ios/IosBackgroundSessionBridge.kt"

    @Test
    fun `iOS downloader is wired to the NSURLSession background bridge`() {
        // Everything that builds a downloader for iOS (library + samples), excluding the bridge itself
        val iosSources = listOf("downloader-runtime", "samples/sample-common", "samples/sample-ios")
            .flatMap { kotlinSources(repoRoot / it / "src") }
            .filter { it.toString().contains("/iosMain/") || it.toString().contains("/commonMain/") }
            .filter { it != bridgeFile && it.name != "IosAppDelegateHelper.kt" }

        val referencing = iosSources.filter { "IosBackgroundSessionBridge" in read(it) }
        assertTrue(
            referencing.isNotEmpty(),
            "No iOS/common source constructs or uses IosBackgroundSessionBridge: downloads use the " +
                    "in-process Ktor Darwin engine and stop when the app is suspended."
        )
    }

    @Test
    fun `pausing an iOS download keeps the resume data`() {
        val source = read(bridgeFile)
        assertTrue(
            !Regex("""cancelByProducingResumeData\s*\{\s*_\s*->""").containsMatchIn(source),
            "cancelByProducingResumeData ignores its NSData argument (`{ _ -> ... }`)."
        )
        assertTrue(
            !source.contains("onPaused?.invoke(null)"),
            "onPaused is always invoked with null, so resume data is never handed to the caller/storage."
        )
    }

    @Test
    fun `iOS task mapping survives process death`() {
        val source = read(bridgeFile)
        val inMemoryOnly = source.contains("private val activeTasks = mutableMapOf<ULong, DownloadId>()")
        val persisted = Regex("""repository\.\w*(TaskId|Task|ResumeData)\w*\(""").containsMatchIn(source)
        assertTrue(
            !inMemoryOnly || persisted,
            "activeTasks (taskIdentifier -> DownloadId) is an in-memory mutableMap and is never " +
                    "persisted; after relaunch for background events all delegate callbacks are dropped."
        )
    }
}

