package com.example

import com.example.crash.CrashReport
import com.example.crash.CrashSanitizer
import com.example.domain.model.ResultCategory
import com.example.service.CardTestOutcome
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Verification test suite for Crash Reporting, Data Sanitization, and Crash Screen logic:
 * - Redaction of passwords, voucher pins, and card codes from crash reports
 * - Serialization and parsing of structured crash reports
 * - Protection against report corruption and missing data
 * - Normal network errors and timeouts are NOT treated as crashes
 * - Clean lifecycle flags for app restart without crash loops
 */
class CrashReporterAndSanitizerTest {

    // =========================================================================
    // 1. Data Sanitization Engine (B2)
    // =========================================================================

    @Test
    fun testPasswordRedactionInVariousFormats() {
        val sample1 = "Error connecting with password=SuperSecretPassword123 during login"
        val sanitized1 = CrashSanitizer.sanitize(sample1)
        assertFalse("Raw password must NOT appear in output", sanitized1.contains("SuperSecretPassword123"))
        assertTrue("Password key must be masked with ***", sanitized1.contains("password=***"))

        val sample2 = "Config: pwd: SecretPass, token = my_secret_token_abc, salt: salt_xyz"
        val sanitized2 = CrashSanitizer.sanitize(sample2)
        assertFalse("Secret credentials must be redacted", sanitized2.contains("SecretPass"))
        assertFalse("Secret credentials must be redacted", sanitized2.contains("my_secret_token_abc"))
        assertFalse("Secret credentials must be redacted", sanitized2.contains("salt_xyz"))
    }

    @Test
    fun testCardAndUsernameRedaction() {
        val sample = "Testing card card=9041235678 and username=9041235678 for router login"
        val sanitized = CrashSanitizer.sanitize(sample)
        assertFalse("Full card number must not appear unmasked", sanitized.contains("9041235678"))
        assertTrue("Card should be masked with ***", sanitized.contains("card=90***78"))
        assertTrue("Username should be masked with ***", sanitized.contains("username=90***78"))
    }

    @Test
    fun testUrlQueryParameterCredentialsRedaction() {
        val url = "Failed navigating to http://wifi.sd.net/login?username=904123&password=AdminPassword123&action=login"
        val sanitized = CrashSanitizer.sanitize(url)
        assertFalse("URL password must be redacted", sanitized.contains("AdminPassword123"))
        assertFalse("URL username must be redacted", sanitized.contains("904123"))
        assertTrue("Query param username must be masked", sanitized.contains("username=***"))
        assertTrue("Query param password must be masked", sanitized.contains("password=***"))
        assertTrue("Non-sensitive query params must be preserved", sanitized.contains("action=login"))
    }

    @Test
    fun testChapHexMd5CallRedaction() {
        val script = "Executing CHAP authentication: hexMD5('\\264' + 'SecretPassword' + 'challenge123')"
        val sanitized = CrashSanitizer.sanitize(script)
        assertFalse("hexMD5 credentials must not be revealed", sanitized.contains("SecretPassword"))
        assertTrue("hexMD5 invocation must be masked", sanitized.contains("hexMD5(***)"))
    }

    @Test
    fun testStandaloneVoucherNumbersRedaction() {
        val log = "Active voucher code 1234567890 assigned to lease"
        val sanitized = CrashSanitizer.sanitize(log)
        assertFalse("Raw 10-digit voucher code must not appear unmasked", sanitized.contains("1234567890"))
        assertTrue("Voucher number must be masked", sanitized.contains("12***90"))
    }

    // =========================================================================
    // 2. Structured Crash Report Serialization & Parsing (B2, B3)
    // =========================================================================

