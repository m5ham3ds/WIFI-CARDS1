package com.example.crash

import android.content.Context
import android.os.Build
import com.example.BuildConfig
import com.example.util.AppLogger
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashReporter {
    private const val CRASH_DIR_NAME = "crash_reports"
    private const val LATEST_CRASH_FILENAME = "crash_latest.txt"
    private const val ACKNOWLEDGED_FILENAME = ".crash_acknowledged"
    private const val MAX_CRASH_FILES = 3
    private const val MAX_FILE_SIZE_BYTES = 256 * 1024L // 256KB cap

    fun getCrashDir(context: Context): File {
        val dir = File(context.filesDir, CRASH_DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun buildReport(thread: Thread, throwable: Throwable, context: Context): CrashReport {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        val timestamp = dateFormat.format(Date())

        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val rawStackTrace = sw.toString()
        val sanitizedStackTrace = CrashSanitizer.sanitize(rawStackTrace)

        val rawRecentLogs = AppLogger.getRecentLogs()
        val sanitizedLogs = rawRecentLogs.map { CrashSanitizer.sanitize(it) }

        val rawMessage = throwable.localizedMessage ?: throwable.message
        val sanitizedMessage = rawMessage?.let { CrashSanitizer.sanitize(it) }

        return CrashReport(
            timestamp = timestamp,
            threadName = thread.name,
            appVersionName = BuildConfig.VERSION_NAME,
            appVersionCode = BuildConfig.VERSION_CODE,
            androidRelease = Build.VERSION.RELEASE ?: "Unknown",
            sdkInt = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER ?: "Unknown",
            model = Build.MODEL ?: "Unknown",
            device = Build.DEVICE ?: "Unknown",
            exceptionClass = throwable.javaClass.name,
            exceptionMessage = sanitizedMessage,
            sanitizedStackTrace = sanitizedStackTrace,
            sanitizedRecentLogs = sanitizedLogs
        )
    }

    @Synchronized
    fun saveReportSync(report: CrashReport, context: Context): File? {
        return try {
            val dir = getCrashDir(context)
            rotateReports(dir)

            val text = report.toFormattedText()
            val cappedText = if (text.length > MAX_FILE_SIZE_BYTES) {
                text.take(MAX_FILE_SIZE_BYTES.toInt()) + "\n[TRUNCATED_DUE_TO_SIZE]"
            } else {
                text
            }

            // Save as latest active crash report
            val latestFile = File(dir, LATEST_CRASH_FILENAME)
            latestFile.writeText(cappedText)

            // Save historical copy for audit
            val timestampedFile = File(dir, "crash_${System.currentTimeMillis()}.txt")
            timestampedFile.writeText(cappedText)

            // Clear previous acknowledged flag
            val ackFile = File(dir, ACKNOWLEDGED_FILENAME)
            if (ackFile.exists()) ackFile.delete()

            latestFile
        } catch (_: Throwable) {
            null
        }
    }

    @Synchronized
    fun getLatestReport(context: Context): CrashReport? {
        return try {
            val dir = getCrashDir(context)
            val latestFile = File(dir, LATEST_CRASH_FILENAME)
            if (!latestFile.exists() || latestFile.length() == 0L) return null
            val text = latestFile.readText()
            CrashReport.fromFormattedText(text)
        } catch (_: Throwable) {
            null
        }
    }

    @Synchronized
    fun markLatestReportAcknowledged(context: Context) {
        try {
            val dir = getCrashDir(context)
            val ackFile = File(dir, ACKNOWLEDGED_FILENAME)
            ackFile.createNewFile()
        } catch (_: Throwable) {}
    }

    @Synchronized
    fun isLatestReportAcknowledged(context: Context): Boolean {
        return try {
            val dir = getCrashDir(context)
            File(dir, ACKNOWLEDGED_FILENAME).exists()
        } catch (_: Throwable) {
            false
        }
    }

    @Synchronized
    fun rotateReports(dir: File) {
        try {
            val files = dir.listFiles { file ->
                file.name.startsWith("crash_") && file.name != LATEST_CRASH_FILENAME
            }?.sortedByDescending { it.lastModified() } ?: return

            if (files.size >= MAX_CRASH_FILES) {
                files.drop(MAX_CRASH_FILES - 1).forEach { it.delete() }
            }
        } catch (_: Throwable) {}
    }
}
