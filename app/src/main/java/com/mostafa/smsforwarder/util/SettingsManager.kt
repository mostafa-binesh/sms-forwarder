package com.mostafa.smsforwarder.util

import android.content.Context
import android.content.SharedPreferences
import com.mostafa.smsforwarder.filter.FilterMode

/**
 * SharedPreferences wrapper for all app settings.
 * Provides typed accessors for Telegram config, filter settings, and keywords.
 */
class SettingsManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "sms_forwarder_prefs"

        // Telegram settings
        private const val KEY_BOT_TOKEN = "bot_token"
        private const val KEY_CHAT_ID = "chat_id"

        // General settings
        private const val KEY_IS_ENABLED = "is_enabled"

        // Filter settings
        private const val KEY_FILTER_MODE = "filter_mode"
        private const val KEY_SENDER_NUMBERS = "sender_numbers"
        private const val KEY_KEYWORDS = "keywords"
        private const val KEY_NEGATIVE_KEYWORDS = "negative_keywords"

        // Separator for list storage
        private const val LIST_SEPARATOR = "|||"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Telegram Settings ──────────────────────────────────────────────

    var botToken: String
        get() = prefs.getString(KEY_BOT_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_BOT_TOKEN, value.trim()).apply()

    var chatId: String
        get() = prefs.getString(KEY_CHAT_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CHAT_ID, value.trim()).apply()

    // ── General Settings ───────────────────────────────────────────────

    var isEnabled: Boolean
        get() = prefs.getBoolean(KEY_IS_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_ENABLED, value).apply()

    // ── Filter Settings ────────────────────────────────────────────────

    var filterMode: FilterMode
        get() {
            val name = prefs.getString(KEY_FILTER_MODE, FilterMode.ALL.name) ?: FilterMode.ALL.name
            return try {
                FilterMode.valueOf(name)
            } catch (e: IllegalArgumentException) {
                FilterMode.ALL
            }
        }
        set(value) = prefs.edit().putString(KEY_FILTER_MODE, value.name).apply()

    var senderNumbers: List<String>
        get() = getStringList(KEY_SENDER_NUMBERS)
        set(value) = setStringList(KEY_SENDER_NUMBERS, value)

    var keywords: List<String>
        get() = getStringList(KEY_KEYWORDS)
        set(value) = setStringList(KEY_KEYWORDS, value)

    var negativeKeywords: List<String>
        get() = getStringList(KEY_NEGATIVE_KEYWORDS)
        set(value) = setStringList(KEY_NEGATIVE_KEYWORDS, value)

    // ── Helper Methods ─────────────────────────────────────────────────

    /**
     * Check if the bot token and chat ID are configured.
     */
    fun isTelegramConfigured(): Boolean {
        return botToken.isNotBlank() && chatId.isNotBlank()
    }

    /**
     * Clear all settings (factory reset).
     */
    fun clearAll() {
        prefs.edit().clear().apply()
    }

    /**
     * Export all settings as a map for debugging/backup.
     */
    fun exportSettings(): Map<String, *> {
        return prefs.all
    }

    // ── Private Helpers ────────────────────────────────────────────────

    private fun getStringList(key: String): List<String> {
        val stored = prefs.getString(key, "") ?: ""
        if (stored.isBlank()) return emptyList()
        return stored.split(LIST_SEPARATOR).filter { it.isNotBlank() }
    }

    private fun setStringList(key: String, list: List<String>) {
        val filtered = list.filter { it.isNotBlank() }
        val stored = filtered.joinToString(LIST_SEPARATOR)
        prefs.edit().putString(key, stored).apply()
    }
}
