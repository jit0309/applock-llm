package com.example.applock

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.*

/**
 * UsageEvents를 모니터링하고 Firebase에 로깅하는 헬퍼 클래스 (개선 버전)
 *
 * 개선사항:
 * 1. 이벤트 버퍼링: 일정 개수가 모이면 자동 저장
 * 2. 주기적 저장: 긴 간격으로 한 번에 저장
 * 3. 메모리 효율: 최대 버퍼 크기 제한
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
    private var eventIndex: Long = 1L

    // 버퍼 설정
    private val eventBuffer = mutableListOf<UsageEventLogData>()
    private val maxBufferSize = 100  // 최대 100개까지 모으기
    private val minBufferSize = 20   // 최소 20개 이상일 때만 저장

    private val allowedPackages = setOf(
        "com.example.applock",
        "com.android.settings",
        "com.sec.android.app.launcher",
        "com.android.systemui",
        "android"
    )

    private fun filterPackageName(pkg: String?): String? {
        if (pkg == null) return null
        return if (allowedPackages.contains(pkg)) pkg else null
    }

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
            19 -> "CONTINUING_FOREGROUND_SERVICE"
            20 -> "ROLLOVER_FOREGROUND_SERVICE"
            30 -> "NOTIFICATION_INTERRUPTION"
            else -> "TYPE_$eventType"
        }
    }

    /**
     * 모니터링 시작 (개선된 버전)
     * @param intervalMs 이벤트 체크 간격 (기본: 5분)
     * @param batchSize 한 번에 저장할 이벤트 개수 (기본: 50개)
     */
    fun startMonitoring(
        intervalMs: Long = 60 * 60000L,  // 5분으로 증가
        batchSize: Int = 50
    ) {
        stopMonitoring()

        Log.d(TAG, "✅ UsageEvents 모니터링 시작 (간격: ${intervalMs/1000}초, 배치크기: $batchSize)")

        monitoringJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                try {
                    collectNewEvents()

                    // 조건부 저장: 버퍼가 일정 크기 이상이면 저장
                    if (eventBuffer.size >= batchSize) {
                        flushBuffer()
                    }

                    delay(intervalMs)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in monitoring loop: ${e.message}", e)
                    delay(intervalMs)
                }
            }
        }
    }

    /**
     * 새로운 이벤트 수집 (버퍼에 추가만 함)
     */
    private fun collectNewEvents() {
        val currentTime = System.currentTimeMillis()
        val events = usageStatsManager.queryEvents(lastEventTime, currentTime)

        if (events == null) {
            Log.w(TAG, "UsageEvents를 가져올 수 없습니다.")
            return
        }

        val event = UsageEvents.Event()
        var newEventCount = 0

        while (events.hasNextEvent()) {
            events.getNextEvent(event)

            val filteredPackage = filterPackageName(event.packageName)

            val eventData = UsageEventLogData(
                idx = eventIndex++,
                appPackage = filteredPackage ?: "",
                eventType = getEventTypeName(event.eventType),
                eventTime = event.timeStamp
            )

            eventBuffer.add(eventData)
            newEventCount++

            // 버퍼가 최대 크기를 초과하면 즉시 저장
            if (eventBuffer.size >= maxBufferSize) {
                Log.d(TAG, "⚠️ 버퍼 최대 크기 도달 - 즉시 저장")
                flushBuffer()
            }
        }

        lastEventTime = currentTime

        if (newEventCount > 0) {
            Log.d(TAG, "📝 $newEventCount 개 이벤트 수집됨 (버퍼: ${eventBuffer.size})")
        }
    }

    /**
     * 버퍼의 이벤트를 Firebase에 저장
     */
    private fun flushBuffer() {
        if (eventBuffer.isEmpty()) {
            return
        }

        // 최소 크기 이상일 때만 저장 (너무 자주 저장하는 것 방지)
        if (eventBuffer.size < minBufferSize) {
            Log.d(TAG, "버퍼 크기가 최소 크기 미만 (${eventBuffer.size}/$minBufferSize) - 저장 보류")
            return
        }

        val eventsToSave = eventBuffer.toList()
        eventBuffer.clear()

        try {
            firebaseManager.logUsageEventsBatch(userId, eventsToSave)
            Log.d(TAG, "✅ ${eventsToSave.size}개 이벤트 Firebase에 저장 완료")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Firebase 저장 실패: ${e.message}", e)
            // 실패한 이벤트를 버퍼에 다시 추가 (선택사항)
            // eventBuffer.addAll(0, eventsToSave)
        }
    }

    /**
     * 모니터링 중지
     */
    fun stopMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = null

        // 중지 시 남은 이벤트 저장
        if (eventBuffer.isNotEmpty()) {
            Log.d(TAG, "모니터링 중지 - 남은 ${eventBuffer.size}개 이벤트 저장")
            flushBuffer()
        }

        Log.d(TAG, "⏹️ UsageEvents 모니터링 중지")
    }

    /**
     * 수동으로 버퍼 비우기 (앱 종료 전 등)
     */
    fun forceFlush() {
        Log.d(TAG, "강제 플러시 - ${eventBuffer.size}개 이벤트")
        if (eventBuffer.isNotEmpty()) {
            val eventsToSave = eventBuffer.toList()
            eventBuffer.clear()
            firebaseManager.logUsageEventsBatch(userId, eventsToSave)
        }
    }

    /**
     * 현재 버퍼 상태 확인
     */
    fun getBufferStatus(): String {
        return "버퍼: ${eventBuffer.size}/$maxBufferSize"
    }
}
