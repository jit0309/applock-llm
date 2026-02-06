package com.example.applock

import android.content.Context
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

/**
 * Modified UsageRecordActivity - Uses real-time AccessibilityService data from Firebase
 * NO UsageStatsManager needed!
 */
class UsageRecordActivity : AppCompatActivity() {

    private lateinit var dateTextView: TextView
    private lateinit var totalUsageTextView: TextView
    private lateinit var sessionCountTextView: TextView
    private lateinit var mainAppsTextView: TextView
    private lateinit var emptyStateContainer: View
    private lateinit var prevDateButton: ImageView
    private lateinit var nextDateButton: ImageView
    private lateinit var sessionsRecyclerView: RecyclerView

    private lateinit var sessionAdapter: SessionAdapter
    private var currentDate = Calendar.getInstance()
    private lateinit var userId: String

    private val labelCache = mutableMapOf<String, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            setContentView(R.layout.activity_usage_record)

            userId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)

            initializeViews()
            setupDateNavigation()
            setupRecyclerView()
            loadUsageData()

        } catch (e: Exception) {
            Log.e("UsageRecord", "Error in onCreate", e)
            e.printStackTrace()
        }
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
                startActivity(android.content.Intent(this, ChatbotActivity::class.java))
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

    /**
     * Load usage data from Firebase (app_usage_sessions)
     * NO UsageStatsManager used!
     */
    private fun loadUsageData() {
        updateDateDisplay()
        Log.d("UsageRecord", "📊 Loading data for: ${getDateString(currentDate)}")

        val database = FirebaseDatabase.getInstance()
        val usageRef = database.getReference("app_usage_sessions/$userId")

        usageRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val sessions = parseUsageSessions(snapshot)

                // Filter sessions for the current date
                val targetDateStart = getDateStartMillis(currentDate)
                val targetDateEnd = getDateEndMillis(currentDate)

                val filteredSessions = sessions.filter {
                    it.startTime in targetDateStart..targetDateEnd
                }

                Log.d("UsageRecord", "Found ${filteredSessions.size} sessions for selected date")

                if (filteredSessions.isEmpty()) {
                    showEmptyState()
                    return
                }

                // Process sessions for UI display
                processAndDisplaySessions(filteredSessions)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("UsageRecord", "Failed to load sessions: ${error.message}")
                showEmptyState()
            }
        })
    }

    /**
     * Parse Firebase usage sessions data structure:
     * app_usage_sessions/{userId}/{sessionId}/{
     *   startTime, endTime, duration,
     *   apps: { packageName: durationMillis, ... }
     * }
     */
    private fun parseUsageSessions(snapshot: DataSnapshot): List<SessionWithApps> {
        val sessions = mutableListOf<SessionWithApps>()

        snapshot.children.forEach { sessionSnapshot ->
            try {
                val startTime = sessionSnapshot.child("startTime").getValue(Long::class.java) ?: 0L
                val endTime = sessionSnapshot.child("endTime").getValue(Long::class.java) ?: 0L
                val duration = sessionSnapshot.child("duration").getValue(Long::class.java) ?: 0L

                // Parse apps usage map
                val appsMap = mutableMapOf<String, Int>()
                val appsSnapshot = sessionSnapshot.child("apps")

                appsSnapshot.children.forEach { appEntry ->
                    val key = appEntry.key ?: return@forEach

                    // 새 형식 / 이전 형식 모두 처리
                    val packageName: String
                    val durationMillis: Long

                    val storedPkg = appEntry.child("packageName")
                        .getValue(String::class.java)

                    if (storedPkg != null) {
                        // 새 형식: { packageName: "com.kakao.talk", duration: 123456 }
                        packageName    = storedPkg
                        durationMillis = appEntry.child("duration")
                            .getValue(Long::class.java) ?: 0L
                    } else {
                        // 이전 형식: key = "com_kakao_talk", value = 123456
                        packageName    = key
                        durationMillis = appEntry.getValue(Long::class.java) ?: 0L
                    }

                    val durationMinutes = (durationMillis / 1000 / 60).toInt()

                    if (durationMinutes > 0 && !isSystemApp(packageName)) {
                        val displayName = getAppDisplayName(packageName)
                        appsMap[displayName] = (appsMap[displayName] ?: 0) + durationMinutes
                    }
                }

                // Only add sessions with actual app usage
                if (appsMap.isNotEmpty() && startTime > 0 && endTime > 0) {
                    sessions.add(
                        SessionWithApps(
                            startTime = startTime,
                            endTime = endTime,
                            duration = (duration / 1000).toFloat(), // Convert to seconds
                            apps = appsMap
                        )
                    )
                    Log.d("UsageRecord", "✅ Parsed session: ${appsMap.size} apps, duration: ${duration/1000}s")
                }
            } catch (e: Exception) {
                Log.e("UsageRecord", "Error parsing session: ${e.message}")
            }
        }

        return sessions
    }

    /**
     * Process sessions and update UI
     */
    private fun processAndDisplaySessions(sessions: List<SessionWithApps>) {
        var totalMinutes = 0
        val globalAppUsageMap = mutableMapOf<String, Int>()
        val validSessions = mutableListOf<SessionWithApps>()

        for (session in sessions) {
            val sessionMinutes = (session.duration / 60).toInt()

            // Filter out very short sessions (< 10 minutes)
            if (sessionMinutes < 10) {
                Log.d("UsageRecord", "⚠️ Skipping short session: ${sessionMinutes}min")
                continue
            }

            validSessions.add(session)
            totalMinutes += sessionMinutes

            // Accumulate app usage globally
            session.apps.forEach { (app, minutes) ->
                globalAppUsageMap[app] = (globalAppUsageMap[app] ?: 0) + minutes
            }
        }

        // Update UI
        updateUI(totalMinutes, validSessions.size, globalAppUsageMap, validSessions)
    }

    /**
     * Update UI with processed data
     */
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

                // Display total usage time
                totalUsageTextView.text = "${totalMinutes}분"
                sessionCountTextView.text = "${sessionCount}개"

                // Display top app
                val topApp = appUsageMap.maxByOrNull { it.value }
                if (topApp != null) {
                    mainAppsTextView.text = "${topApp.key}\n(${topApp.value}분)"
                    mainAppsTextView.textSize = 14f
                } else {
                    mainAppsTextView.text = "📱"
                    mainAppsTextView.textSize = 24f
                }

                // Update session list (most recent first)
                sessionAdapter.submitList(sessions.sortedByDescending { it.startTime })

                Log.d("UsageRecord", """
                    📊 UI Updated:
                    Total: ${totalMinutes}min
                    Sessions: $sessionCount
                    Top App: ${topApp?.key} (${topApp?.value}min)
                """.trimIndent())
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

    /**
     * Get app display name from package name
     */
    private fun getAppDisplayName(packageName: String): String {
        // ① 캐시 조회
        labelCache[packageName]?.let { return it }

        // ② _ → . 복원 (Firebase sanitized key 대응)
        val originalPackage = packageName.replace("_", ".")

        // ③ PackageManager로 Label 조회
        val label = try {
            val appInfo = packageManager.getApplicationInfo(originalPackage, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            // 미설치 앱 등 조회 실패 시 마지막 세그먼트
            originalPackage.substringAfterLast(".").replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault())
                else it.toString()
            }
        }

        // ④ 캐시 저장
        labelCache[packageName] = label
        return label
    }

    /**
     * Check if package is a system app (for filtering display)
     */
    private fun isSystemApp(packageName: String): Boolean {
        val systemApps = listOf(
            "com.android.systemui",
            "com.android.launcher3",
            "com.sec.android.app.launcher",
            "com.samsung.android.app.aodservice",
            "com.android.settings",
            "com.sec.android.app.settings",
            "com.google.android.apps.nexuslauncher",
            "com.samsung.android.app.launcher",
            "com.samsung.android.honeyboard",
            "com.navercorp.android.smartboard",
            "com.samsung.android.dialer",
            "com.example.applock"  // Your own app
        )

        // Check if package matches system apps
        if (systemApps.any { packageName.contains(it) }) {
            return true
        }

        // Check for launcher, home, or android keywords
        if (packageName.contains("launcher") ||
            packageName.contains("home") ||
            packageName == "android" ||
            packageName == "oneui") {
            return true
        }

        return false
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
            // Display session time
            val timeFormat = SimpleDateFormat("a hh:mm", Locale.KOREAN)
            val startTimeStr = timeFormat.format(Date(session.startTime))
            val endTimeStr = timeFormat.format(Date(session.endTime))
            sessionTimeText.text = "$startTimeStr - $endTimeStr"

            // Display total session duration
            val minutes = (session.duration / 60).toInt()
            sessionDurationText.text = "${minutes}분"

            // Display app list sorted by usage time (descending)
            appsContainer.removeAllViews()
            session.apps.entries
                .sortedByDescending { it.value }
                .forEach { (appName, appMinutes) ->
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

    data class SessionWithApps(
        val startTime: Long,
        val endTime: Long,
        val duration: Float,
        val apps: Map<String, Int>
    )

    override fun onResume() {
        super.onResume()
        loadUsageData()
    }
}