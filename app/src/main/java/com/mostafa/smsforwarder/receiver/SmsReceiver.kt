package com.mostafa.smsforwarder.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.mostafa.smsforwarder.db.AppDatabase
import com.mostafa.smsforwarder.db.SmsLog
import com.mostafa.smsforwarder.filter.BankFilter
import com.mostafa.smsforwarder.sender.WebhookSender
import com.mostafa.smsforwarder.util.SettingsManager
import com.mostafa.smsforwarder.util.SmsParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        val settings = SettingsManager(context)

        if (!settings.isEnabled) {
            Log.d(TAG, "SMS forwarding is disabled, skipping.")
            return
        }

        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        // Group messages by sender to handle multi-part SMS
        val groupedMessages = messages.groupBy { it.displayOriginatingAddress ?: it.originatingAddress ?: "Unknown" }

        for ((sender, parts) in groupedMessages) {
            val fullBody = parts.joinToString("") { it.messageBody ?: "" }

            processSms(context, sender, fullBody, settings)
        }
    }

    private fun processSms(context: Context, sender: String, body: String, settings: SettingsManager) {
        val filter = BankFilter(settings)
        val shouldForward = filter.shouldForward(sender, body)

        Log.d(TAG, "SMS from $sender, shouldForward=$shouldForward")

        val db = AppDatabase.getInstance(context)
        val dao = db.smsLogDao()

        scope.launch {
            if (shouldForward) {
                // Send to webhook server
                val webhookUrl = settings.webhookUrl
                val apiKey = settings.webhookApiKey

                if (webhookUrl.isBlank() || apiKey.isBlank()) {
                    Log.e(TAG, "Webhook not configured!")
                    val smsLog = SmsLog(
                        timestamp = System.currentTimeMillis(),
                        sender = sender,
                        messageBody = body,
                        forwardStatus = "FAILED",
                        errorMessage = "Webhook not configured"
                    )
                    dao.insert(smsLog)
                    return@launch
                }

                val result = WebhookSender.send(
                    webhookUrl = webhookUrl,
                    apiKey = apiKey,
                    sender = sender,
                    message = body
                )

                val smsLog = SmsLog(
                    timestamp = System.currentTimeMillis(),
                    sender = sender,
                    messageBody = body,
                    forwardStatus = if (result.isSuccess) "SUCCESS" else "FAILED",
                    errorMessage = result.exceptionOrNull()?.message
                )
                dao.insert(smsLog)

                if (result.isSuccess) {
                    Log.d(TAG, "Successfully forwarded SMS from $sender to webhook")
                } else {
                    Log.e(TAG, "Failed to forward SMS from $sender: ${result.exceptionOrNull()?.message}")
                }
            } else {
                // Log but don't forward
                val smsLog = SmsLog(
                    timestamp = System.currentTimeMillis(),
                    sender = sender,
                    messageBody = body,
                    forwardStatus = "FILTERED",
                    errorMessage = null
                )
                dao.insert(smsLog)
                Log.d(TAG, "SMS from $sender was filtered out")
            }
        }
    }
}
