package com.mostafa.smsforwarder.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room database for SMS Forwarder.
 * Version 2 — Added retry fields for persistent message queue.
 */
@Database(
    entities = [SmsLog::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun smsLogDao(): SmsLogDao

    companion object {
        private const val DATABASE_NAME = "sms_forwarder.db"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Migration from version 1 to 2 — adds retry fields.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add new columns for retry functionality
                database.execSQL("ALTER TABLE sms_logs ADD COLUMN retry_count INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE sms_logs ADD COLUMN max_retries INTEGER NOT NULL DEFAULT 10")
                database.execSQL("ALTER TABLE sms_logs ADD COLUMN next_retry_at INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE sms_logs ADD COLUMN last_attempt_at INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Get the singleton instance of the database.
         * Thread-safe double-checked locking.
         */
        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context): AppDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DATABASE_NAME
            )
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}
