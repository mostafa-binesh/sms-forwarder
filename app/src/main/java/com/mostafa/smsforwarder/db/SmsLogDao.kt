package com.mostafa.smsforwarder.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for the sms_logs table.
 */
@Dao
interface SmsLogDao {

    /**
     * Insert a new SMS log entry. Replaces on conflict by primary key.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(smsLog: SmsLog): Long

    /**
     * Get all SMS logs ordered by timestamp descending (newest first).
     * Returns a Flow for reactive UI updates.
     */
    @Query("SELECT * FROM sms_logs ORDER BY timestamp DESC")
    fun getAll(): Flow<List<SmsLog>>

    /**
     * Get the count of failed forward attempts.
     */
    @Query("SELECT COUNT(*) FROM sms_logs WHERE forward_status = 'FAILED'")
    suspend fun getFailedCount(): Int

    /**
     * Get the count of successful forward attempts.
     */
    @Query("SELECT COUNT(*) FROM sms_logs WHERE forward_status = 'SUCCESS'")
    suspend fun getSuccessCount(): Int

    /**
     * Get the count of filtered (not forwarded) SMS.
     */
    @Query("SELECT COUNT(*) FROM sms_logs WHERE forward_status = 'FILTERED'")
    suspend fun getFilteredCount(): Int

    /**
     * Delete all SMS log entries older than the given timestamp.
     */
    @Query("DELETE FROM sms_logs WHERE timestamp < :timestamp")
    suspend fun deleteOlderThan(timestamp: Long)

    /**
     * Get a specific SMS log by ID.
     */
    @Query("SELECT * FROM sms_logs WHERE id = :id")
    suspend fun getById(id: Long): SmsLog?

    /**
     * Delete all SMS logs.
     */
    @Query("DELETE FROM sms_logs")
    suspend fun deleteAll()

    /**
     * Get the most recent N log entries.
     */
    @Query("SELECT * FROM sms_logs ORDER BY timestamp DESC LIMIT :limit")
    fun getRecent(limit: Int): Flow<List<SmsLog>>
}
