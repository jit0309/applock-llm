package com.example.applock

import android.app.Application
import android.content.ComponentCallbacks2
import android.os.Process
import android.provider.Settings
import android.util.Log

class AppLockApplication : Application() {

    companion object {
        private const val TAG = "AppLockApplication"
        private var currentPID: Int = 0
    }

    override fun onCreate() {
        super.onCreate()

        currentPID = Process.myPid()

        val userId = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ANDROID_ID
        )

        FirebaseManager.getInstance().logError(
            userId,
            ErrorType.SERVICE_ERROR,
            "APP_CREATE | PID=$currentPID}"
        )

        Log.d(TAG, "✅ Application onCreate - PID: $currentPID")
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)

        val userId = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ANDROID_ID
        )

        val levelName = when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> "RUNNING_MODERATE"
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "RUNNING_LOW"
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "RUNNING_CRITICAL"
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "UI_HIDDEN"
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "BACKGROUND"
            ComponentCallbacks2.TRIM_MEMORY_MODERATE -> "MODERATE"
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "COMPLETE"
            else -> "UNKNOWN_$level"
        }

        FirebaseManager.getInstance().logError(
            userId,
            ErrorType.SERVICE_ERROR,
            "$levelName | PID=$currentPID}"
        )

        Log.w(TAG, "⚠️ onTrimMemory - Level: $level ($levelName), PID: $currentPID")
    }
}