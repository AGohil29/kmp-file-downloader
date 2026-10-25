package com.arun.downloader.sample.common

import com.arun.downloader.core.FileDownloader
import com.arun.downloader.core.model.DownloaderConfig
import com.arun.downloader.filesystem.OkioDownloadFileSystem
import com.arun.downloader.network.KtorNetworkTransport
import com.arun.downloader.runtime.DefaultFileDownloader
import com.arun.downloader.runtime.desktop.DesktopPathResolver
import com.arun.downloader.runtime.desktop.DesktopShutdownHook
import com.arun.downloader.storage.db.DatabaseDriverFactory
import com.arun.downloader.storage.db.createDownloadDatabase
import com.arun.downloader.storage.repository.SqlDelightDownloadRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.Dispatchers
import okio.Path.Companion.toPath
import java.io.File

actual class PlatformDownloaderFactory {
    actual fun resolvePaths(): PlatformPaths {
        val userHome = System.getProperty("user.home") ?: "."
        val downloads = File(userHome, "Downloads").apply { mkdirs() }
        val staging = DesktopPathResolver.getDefaultDownloadDirectory()
        return PlatformPaths(
            downloadDirectory = downloads.absolutePath.toPath(),
            stagingDirectory = staging
        )
    }

    actual fun createDownloader(paths: PlatformPaths): FileDownloader {
        val dbDir = DesktopPathResolver.getDefaultDatabaseDirectory().toFile().apply { mkdirs() }
        val dbFile = File(dbDir, "downloader_sample.db")

        // Use the library factory: it creates the schema for new files and upgrades existing
        // databases (e.g. adds final_path) instead of silently ignoring "already created" errors.
        val database = createDownloadDatabase(DatabaseDriverFactory(dbFile))

        val repository = SqlDelightDownloadRepository(
            database = database,
            ioDispatcher = Dispatchers.IO
        )

        val httpClient = HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = null
                connectTimeoutMillis = 30_000L
                socketTimeoutMillis = 30_000L
            }
        }

        val config = DownloaderConfig(
            maxConcurrentDownloads = 3,
            stagingDirectory = paths.stagingDirectory
        )

        val downloader = DefaultFileDownloader(
            config = config,
            repository = repository,
            network = KtorNetworkTransport(httpClient),
            fileSystem = OkioDownloadFileSystem()
        )

        DesktopShutdownHook.register {
            downloader.shutdown()
        }

        return downloader
    }
}