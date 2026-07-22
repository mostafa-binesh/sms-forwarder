package com.mostafa.smsforwarder.sender

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Sends messages to Telegram via the Bot API.
 */
object TelegramSender {

    private const val TAG = "TelegramSender"
    private const val BASE_URL = "https://api.telegram.org"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    /**
     * Send a text message to a Telegram chat via the Bot API.
     *
     * @param botToken The Telegram bot token
     * @param chatId The target chat ID (user or group)
     * @param message The message text to send
     * @return Result<Unit> wrapping success or failure with exception
     */
    suspend fun sendMessage(
        botToken: String,
        chatId: String,
        message: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (botToken.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("Bot token is empty"))
            }
            if (chatId.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("Chat ID is empty"))
            }
            if (message.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("Message is empty"))
            }

            val url = "$BASE_URL/bot$botToken/sendMessage"

            val jsonBody = JSONObject().apply {
                put("chat_id", chatId)
                put("text", message)
                put("parse_mode", "Markdown")
            }

            val requestBody = jsonBody.toString().toRequestBody(JSON_MEDIA_TYPE)

            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .addHeader("Content-Type", "application/json")
                .build()

            Log.d(TAG, "Sending message to chat_id=$chatId")

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            response.close()

            val jsonResponse = JSONObject(responseBody)
            val ok = jsonResponse.optBoolean("ok", false)

            if (response.isSuccessful && ok) {
                Log.d(TAG, "Message sent successfully")
                Result.success(Unit)
            } else {
                val errorCode = jsonResponse.optInt("error_code", response.code)
                val description = jsonResponse.optString("description", "Unknown error")
                Log.e(TAG, "Telegram API error $errorCode: $description")
                Result.failure(TelegramApiException(errorCode, description))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send message to Telegram", e)
            Result.failure(e)
        }
    }

    /**
     * Test bot connectivity by calling getMe.
     */
    suspend fun testBotConnection(botToken: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (botToken.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("Bot token is empty"))
            }

            val url = "$BASE_URL/bot$botToken/getMe"

            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            response.close()

            val jsonResponse = JSONObject(responseBody)
            val ok = jsonResponse.optBoolean("ok", false)

            if (response.isSuccessful && ok) {
                val result = jsonResponse.optJSONObject("result")
                val botUsername = result?.optString("username", "Unknown") ?: "Unknown"
                val botName = result?.optString("first_name", "Unknown") ?: "Unknown"
                Result.success("@$botUsername ($botName)")
            } else {
                val description = jsonResponse.optString("description", "Unknown error")
                Result.failure(TelegramApiException(response.code, description))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to test bot connection", e)
            Result.failure(e)
        }
    }
}

/**
 * Custom exception for Telegram API errors.
 */
class TelegramApiException(
    val errorCode: Int,
    val description: String
) : Exception("Telegram API error $errorCode: $description")
