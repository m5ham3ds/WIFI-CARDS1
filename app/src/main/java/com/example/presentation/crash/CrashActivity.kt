package com.example.presentation.crash

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.example.R
import com.example.crash.CrashReport
import com.example.crash.CrashReporter
import com.example.data.local.preferences.ThemePreferences
import com.example.domain.model.AppPrimaryColor
import com.example.presentation.MainActivity
import com.example.presentation.compose.WifiCardTheme
import com.example.util.LocaleHelper
import java.io.File

class CrashActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CRASH_REPORT_PATH = "extra_crash_report_path"
    }

    private var currentReport: CrashReport? = null

    override fun attachBaseContext(newBase: Context) {
        val lang = LocaleHelper.getPersistedLocale(newBase)
        super.attachBaseContext(LocaleHelper.setLocale(newBase, lang))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val themeMode = try {
            val sharedPreferences = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
            sharedPreferences.getString("theme", "dark") ?: "dark"
        } catch (_: Exception) {
            "dark"
        }
        val nightMode = when (themeMode) {
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)

        val primaryColorKey = ThemePreferences.getPrimaryColorSync(this)
        val primaryColor = AppPrimaryColor.fromKey(primaryColorKey)
        setTheme(primaryColor.styleResId)

        super.onCreate(savedInstanceState)

        currentReport = loadReport()

        setContent {
            WifiCardTheme {
                CrashScreenCompose(
                    report = currentReport,
                    onCopyLogs = { copyReportToClipboard() },
                    onRestartApp = { restartApplication() }
                )
            }
        }
    }

    private fun loadReport(): CrashReport? {
        val path = intent.getStringExtra(EXTRA_CRASH_REPORT_PATH)
        if (!path.isNullOrBlank()) {
            try {
                val file = File(path)
                if (file.exists() && file.length() > 0) {
                    val report = CrashReport.fromFormattedText(file.readText())
                    if (report != null) return report
                }
            } catch (_: Throwable) {}
        }
        return CrashReporter.getLatestReport(this)
    }

    fun copyReportToClipboard() {
        val report = currentReport ?: CrashReporter.getLatestReport(this)
        if (report == null) {
            Toast.makeText(this, getString(R.string.crash_no_report), Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Crash Log", report.toFormattedText())
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, getString(R.string.crash_logs_copied), Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.crash_no_report), Toast.LENGTH_SHORT).show()
        }
    }

    fun restartApplication() {
        CrashReporter.markLatestReportAcknowledged(this)

        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, MainActivity::class.java)

        launchIntent.putExtra("extra_from_crash_restart", true)
        launchIntent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
        )

        startActivity(launchIntent)
        finishAffinity()
    }
}
