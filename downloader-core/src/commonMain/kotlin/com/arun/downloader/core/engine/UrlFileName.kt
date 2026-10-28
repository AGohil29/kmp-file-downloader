package com.arun.downloader.core.engine

/**
 * Derives a candidate file name from a download URL: drops the query and fragment, takes the last
 * path segment and percent-decodes it. Returns an empty string when no name can be derived, so
 * callers can pass the result to `DownloadFileSystem.sanitizeFileName` (which generates a fallback).
 */
fun fileNameFromUrl(url: String): String {
    val withoutQuery = url.substringBefore('#').substringBefore('?')
    // A bare authority such as "https://host" (or "https://host/") has no file name.
    val afterScheme = withoutQuery.substringAfter("://", "")
    val path = if (afterScheme.isEmpty()) withoutQuery else afterScheme.substringAfter('/', "")
    if (path.isEmpty() || path.endsWith('/')) return ""
    return percentDecode(path.substringAfterLast('/'))
}

private fun percentDecode(value: String): String {
    if ('%' !in value) return value
    val out = ByteArray(value.length * 4)
    var n = 0
    var i = 0
    while (i < value.length) {
        val c = value[i]
        if (c == '%' && i + 2 < value.length && hexValue(value[i + 1]) >= 0 && hexValue(value[i + 2]) >= 0) {
            out[n++] = ((hexValue(value[i + 1]) shl 4) or hexValue(value[i + 2])).toByte()
            i += 3
        } else {
            val bytes = c.toString().encodeToByteArray()
            // Surrogate pairs: keep both halves together so they encode as one code point.
            if (c.isHighSurrogate() && i + 1 < value.length) {
                val pair = value.substring(i, i + 2).encodeToByteArray()
                pair.copyInto(out, n)
                n += pair.size
                i += 2
            } else {
                bytes.copyInto(out, n)
                n += bytes.size
                i++
            }
        }
    }
    return out.decodeToString(0, n)
}

private fun hexValue(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    in 'A'..'F' -> c - 'A' + 10
    else -> -1
}



