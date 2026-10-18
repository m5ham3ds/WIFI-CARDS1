package com.example.crash

data class CrashReport(
    val timestamp: String,
    val threadName: String,
    val appVersionName: String,
    val appVersionCode: Int,
    val androidRelease: String,
    val sdkInt: Int,
    val manufacturer: String,
    val model: String,
    val device: String,
    val exceptionClass: String,
    val exceptionMessage: String?,
    val sanitizedStackTrace: String,
    val sanitizedRecentLogs: List<String>
) {
    fun toFormattedText(): String {
        val sb = StringBuilder()
        sb.appendLine("==================================================")
        sb.appendLine("            WIFI-CARD CRASH REPORT               ")
        sb.appendLine("==================================================")
        sb.appendLine("Time: $timestamp")
        sb.appendLine("Thread: $threadName")
        sb.appendLine("App Version: $appVersionName ($appVersionCode)")
        sb.appendLine("Android OS: $androidRelease (API $sdkInt)")
        sb.appendLine("Device: $manufacturer $model ($device)")
        sb.appendLine("--------------------------------------------------")
        sb.appendLine("EXCEPTION: $exceptionClass")
        sb.appendLine("MESSAGE: ${exceptionMessage ?: "None"}")
        sb.appendLine("--------------------------------------------------")
        sb.appendLine("STACK TRACE:")
        sb.appendLine(sanitizedStackTrace)
        sb.appendLine("--------------------------------------------------")
        sb.appendLine("RECENT LOGS:")
        if (sanitizedRecentLogs.isEmpty()) {
            sb.appendLine("(No recent logs available)")
        } else {
            sanitizedRecentLogs.forEach { sb.appendLine(it) }
        }
        sb.appendLine("==================================================")
        return sb.toString()
    }

    companion object {
        fun fromFormattedText(text: String): CrashReport? {
            if (text.isBlank() || !text.contains("WIFI-CARD CRASH REPORT")) return null
            return try {
                fun extractValue(prefix: String): String {
                    val line = text.lines().firstOrNull { it.startsWith(prefix) } ?: return ""
                    return line.removePrefix(prefix).trim()
                }

                val timestamp = extractValue("Time:")
                val threadName = extractValue("Thread:")
                val appVer = extractValue("App Version:")
                val appVersionName = appVer.substringBefore(" (").ifBlank { "1.0.0" }
                val appVersionCode = appVer.substringAfter("(").substringBefore(")").toIntOrNull() ?: 1
                val osLine = extractValue("Android OS:")
                val androidRelease = osLine.substringBefore(" (").ifBlank { "Unknown" }
                val sdkInt = osLine.substringAfter("API ").substringBefore(")").toIntOrNull() ?: 0
                val devLine = extractValue("Device:")
                val manufacturer = devLine.substringBefore(" ").ifBlank { "Unknown" }
                val model = devLine.substringAfter(" ").substringBefore(" (").ifBlank { "Unknown" }
                val device = devLine.substringAfter("(").substringBefore(")").ifBlank { "Unknown" }
                val exceptionClass = extractValue("EXCEPTION:").ifBlank { "UnknownException" }
                val exceptionMessage = extractValue("MESSAGE:").let { if (it == "None") null else it }

                val stackTrace = text.substringAfter("STACK TRACE:\n")
                    .substringBefore("\n--------------------------------------------------\nRECENT LOGS:")
                    .trim()

                val logsText = text.substringAfter("RECENT LOGS:\n")
                    .substringBefore("\n==================================================")
                    .trim()
                val recentLogs = if (logsText.contains("(No recent logs available)") || logsText.isBlank()) {
                    emptyList()
                } else {
                    logsText.lines()
                }

                CrashReport(
                    timestamp = timestamp,
                    threadName = threadName,
                    appVersionName = appVersionName,
                    appVersionCode = appVersionCode,
                    androidRelease = androidRelease,
                    sdkInt = sdkInt,
                    manufacturer = manufacturer,
                    model = model,
                    device = device,
                    exceptionClass = exceptionClass,
                    exceptionMessage = exceptionMessage,
                    sanitizedStackTrace = stackTrace,
                    sanitizedRecentLogs = recentLogs
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
