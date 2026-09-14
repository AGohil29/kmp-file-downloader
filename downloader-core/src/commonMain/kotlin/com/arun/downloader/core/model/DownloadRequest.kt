package com.arun.downloader.core.model

import okio.Path

data class DownloadRequest(
    val url: String,
    val destinationDirectory: Path,
    val fileName: String? = null,
    val priority: DownloadPriority = DownloadPriority.NORMAL,
    val headers: Map<String, String> = emptyMap(),
    val conflictStrategy: ConflictStrategy = ConflictStrategy.RENAME_NEW_INCREMENTAL,
    val expectedChecksum: ChecksumValidation? = null
) {
    init {
        require(url.isNotBlank()) { "URL cannot be blank." }
        require(url.startsWith("http://") || url.startsWith("https://")) {
            "URL must use HTTP or HTTPS protocols: $url"
        }
        fileName?.let {
            require(!it.contains("/") && !it.contains("\\") && !it.contains("..")) {
                "Filename contains illegal path separators: $it"
            }
        }
    }
}

data class ChecksumValidation(
    val algorithm: Algorithm,
    val expectedHash: String
) {
    enum class Algorithm { MD5, SHA1, SHA256 }
}