package com.mostafa.smsforwarder.sender

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sends bank SMS to webhook server for processing.
 */
object WebhookSender {

    private const val TAG = "WebhookSender"

    /**
     * Send SMS to webhook server.
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
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val url = URL(webhookUrl)
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
            Log.e(TAG, "Failed to send SMS to webhook", e)
            Result.failure(e)
        }
    }

    /**
     * Test webhook connection.
     */
    suspend fun testConnection(webhookUrl: String, apiKey: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val healthUrl = webhookUrl.replace("/sms", "/health")
            val url = URL(healthUrl)
            val connection = url.openConnection() as HttpURLConnection

            connection.apply {
                requestMethod = "GET"
                setRequestProperty("X-API-Key", apiKey)
                connectTimeout = 10_000
                readTimeout = 10_000
            }

            val responseCode = connection.responseCode
            val responseBody = connection.inputStream.bufferedReader().use { it.readText() }

            if (responseCode in 200..299) {
                Result.success("Connected! Server status: $responseBody")
            } else {
                Result.failure(Exception("Server error: $responseCode"))
            }
        } catch (e: Exception) {
            Result.failure(e)
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
