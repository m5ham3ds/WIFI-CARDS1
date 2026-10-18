package com.example.presentation

import android.app.Application
import android.os.Build
import com.example.BuildConfig
import com.example.crash.CrashHandler
import com.example.di.appModule
import com.example.di.viewModelModule
import com.example.util.AppLogger
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import timber.log.Timber

class MyApplication : Application() {
    override fun attachBaseContext(base: android.content.Context) {
        val lang = com.example.util.LocaleHelper.getPersistedLocale(base)
        super.attachBaseContext(com.example.util.LocaleHelper.setLocale(base, lang))
    }

    override fun onCreate() {
        super.onCreate()

        val processName = getProcessNameCompat()
        val isCrashProcess = processName.endsWith(":crash")

        if (isCrashProcess) {
            // Dedicated crash reporting process:
            // Do NOT initialize Koin DI, Room DB, or background test services.
            return
        }

        // Install uncaught exception handler early in main application process
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(this, defaultHandler))

        // Timber logging (debug builds only to prevent logcat credential leaks)
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
            Timber.d("MyApplication onCreate called")
        }

        // App Logger
        AppLogger.init(this)

        // Koin DI Initialize
        startKoin {
            androidContext(this@MyApplication)
            modules(listOf(appModule, viewModelModule))
        }
    }

    private fun getProcessNameCompat(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return Application.getProcessName()
        }
        val pid = android.os.Process.myPid()
        val am = getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        am?.runningAppProcesses?.forEach { processInfo ->
            if (processInfo.pid == pid) {
                return processInfo.processName
            }
        }
        return packageName
    }
}
