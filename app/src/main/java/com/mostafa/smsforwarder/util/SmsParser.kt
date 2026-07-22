package com.mostafa.smsforwarder.util

/**
 * Parser for extracting structured data from Persian bank SMS messages.
 * Handles common bank SMS formats including card number, amount, date, and balance.
 */
object SmsParser {

    /**
     * Parsed SMS data container.
     */
    data class ParsedSms(
        val cardNumber: String = "",
        val amount: Long? = null,
        val date: String = "",
        val balanceAfter: Long? = null,
        val rawMessage: String = ""
    )

    // Card number patterns: 16-digit, or last 4 digits with **** prefix
    private val CARD_NUMBER_PATTERNS = listOf(
        // Standalone 14-19 digit card number on its own line (user's format)
        Regex("""^\s*(\d{14,19})\s*$""", RegexOption.MULTILINE),
        // Card with label prefix
        Regex("""(?:کارت|کارت\s*:\s*|card\s*:\s*|Card)\s*(\d{4})\s*(\d{4})\s*(\d{4})\s*(\d{4})""", RegexOption.IGNORE_CASE),
        Regex("""(?:کارت|کارت\s*:\s*|card\s*:\s*|Card)\s*\*{4}\s*(\d{4})""", RegexOption.IGNORE_CASE),
        Regex("""(\d{4})\s*-?\s*(\d{4})\s*-?\s*(\d{4})\s*-?\s*(\d{4})"""),
        Regex("""(?:شماره\s*کارت|card\s*number)\s*:\s*(\d{4}\s*\d{4}\s*\d{4}\s*\d{4})""", RegexOption.IGNORE_CASE),
        // 14-19 digit number anywhere in text
        Regex("""(\d{14,19})""")
    )

    // Amount patterns: Persian/English digits with تومان/ریال/IRR/rial
    private val AMOUNT_PATTERNS = listOf(
        // User's format: amount followed by +/- sign at the END (e.g., "2,250,000-" or "20,000,000+")
        Regex("""([\d,]+)([+-])\s*$""", RegexOption.MULTILINE),
        // Standard: +/- before amount
        Regex("""([+-])\s*([\d,.]+)\s*(?:تومان|ریال|IRR|rial|Rials|Tomans)?""", RegexOption.IGNORE_CASE),
        Regex("""(?:مبلغ|amount)\s*:\s*([+-]?\s*[\d,.]+)""", RegexOption.IGNORE_CASE),
        Regex("""(?:برداشت|کسر|خرید|پرداخت|انتقال از|واریز\s*به)\s*.*?([\d,.]+)\s*(?:تومان|ریال|IRR|rial)""", RegexOption.IGNORE_CASE),
        Regex("""([\d,.]+)\s*(?:تومان|ریال|IRR|rial|Tomans|Rials)""", RegexOption.IGNORE_CASE),
    )

    // Jalali date patterns
    private val DATE_PATTERNS = listOf(
        Regex("""(\d{4}[/-]\d{1,2}[/-]\d{1,2})\s*(?:ساعت\s*(\d{1,2}:\d{2}(?::\d{2})?))?"""),
        Regex("""(\d{4}/\d{1,2}/\d{1,2})\s*(\d{1,2}:\d{2})"""),
        Regex("""تاریخ\s*:\s*(\d{4}[/-]\d{1,2}[/-]\d{1,2})""", RegexOption.IGNORE_CASE),
        Regex("""date\s*:\s*(\d{4}[/-]\d{1,2}[/-]\d{1,2})""", RegexOption.IGNORE_CASE),
        Regex("""(\d{2}/\d{2}/\d{4})"""),  // MM/DD/YYYY fallback
    )

    // Balance patterns: مانده or موجودی followed by amount
    private val BALANCE_PATTERNS = listOf(
        Regex("""مانده\s*:\s*([\d,.\u06F0-\u06F9]+)\s*(?:تومان|ریال|IRR|rial)?""", RegexOption.IGNORE_CASE),
        Regex("""موجودی\s*:\s*([\d,.\u06F0-\u06F9]+)\s*(?:تومان|ریال|IRR|rial)?""", RegexOption.IGNORE_CASE),
        Regex("""(?:balance|Balance)\s*:\s*([\d,.\u06F0-\u06F9]+)""", RegexOption.IGNORE_CASE),
        Regex("""مانده\s*حساب\s*(?:شما\s*)?:?\s*([\d,.\u06F0-\u06F9]+)""", RegexOption.IGNORE_CASE),
        Regex("""(?:remaining|Remaining)\s*(?:balance)?\s*:\s*([\d,.\u06F0-\u06F9]+)""", RegexOption.IGNORE_CASE),
    )

