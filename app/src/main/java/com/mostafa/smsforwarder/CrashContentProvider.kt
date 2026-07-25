package com.mostafa.smsforwarder

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CrashContentProvider runs BEFORE Application.onCreate().
 * This is the earliest code that executes in an Android app.
 * It installs the crash handler so we can catch ANY crash.
 */
class CrashContentProvider : ContentProvider() {

    companion object {
        private const val TAG = "CrashProvider"
        private const val CRASH_SERVER = "https://financeapp.artapanel.xyz"
        private const val CRASH_API_KEY = "sms-forwarder-2026"
        private var installed = false
    }

    override fun onCreate(): Boolean {
        if (!installed) {
            installed = true
            installCrashHandler()
            Log.d(TAG, "Crash handler installed via ContentProvider")
        }
        return true
    }

    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val crashInfo = buildCrashReport(thread, throwable)

                // Log to logcat (always works)
                Log.e(TAG, "========== CRASH ==========")
                Log.e(TAG, crashInfo)
                Log.e(TAG, "===========================")

                // Save to internal file
                saveToFile(crashInfo)

                // Send to server (fire and forget, 10s timeout)
                sendToServer(crashInfo)
            } catch (e: Exception) {
                Log.e(TAG, "Crash handler inner error: ${e.message}", e)
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun buildCrashReport(thread: Thread, throwable: Throwable): String {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val sw = java.io.StringWriter()
        throwable.printStackTrace(java.io.PrintWriter(sw))
        return """
            |=== SMS FORWARDER CRASH ===
            |Time: $ts
            |Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}
            |Android: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})
            |Thread: ${thread.name}
            |Exception: ${throwable.javaClass.name}
            |Message: ${throwable.message}
            |
            |=== STACK TRACE ===
            |${sw.toString()}
        """.trimMargin()
    }

    private fun saveToFile(crashInfo: String) {
        try {
            val ctx = context ?: return
            val dir = File(ctx.filesDir, "crashes")
            dir.mkdirs()
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            File(dir, "crash_$ts.txt").writeText(crashInfo)
            Log.d(TAG, "Crash saved to file")
        } catch (e: Exception) {
            Log.e(TAG, "File save failed: ${e.message}")
        }
    }

    private fun sendToServer(crashInfo: String) {
        try {
            val ctx = context ?: return
            // Try prefs first, fallback to hardcoded
            var serverUrl = ""
            try {
                val prefs = ctx.getSharedPreferences("sms_forwarder_prefs", android.content.Context.MODE_PRIVATE)
                serverUrl = prefs.getString("webhook_url", "") ?: ""
            } catch (_: Exception) {}

            if (serverUrl.isBlank()) serverUrl = CRASH_SERVER

            Log.d(TAG, "Sending crash to: $serverUrl/api/crash")

            val url = URL("${serverUrl.trimEnd('/')}/api/crash")
            val conn = url.openConnection() as HttpURLConnection
            conn.apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "text/plain; charset=UTF-8")
                setRequestProperty("X-API-Key", CRASH_API_KEY)
                connectTimeout = 10000
                readTimeout = 10000
                doOutput = true
            }
            conn.outputStream.use { it.write(crashInfo.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            Log.d(TAG, "Crash sent! Response: $code")
            conn.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Server send failed: ${e.message}")
        }
    }

    // Required ContentProvider stubs — unused
    override fun query(uri: Uri, proj: Array<String>?, sel: String?, args: Array<String>?, sort: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, sel: String?, args: Array<String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, sel: String?, args: Array<String>?) = 0
}
