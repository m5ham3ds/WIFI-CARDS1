package com.example.crash

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.presentation.crash.CrashActivity
import java.util.concurrent.atomic.AtomicBoolean

class CrashHandler(
    private val context: Context,
    private val defaultHandler: Thread.UncaughtExceptionHandler?
) : Thread.UncaughtExceptionHandler {

    companion object {
        private val isHandling = AtomicBoolean(false)
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        if (!isHandling.compareAndSet(false, true)) {
            defaultHandler?.uncaughtException(thread, throwable)
            return
        }

        try {
            // 1. Build and synchronously persist sanitized crash report
            val report = CrashReporter.buildReport(thread, throwable, context)
            val file = CrashReporter.saveReportSync(report, context)

            // 2. Launch CrashActivity in separate isolated process (:crash)
            val intent = Intent(context, CrashActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                file?.let { putExtra(CrashActivity.EXTRA_CRASH_REPORT_PATH, it.absolutePath) }
            }
            context.startActivity(intent)

            // 3. Brief yield for OS activity dispatch
            Thread.sleep(350L)
        } catch (e: Throwable) {
            Log.e("CrashHandler", "Error handling uncaught exception", e)
        } finally {
            // Terminate crashed process cleanly so Android OS transitions cleanly to CrashActivity
            try {
                android.os.Process.killProcess(android.os.Process.myPid())
                System.exit(10)
            } catch (_: Throwable) {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }
}
