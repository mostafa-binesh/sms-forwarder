package com.mostafa.smsforwarder.sender

import android.content.Context
import android.util.Log
import com.mostafa.smsforwarder.db.AppDatabase
import com.mostafa.smsforwarder.db.SmsLog
import com.mostafa.smsforwarder.util.AppLogger
import com.mostafa.smsforwarder.util.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sends bank SMS to webhook server with retry logic.
 */
object WebhookSender {

    private const val TAG = "WebhookSender"
    private const val BASE_BACKOFF_MS = 5000L // 5 seconds
    private const val MAX_BACKOFF_MS = 300000L // 5 minutes
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Send SMS to webhook server with immediate attempt.
     * @param webhookUrl The webhook endpoint URL
     * @param apiKey API key for authentication
     * @param sender SMS sender number
     * @param message SMS body
     * @return Result with success status
     */
    suspend fun send(
        webhookUrl: String,
        apiKey: String,
        sender: String,
        message: String
    ): Result<Unit> {
        // Normalize URL: auto-add /api/sms if missing
        val normalizedUrl = normalizeSmsUrl(webhookUrl)
        return try {
            val url = URL(normalizedUrl)
            val connection = url.openConnection() as HttpURLConnection

            connection.apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-API-Key", apiKey)
                connectTimeout = 10_000
                readTimeout = 10_000
                doOutput = true
            }

            // Build JSON body
            val jsonBody = buildJsonObject {
                put("sender", sender)
                put("message", message)
            }

            // Write to output stream
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(jsonBody)
                writer.flush()
            }

            val responseCode = connection.responseCode
            val responseBody = connection.inputStream.bufferedReader().use { it.readText() }

