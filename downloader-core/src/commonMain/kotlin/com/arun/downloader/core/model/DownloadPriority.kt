package com.arun.downloader.core.model

/**
 * Priority definitions governing scheduler queue ordering.
 * Higher weights are prioritized by the dispatcher engine.
 */
enum class DownloadPriority(val weight: Int) {
    LOW(10),
    NORMAL(20),
    HIGH(30);

    companion object {
        fun fromWeight(weight: Int): DownloadPriority =
            entries.firstOrNull { it.weight == weight } ?: NORMAL
    }
}