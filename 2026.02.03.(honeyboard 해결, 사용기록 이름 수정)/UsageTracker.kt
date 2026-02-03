package com.example.applock

import android.content.Context
import android.util.Log
import com.google.firebase.database.FirebaseDatabase

/**
 * Real-time app usage tracker for AccessibilityService
 * Tracks foreground app changes and accumulates usage time per app
 */
class UsageTracker(
    private val context: Context,
    private val userId: String
) {
    companion object {
        private const val TAG = "UsageTracker"

        // System packages to track but not display in UI
        private val SYSTEM_PACKAGES = setOf(
            "com.sec.android.app.launcher",
            "com.android.systemui",
            "com.android.settings",
            "com.samsung.android.app.launcher",
            "com.google.android.packageinstaller",
            "com.android.settings.intelligence",
            "com.google.android.cellbroadcastreceiver",
            "com.samsung.android.spay",
            "com.samsung.android.dialer",
            "com.samsung.android.honeyboard",
            "com.navercorp.android.smartboard",
            "com.samsung.android.app.clockpack",
            "com.samsung.android.app.aodservice",
            "com.ktcs.whowho",
            "android",
            "oneui"
        )
    }

    // Session tracking
    private var isSessionActive = false
    private var sessionStartTime: Long = 0L
    private var sessionId: String? = null

    // Current app tracking
    private var currentApp: String? = null
    private var currentAppStartTime: Long = 0L

    // Usage accumulation: Map<packageName, totalUsageMillis>
    private val usageMap = mutableMapOf<String, Long>()

    /**
     * Start a new usage tracking session
     * Call this when COUNT_DOWN mode starts
     */
    fun startSession() {
        if (isSessionActive) {
            Log.w(TAG, "Session already active, ending previous session first")
            endSession()
        }

        val now = System.currentTimeMillis()
        sessionStartTime = now
        sessionId = generateSessionId(now)
        isSessionActive = true
        usageMap.clear()

        Log.d(TAG, "✅ Session started: $sessionId at ${formatTime(now)}")
    }

    /**
     * Record app transition
     * Call this on TYPE_WINDOW_STATE_CHANGED event
     */
    fun onAppChanged(newPackageName: String?) {
        if (!isSessionActive) {
            Log.d(TAG, "⚠️ Session not active, ignoring app change: $newPackageName")
            return
        }

        val now = System.currentTimeMillis()

        // Finalize previous app duration
        if (currentApp != null && currentAppStartTime > 0) {
            val duration = now - currentAppStartTime
            if (duration > 0) {
                usageMap[currentApp!!] = (usageMap[currentApp!!] ?: 0L) + duration
                Log.d(TAG, "📱 App usage recorded: $currentApp = ${duration}ms (${duration/1000}s)")
            }
        }

        // Start tracking new app
        currentApp = newPackageName
        currentAppStartTime = if (newPackageName != null) now else 0L

        if (newPackageName != null) {
            val appLabel = try {
                val appInfo = context.packageManager.getApplicationInfo(newPackageName, 0)
                context.packageManager.getApplicationLabel(appInfo).toString()
            } catch (e: Exception) { "Unknown" }
            Log.d(TAG, "📱 App changed to: $newPackageName → $appLabel")
        }
    }

    /**
     * End the current session and save to Firebase
     * Call this when COUNT_DOWN mode ends or service stops
     */
    fun endSession() {
        if (!isSessionActive) {
            Log.w(TAG, "No active session to end")
            return
        }

        val now = System.currentTimeMillis()

        // Finalize last app duration
        if (currentApp != null && currentAppStartTime > 0) {
            val duration = now - currentAppStartTime
            if (duration > 0) {
                usageMap[currentApp!!] = (usageMap[currentApp!!] ?: 0L) + duration
                Log.d(TAG, "📱 Final app usage recorded: $currentApp = ${duration}ms")
            }
        }

        // Save to Firebase
        if (sessionId != null && usageMap.isNotEmpty()) {
            saveSessionToFirebase(sessionId!!, sessionStartTime, now, usageMap)
        }

        // Reset state
        Log.d(TAG, "✅ Session ended: $sessionId, duration: ${formatDuration(now - sessionStartTime)}")
        resetSession()
    }

    /**
     * Pause tracking (e.g., screen off)
     * Saves current app duration but keeps session active
     */
    fun pauseTracking() {
        if (!isSessionActive) return

        val now = System.currentTimeMillis()

        // Finalize current app duration
        if (currentApp != null && currentAppStartTime > 0) {
            val duration = now - currentAppStartTime
            if (duration > 0) {
                usageMap[currentApp!!] = (usageMap[currentApp!!] ?: 0L) + duration
                Log.d(TAG, "⏸️ Paused tracking: $currentApp = ${duration}ms")
            }
        }

        // Clear current app but keep session active
        currentApp = null
        currentAppStartTime = 0L
    }

    /**
     * Resume tracking (e.g., screen on)
     * Starts tracking from current foreground app
     */
    fun resumeTracking(foregroundApp: String?) {
        if (!isSessionActive) return

        val now = System.currentTimeMillis()
        currentApp = foregroundApp
        currentAppStartTime = if (foregroundApp != null) now else 0L

        Log.d(TAG, "▶️ Resumed tracking with app: $foregroundApp")
    }

    /**
     * Check if a package is a system package
     */
    fun isSystemPackage(packageName: String): Boolean {
        return SYSTEM_PACKAGES.contains(packageName) ||
                packageName.startsWith("android") ||
                packageName.contains("launcher") ||
                packageName.contains("systemui")
    }

    /**
     * Get current session statistics
     */
    fun getSessionStats(): SessionStats? {
        if (!isSessionActive) return null

        val now = System.currentTimeMillis()
        val totalDuration = now - sessionStartTime

        return SessionStats(
            sessionId = sessionId!!,
            startTime = sessionStartTime,
            duration = totalDuration,
            appCount = usageMap.size,
            totalUsage = usageMap.values.sum(),
            topApp = usageMap.maxByOrNull { it.value }?.key
        )
    }

    /**
     * Get current usage map (for debugging)
     */
    fun getCurrentUsageMap(): Map<String, Long> {
        return usageMap.toMap()
    }

    /**
     * Check if session is active
     */
    fun isSessionActive(): Boolean = isSessionActive

    // Private helper methods

    private fun resetSession() {
        isSessionActive = false
        sessionStartTime = 0L
        sessionId = null
        currentApp = null
        currentAppStartTime = 0L
        usageMap.clear()
    }

    private fun generateSessionId(timestamp: Long): String {
        val dateFormat = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
        return dateFormat.format(java.util.Date(timestamp))
    }

    private fun saveSessionToFirebase(
        sessionId: String,
        startTime: Long,
        endTime: Long,
        usageMap: Map<String, Long>
    ) {
        try {
            val database = FirebaseDatabase.getInstance()
            val sessionRef = database.getReference("app_usage_sessions/$userId/$sessionId")

            // Prepare session data
            val sessionData = mutableMapOf<String, Any>(
                "startTime" to startTime,
                "endTime" to endTime,
                "duration" to (endTime - startTime),
                "timestamp" to java.text.SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss_SSS",
                    java.util.Locale.getDefault()
                ).format(java.util.Date(startTime))
            )

            // Add app usage data
            val appsData = mutableMapOf<String, Any>()          // Long → Any
            usageMap.forEach { (pkg, duration) ->
                if (duration > 0) {
                    appsData[sanitizeKey(pkg)] = mapOf(         // Long → Map
                        "packageName" to pkg,                   // 원본 보존
                        "duration"    to duration
                    )
                }
            }
            sessionData["apps"] = appsData

            // Save to Firebase
            sessionRef.setValue(sessionData)
                .addOnSuccessListener {
                    Log.d(TAG, "✅ Session saved to Firebase: $sessionId with ${appsData.size} apps")
                    Log.d(TAG, "Apps: ${appsData.keys.take(5)}")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "❌ Failed to save session: ${e.message}", e)
                }

        } catch (e: Exception) {
            Log.e(TAG, "Error saving to Firebase: ${e.message}", e)
        }
    }

    private fun sanitizeKey(key: String): String {
        // Firebase keys cannot contain . $ # [ ] /
        return key.replace(".", "_")
            .replace("$", "_")
            .replace("#", "_")
            .replace("[", "_")
            .replace("]", "_")
            .replace("/", "_")
    }

    private fun formatTime(millis: Long): String {
        val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(millis))
    }

    private fun formatDuration(millis: Long): String {
        val seconds = millis / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        return String.format("%02d:%02d:%02d", hours, minutes % 60, seconds % 60)
    }

    // Data class for session statistics
    data class SessionStats(
        val sessionId: String,
        val startTime: Long,
        val duration: Long,
        val appCount: Int,
        val totalUsage: Long,
        val topApp: String?
    )
}