            if (responseCode in 200..299) {
                Log.d(TAG, "Successfully sent SMS to webhook: $responseCode")
                Result.success(Unit)
            } else {
                Log.e(TAG, "Webhook error: $responseCode - $responseBody")
                Result.failure(Exception("Webhook error: $responseCode"))
            }
        } catch (e: Exception) {
            val detail = when (e) {
                is java.net.SocketTimeoutException -> "Timeout after 10s — سرور پاسخ نمی‌دهد"
                is java.net.ConnectException -> "اتصال برقرار نشد: ${e.message}"
                is java.net.UnknownHostException -> "آدرس DNS یافت نشد"
                is javax.net.ssl.SSLException -> "خطای SSL: ${e.message}"
                else -> "${e.javaClass.simpleName}: ${e.message}"
            }
            AppLogger.e(TAG, "Failed to send SMS to webhook ($normalizedUrl): $detail", e)
            Result.failure(e)
        }
    }

    /**
     * Send SMS with retry logic - saves to DB first, then retries on failure.
     */
    suspend fun sendWithRetry(
        context: Context,
        webhookUrl: String,
        apiKey: String,
        sender: String,
        message: String
    ) {
        val db = AppDatabase.getInstance(context)
        val dao = db.smsLogDao()
        val settings = SettingsManager(context)

        // Save to database first with PENDING status
        val smsLog = SmsLog(
            timestamp = System.currentTimeMillis(),
            sender = sender,
            messageBody = message,
            forwardStatus = "PENDING",
            retryCount = 0,
            maxRetries = settings.maxRetries,
            nextRetryAt = System.currentTimeMillis(),
            lastAttemptAt = 0
        )
        val id = dao.insert(smsLog)
        Log.d(TAG, "SMS saved to queue with ID: $id")

        // Start retry worker
        startRetryWorker(context)
    }

    /**
     * Start the retry worker to process pending messages.
     */
    fun startRetryWorker(context: Context) {
        scope.launch {
            processPendingRetries(context)
        }
    }

    /**
     * Process all pending retries with exponential backoff.
     */
    private suspend fun processPendingRetries(context: Context) {
        val db = AppDatabase.getInstance(context)
        val dao = db.smsLogDao()
        val settings = SettingsManager(context)
        val webhookUrl = settings.webhookUrl
        val apiKey = settings.webhookApiKey

        if (webhookUrl.isBlank() || apiKey.isBlank()) {
            Log.e(TAG, "Webhook not configured, skipping retries")
            return
        }

        while (true) {
            try {
                val currentTime = System.currentTimeMillis()
                val pendingMessages = dao.getPendingRetries(currentTime)

                if (pendingMessages.isEmpty()) {
                    Log.d(TAG, "No pending messages, sleeping for 30 seconds")
                    delay(30_000) // Sleep 30 seconds before checking again
                    continue
                }

                Log.d(TAG, "Processing ${pendingMessages.size} pending messages")

                for (smsLog in pendingMessages) {
                    try {
                        val result = send(
                            webhookUrl = webhookUrl,
                            apiKey = apiKey,
                            sender = smsLog.sender,
                            message = smsLog.messageBody
                        )

                        val attemptTime = System.currentTimeMillis()

                        if (result.isSuccess) {
                            dao.markAsSent(smsLog.id, attemptTime)
                            Log.d(TAG, "Message ${smsLog.id} sent successfully")
                        } else {
                            val newRetryCount = smsLog.retryCount + 1
                            val backoffMs = calculateBackoff(newRetryCount)
                            val nextRetryAt = attemptTime + backoffMs

                            if (newRetryCount >= smsLog.maxRetries) {
                                dao.markAsFailed(
                                    smsLog.id,
                                    "Max retries ($newRetryCount) exhausted: ${result.exceptionOrNull()?.message}",
                                    attemptTime
                                )
                                Log.e(TAG, "Message ${smsLog.id} failed after $newRetryCount retries")
                                AppLogger.e(TAG, "SMS #${smsLog.id} failed after $newRetryCount retries: ${result.exceptionOrNull()?.message}")
                            } else {
                                dao.updateRetry(
                                    smsLog.id,
                                    newRetryCount,
                                    nextRetryAt,
                                    attemptTime,
                                    result.exceptionOrNull()?.message ?: "Unknown error"
                                )
                                Log.d(TAG, "Message ${smsLog.id} will retry at $nextRetryAt (attempt $newRetryCount/${smsLog.maxRetries})")
                            }
                        }
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "Error processing message ${smsLog.id}", e)
                    }
                }

                // Clean up exhausted retries
                dao.cleanUpExhaustedRetries()

                // Wait before next batch
                delay(30_000)

            } catch (e: Exception) {
                Log.e(TAG, "Error in retry loop", e)
                delay(60_000) // Wait longer on error
            }
        }
    }

    /**
     * Calculate exponential backoff with jitter.
     * Attempt 1: 5s, 2: 10s, 3: 20s, 4: 40s, 5: 80s, 6: 160s, 7: 320s (capped at 5 min)
     */
    private fun calculateBackoff(retryCount: Int): Long {
        val backoff = BASE_BACKOFF_MS * (1L shl (retryCount - 1)) // 2^(n-1) * base
        val jitter = (Math.random() * 1000).toLong() // Add random jitter
        return minOf(backoff + jitter, MAX_BACKOFF_MS)
    }

    /**
     * Test webhook connection — validates server is reachable and API key is accepted.
     */
    suspend fun testConnection(webhookUrl: String, apiKey: String): Result<String> {
        return try {
            // Derive base URL and health endpoint from webhook URL
            val baseUrl = webhookUrl.trimEnd('/')
            val healthUrl = when {
                // /api/sms or /api/sms/ → /api/health
                baseUrl.endsWith("/api/sms") -> baseUrl.dropLast(4) + "/health"
                baseUrl.endsWith("/api/sms/") -> baseUrl.dropLast(5) + "/health"
                // /sms → /api/health (auto-add /api/ prefix)
                baseUrl.endsWith("/sms") -> baseUrl.dropLast(4) + "api/health"
                baseUrl.endsWith("/sms/") -> baseUrl.dropLast(5) + "api/health"
                // /health → same
                baseUrl.endsWith("/health") -> baseUrl
                // base URL → /api/health
                else -> "$baseUrl/api/health"
            }

            Log.d(TAG, "Testing connection to: $healthUrl")
            val url = URL(healthUrl)
            val connection = url.openConnection() as HttpURLConnection

            connection.apply {
                requestMethod = "GET"
                setRequestProperty("X-API-Key", apiKey)
                connectTimeout = 10_000
                readTimeout = 10_000
            }

            val responseCode = connection.responseCode
            val body = if (responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "No response body"
            }

            connection.disconnect()

            if (responseCode in 200..299) {
                Log.d(TAG, "Connection test OK: $responseCode")
                AppLogger.i(TAG, "Connection test OK: $responseCode — $healthUrl")
                Result.success("✅ اتصال موفق! سرور فعال است.\nوضعیت: $body")
            } else {
                val errorMsg = "خطای سرور: $responseCode — $body"
                AppLogger.e(TAG, errorMsg)
                Result.failure(Exception(errorMsg))
            }
        } catch (e: java.net.UnknownHostException) {
            AppLogger.e(TAG, "DNS resolution failed for webhook URL", e)
            Result.failure(Exception("❌ آدرس سرور یافت نشد. URL را بررسی کنید."))
        } catch (e: java.net.ConnectException) {
            AppLogger.e(TAG, "Connection refused: $webhookUrl", e)
            Result.failure(Exception("❌ اتصال برقرار نشد. سرور در دسترس نیست."))
        } catch (e: java.net.SocketTimeoutException) {
            AppLogger.e(TAG, "Connection timed out: $webhookUrl", e)
            Result.failure(Exception("❌ اتصال بیش از حد طول کشید (timeout)."))
        } catch (e: java.net.MalformedURLException) {
            AppLogger.e(TAG, "Invalid URL: $webhookUrl", e)
            Result.failure(Exception("❌ آدرس URL نامعتبر است."))
        } catch (e: Exception) {
            AppLogger.e(TAG, "Unexpected error during connection test", e)
            Result.failure(Exception("❌ خطا: ${e.message ?: e.javaClass.simpleName}"))
        }
    }

    /**
     * Normalize webhook URL to always point to /api/sms.
     * Handles: base URL, /sms, /api/sms, /api/data — all map to /api/sms.
     */
    private fun normalizeSmsUrl(webhookUrl: String): String {
        val base = webhookUrl.trimEnd('/')
        return when {
            base.endsWith("/api/sms") -> base
            base.endsWith("/api/sms/") -> base.trimEnd('/')
            base.endsWith("/api/data") -> base.dropLast(4) + "sms"
            base.endsWith("/sms") -> {
                val prefix = base.dropLast(4)
                if (prefix.endsWith("/api/")) base
                else "${prefix}api/sms"
            }
            base.endsWith("/sms/") -> normalizeSmsUrl(base.trimEnd('/'))
            else -> "$base/api/sms"
        }
    }

    /**
     * Simple JSON builder (no external dependencies).
     */
    private fun buildJsonObject(block: JsonObjectBuilder.() -> Unit): String {
        val builder = JsonObjectBuilder()
        builder.block()
        return builder.build()
    }

    private class JsonObjectBuilder {
        private val entries = mutableListOf<Pair<String, String>>()

        fun put(key: String, value: String) {
            entries.add(key to "\"$value\"")
        }

        fun put(key: String, value: Number) {
            entries.add(key to "$value")
        }

        fun build(): String {
            return entries.joinToString(",") { (key, value) ->
                "\"$key\":$value"
            }.let { "{$it}" }
        }
    }
}
