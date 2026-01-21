// UsageEventsMonitor.kt
package com.example.applock

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue

class UsageEventsMonitor(
    private val context: Context,
    private val userId: String
) {
    private val TAG = "UsageEventsMonitor"
    private val firebaseManager = FirebaseManager.getInstance()
    private val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    private val packageManager = context.packageManager

    private var monitoringJob: Job? = null
    private var flushJob: Job? = null
    private var lastEventTime: Long = System.currentTimeMillis()

    private val eventBuffer = ConcurrentLinkedQueue<UsageEventLogData>()
//    private val BUFFER_MAX_SIZE = 150
    private val FLUSH_INTERVAL_MS = 60_000L

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
            10 -> "NOTIFICATION_SEEN"
            12 -> "NOTIFICATION_INTERRUPTION"
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

    fun startMonitoring(intervalMs: Long = 10_000L) {
        stopMonitoring()
        Log.d(TAG, "✅ UsageEvents 모니터링 시작 (Batch 모드)")

        // 이벤트 수집
        monitoringJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                try {
                    collectEvents()

//                    // 버퍼가 가득 차면 즉시 flush
//                    if (eventBuffer.size >= BUFFER_MAX_SIZE) {
//                        flushEvents()
//                    }

                    delay(intervalMs)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in monitoring: ${e.message}", e)
                    delay(intervalMs)
                }
            }
        }

        // 주기적 flush
        flushJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                delay(FLUSH_INTERVAL_MS)
                flushEvents()
            }
        }
    }

    private fun collectEvents() {
        val currentTime = System.currentTimeMillis()
        val events = usageStatsManager.queryEvents(lastEventTime, currentTime) ?: return

        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)

            eventBuffer.add(
                UsageEventLogData(
                    appName = getAppName(event.packageName),
                    appPackage = event.packageName,
                    eventType = getEventTypeName(event.eventType),
                    eventTime = event.timeStamp
                )
            )
        }

        lastEventTime = currentTime
    }

    private suspend fun flushEvents() {
        if (eventBuffer.isEmpty()) return

        val eventsToSend = mutableListOf<UsageEventLogData>()
        while (eventBuffer.isNotEmpty()) {
            eventBuffer.poll()?.let { eventsToSend.add(it) }
        }

        if (eventsToSend.isEmpty()) return

        Log.d(TAG, "📤 Flushing ${eventsToSend.size} events")

        // 150개 단위로 chunk
        eventsToSend.chunked(150).forEach { chunk ->
            firebaseManager.logUsageEventsBatch(userId, chunk)
            delay(100) // Firebase 요청 간격
        }
    }

    fun stopMonitoring() {
        runBlocking {
            flushEvents() // 남은 이벤트 전송
        }

        monitoringJob?.cancel()
        flushJob?.cancel()
        monitoringJob = null
        flushJob = null

        Log.d(TAG, "⏹️ UsageEvents 모니터링 중지")
    }
}