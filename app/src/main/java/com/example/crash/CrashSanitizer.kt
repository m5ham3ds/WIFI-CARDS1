package com.example.crash

object CrashSanitizer {

    // Sensitive keys such as password, pwd, token, secret, salt, pin
    private val SENSITIVE_KEY_REGEX = Regex(
        """(?i)\b(password|passwd|pwd|pass|secret|token|hash|salt|md5_salt)\b\s*[:=]\s*([^\s,;'"&>]+)"""
    )

    // Key-value pairs for card codes / usernames / pins
    private val SENSITIVE_CARD_KEY_REGEX = Regex(
        """(?i)\b(card|cardcode|card_code|cardval|card_val|user|username|uname|pin)\b\s*[:=]\s*([^\s,;'"&>]+)"""
    )

    // Query parameters in URLs: &username=123&password=456
    private val URL_QUERY_PARAM_REGEX = Regex(
        """(?i)([?&](?:username|uname|password|passwd|pwd|pin|card|cardcode|card_code)=)([^&\s]+)"""
    )

    // CHAP hexMD5 calls containing salt or password
    private val JS_HEX_MD5_REGEX = Regex(
        """(?i)hexMD5\s*\([^)]*\)"""
    )

    // Standalone digit sequences between 6 and 18 digits (which may represent cards/vouchers)
    private val STANDALONE_CARD_NUMBER_REGEX = Regex(
        """(?<=\s|^|[=:(,\"'\[])(\d{6,18})(?=\s|$|[=:\),\";'\]])"""
    )

    fun sanitize(input: String?): String {
        if (input.isNullOrBlank()) return ""

        var result = input

        // 1. Redact sensitive URL query parameters
        result = URL_QUERY_PARAM_REGEX.replace(result) { matchResult ->
            "${matchResult.groupValues[1]}***"
        }

        // 2. Redact passwords and secrets
        result = SENSITIVE_KEY_REGEX.replace(result) { matchResult ->
            val key = matchResult.groupValues[1]
            "$key=***"
        }

        // 3. Redact cards and usernames associated with keys
        result = SENSITIVE_CARD_KEY_REGEX.replace(result) { matchResult ->
            val key = matchResult.groupValues[1]
            val value = matchResult.groupValues[2]
            val maskedVal = if (value.length > 4) value.take(2) + "***" + value.takeLast(2) else "***"
            "$key=$maskedVal"
        }

        // 4. Redact CHAP hexMD5 calls
        result = JS_HEX_MD5_REGEX.replace(result, "hexMD5(***)")

        // 5. Redact standalone digit sequences (cards / vouchers)
        result = STANDALONE_CARD_NUMBER_REGEX.replace(result) { matchResult ->
            val digits = matchResult.groupValues[1]
            if (digits.length > 4) digits.take(2) + "***" + digits.takeLast(2) else "***"
        }

        return result
    }
}
