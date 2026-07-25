package com.mostafa.smsforwarder

import android.app.Application
import android.util.Log

class SmsForwarderApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.d("SmsForwarderApp", "Application.onCreate OK")
    }
}
