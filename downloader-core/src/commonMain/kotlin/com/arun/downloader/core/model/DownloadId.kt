package com.arun.downloader.core.model

import kotlin.jvm.JvmInline

@JvmInline
value class DownloadId(val raw: String) {
    init {
        require(raw.isNotBlank()) { "DownloadId must not be blank." }
    }

    override fun toString(): String = raw

    companion object {
        fun generate(): DownloadId {
            val timestamp = getMonotonicTimestampNs()
            val randomSuffix = (1..6)
                .map { (('a'..'z') + ('0'..'9')).random() }
                .joinToString("")
            return DownloadId("dl_${timestamp}_$randomSuffix")
        }
    }
}

internal expect fun getMonotonicTimestampNs(): Long