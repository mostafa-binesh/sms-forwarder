package com.mostafa.smsforwarder

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Environment
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
    }

    init {
        if (!crashHandlerInstalled) {
            crashHandlerInstalled = true
            try {
                installCrashHandler()
            } catch (e: Exception) {
                // Last resort: log to logcat
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
                
                // 1. Save to internal storage
                saveToInternal(crashInfo)
                
                // 2. Save to Downloads (persists after uninstall!)
                saveToDownloads(crashInfo)
                
                // 3. Try to send to server
                sendCrashToServer(crashInfo)
                
                // 4. Log everything to logcat
                Log.e(TAG, "========== CRASH ==========")
                Log.e(TAG, crashInfo)
                Log.e(TAG, "===========================")
            } catch (e: Exception) {
                Log.e(TAG, "CRASH HANDLER ALSO FAILED: ${e.message}", e)
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

    private fun saveToInternal(crashInfo: String) {
        try {
            val dir = File(filesDir, "crash_reports")
            dir.mkdirs()
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            File(dir, "crash_$ts.txt").writeText(crashInfo)
        } catch (_: Exception) {}
    }

    private fun saveToDownloads(crashInfo: String) {
        try {
            // Save to Downloads folder — persists after uninstall!
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            downloads.mkdirs()
            val crashDir = File(downloads, "SmsForwarderCrashes")
            crashDir.mkdirs()
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(crashDir, "crash_$ts.txt")
            file.writeText(crashInfo)
            Log.d(TAG, "Crash saved to: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save to Downloads: ${e.message}")
        }
    }

    private fun sendCrashToServer(crashInfo: String) {
        try {
            val prefs = getSharedPreferences("sms_forwarder_prefs", Context.MODE_PRIVATE)
            val webhookUrl = prefs.getString("webhook_url", "") ?: ""
            if (webhookUrl.isBlank()) {
                Log.w(TAG, "No webhook URL saved, skipping server send")
                return
            }
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
            Log.d(TAG, "Crash sent to server")
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
