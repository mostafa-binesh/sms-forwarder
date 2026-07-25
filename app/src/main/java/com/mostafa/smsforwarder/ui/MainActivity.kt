package com.mostafa.smsforwarder.ui

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this).apply {
            text = "✅ App works! Crash is fixed.\nVersion: debug"
            textSize = 24f
            setPadding(48, 48, 48, 48)
        }
        setContentView(tv)
    }
}
