package com.mostafa.smsforwarder.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.mostafa.smsforwarder.db.AppDatabase
import com.mostafa.smsforwarder.db.SmsLog
import com.mostafa.smsforwarder.sender.WebhookSender
import com.mostafa.smsforwarder.util.SettingsManager
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
        Log.d(TAG, "Managing SMS from $sender")

        val db = AppDatabase.getInstance(context)
        val dao = db.smsLogDao()

        scope.launch {
            val now = System.currentTimeMillis()
            val isConfigured = settings.webhookUrl.isNotBlank() && settings.webhookApiKey.isNotBlank()

            val smsLog = SmsLog(
                timestamp = now,
                sender = sender,
                messageBody = body,
                forwardStatus = if (isConfigured) "PENDING" else "FAILED",
                errorMessage = if (isConfigured) null else "Webhook not configured",
                retryCount = 0,
                maxRetries = settings.maxRetries,
                nextRetryAt = now,
                lastAttemptAt = 0
            )

            val id = dao.insert(smsLog)
            Log.d(TAG, "SMS saved with ID: $id, status=${smsLog.forwardStatus}")

            if (isConfigured) {
                WebhookSender.startRetryWorker(context)
            }
        }
    }
}
