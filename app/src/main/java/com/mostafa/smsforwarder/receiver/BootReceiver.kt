package com.mostafa.smsforwarder.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.mostafa.smsforwarder.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i(TAG, "Device booted — SMS Forwarder service is ready to receive messages.")

            // Log startup event to database
            val db = AppDatabase.getInstance(context)
            val dao = db.smsLogDao()

            scope.launch {
                val startupLog = com.mostafa.smsforwarder.db.SmsLog(
                    timestamp = System.currentTimeMillis(),
                    sender = "SYSTEM",
                    messageBody = "SMS Forwarder started after device boot",
                    forwardStatus = "SYSTEM",
                    errorMessage = null
                )
                dao.insert(startupLog)
                Log.d(TAG, "Startup event logged to database")
            }

            // Notify receiver that work is complete
            val pendingResult = goAsync()
            scope.launch {
                // Give coroutines a moment to finish
                kotlinx.coroutines.delay(1000)
                pendingResult.finish()
            }
        }
    }
}
