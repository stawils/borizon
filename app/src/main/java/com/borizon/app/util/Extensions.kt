package com.borizon.app.util

import android.util.Log
import com.borizon.app.BuildConfig

fun debugLog(tag: String, msg: String) {
    Log.i(tag, msg)
}

fun debugLog(tag: String, msg: String, tr: Throwable) {
    Log.i(tag, msg, tr)
}

/**
 * General-purpose Kotlin extensions for the Borizon app.
 */

/**
 * Truncates a string to [maxChars] characters, appending "..." if truncated.
 */
fun String.truncate(maxChars: Int): String {
    return if (length <= maxChars) this else take(maxChars) + "..."
}

private val dateFormat = java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.US)
private val timeFormat = java.text.SimpleDateFormat("h:mm a", java.util.Locale.US)

fun Long.toDateString(): String = synchronized(dateFormat) { dateFormat.format(java.util.Date(this)) }

fun Long.toTimeString(): String = synchronized(timeFormat) { timeFormat.format(java.util.Date(this)) }

/**
 * Escapes SQLite LIKE wildcard characters (% _ \) so the string matches literally.
 */
fun String.escapeLike(): String =
    replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
