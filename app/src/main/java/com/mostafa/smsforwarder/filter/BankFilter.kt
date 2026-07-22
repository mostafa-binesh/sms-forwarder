package com.mostafa.smsforwarder.filter

import android.util.Log
import com.mostafa.smsforwarder.util.SettingsManager

/**
 * Filter configuration for bank SMS filtering.
 */
data class FilterConfig(
    val senderNumbers: List<String> = emptyList(),
    val keywords: List<String> = emptyList(),
    val negativeKeywords: List<String> = emptyList(),
    val filterMode: FilterMode = FilterMode.ALL
)

/**
 * Filter modes:
 * - ALL: Forward every SMS
 * - WHITELIST: Only forward SMS from configured sender numbers or matching keywords
 * - AUTO: Automatically detect bank SMS using common Persian bank keywords
 */
enum class FilterMode {
    ALL,
    WHITELIST,
    AUTO
}

/**
 * Filters bank SMS based on configuration.
 */
class BankFilter(private val settings: SettingsManager) {

    companion object {
        private const val TAG = "BankFilter"

        // Common Persian bank SMS sender shortcodes and keywords
        private val AUTO_SENDER_KEYWORDS = listOf(
            "بانک", " BANK", "SADAD", "SHAPARAK", "PAY.ir"
        )

        // Common Persian bank SMS content keywords
        private val AUTO_BANK_KEYWORDS = listOf(
            "کارت",
            "بانک",
            "تراکنش",
            "برداشت",
            "واریز",
            "مانده",
            " موجودی",
            "پرداخت",
            "انتقال",
            "خرید",
            " سقف ",
            "تعداد",
            "مبلغ",
            "تاریخ",
            "ساعت",
            "مکان",
            "پایانه",
            "پوز",
            "POS",
            "واریز شد",
            "برداشت شد",
            "کسر شد",
            "افزایش یافت",
            "کاهش یافت",
            "حساب شما",
            "کارت شما",
            "card",
            "transaction",
            "balance",
            "withdrawal",
            "deposit"
        )

        // Persian negative keywords that indicate non-transactional messages
        private val AUTO_NEGATIVE_KEYWORDS = listOf(
            "تبلیغ",
            "قرعه کشی",
            "جشنواره",
            " win ",
            "winner",
            "کد تخفیف",
            "coupons"
        )
    }

    /**
     * Determine if an SMS should be forwarded based on the current filter mode and settings.
     */
    fun shouldForward(sender: String, body: String): Boolean {
        val config = loadConfig()

        return when (config.filterMode) {
            FilterMode.ALL -> true
            FilterMode.WHITELIST -> matchesWhitelist(sender, body, config)
            FilterMode.AUTO -> matchesAutoDetection(sender, body)
        }
    }

    private fun loadConfig(): FilterConfig {
        return FilterConfig(
            senderNumbers = settings.senderNumbers,
            keywords = settings.keywords,
            negativeKeywords = settings.negativeKeywords,
            filterMode = settings.filterMode
        )
    }

    /**
     * WHITELIST mode: forward if sender is in the whitelist or body contains any keyword,
     * unless it contains a negative keyword.
     */
    private fun matchesWhitelist(sender: String, body: String, config: FilterConfig): Boolean {
        // Check negative keywords first — these always block
        if (config.negativeKeywords.isNotEmpty()) {
            val lowerBody = body.lowercase()
            for (negKeyword in config.negativeKeywords) {
                if (lowerBody.contains(negKeyword.lowercase())) {
                    Log.d(TAG, "Blocked by negative keyword: $negKeyword")
                    return false
                }
            }
        }

        // Check sender whitelist
        if (config.senderNumbers.isNotEmpty()) {
            val normalizedSender = sender.trim()
            for (allowedSender in config.senderNumbers) {
                if (normalizedSender.contains(allowedSender.trim(), ignoreCase = true) ||
                    allowedSender.trim().contains(normalizedSender, ignoreCase = true)
                ) {
                    Log.d(TAG, "Matched whitelist sender: $allowedSender")
                    return true
                }
            }
        }

        // Check keyword match
        if (config.keywords.isNotEmpty()) {
            val lowerBody = body.lowercase()
            for (keyword in config.keywords) {
                if (lowerBody.contains(keyword.lowercase().trim())) {
                    Log.d(TAG, "Matched keyword: $keyword")
                    return true
                }
            }
        }

        return false
    }

    /**
     * AUTO mode: heuristically detect bank SMS using common Persian banking patterns.
     */
    private fun matchesAutoDetection(sender: String, body: String): Boolean {
        val lowerBody = body.lowercase()
        val upperSender = sender.uppercase()

        // Check for negative keywords (non-bank messages)
        for (negKeyword in AUTO_NEGATIVE_KEYWORDS) {
            if (lowerBody.contains(negKeyword.lowercase().trim())) {
                Log.d(TAG, "AUTO: Rejected by negative keyword: $negKeyword")
                return false
            }
        }

        // Check sender against known bank sender patterns
        for (senderKeyword in AUTO_SENDER_KEYWORDS) {
            if (upperSender.contains(senderKeyword.uppercase()) ||
                sender.contains(senderKeyword, ignoreCase = true)
            ) {
                Log.d(TAG, "AUTO: Matched sender pattern: $senderKeyword")
                return true
            }
        }

        // Check for bank-related numeric sender (shortcode)
        val numericSender = sender.replace(Regex("[^0-9]"), "")
        if (numericSender.isNotEmpty() && numericSender.length <= 10) {
            // Short numeric codes are often bank SMS senders
            var keywordCount = 0
            for (keyword in AUTO_BANK_KEYWORDS) {
                if (lowerBody.contains(keyword.lowercase().trim())) {
                    keywordCount++
                }
            }
            // Require at least 2 bank keywords to confirm it's a bank SMS
            if (keywordCount >= 2) {
                Log.d(TAG, "AUTO: Matched $keywordCount bank keywords from numeric sender")
                return true
            }
        }

        // Check body for strong bank indicators regardless of sender
        val strongIndicators = listOf("مانده", "موجودی", "برداشت", "واریز", "تراکنش", "کارت")
        var strongCount = 0
        for (indicator in strongIndicators) {
            if (body.contains(indicator, ignoreCase = true)) {
                strongCount++
            }
        }
        if (strongCount >= 2) {
            Log.d(TAG, "AUTO: Matched $strongCount strong bank indicators in body")
            return true
        }

        return false
    }
}
