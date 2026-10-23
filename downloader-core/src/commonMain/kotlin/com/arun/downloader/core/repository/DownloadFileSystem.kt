package com.arun.downloader.core.repository

import com.arun.downloader.core.model.ConflictStrategy
import okio.Path
import okio.Sink

interface DownloadFileSystem {
    fun exists(path: Path): Boolean
    fun size(path: Path): Long
    fun createDirectories(dir: Path)
    fun sink(path: Path, append: Boolean = false): Sink
    fun atomicMove(source: Path, destination: Path)
    fun delete(path: Path)
    fun resolveUniquePath(directory: Path, baseName: String, conflictStrategy: ConflictStrategy): Path
    fun sanitizeFileName(candidateName: String): String
}