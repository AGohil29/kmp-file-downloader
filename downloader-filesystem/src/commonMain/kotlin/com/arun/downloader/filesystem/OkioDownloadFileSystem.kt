package com.arun.downloader.filesystem

import com.arun.downloader.core.model.ConflictStrategy
import com.arun.downloader.core.model.DownloadError
import com.arun.downloader.core.repository.DownloadFileSystem
import okio.FileSystem
import okio.Path
import okio.SYSTEM
import okio.Sink

class OkioDownloadFileSystem(
    private val fs: FileSystem = FileSystem.SYSTEM
) : DownloadFileSystem {

    override fun exists(path: Path): Boolean = fs.exists(path)

    override fun size(path: Path): Long {
        return try {
            fs.metadataOrNull(path)?.size ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    override fun createDirectories(dir: Path) {
        try {
            fs.createDirectories(dir)
        } catch (e: Exception) {
            throw DownloadError.NonRetryable.FileSystemPermissionDenied(dir.toString(), cause = e)
        }
    }

    override fun sink(path: Path, append: Boolean): Sink {
        path.parent?.let { createDirectories(it) }
        return try {
            if (append) fs.appendingSink(path) else fs.sink(path)
        } catch (e: Exception) {
            throw DownloadError.NonRetryable.FileSystemPermissionDenied(path.toString(), cause = e)
        }
    }

    override fun atomicMove(source: Path, destination: Path) {
        destination.parent?.let { createDirectories(it) }
        try {
            fs.atomicMove(source, destination)
        } catch (_: Exception) {
            // Fallback for filesystems where atomicMove across mount points isn't supported
            try {
                fs.copy(source, destination)
                fs.delete(source)
            } catch (fallbackEx: Exception) {
                throw DownloadError.NonRetryable.FileSystemPermissionDenied(destination.toString(), cause = fallbackEx)
            }
        }
    }

    override fun delete(path: Path) {
        try {
            if (fs.exists(path)) {
                fs.delete(path)
            }
        } catch (_: Exception) {
            // Best effort deletion
        }
    }

    override fun sanitizeFileName(candidateName: String): String {
        // Strip path traversal characters, drive roots, and illegal system tokens
        var sanitized = candidateName
            .replace("\\", "/")
            .substringAfterLast("/")
            .trim()

        if (sanitized.contains("..")) {
            sanitized = sanitized.replace("..", "")
        }

        // Strip illegal Windows / Unix characters: < > : " / \ | ? * and ASCII 0-31
        sanitized = sanitized.replace(Regex("[<>:\"/\\\\|?*\\x00-\\x1F]"), "_")

        if (sanitized.isBlank()) {
            sanitized = "download_${getMonotonicClockNs()}"
        }

        // Prevent Windows reserved device names
        val upper = sanitized.uppercase()
        val reserved = listOf("CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "LPT1", "LPT2")
        if (reserved.any { upper == it || upper.startsWith("$it.") }) {
            sanitized = "_$sanitized"
        }

        return sanitized
    }

    override fun resolveUniquePath(directory: Path, baseName: String, conflictStrategy: ConflictStrategy): Path {
        val sanitized = sanitizeFileName(baseName)
        val initialPath = directory / sanitized

        if (!exists(initialPath)) {
            return initialPath
        }

        return when (conflictStrategy) {
            ConflictStrategy.OVERWRITE -> initialPath
            ConflictStrategy.FAIL -> throw DownloadError.NonRetryable.FileAlreadyExists(initialPath.toString())
            ConflictStrategy.RENAME_NEW_INCREMENTAL -> {
                val dotIndex = sanitized.lastIndexOf('.')
                val name = if (dotIndex != -1) sanitized.substring(0, dotIndex) else sanitized
                val ext = if (dotIndex != -1) sanitized.substring(dotIndex) else ""

                var counter = 1
                var candidate: Path
                do {
                    candidate = directory / "$name ($counter)$ext"
                    counter++
                } while (exists(candidate))
                candidate
            }
        }
    }
}

internal expect fun getMonotonicClockNs(): Long