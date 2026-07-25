package com.mostafa.smsforwarder.util

import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-app error logger — captures error/warning logs for display in the Logs screen.
 * Thread-safe via CopyOnWriteArrayList. Keeps last 200 entries in memory.
 */
object AppLogger {

    private const val MAX_ENTRIES = 200

    data class ErrorEntry(
        val timestamp: Long,
        val tag: String,
        val level: Level,
        val message: String,
        val throwable: Throwable? = null
    )

    enum class Level { DEBUG, INFO, WARN, ERROR }

    private val entries = CopyOnWriteArrayList<ErrorEntry>()

    /** Log an error — also forwards to Android Log.e(). */
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        addEntry(Level.ERROR, tag, message, throwable)
    }

    /** Log a warning — also forwards to Android Log.w(). */
    fun w(tag: String, message: String, throwable: Throwable? = null) {
        Log.w(tag, message, throwable)
        addEntry(Level.WARN, tag, message, throwable)
    }

    /** Log info — also forwards to Android Log.i(). */
    fun i(tag: String, message: String) {
        Log.i(tag, message)
        addEntry(Level.INFO, tag, message, null)
    }

    /** Log debug — also forwards to Android Log.d(). */
    fun d(tag: String, message: String) {
        Log.d(tag, message)
        addEntry(Level.DEBUG, tag, message, null)
    }

    /** Get all stored error entries. */
    fun getErrors(): List<ErrorEntry> {
        return entries.toList()
    }

    /** Get errors/warnings only (filtered). */
    fun getWarningsAndErrors(): List<ErrorEntry> {
        return entries.filter { it.level == Level.ERROR || it.level == Level.WARN }
    }

    /** Clear all entries. */
    fun clear() {
        entries.clear()
    }

    private fun addEntry(level: Level, tag: String, message: String, throwable: Throwable?) {
        val entry = ErrorEntry(
            timestamp = System.currentTimeMillis(),
            tag = tag,
            level = level,
            message = if (throwable != null) "$message: ${throwable.message ?: throwable.javaClass.simpleName}" else message,
            throwable = throwable
        )
        entries.add(entry)

        // Trim if too many entries
        while (entries.size > MAX_ENTRIES) {
            entries.removeAt(0)
        }
    }
}
