package com.example.applock

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
import android.app.ActivityOptions


/**
 * UsageRecordActivity - Firebase 기반 사용 기록 표시
 * 새로운 다크 테마 UI에 맞춤
 */
class UsageRecordActivity : AppCompatActivity() {

    private lateinit var dateTextView: TextView
    private lateinit var totalUsageText: TextView
    private lateinit var emptyStateContainer: View
    private lateinit var prevDateButton: ImageView
    private lateinit var nextDateButton: ImageView
    private lateinit var sessionsRecyclerView: RecyclerView

    private lateinit var sessionAdapter: SessionAdapter
    private var currentDate = Calendar.getInstance()
    private lateinit var userId: String

    private val labelCache = mutableMapOf<String, String>()
    private val negotiationResults = mutableMapOf<Long, NegotiationResult>()  // sessionStartTime -> result

    data class NegotiationResult(
        val judgment: String,   // "승인" or "거부"
        val minutes: Int
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            setContentView(R.layout.activity_usage_record)

            userId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)

            initializeViews()
            setupBottomNav()
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
        totalUsageText = findViewById(R.id.totalUsageText)
        emptyStateContainer = findViewById(R.id.emptyStateContainer)
        prevDateButton = findViewById(R.id.prevDateButton)
        nextDateButton = findViewById(R.id.nextDateButton)
        sessionsRecyclerView = findViewById(R.id.sessionsRecyclerView)
    }

    private fun setupBottomNav() {
        // 기록 탭 활성화 (현재 화면)
        findViewById<ImageView>(R.id.navRecordIcon)?.setColorFilter(Color.WHITE)
        findViewById<TextView>(R.id.navRecordText)?.setTextColor(Color.WHITE)

        findViewById<LinearLayout>(R.id.navLock)?.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            val options = ActivityOptions.makeCustomAnimation(
                this,
                R.anim.slide_in_left,
                R.anim.slide_out_right
            )
            startActivity(intent, options.toBundle())
            finish()
        }

        findViewById<LinearLayout>(R.id.navChat)?.setOnClickListener {
            val intent = Intent(this, ChatbotActivity::class.java)
            val options = ActivityOptions.makeCustomAnimation(
                this,
                R.anim.slide_in_left,
                R.anim.slide_out_right
            )
            startActivity(intent, options.toBundle())
            finish()
        }
    }



    private fun setupDateNavigation() {
        updateDateDisplay()

        prevDateButton.setOnClickListener {
            currentDate.add(Calendar.DAY_OF_MONTH, -1)
            updateDateDisplay()
            loadUsageData()
        }

        nextDateButton.setOnClickListener {
            val today = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

            val cur = (currentDate.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

            // currentDate < today 일 때만 다음으로 이동 가능
            if (cur.before(today)) {
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
        val sdf = SimpleDateFormat("yyyy년 M월 d일(E)", Locale.KOREAN)
        dateTextView.text = sdf.format(currentDate.time)

        // 오늘 날짜 (00:00 기준)
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        // 현재 선택된 날짜 (00:00 기준)
        val cur = (currentDate.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        // ▶ 다음 날짜 버튼 상태 갱신
        val canGoNext = cur.before(today)
        nextDateButton.isEnabled = canGoNext
        nextDateButton.alpha = if (canGoNext) 1f else 0.3f
    }


    /**
     * Firebase에서 사용 기록 로드
     */
    private fun loadUsageData() {
        Log.d("UsageRecord", "📊 Loading data for: ${getDateString(currentDate)}")

        val database = FirebaseDatabase.getInstance()

        // 1) 협상 결과 먼저 로드
        loadNegotiationResults {
            // 2) 세션 데이터 로드
            val usageRef = database.getReference("app_usage_sessions/$userId")
            usageRef.addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val sessions = parseUsageSessions(snapshot)

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

                    processAndDisplaySessions(filteredSessions)
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e("UsageRecord", "Failed to load sessions: ${error.message}")
                    showEmptyState()
                }
            })
        }
    }

    /**
     * Firebase에서 협상 결과 로드
     */
    private fun loadNegotiationResults(onComplete: () -> Unit) {
        val negRef = FirebaseDatabase.getInstance()
            .getReference("negotiation_results/$userId")

        negRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                negotiationResults.clear()
                snapshot.children.forEach { child ->
                    try {
                        val sessionStartTime = child.key?.toLongOrNull() ?: return@forEach
                        val judgment = child.child("judgment").getValue(String::class.java) ?: return@forEach
                        val minutes = child.child("minutes").getValue(Int::class.java) ?: 0
                        negotiationResults[sessionStartTime] = NegotiationResult(judgment, minutes)
                    } catch (e: Exception) {
                        Log.e("UsageRecord", "Error parsing negotiation result: ${e.message}")
                    }
                }
                Log.d("UsageRecord", "🏅 Loaded ${negotiationResults.size} negotiation results")
                onComplete()
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("UsageRecord", "Failed to load negotiation results: ${error.message}")
                onComplete() // 실패해도 세션은 표시
            }
        })
    }

    /**
     * Firebase 세션 데이터 파싱
     */
    private fun parseUsageSessions(snapshot: DataSnapshot): List<SessionWithApps> {
        val sessions = mutableListOf<SessionWithApps>()

        snapshot.children.forEach { sessionSnapshot ->
            try {
                val startTime = sessionSnapshot.child("startTime").getValue(Long::class.java) ?: 0L
                val endTime = sessionSnapshot.child("endTime").getValue(Long::class.java) ?: 0L
                val duration = sessionSnapshot.child("duration").getValue(Long::class.java) ?: 0L

                val appsMap = mutableMapOf<String, Int>()
                val appsSnapshot = sessionSnapshot.child("apps")

                appsSnapshot.children.forEach { appEntry ->
                    val key = appEntry.key ?: return@forEach

                    val packageName: String
                    val durationMillis: Long

                    val storedPkg = appEntry.child("packageName").getValue(String::class.java)

                    if (storedPkg != null) {
                        packageName = storedPkg
                        durationMillis = appEntry.child("duration").getValue(Long::class.java) ?: 0L
                    } else {
                        packageName = key
                        durationMillis = appEntry.getValue(Long::class.java) ?: 0L
                    }

                    val durationMinutes = (durationMillis / 1000 / 60).toInt()

                    if (durationMinutes > 0 && !isSystemApp(packageName)) {
                        val displayName = getAppDisplayName(packageName)
                        appsMap[displayName] = (appsMap[displayName] ?: 0) + durationMinutes
                    }
                }

                if (appsMap.isNotEmpty() && startTime > 0 && endTime > 0) {
                    sessions.add(
                        SessionWithApps(
                            startTime = startTime,
                            endTime = endTime,
                            duration = (duration / 1000).toFloat(),
                            apps = appsMap
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e("UsageRecord", "Error parsing session: ${e.message}")
            }
        }

        return sessions
    }

    /**
     * 세션 데이터 처리 및 UI 업데이트
     */
    private fun processAndDisplaySessions(sessions: List<SessionWithApps>) {
        var totalMinutes = 0
        val validSessions = mutableListOf<SessionWithApps>()

        for (session in sessions) {
            val sessionMinutes = (session.duration / 60).toInt()
            if (sessionMinutes < 10) continue

            validSessions.add(session)
            totalMinutes += sessionMinutes
        }

        runOnUiThread {
            if (validSessions.isNotEmpty()) {
                emptyStateContainer.visibility = View.GONE
                sessionsRecyclerView.visibility = View.VISIBLE

                // 총 사용 시간 포맷
                if (totalMinutes >= 60) {
                    val hours = totalMinutes / 60
                    val mins = totalMinutes % 60
                    totalUsageText.text = if (mins > 0) "${hours}시간 ${mins}분" else "${hours}시간"
                } else {
                    totalUsageText.text = "${totalMinutes}분"
                }

                sessionAdapter.submitList(validSessions.sortedByDescending { it.startTime })
            } else {
                showEmptyState()
            }
        }
    }

    private fun showEmptyState() {
        runOnUiThread {
            emptyStateContainer.visibility = View.VISIBLE
            sessionsRecyclerView.visibility = View.GONE
            totalUsageText.text = "0분"
        }
    }

    private fun getAppDisplayName(packageName: String): String {
        labelCache[packageName]?.let { return it }

        val originalPackage = packageName.replace("_", ".")

        val label = try {
            val appInfo = packageManager.getApplicationInfo(originalPackage, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            originalPackage.substringAfterLast(".").replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale.getDefault())
                else it.toString()
            }
        }

        labelCache[packageName] = label
        return label
    }

    private fun isSystemApp(packageName: String): Boolean {
        val systemApps = listOf(
            "com.android.systemui", "com.android.launcher3",
            "com.sec.android.app.launcher", "com.samsung.android.app.aodservice",
            "com.android.settings", "com.sec.android.app.settings",
            "com.google.android.apps.nexuslauncher", "com.samsung.android.app.launcher",
            "com.samsung.android.honeyboard", "com.navercorp.android.smartboard",
            "com.samsung.android.dialer", "com.example.applock"
        )
        if (systemApps.any { packageName.contains(it) }) return true
        if (packageName.contains("launcher") || packageName.contains("home") ||
            packageName == "android" || packageName == "oneui") return true
        return false
    }

    private fun getDateString(calendar: Calendar): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(calendar.time)
    }

    private fun getDateStartMillis(calendar: Calendar): Long {
        val cal = calendar.clone() as Calendar
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun getDateEndMillis(calendar: Calendar): Long {
        val cal = calendar.clone() as Calendar
        cal.set(Calendar.HOUR_OF_DAY, 23); cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59); cal.set(Calendar.MILLISECOND, 999)
        return cal.timeInMillis
    }

    // ========== RecyclerView Adapter ==========

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

    // ========== ViewHolder ==========

    inner class SessionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val sessionCardRoot: LinearLayout = itemView.findViewById(R.id.sessionCardRoot)
        private val sessionTimeText: TextView = itemView.findViewById(R.id.sessionTimeText)
        private val sessionDurationText: TextView = itemView.findViewById(R.id.sessionDurationText)
        private val approvalBadge: TextView = itemView.findViewById(R.id.approvalBadge)
        private val appsContainer: LinearLayout = itemView.findViewById(R.id.appsContainer)
        private val moreAppsText: TextView = itemView.findViewById(R.id.moreAppsText)

        fun bind(session: SessionWithApps) {
            // 세션 카드 클릭 → 채팅으로 이동
            sessionCardRoot.setOnClickListener {
                navigateToChat(session)
            }

            // 시간 범위
            val timeFormat = SimpleDateFormat("a hh:mm", Locale.KOREAN)
            val startStr = timeFormat.format(Date(session.startTime))
            val endStr = timeFormat.format(Date(session.endTime))
            sessionTimeText.text = "$startStr - $endStr"

            // 총 세션 시간
            val totalMinutes = (session.duration / 60).toInt()
            sessionDurationText.text = if (totalMinutes >= 60) {
                "${totalMinutes / 60}시간 ${totalMinutes % 60}분"
            } else {
                "${totalMinutes}분"
            }

            // 승인/거절 배지 표시
            val result = negotiationResults[session.startTime]
            if (result != null) {
                approvalBadge.visibility = View.VISIBLE
                if (result.judgment == "승인") {
                    approvalBadge.text = "승인 ${result.minutes}분"
                    approvalBadge.setTextColor(Color.parseColor("#55A96F"))
                    approvalBadge.setBackgroundResource(R.drawable.bg_badge_approved)
                    sessionCardRoot.setBackgroundResource(R.drawable.bg_session_approved)
                } else {
                    approvalBadge.text = "거부"
                    approvalBadge.setTextColor(Color.parseColor("#C84949"))
                    approvalBadge.setBackgroundResource(R.drawable.bg_badge_rejected)
                    sessionCardRoot.setBackgroundResource(R.drawable.bg_session_rejected)
                }
            } else {
                approvalBadge.visibility = View.GONE
                sessionCardRoot.setBackgroundResource(R.drawable.bg_session_default)
            }

            // 앱 목록 (상위 3개만 표시)
            appsContainer.removeAllViews()
            val sortedApps = session.apps.entries.sortedByDescending { it.value }
            val topApps = sortedApps.take(3)
            val remainingCount = sortedApps.size - 3

            topApps.forEach { (appName, appMinutes) ->
                val appRow = LayoutInflater.from(itemView.context)
                    .inflate(R.layout.item_app_usage_row, appsContainer, false)

                appRow?.let {
                    it.findViewById<TextView>(R.id.appNameText)?.text = appName
                    it.findViewById<TextView>(R.id.appTimeText)?.text = "${appMinutes}분"
                    appsContainer.addView(it)
                } ?: run {
                    // fallback: item_app_usage_row가 없으면 동적 생성
                    val row = LinearLayout(itemView.context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, 6, 0, 6)
                    }
                    val nameView = TextView(itemView.context).apply {
                        text = appName
                        setTextColor(Color.parseColor("#CCCCCC"))
                        textSize = 13f
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    val timeView = TextView(itemView.context).apply {
                        text = "${appMinutes}분"
                        setTextColor(Color.parseColor("#999999"))
                        textSize = 13f
                    }
                    row.addView(nameView)
                    row.addView(timeView)
                    appsContainer.addView(row)
                }
            }

            // "그 외 n개" 표시
            if (remainingCount > 0) {
                moreAppsText.visibility = View.VISIBLE
                moreAppsText.text = "그 외 ${remainingCount}개"
            } else {
                moreAppsText.visibility = View.GONE
            }
        }
    }

    // ========== 세션 → 채팅 이동 ==========

    private fun navigateToChat(session: SessionWithApps) {
        val appsText = session.apps.entries
            .sortedByDescending { it.value }
            .take(5)
            .joinToString(", ") { "${it.key} ${it.value}분" }

        val durationMin = (session.duration / 60).toInt()
        val timeFormat = SimpleDateFormat("a hh:mm", Locale.KOREAN)
        val startStr = timeFormat.format(Date(session.startTime))
        val endStr = timeFormat.format(Date(session.endTime))

        val sessionSummary = "[$startStr ~ $endStr] ${durationMin}분 사용\n사용 앱: $appsText"

        val intent = Intent(this, ChatbotActivity::class.java).apply {
            putExtra("SESSION_SUMMARY", sessionSummary)
            putExtra("SESSION_START_TIME", session.startTime)
            putExtra("SESSION_END_TIME", session.endTime)
            putExtra("SESSION_DURATION_MIN", durationMin)
        }
        startActivity(intent)
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
