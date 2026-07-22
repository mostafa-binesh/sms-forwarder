package com.mostafa.smsforwarder.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room Entity representing a single SMS log entry.
 */
@Entity(tableName = "sms_logs")
data class SmsLog(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long,

    @ColumnInfo(name = "sender")
    val sender: String,

    @ColumnInfo(name = "message_body")
    val messageBody: String,

    @ColumnInfo(name = "forward_status")
    val forwardStatus: String, // "SUCCESS", "FAILED", "FILTERED", "SYSTEM"

    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null
)
