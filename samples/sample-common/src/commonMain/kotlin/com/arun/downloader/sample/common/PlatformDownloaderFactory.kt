package com.arun.downloader.sample.common

import com.arun.downloader.core.FileDownloader
import okio.Path

data class PlatformPaths(
    val downloadDirectory: Path,
    val stagingDirectory: Path
)

expect class PlatformDownloaderFactory {
    fun resolvePaths(): PlatformPaths
    fun createDownloader(paths: PlatformPaths): FileDownloader
}