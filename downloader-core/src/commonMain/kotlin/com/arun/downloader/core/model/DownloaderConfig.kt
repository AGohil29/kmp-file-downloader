package com.arun.downloader.core.model

import okio.Path

data class DownloaderConfig(
    val maxConcurrentDownloads: Int = 3,
    val stagingDirectory: Path,
    val connectTimeoutMs: Long = 15_000L,
    val readTimeoutMs: Long = 30_000L,
    val progressUpdateIntervalMs: Long = 100L
)
