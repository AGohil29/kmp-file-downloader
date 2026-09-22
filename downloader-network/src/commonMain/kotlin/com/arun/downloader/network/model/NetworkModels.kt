package com.arun.downloader.network.model

import io.ktor.utils.io.ByteReadChannel

data class NetworkRequest(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val rangeStart: Long? = null,
    val rangeEnd: Long? = null,
    val ifRange: String? = null
)

data class NetworkResponse(
    val statusCode: Int,
    val headers: Map<String, String>,
    val bodyChannel: ByteReadChannel,
    val contentLength: Long?,
    val isPartialContent: Boolean,
    val etag: String?,
    val lastModified: String?
) {
    fun getHeader(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}