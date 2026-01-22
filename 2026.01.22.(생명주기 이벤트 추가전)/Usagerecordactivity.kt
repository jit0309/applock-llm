package com.example.applock

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.database.*
import java.text.SimpleDateFormat
import java.util.*

class UsageRecordActivity : AppCompatActivity() {

    private lateinit var dateTextView: TextView
    private lateinit var totalUsageTextView: TextView
    private lateinit var sessionCountTextView: TextView
    private lateinit var mainAppsTextView: TextView
    private lateinit var emptyStateContainer: View
    private lateinit var prevDateButton: ImageView
    private lateinit var nextDateButton: ImageView
    private lateinit var sessionsRecyclerView: RecyclerView

    private lateinit var usageStatsManager: UsageStatsManager
    private lateinit var sessionAdapter: SessionAdapter
    private var currentDate = Calendar.getInstance()
    private lateinit var userId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            setContentView(R.layout.activity_usage_record)

            userId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

            if (!hasUsageStatsPermission()) {
                showPermissionDialog()
                return
            }

            initializeViews()
            setupDateNavigation()
            setupRecyclerView()
            loadUsageData()

        } catch (e: Exception) {
            Log.e("UsageRecord", "Error in onCreate", e)
            e.printStackTrace()
        }
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun showPermissionDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("권한 필요")
            .setMessage("사용 기록을 보려면 '사용 기록 접근' 권한이 필요합니다.")
            .setPositiveButton("설정으로 이동") { _, _ ->
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                finish()
            }
            .setNegativeButton("취소") { _, _ ->
                finish()
            }
            .show()
    }

    private fun initializeViews() {
        dateTextView = findViewById(R.id.dateTextView)
        totalUsageTextView = findViewById(R.id.totalUsageText)
        sessionCountTextView = findViewById(R.id.sessionCountText)
        mainAppsTextView = findViewById(R.id.mainAppsText)
        emptyStateContainer = findViewById(R.id.emptyStateContainer)
        prevDateButton = findViewById(R.id.prevDateButton)
        nextDateButton = findViewById(R.id.nextDateButton)
        sessionsRecyclerView = findViewById(R.id.sessionsRecyclerView)

        findViewById<View>(R.id.homeButton).setOnClickListener {
            finish()
        }

        findViewById<View>(R.id.chatButton).setOnClickListener {
            try {
                startActivity(Intent(this, ChatbotActivity::class.java))
            } catch (e: Exception) {
                Log.e("UsageRecord", "ChatbotActivity not found", e)
            }
        }
    }

    private fun setupDateNavigation() {
        prevDateButton.setOnClickListener {
            currentDate.add(Calendar.DAY_OF_MONTH, -1)
            updateDateDisplay()
            loadUsageData()
        }

        nextDateButton.setOnClickListener {
            val tomorrow = Calendar.getInstance()
            tomorrow.add(Calendar.DAY_OF_MONTH, 1)

            if (currentDate.before(tomorrow)) {
                currentDate.add(Calendar.DAY_OF_MONTH, 1)
                updateDateDisplay()
                loadUsageData()
            }
        }
    }

    private fun setupRecyclerView() {
        sessionAdapter = SessionAdapter()
        sessionsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@UsageRecordActivity)
            adapter = sessionAdapter
        }
    }

    private fun updateDateDisplay() {
        val dateFormat = SimpleDateFormat("yyyy년 M월 d일 E", Locale.KOREAN)
        dateTextView.text = dateFormat.format(currentDate.time)
    }

    private fun loadUsageData() {
        updateDateDisplay()
        Log.d("UsageRecord", "Loading data for: ${getDateString(currentDate)}")

        val database = FirebaseDatabase.getInstance()
        val countdownRef = database.getReference("countdown_sessions/$userId")

        countdownRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val sessions = parseCountdownSessions(snapshot)

                // 날짜 필터링
                val targetDateStart = getDateStartMillis(currentDate)
                val targetDateEnd = getDateEndMillis(currentDate)
                val filteredSessions = sessions.filter {
                    it.startTime in targetDateStart..targetDateEnd
                }

                if (filteredSessions.isEmpty()) {
                    showEmptyState()
                    return
                }

                // 각 세션에 대해 앱 사용 통계 로드
                loadAppUsageForSessions(filteredSessions)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("UsageRecord", "Failed to load sessions: ${error.message}")
                showEmptyState()
            }
        })
    }

    private fun loadAppUsageForSessions(sessions: List<SessionInfo>) {
        val sessionsWithApps = mutableListOf<SessionWithApps>()
        var totalMinutes = 0
        val globalAppUsageMap = mutableMapOf<String, Int>()

        for (session in sessions) {
            val sessionMinutes = (session.duration / 60).toInt()

            // 10분 미만 세션 제외
            if (sessionMinutes < 10) continue

            // UsageStatsManager로 세션 시간대의 앱 사용 통계 가져오기
            val appUsageMap = getAppUsageInTimeRange(session.startTime, session.endTime)

            sessionsWithApps.add(SessionWithApps(
                startTime = session.startTime,
                endTime = session.endTime,
                duration = session.duration,
                apps = appUsageMap
            ))

            totalMinutes += sessionMinutes
            appUsageMap.forEach { (app, minutes) ->
                globalAppUsageMap[app] = (globalAppUsageMap[app] ?: 0) + minutes
            }
        }

        // UI 업데이트
        updateUI(totalMinutes, sessionsWithApps.size, globalAppUsageMap, sessionsWithApps)
    }

    private fun getAppUsageInTimeRange(startTime: Long, endTime: Long): Map<String, Int> {
        val appUsageMap = mutableMapOf<String, Int>()

        try {
            // UsageEvents API 사용 (더 정확함)
            val usageEvents = usageStatsManager.queryEvents(startTime, endTime)

            var currentApp: String? = null
            var currentStartTime: Long? = null

            while (usageEvents.hasNextEvent()) {
                val event = android.app.usage.UsageEvents.Event()
                usageEvents.getNextEvent(event)

                // 시스템 앱 및 자기 자신 제외
                val pkgName = event.packageName ?: continue  // null이면 건너뛰기
                if (isSystemApp(pkgName) || pkgName == packageName) {  // 자기 자신도 제외!
                    continue
                }

                when (event.eventType) {
                    android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED,
                    android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                        // 이전 앱이 있으면 시간 계산
                        if (currentApp != null && currentStartTime != null) {
                            val duration = (event.timeStamp - currentStartTime) / 1000 / 60  // 분
                            if (duration > 0) {
                                val appName = getAppDisplayName(currentApp!!)
                                appUsageMap[appName] = (appUsageMap[appName] ?: 0) + duration.toInt()
                            }
                        }
                        // 새 앱 시작
                        currentApp = pkgName
                        currentStartTime = event.timeStamp
                    }

                    android.app.usage.UsageEvents.Event.ACTIVITY_PAUSED,
                    android.app.usage.UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                        // 현재 앱 종료
                        if (currentApp == event.packageName && currentStartTime != null) {
                            val duration = (event.timeStamp - currentStartTime) / 1000 / 60
                            if (duration > 0) {
                                val appName = getAppDisplayName(currentApp!!)
                                appUsageMap[appName] = (appUsageMap[appName] ?: 0) + duration.toInt()
                            }
                            currentApp = null
                            currentStartTime = null
                        }
                    }
                }
            }

            // 세션 끝까지 사용 중이던 앱 처리
            if (currentApp != null && currentStartTime != null) {
                val duration = (endTime - currentStartTime) / 1000 / 60
                if (duration > 0) {
                    val appName = getAppDisplayName(currentApp!!)
                    appUsageMap[appName] = (appUsageMap[appName] ?: 0) + duration.toInt()
                }
            }

            Log.d("UsageRecord", "Session apps: $appUsageMap")

        } catch (e: Exception) {
            Log.e("UsageRecord", "Error getting app usage: ${e.message}")
        }

        return appUsageMap
    }

    private fun parseCountdownSessions(snapshot: DataSnapshot): List<SessionInfo> {
        val sessions = mutableListOf<SessionInfo>()
        var currentStartTime: Long? = null
        var currentStartAvailableTime: Float? = null

        val sortedSessions = snapshot.children.sortedBy {
            parseFirebaseTimestamp(it.key ?: "")
        }

        for (sessionSnapshot in sortedSessions) {
            try {
                val sessionData = sessionSnapshot.value as? Map<*, *> ?: continue
                val type = sessionData["Type"] as? String ?: continue
                val timestamp = parseFirebaseTimestamp(sessionSnapshot.key ?: "")
                val availableTimeStr = sessionData["AvailableTime"] as? String ?: "00:00:00"
                val availableTime = parseTimeString(availableTimeStr)

                when (type) {
                    "P_START", "START" -> {
                        currentStartTime = timestamp
                        currentStartAvailableTime = availableTime
                    }
                    "P_END", "END" -> {
                        if (currentStartTime != null && currentStartAvailableTime != null) {
                            val duration = currentStartAvailableTime - availableTime
                            if (duration > 0) {
                                sessions.add(SessionInfo(
                                    startTime = currentStartTime,
                                    endTime = timestamp,
                                    duration = duration
                                ))
                            }
                        }
                        currentStartTime = null
                        currentStartAvailableTime = null
                    }
                }
            } catch (e: Exception) {
                Log.e("UsageRecord", "Error parsing session: ${e.message}")
            }
        }

        return sessions
    }

    private fun updateUI(
        totalMinutes: Int,
        sessionCount: Int,
        appUsageMap: Map<String, Int>,
        sessions: List<SessionWithApps>
    ) {
        runOnUiThread {
            if (sessionCount > 0) {
                emptyStateContainer.visibility = View.GONE
                sessionsRecyclerView.visibility = View.VISIBLE

                totalUsageTextView.text = "${totalMinutes}분"
                sessionCountTextView.text = "${sessionCount}개"

                val topApp = appUsageMap.maxByOrNull { it.value }
                if (topApp != null) {
                    mainAppsTextView.text = "${topApp.key}\n(${topApp.value}분)"
                    mainAppsTextView.textSize = 14f
                } else {
                    mainAppsTextView.text = "📱"
                    mainAppsTextView.textSize = 24f
                }

                // 세션 목록 업데이트 (최신순)
                sessionAdapter.submitList(sessions.reversed())
            } else {
                showEmptyState()
            }
        }
    }

    private fun showEmptyState() {
        runOnUiThread {
            emptyStateContainer.visibility = View.VISIBLE
            sessionsRecyclerView.visibility = View.GONE
            totalUsageTextView.text = "0분"
            sessionCountTextView.text = "0개"
            mainAppsTextView.text = "-"
        }
    }

    private fun getAppDisplayName(packageName: String): String {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName.substringAfterLast(".").replaceFirstChar {
                if (it.isLowerCase()) it.titlecase() else it.toString()
            }
        }
    }

    private fun isSystemApp(packageName: String): Boolean {
        // 시스템 앱 및 런처 필터링
        val systemApps = listOf(
            "com.android.systemui",
            "com.android.launcher3",
            "com.sec.android.app.launcher",
            "com.samsung.android.app.aodservice",
            "com.android.settings",
            "com.sec.android.app.settings",
            "com.google.android.apps.nexuslauncher"
        )

        if (systemApps.any { packageName.contains(it) }) {
            return true
        }

        // 런처 관련 키워드
        if (packageName.contains("launcher") ||
            packageName.contains("home") ||
            packageName == "android") {
            return true
        }

        return false
    }

    private fun parseFirebaseTimestamp(timestamp: String): Long {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss_SSS", Locale.getDefault())
            sdf.parse(timestamp)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    private fun parseTimeString(timeStr: String): Float {
        return try {
            val parts = timeStr.split(":")
            if (parts.size == 3) {
                val hours = parts[0].toFloat()
                val minutes = parts[1].toFloat()
                val seconds = parts[2].toFloat()
                hours * 3600 + minutes * 60 + seconds
            } else {
                0f
            }
        } catch (e: Exception) {
            0f
        }
    }

    private fun getDateString(calendar: Calendar): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        return dateFormat.format(calendar.time)
    }

    private fun getDateStartMillis(calendar: Calendar): Long {
        val cal = calendar.clone() as Calendar
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun getDateEndMillis(calendar: Calendar): Long {
        val cal = calendar.clone() as Calendar
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        return cal.timeInMillis
    }

    // RecyclerView Adapter
    inner class SessionAdapter : RecyclerView.Adapter<SessionViewHolder>() {
        private var sessions = listOf<SessionWithApps>()

        fun submitList(newSessions: List<SessionWithApps>) {
            sessions = newSessions
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SessionViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_session_usage_record, parent, false)
            return SessionViewHolder(view)
        }

        override fun onBindViewHolder(holder: SessionViewHolder, position: Int) {
            holder.bind(sessions[position])
        }

        override fun getItemCount() = sessions.size
    }

    // ViewHolder
    inner class SessionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val sessionTimeText: TextView = itemView.findViewById(R.id.sessionTimeText)
        private val sessionDurationText: TextView = itemView.findViewById(R.id.sessionDurationText)
        private val appsContainer: LinearLayout = itemView.findViewById(R.id.appsContainer)

        fun bind(session: SessionWithApps) {
            // 시간 표시
            val timeFormat = SimpleDateFormat("a hh:mm", Locale.KOREAN)
            val startTimeStr = timeFormat.format(Date(session.startTime))
            val endTimeStr = timeFormat.format(Date(session.endTime))
            sessionTimeText.text = "$startTimeStr - $endTimeStr"

            // 총 시간 표시
            val minutes = (session.duration / 60).toInt()
            sessionDurationText.text = "${minutes}분"

            // 앱 목록 표시
            appsContainer.removeAllViews()
            session.apps.entries.sortedByDescending { it.value }.forEach { (appName, appMinutes) ->
                val appView = LayoutInflater.from(itemView.context)
                    .inflate(android.R.layout.simple_list_item_2, appsContainer, false)

                appView.findViewById<TextView>(android.R.id.text1).apply {
                    text = appName
                    textSize = 14f
                    setTextColor(Color.parseColor("#000000"))
                }

                appView.findViewById<TextView>(android.R.id.text2).apply {
                    text = "${appMinutes}분"
                    textSize = 12f
                    setTextColor(Color.parseColor("#666666"))
                }

                appsContainer.addView(appView)
            }
        }
    }

    data class SessionInfo(
        val startTime: Long,
        val endTime: Long,
        val duration: Float
    )

    data class SessionWithApps(
        val startTime: Long,
        val endTime: Long,
        val duration: Float,
        val apps: Map<String, Int>
    )

    override fun onResume() {
        super.onResume()
        if (hasUsageStatsPermission() && ::usageStatsManager.isInitialized) {
            loadUsageData()
        }
    }
}