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

    override fun onCreate() {
        super.onCreate()
        debugLog("=== SmsForwarderApp.onCreate START ===")
        debugLog("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")

        try {
            installCrashHandler()
            debugLog("Crash handler installed OK")
        } catch (e: Exception) {
            debugLog("CRASH in installCrashHandler: ${e.message}")
        }

        try {
            createNotificationChannel()
            debugLog("Notification channel OK")
        } catch (e: Exception) {
            debugLog("CRASH in createNotificationChannel: ${e.message}")
        }

        debugLog("=== SmsForwarderApp.onCreate END ===")
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
                debugLog("CRASH CAUGHT!\n$crashInfo")
                saveCrashToFile(crashInfo)
                sendCrashToServer(crashInfo)
            } catch (e: Exception) {
                debugLog("CRASH HANDLER FAILED: ${e.message}\n${e.stackTraceToString()}")
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun buildCrashReport(thread: Thread, throwable: Throwable): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        val now = dateFormat.format(Date())

        val sw = java.io.StringWriter()
        throwable.printStackTrace(java.io.PrintWriter(sw))
        val stackTrace = sw.toString()

        return buildString {
            appendLine("=== CRASH REPORT ===")
            appendLine("Time: $now")
            appendLine("App Version: ${getAppVersion()}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Thread: ${thread.name}")
            appendLine("Exception: ${throwable.javaClass.name}")
            appendLine("Message: ${throwable.message}")
            appendLine()
            appendLine("=== STACK TRACE ===")
            appendLine(stackTrace)
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

    private fun saveCrashToFile(crashInfo: String) {
        try {
            val dir = File(filesDir, "crash_reports")
            dir.mkdirs()
            val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            val fileName = "crash_${dateFormat.format(Date())}.txt"
            val file = File(dir, fileName)
            FileWriter(file).use { it.write(crashInfo) }
        } catch (_: Exception) {}
    }

    private fun sendCrashToServer(crashInfo: String) {
        try {
            val prefs = getSharedPreferences("sms_forwarder_prefs", Context.MODE_PRIVATE)
            val webhookUrl = prefs.getString("webhook_url", "") ?: ""
            if (webhookUrl.isBlank()) return

            val url = URL("${webhookUrl.trimEnd('/')}/api/crash")
            val connection = url.openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "text/plain; charset=UTF-8")
                connectTimeout = 5000
                readTimeout = 5000
                doOutput = true
            }
            connection.outputStream.use { it.write(crashInfo.toByteArray(Charsets.UTF_8)) }
            connection.responseCode
            connection.disconnect()
        } catch (_: Exception) {}
    }

    companion object {
        const val CHANNEL_ID = "sms_forwarder_channel"
        private const val TAG = "SmsForwarderApp"
    }

    /**
     * Write to debug log file in app's internal storage.
     * Each line is timestamped. Use this to trace lifecycle.
     */
    fun debugLog(message: String) {
        try {
            val dir = File(filesDir, "debug")
            dir.mkdirs()
            val file = File(dir, "lifecycle.log")
            val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            FileWriter(file, true).use { writer ->
                writer.write("[$ts] $message\n")
            }
            Log.d(TAG, "[$ts] $message")
        } catch (_: Exception) {}
    }
}
