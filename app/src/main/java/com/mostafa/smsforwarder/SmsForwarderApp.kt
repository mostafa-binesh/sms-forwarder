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
        createNotificationChannel()
        installCrashHandler()
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
                saveCrashToFile(crashInfo)
                sendCrashToServer(crashInfo)
            } catch (_: Exception) {
                // Don't crash the crash handler
            }
            // Let the default handler show the crash dialog
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun buildCrashReport(thread: Thread, throwable: Throwable): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        val now = dateFormat.format(Date())

        val sw = java.io.StringWriter()
        throwable.printStackTrace(java.io.PrintWriter(sw))
        val stackTrace = sw.toString()

        val deviceInfo = buildString {
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

        return deviceInfo
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
            Log.e(TAG, "Crash saved to: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save crash report", e)
        }
    }

    private fun sendCrashToServer(crashInfo: String) {
        try {
            // Read webhook URL from SharedPreferences (don't depend on SettingsManager)
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
            connection.outputStream.use { os ->
                os.write(crashInfo.toByteArray(Charsets.UTF_8))
            }
            connection.responseCode // trigger the request
            connection.disconnect()
            Log.d(TAG, "Crash report sent to server")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send crash report to server", e)
        }
    }

    companion object {
        const val CHANNEL_ID = "sms_forwarder_channel"
        private const val TAG = "SmsForwarderApp"
    }
}