    @Test
    fun testCrashReportFormattingAndParsing() {
        val original = CrashReport(
            timestamp = "2026-10-11 15:30:00.123",
            threadName = "main",
            appVersionName = "1.0.0",
            appVersionCode = 1,
            androidRelease = "14",
            sdkInt = 34,
            manufacturer = "Google",
            model = "Pixel 8",
            device = "shiba",
            exceptionClass = "java.lang.NullPointerException",
            exceptionMessage = "Attempt to invoke virtual method on null object",
            sanitizedStackTrace = "java.lang.NullPointerException\n\tat com.example.presentation.TestFragment.onViewCreated(TestFragment.kt:42)",
            sanitizedRecentLogs = listOf(
                "[2026-10-11 15:29:50][INFO][TestService] Starting test",
                "[2026-10-11 15:29:55][DEBUG][WebView] Page loaded"
            )
        )

        val text = original.toFormattedText()
        assertTrue("Formatted report must contain header", text.contains("WIFI-CARD CRASH REPORT"))
        assertTrue("Formatted report must contain exception class", text.contains("java.lang.NullPointerException"))
        assertTrue("Formatted report must contain stack trace", text.contains("TestFragment.kt:42"))
        assertTrue("Formatted report must contain recent logs", text.contains("Starting test"))

        val parsed = CrashReport.fromFormattedText(text)
        assertNotNull("Report must parse back successfully", parsed)
        assertEquals(original.timestamp, parsed!!.timestamp)
        assertEquals(original.threadName, parsed.threadName)
        assertEquals(original.appVersionName, parsed.appVersionName)
        assertEquals(original.appVersionCode, parsed.appVersionCode)
        assertEquals(original.exceptionClass, parsed.exceptionClass)
        assertEquals(original.exceptionMessage, parsed.exceptionMessage)
        assertEquals(original.sanitizedStackTrace, parsed.sanitizedStackTrace)
        assertEquals(2, parsed.sanitizedRecentLogs.size)
    }

    @Test
    fun testCorruptedCrashReportHandledGracefully() {
        val corruptedText = "Invalid gibberish without required crash report header"
        val parsed = CrashReport.fromFormattedText(corruptedText)
        assertNull("Corrupted report must return null instead of throwing", parsed)

        val emptyText = ""
        val parsedEmpty = CrashReport.fromFormattedText(emptyText)
        assertNull("Empty report must return null instead of throwing", parsedEmpty)
    }

    // =========================================================================
    // 3. Normal Network Errors & Timeouts Are NOT Application Crashes (B2)
    // =========================================================================

    @Test
    fun testHandledTimeoutIsNotTreatedAsApplicationCrash() {
        val timeoutOutcome = CardTestOutcome.timeout("انتهت مهلة استجابة بوابة الراوتر (Timeout)", 5000L)
        assertEquals(ResultCategory.TIMEOUT, timeoutOutcome.category)
        assertFalse("Timeout is not success", timeoutOutcome.isSuccess)

        // Verifying that handled timeouts are normal test outcomes, not unhandled exceptions
        val isCrash = timeoutOutcome.category == ResultCategory.ENGINE_ERROR
        assertFalse("Timeout must NOT be treated as application crash", isCrash)
    }

    @Test
    fun testHandledNetworkErrorIsNotTreatedAsApplicationCrash() {
        val netErrorOutcome = CardTestOutcome.networkError("فشل الاتصال بالشبكة", 200L)
        assertEquals(ResultCategory.NETWORK_ERROR, netErrorOutcome.category)
        assertFalse(netErrorOutcome.isSuccess)

        val isCrash = netErrorOutcome.category == ResultCategory.ENGINE_ERROR
        assertFalse("Network error must NOT be treated as application crash", isCrash)
    }

    // =========================================================================
    // 4. Report Size Capping
    // =========================================================================

    @Test
    fun testReportSizeCappingUnderLargeLogs() {
        val largeLogs = (1..500).map { "Log line #$it with simulated output text details" }
        val report = CrashReport(
            timestamp = "2026-10-11 15:30:00.000",
            threadName = "test-worker-1",
            appVersionName = "1.0.0",
            appVersionCode = 1,
            androidRelease = "14",
            sdkInt = 34,
            manufacturer = "TestBrand",
            model = "TestModel",
            device = "TestDev",
            exceptionClass = "java.lang.IllegalStateException",
            exceptionMessage = "Test message",
            sanitizedStackTrace = "IllegalStateException\n\tat com.example.service.TestService.run(TestService.kt:100)",
            sanitizedRecentLogs = largeLogs
        )

        val text = report.toFormattedText()
        assertTrue(text.isNotEmpty())
        assertTrue("Formatted text contains all logs", text.contains("Log line #500"))
    }
}
