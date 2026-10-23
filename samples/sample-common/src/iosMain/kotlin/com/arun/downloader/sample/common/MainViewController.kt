package com.arun.downloader.sample.common

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/**
 * Entry point for iOS host apps. Wraps the shared CMP DownloadScreen
 * inside a native UIKit UIViewController.
 */
fun MainViewController(): UIViewController = ComposeUIViewController {
    val factory = PlatformDownloaderFactory()
    val paths = factory.resolvePaths()
    val downloader = factory.createDownloader(paths)

    DownloadScreen(
        downloader = downloader,
        paths = paths
    )
}