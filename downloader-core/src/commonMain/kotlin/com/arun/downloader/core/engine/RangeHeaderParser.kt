package com.arun.downloader.core.engine

data class ContentRange(
    val rangeStart: Long,
    val rangeEnd: Long,
    val totalBytes: Long?
) {
    companion object {
        private val CONTENT_RANGE_REGEX = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""", RegexOption.IGNORE_CASE)

        /**
         * Parses header values like:
         * "bytes 5000-9999/10000"
         * "bytes 5000-9999/"
         */
        fun parseOrNull(headerValue: String?): ContentRange? {
            if (headerValue.isNullOrBlank()) return null
            val match = CONTENT_RANGE_REGEX.matchEntire(headerValue.trim()) ?: return null

            val (startStr, endStr, totalStr) = match.destructured
            val start = startStr.toLongOrNull() ?: return null
            val end = endStr.toLongOrNull() ?: return null
            val total = if (totalStr == "*") null else totalStr.toLongOrNull()

            if (start > end || (total != null && end >= total)) return null

            return ContentRange(rangeStart = start, rangeEnd = end, totalBytes = total)
        }
    }
}