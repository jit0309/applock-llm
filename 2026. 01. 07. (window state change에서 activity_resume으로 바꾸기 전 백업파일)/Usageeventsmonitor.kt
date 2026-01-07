package com.example.applock

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.*

/**
 * UsageEvents를 모니터링하고 Firebase에 즉시 로깅하는 헬퍼 클래스 (테스트 버전 - 버퍼 없음)
 */
class UsageEventsMonitor(
    private val context: Context,
    private val userId: String
) {
    private val TAG = "UsageEventsMonitor"
    private val firebaseManager = FirebaseManager.getInstance()
    private val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    private val packageManager = context.packageManager

    private var monitoringJob: Job? = null
    private var lastEventTime: Long = System.currentTimeMillis()


    private fun getEventTypeName(eventType: Int): String {
        return when (eventType) {
            UsageEvents.Event.ACTIVITY_RESUMED -> "ACTIVITY_RESUMED"
            UsageEvents.Event.ACTIVITY_PAUSED -> "ACTIVITY_PAUSED"
            UsageEvents.Event.ACTIVITY_STOPPED -> "ACTIVITY_STOPPED"
            UsageEvents.Event.CONFIGURATION_CHANGE -> "CONFIGURATION_CHANGE"
            UsageEvents.Event.DEVICE_SHUTDOWN -> "DEVICE_SHUTDOWN"
            UsageEvents.Event.DEVICE_STARTUP -> "DEVICE_STARTUP"
            UsageEvents.Event.KEYGUARD_HIDDEN -> "KEYGUARD_HIDDEN"
            UsageEvents.Event.KEYGUARD_SHOWN -> "KEYGUARD_SHOWN"
            UsageEvents.Event.SCREEN_INTERACTIVE -> "SCREEN_INTERACTIVE"
            UsageEvents.Event.SCREEN_NON_INTERACTIVE -> "SCREEN_NON_INTERACTIVE"
            UsageEvents.Event.SHORTCUT_INVOCATION -> "SHORTCUT_INVOCATION"
            UsageEvents.Event.STANDBY_BUCKET_CHANGED -> "STANDBY_BUCKET_CHANGED"
            UsageEvents.Event.USER_INTERACTION -> "USER_INTERACTION"
            UsageEvents.Event.FOREGROUND_SERVICE_START -> "FOREGROUND_SERVICE_START"
            UsageEvents.Event.FOREGROUND_SERVICE_STOP -> "FOREGROUND_SERVICE_STOP"
            10 -> "NOTIFICATION_SEEN" // 상단바 내려서 최초 확인시
            12 -> "NOTIFICATION_INTERRUPTION" // 알림 올 때
            19 -> "CONTINUING_FOREGROUND_SERVICE"
            20 -> "ROLLOVER_FOREGROUND_SERVICE"
            30 -> "NOTIFICATION_INTERRUPTION"
            else -> "TYPE_$eventType"
        }
    }

    private fun getAppName(packageName: String): String {
        return try {
            val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(applicationInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        }
    }

    /**
     * 모니터링 시작 (버퍼 없이 즉시 저장)
     * @param intervalMs 이벤트 체크 간격 (기본: 10초)
     */
    fun startMonitoring(intervalMs: Long = 10_000L) {
        stopMonitoring()

        Log.d(TAG, "✅ UsageEvents 모니터링 시작 (즉시 저장 모드, 간격: ${intervalMs/1000}초)")

        monitoringJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                try {
                    collectAndSaveEvents()
                    delay(intervalMs)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in monitoring loop: ${e.message}", e)
                    delay(intervalMs)
                }
            }
        }
    }

    /**
     * 새로운 이벤트 수집 및 즉시 저장
     */
    /**
     * 새로운 이벤트 수집 및 즉시 저장
     */
    private fun collectAndSaveEvents() {
        val currentTime = System.currentTimeMillis()
        val events = usageStatsManager.queryEvents(lastEventTime, currentTime)

        if (events == null) {
            Log.w(TAG, "UsageEvents를 가져올 수 없습니다.")
            return
        }

        val event = UsageEvents.Event()
        var savedCount = 0

        while (events.hasNextEvent()) {
            events.getNextEvent(event)

            val eventData = UsageEventLogData(
                appName = getAppName(event.packageName),
                appPackage = event.packageName,
                eventType = getEventTypeName(event.eventType),
                eventTime = event.timeStamp
            )

            firebaseManager.logUsageEventsBatch(userId, listOf(eventData))
        }
    }

    /**
     * 모니터링 중지
     */
    fun stopMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = null
        Log.d(TAG, "⏹️ UsageEvents 모니터링 중지")
    }
}