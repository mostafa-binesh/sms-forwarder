package com.mostafa.smsforwarder

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SmsForwarderApp : Application() {

    companion object {
        const val CHANNEL_ID = "sms_forwarder_channel"
        private const val TAG = "SmsForwarderApp"
        private var crashHandlerInstalled = false
        // Hardcoded fallback for crash reports
        private const val CRASH_SERVER = "https://financeapp.artapanel.xyz"
        private const val CRASH_API_KEY = "sms-forwarder-2026"
    }

    init {
        if (!crashHandlerInstalled) {
            crashHandlerInstalled = true
            try {
                installCrashHandler()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to install crash handler: ${e.message}", e)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        debugLog("=== SmsForwarderApp.onCreate ===")
        try {
            createNotificationChannel()
            debugLog("Notification channel OK")
        } catch (e: Exception) {
            debugLog("CRASH in createNotificationChannel: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Forwarding SMS notifications"
                enableVibration(false)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val crashInfo = buildCrashReport(thread, throwable)
                Log.e(TAG, "========== CRASH ==========\n$crashInfo\n===========================")
                saveToInternal(crashInfo)
                sendCrashToServer(crashInfo)
            } catch (e: Exception) {
                Log.e(TAG, "CRASH HANDLER FAILED: ${e.message}", e)
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun buildCrashReport(thread: Thread, throwable: Throwable): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        val now = dateFormat.format(Date())
        val sw = java.io.StringWriter()
        throwable.printStackTrace(java.io.PrintWriter(sw))

        return buildString {
            appendLine("=== SMS FORWARDER CRASH REPORT ===")
            appendLine("Time: $now")
            appendLine("App Version: ${getAppVersion()}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Thread: ${thread.name}")
            appendLine("Exception: ${throwable.javaClass.name}")
            appendLine("Message: ${throwable.message}")
            appendLine()
            appendLine("=== STACK TRACE ===")
            appendLine(sw.toString())
        }
    }

    private fun getAppVersion(): String {
        return try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            "${pInfo.versionName} (${pInfo.longVersionCode})"
        } catch (_: Exception) {
            "unknown"
        }
    }

    private fun saveToInternal(crashInfo: String) {
        try {
            val dir = File(filesDir, "crash_reports")
            dir.mkdirs()
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            File(dir, "crash_$ts.txt").writeText(crashInfo)
        } catch (_: Exception) {}
    }

    /**
     * Send crash to server. Uses hardcoded URL as fallback
     * (SharedPreferences may not have the URL on first launch).
     */
    private fun sendCrashToServer(crashInfo: String) {
        // Try SharedPreferences first, fallback to hardcoded
        var webhookUrl = ""
        try {
            val prefs = getSharedPreferences("sms_forwarder_prefs", Context.MODE_PRIVATE)
            webhookUrl = prefs.getString("webhook_url", "") ?: ""
        } catch (_: Exception) {}

        if (webhookUrl.isBlank()) {
            webhookUrl = CRASH_SERVER
        }

        try {
            val url = URL("${webhookUrl.trimEnd('/')}/api/crash")
            val connection = url.openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "text/plain; charset=UTF-8")
                setRequestProperty("X-API-Key", CRASH_API_KEY)
                connectTimeout = 10000
                readTimeout = 5000
                doOutput = true
            }
            connection.outputStream.use { it.write(crashInfo.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            Log.d(TAG, "Crash sent to server, response: $code")
            connection.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send crash to server: ${e.message}")
        }
    }

    fun debugLog(message: String) {
        try {
            val dir = File(filesDir, "debug")
            dir.mkdirs()
            val file = File(dir, "lifecycle.log")
            val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            FileWriter(file, true).use { it.write("[$ts] $message\n") }
            Log.d(TAG, "[$ts] $message")
        } catch (_: Exception) {}
    }
}
