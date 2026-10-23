package com.arun.downloader.runtime.desktop

import okio.Path
import okio.Path.Companion.toPath

object DesktopPathResolver {
    fun getDefaultDownloadDirectory(): Path {
        val userHome = System.getProperty("user.home") ?: "."
        val osName = System.getProperty("os.name", "").lowercase()

        return when {
            osName.contains("win") -> "$userHome\\Downloads".toPath()
            osName.contains("mac") -> "$userHome/Downloads".toPath()
            else -> {
                val xdgDownloads = System.getenv("XDG_DOWNLOAD_DIR")
                if (!xdgDownloads.isNullOrBlank()) {
                    xdgDownloads.toPath()
                } else {
                    "$userHome/Downloads".toPath()
                }
            }
        }
    }

    fun getDefaultDatabaseDirectory(appName: String = "KmpFileDownloader"): Path {
        val userHome = System.getProperty("user.home") ?: "."
        val osName = System.getProperty("os.name", "").lowercase()

        return when {
            osName.contains("win") -> {
                val appData = System.getenv("APPDATA") ?: "$userHome\\AppData\\Roaming"
                "$appData\\$appName".toPath()
            }
            osName.contains("mac") -> {
                "$userHome/Library/Application Support/$appName".toPath()
            }
            else -> {
                val xdgData = System.getenv("XDG_DATA_HOME") ?: "$userHome/.local/share"
                "$xdgData/$appName".toPath()
            }
        }
    }
}