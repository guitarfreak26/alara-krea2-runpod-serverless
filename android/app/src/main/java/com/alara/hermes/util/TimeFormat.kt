package com.alara.hermes.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Relative timestamps in the style of messaging apps: 14:02, Yesterday, Tue, 3 Aug. */
fun formatRelativeTime(timestampMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    if (timestampMs <= 0) return ""
    val now = Calendar.getInstance().apply { timeInMillis = nowMs }
    val then = Calendar.getInstance().apply { timeInMillis = timestampMs }

    val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    if (sameDay) return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestampMs))

    val yesterday = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
    val isYesterday = yesterday.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        yesterday.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    if (isYesterday) return "Yesterday"

    val withinWeek = nowMs - timestampMs < 6L * 24 * 60 * 60 * 1000
    if (withinWeek) return SimpleDateFormat("EEE", Locale.getDefault()).format(Date(timestampMs))

    val sameYear = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
    val pattern = if (sameYear) "d MMM" else "d MMM yyyy"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(timestampMs))
}
