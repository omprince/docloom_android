package com.docloom_android.core.ui

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * "3 minutes ago" style relative time; a port of timeAgo() in the website's
 * lib/client.ts. Uses SimpleDateFormat because java.time needs API 26 and
 * minSdk is 24.
 */
fun timeAgo(iso: String?, nowMillis: Long = System.currentTimeMillis()): String {
    val then = parseIso(iso) ?: return "never"
    val seconds = (nowMillis - then) / 1000.0
    if (seconds < 45) return "just now"
    var value = seconds / 60
    var unit = "minute"
    for ((size, name) in listOf(60 to "minute", 3600 to "hour", 86400 to "day", 604800 to "week")) {
        if (seconds >= size) {
            value = seconds / size
            unit = name
        }
    }
    val rounded = Math.round(value)
    return "$rounded $unit${if (rounded == 1L) "" else "s"} ago"
}

/** Parses RFC 3339 timestamps such as `2026-11-09T10:15:30.123456Z` or `...+05:30`. */
internal fun parseIso(iso: String?): Long? {
    if (iso.isNullOrBlank()) return null
    val match = ISO.matchEntire(iso.trim()) ?: return null
    val (base, fraction, zone) = match.destructured
    val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    val millis = format.parse(base, ParsePosition(0))?.time ?: return null
    val fracMillis = fraction.trimStart('.').padEnd(3, '0').take(3).toLongOrNull() ?: 0
    val offsetMillis = if (zone == "Z" || zone == "z" || zone.isEmpty()) 0L else {
        val sign = if (zone[0] == '-') -1 else 1
        val (h, m) = zone.substring(1).split(":").map { it.toLong() }
        sign * (h * 3600 + m * 60) * 1000
    }
    return millis + fracMillis - offsetMillis
}

private val ISO = Regex("""(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(\.\d+)?(Z|z|[+-]\d{2}:\d{2})?""")
