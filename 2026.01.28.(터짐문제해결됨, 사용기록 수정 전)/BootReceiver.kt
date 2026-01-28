package com.example.applock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            try {
                // SharedPreferences 확인
                val prefs = context.getSharedPreferences("AppLockPrefs", Context.MODE_PRIVATE)
                val wasServiceRunning = prefs.getBoolean("service_was_running", false)

                // 기기 고유 ID 가져오기
                val userId = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ANDROID_ID
                )

//                // Firebase에 부팅 이벤트 기록
//                FirebaseManager.getInstance().logBootEvent(
//                    userId,
//                    BootEventData(
//                        timestamp = System.currentTimeMillis(),
//                        eventType = "BOOT_COMPLETED",
//                        wasServiceRunning = wasServiceRunning
//                    )
//                )

                Log.d("BootReceiver", "Device booted. Service was running: $wasServiceRunning")

                // 이전에 서비스가 실행 중이었다면 MainActivity 실행
                if (wasServiceRunning) {
                    val launchIntent = Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        addCategory(Intent.CATEGORY_LAUNCHER)
                    }
                    context.startActivity(launchIntent)
                    Log.d("BootReceiver", "Launching MainActivity after boot")
                }
            } catch (e: Exception) {
                Log.e("BootReceiver", "Error handling boot event: ${e.message}")
                // Firebase에 에러 로깅
                val userId = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ANDROID_ID
                )
                FirebaseManager.getInstance().logError(
                    userId,
                    ErrorType.SERVICE_ERROR,
                    "Boot receiver error: ${e.message}"
                )
            }
        }
    }
}