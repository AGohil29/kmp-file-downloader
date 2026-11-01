package com.arun.downloader.storage.repository

/**
 * Compact, dependency-free encoding for persisting request headers in a single TEXT column.
 * Format: `k1=v1&k2=v2`, with `%`, `=`, `&` and control characters percent-encoded.
 */
internal object HeaderCodec {

    fun encode(headers: Map<String, String>): String? {
        if (headers.isEmpty()) return null
        return headers.entries.joinToString("&") { (k, v) -> "${escape(k)}=${escape(v)}" }
    }

    fun decode(raw: String?): Map<String, String> {
        if (raw.isNullOrEmpty()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (pair in raw.split('&')) {
            if (pair.isEmpty()) continue
            val sep = pair.indexOf('=')
            if (sep < 0) continue
            result[unescape(pair.substring(0, sep))] = unescape(pair.substring(sep + 1))
        }
        return result
    }

    private fun escape(value: String): String = buildString(value.length) {
        for (c in value) {
            if (c == '%' || c == '=' || c == '&' || c.code < 0x20 || c.code == 0x7F) {
                append('%')
                append(c.code.toString(16).padStart(2, '0').uppercase())
            } else {
                append(c)
            }
        }
    }

    private fun unescape(value: String): String {
        if ('%' !in value) return value
        val sb = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%' && i + 3 <= value.length) {
                val code = value.substring(i + 1, i + 3).toIntOrNull(16)
                if (code != null) {
                    sb.append(code.toChar())
                    i += 3
                    continue
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }
}


