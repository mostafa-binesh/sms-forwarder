package com.mostafa.smsforwarder.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
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
     * Update an existing SMS log entry.
     */
    @Update
    suspend fun update(smsLog: SmsLog)

    /**
     * Get all SMS logs ordered by timestamp descending (newest first).
     * Returns a Flow for reactive UI updates.
     */
    @Query("SELECT * FROM sms_logs ORDER BY timestamp DESC")
    fun getAll(): Flow<List<SmsLog>>

    /**
     * Get all SMS logs as a one-shot list (non-reactive).
     */
    @Query("SELECT * FROM sms_logs ORDER BY timestamp DESC")
    suspend fun getAllOnce(): List<SmsLog>

    /**
     * Get all pending messages that are due for retry.
     */
    @Query("SELECT * FROM sms_logs WHERE forward_status = 'PENDING' AND next_retry_at <= :currentTime AND retry_count < max_retries ORDER BY timestamp ASC")
    suspend fun getPendingRetries(currentTime: Long): List<SmsLog>

    /**
     * Get the count of pending messages.
     */
    @Query("SELECT COUNT(*) FROM sms_logs WHERE forward_status = 'PENDING'")
    suspend fun getPendingCount(): Int

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

    /**
     * Mark a message as sent successfully.
     */
    @Query("UPDATE sms_logs SET forward_status = 'SUCCESS', error_message = NULL, last_attempt_at = :attemptTime WHERE id = :id")
    suspend fun markAsSent(id: Long, attemptTime: Long)

    /**
     * Mark a message as failed after all retries exhausted.
     */
    @Query("UPDATE sms_logs SET forward_status = 'FAILED', error_message = :error, last_attempt_at = :attemptTime WHERE id = :id")
    suspend fun markAsFailed(id: Long, error: String, attemptTime: Long)

    /**
     * Update retry count and schedule next retry with exponential backoff.
     */
    @Query("UPDATE sms_logs SET retry_count = :retryCount, next_retry_at = :nextRetryAt, last_attempt_at = :attemptTime, error_message = :error WHERE id = :id")
    suspend fun updateRetry(id: Long, retryCount: Int, nextRetryAt: Long, attemptTime: Long, error: String)

    /**
     * Clean up old pending messages that exceeded max retries.
     */
    @Query("DELETE FROM sms_logs WHERE forward_status = 'PENDING' AND retry_count >= max_retries")
    suspend fun cleanUpExhaustedRetries()

    /**
     * Reset a failed message back to PENDING for manual retry.
     */
    @Query("UPDATE sms_logs SET forward_status = 'PENDING', retry_count = 0, next_retry_at = :nextRetryAt, error_message = NULL WHERE id = :id AND forward_status = 'FAILED'")
    suspend fun resetForRetry(id: Long, nextRetryAt: Long)

    /**
     * Reset all failed messages back to PENDING for manual bulk retry.
     */
    @Query("UPDATE sms_logs SET forward_status = 'PENDING', retry_count = 0, next_retry_at = :nextRetryAt, error_message = NULL WHERE forward_status = 'FAILED'")
    suspend fun resetAllFailedForRetry(nextRetryAt: Long): Int

    /**
     * Get the count of all managed SMS messages.
     */
    @Query("SELECT COUNT(*) FROM sms_logs")
    suspend fun getAllCount(): Int
}