    /**
     * Parse a bank SMS message and extract structured data.
     */
    fun parse(message: String): ParsedSms {
        return ParsedSms(
            cardNumber = extractCardNumber(message),
            amount = extractAmount(message),
            date = extractDate(message),
            balanceAfter = extractBalance(message),
            rawMessage = message
        )
    }

    /**
     * Extract the last 4 digits of the card number.
     */
    private fun extractCardNumber(message: String): String {
        for (pattern in CARD_NUMBER_PATTERNS) {
            val match = pattern.find(message)
            if (match != null) {
                return when {
                    match.groupValues.size >= 5 && match.groupValues[1].length == 4 -> {
                        // Full 16-digit card: return last 4 digits
                        match.groupValues[4]
                    }
                    match.groupValues.size >= 2 && match.groupValues[1].length == 4 -> {
                        // Last 4 digits already
                        match.groupValues[1]
                    }
                    else -> match.groupValues.drop(1).filter { it.isNotBlank() }.lastOrNull() ?: ""
                }
            }
        }
        return ""
    }

    /**
     * Extract the transaction amount.
     * Determines sign based on transaction type keywords.
     */
    private fun extractAmount(message: String): Long? {
        for (pattern in AMOUNT_PATTERNS) {
            val match = pattern.find(message)
            if (match != null) {
                // Handle sign-at-end pattern (group 1=number, group 2=sign)
                if (match.groupValues.size >= 3 && match.groupValues[2] in listOf("+", "-")) {
                    val numberStr = match.groupValues[1]
                    val sign = match.groupValues[2]
                    val cleanedAmount = extractNumberFromString(numberStr)
                    if (cleanedAmount != null) {
                        return if (sign == "+") cleanedAmount else -cleanedAmount
                    }
                } else {
                    // Handle sign-before-number or keyword-based patterns
                    val isCredit = message.contains(Regex("""واریز|واریز\s*شد|افزایش|deposit|credit""", RegexOption.IGNORE_CASE))
                    val isDebit = message.contains(
                        Regex("""برداشت|کسر|خرید|پرداخت|انتقال از|افزایش\s*یافت|withdrawal|debit|purchase|payment""",
                            RegexOption.IGNORE_CASE)
                    )

                    val amountStr = match.groupValues.getOrNull(1) ?: match.groupValues[0]
                    val cleanedAmount = extractNumberFromString(amountStr)
                    if (cleanedAmount != null) {
                        return when {
                            isCredit -> cleanedAmount
                            isDebit -> -cleanedAmount
                            cleanedAmount > 0 -> cleanedAmount
                            else -> cleanedAmount
                        }
                    }
                }
            }
        }
        return null
    }

    /**
     * Extract the Jalali date (and optional time).
     */
    private fun extractDate(message: String): String {
        for (pattern in DATE_PATTERNS) {
            val match = pattern.find(message)
            if (match != null) {
                val dateStr = match.groupValues[1]
                val timeStr = match.groupValues.getOrNull(2)
                return if (!timeStr.isNullOrBlank()) {
                    "$dateStr $timeStr"
                } else {
                    dateStr
                }
            }
        }
        return ""
    }

    /**
     * Extract the balance after transaction (مانده / موجودی).
     */
    private fun extractBalance(message: String): Long? {
        for (pattern in BALANCE_PATTERNS) {
            val match = pattern.find(message)
            if (match != null) {
                val balanceStr = match.groupValues.getOrNull(1) ?: match.groupValues[0]
                return extractNumberFromString(balanceStr)
            }
        }
        return null
    }

    /**
     * Convert a Persian/English digit string to a Long, handling commas and dots as thousand separators.
     */
    private fun extractNumberFromString(input: String): Long? {
        // Convert Persian digits to English
        var cleaned = input.trim()
            .replace(Regex("[\u06F0-\u06F9]")) { match ->
                val persianDigit = match.value[0].code - 0x06F0
                persianDigit.toString()
            }
            .replace(",", "")
            .replace("،", "") // Persian comma
            .replace(" ", "")

        // Handle cases where dot is used as thousand separator (e.g., 1.234.567)
        // If there are multiple dots, treat them as separators
        val dotCount = cleaned.count { it == '.' }
        if (dotCount > 1) {
            cleaned = cleaned.replace(".", "")
        } else if (dotCount == 1) {
            // Single dot could be decimal — for currency we usually want integer
            // Remove decimal part for Tomans/Rials
            cleaned = cleaned.replace(".", "")
        }

        // Handle sign
        val isNegative = cleaned.startsWith("-")
        cleaned = cleaned.trimStart('-', '+')

        return try {
            val value = cleaned.toLong()
            if (isNegative) -value else value
        } catch (e: NumberFormatException) {
            null
        }
    }
}
