package com.example.applock

import android.accessibilityservice.AccessibilityService
import android.app.AppOpsManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.net.Uri
import android.os.PowerManager
import android.Manifest
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.*
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    // UI 요소 선언
    //private lateinit var btnToggleFeature: Button // 타이머 시작/중지 버튼
    private lateinit var textViewCounter: TextView // 적립 시간 표시
    private lateinit var textViewSessionTime: TextView // 세션 타임 표시
    private lateinit var textViewDividedCounter: TextView // 사용 가능 시간 표시
    //    private lateinit var btnViewUsageStats: Button // 사용 시간 보기 버튼
    private lateinit var textViewSubMessage: TextView // 설명 메시지 표시
    private lateinit var wakeLock: PowerManager.WakeLock // WakeLock 객체
    private lateinit var btnPointToggle: Button // 포인트 사용/중지 버튼

    private val WAKELOCK_TAG = "AppLock:WakeLock" // WakeLock 태그(배터리 절전/최적화 등 제외)

    // Timer 인스턴스 추가
    private val timer = Timer.getInstance()
    private var lifecycleScope = CoroutineScope(Dispatchers.Main + Job())

    // 상태 변수
    private var isTimerRunning = false // 타이머 실행 상태
    private var isButtonEnabled = true // 버튼 활성화 상태
    private var sessionStartTime: Long = 0L  // 0으로 초기화

    private lateinit var prefs: SharedPreferences // SharedPreferences 객체

    // 임시 시간
    private lateinit var btnTemporaryTime: Button // 임시 시간 버튼
    private val TEMP_TIME_DURATION = 5f * 60f  // 5분을 초 단위로
    private val LAST_TEMP_TIME_USE_KEY = "last_temp_time_use" // SharedPreferences 키

    // firebase 유저 ID
    private lateinit var userId: String

    companion object {
        private var isServiceRunning = false // 서비스 실행 상태
        private const val NOTIFICATION_PERMISSION_CODE = 100 // 상단바 권한

        // 서비스 상태 설정
        fun setIsServiceRunning(value: Boolean) {
            isServiceRunning = value
        }
    }

    // BroadcastReceiver: 권한 에러 처리
    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.applock.PERMISSION_ERROR") {
                val message = intent.getStringExtra("message")
                showPermissionDialog(message ?: "Required permissions not granted")
            }
        }
    }

    // BroadcastReceiver: 사용 가능한 시간 업데이트
    private val timeUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // UI 업데이트를 보장하기 위해 runOnUiThread 사용
            runOnUiThread {
                if (intent?.action == AppLockMonitorService.ACTION_TIME_UPDATE) {
                    val remainingTime =
                        intent.getFloatExtra(AppLockMonitorService.EXTRA_AVAILABLE_TIME, 0.0f)
                    val isRunning =
                        intent.getBooleanExtra(AppLockMonitorService.EXTRA_IS_RUNNING, false)
                    val currentApp =
                        intent.getStringExtra(AppLockMonitorService.EXTRA_CURRENT_APP) ?: ""
                    // 새로 추가한 EXTRA_TIMER_MODE 값을 가져옴
                    val timerModeString =
                        intent.getStringExtra("EXTRA_TIMER_MODE") ?: TimerMode.IDLE.name
                    Log.d("MainActivity", "BroadcastReceiver: EXTRA_TIMER_MODE = $timerModeString")

                    val currentMode = try {
                        TimerMode.valueOf(timerModeString)
                    } catch (e: Exception) {
                        Log.e(
                            "MainActivity",
                            "BroadcastReceiver: timerMode 파싱 에러 - $timerModeString",
                            e
                        )
                        TimerMode.IDLE
                    }

                    // 모드에 따라 버튼 텍스트 갱신
                    when (currentMode) {
                        TimerMode.COUNT_UP -> {
                            Log.d(
                                "MainActivity",
                                "BroadcastReceiver: 모드가 COUNT_UP입니다. 버튼 텍스트를 '포인트 사용'으로 변경"
                            )
                            btnPointToggle.text = "포인트 사용"
                        }

                        TimerMode.COUNT_DOWN, TimerMode.IDLE -> {
                            Log.d(
                                "MainActivity",
                                "BroadcastReceiver: 모드가 $currentMode 입니다. 버튼 텍스트를 '적립 시작'으로 변경"
                            )
                            btnPointToggle.text = "적립 시작"
                        }
                    }

                    updateTimerDisplay(remainingTime, isRunning)
                    Log.d(
                        "MainActivity",
                        "BroadcastReceiver: Received time update - Time: $remainingTime, Running: $isRunning, App: $currentApp, Mode: $timerModeString"
                    )
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.e("AppLockDebug", "=== Application Started ===")

        // 비활성 알림 제거
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.cancel(4)  // 비활성 알림의 ID인 4를 사용

        try {
            Log.d("MainActivity", "onCreate started")
            setContentView(R.layout.activity_main)

            // UI 초기화 (initializeViews에서 모든 UI 요소 초기화)
            initializeViews()// UI 초기화

            //Timer의 modeFlow를 구독하여 버튼 텍스트를 실시간 업데이트
            lifecycleScope.launch {
                timer.modeFlow.collect { mode ->
                    Log.d("MainActivity", "modeFlow 수집: $mode")
                    btnPointToggle.text = when (mode) {
                        TimerMode.COUNT_UP -> "포인트 사용"
                        TimerMode.COUNT_DOWN, TimerMode.IDLE -> "적립 시작"
                    }
                }
            }

            //여기서부터 잘 보기
            // SharedPreferences 초기화 및 데이터 로드
            prefs = getSharedPreferences("AppLockPrefs", Context.MODE_PRIVATE)

            // Firebase 사용자 ID 설정
            userId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            timer.setUserId(userId)

            // 시간 추가 명령 감지
            FirebaseManager.getInstance().observeTimeCommand(userId) { addTime ->
                val currentState = timer.timerState.value
                val previousAvailableTime = currentState.availabletime
                Log.d("MainActivity", "이전 사용가능시간: $previousAvailableTime")


                val newTime = currentState.availabletime + addTime
                val newTotalSeconds = newTime * currentState.divideRate  // totalSeconds로 변환
                timer.setInitialState(newTotalSeconds, isDivided = false)


                // SharedPreferences에도 저장
                val newState = timer.timerState.value
                Log.d("MainActivity", "새 사용가능시간: ${newState.availabletime}")

                prefs.edit()
                    .putFloat("accumulated_points", newState.totalSeconds)
                    .putFloat("session_time", newState.sessionTime)
                    .apply()

                prefs.edit()
                    .putFloat("accumulated_points", newState.totalSeconds)
                    .putFloat("session_time", newState.sessionTime)
                    .apply()

                // Firebase에 로그 저장
                Log.d("MainActivity", "📝 logAddTimeCommand 호출 시작")
                FirebaseManager.getInstance().logAddTimeCommand(userId, AddTimeCommandData(
                    timestamp = System.currentTimeMillis(),
                    addedTime = addTime,
                    previousAvailableTime = previousAvailableTime,
                    newAvailableTime = newState.availabletime
                ))
                Log.d("MainActivity", "📝 logAddTimeCommand 호출 완료")

                runOnUiThread {
                    Toast.makeText(this, "${(addTime/60).toInt()}분 추가되었습니다!", Toast.LENGTH_SHORT).show()
                }
            }

            // 원격 제어 명령 감지 (availableTime, divideRate 변경)
            FirebaseManager.getInstance().observeRemoteControl(userId) { controlData ->
                Log.d("MainActivity", "🎮 Remote control received: $controlData")

                val currentState = timer.timerState.value
                val previousAvailableTime = currentState.availabletime
                val previousDivideRate = currentState.divideRate

                Log.d("MainActivity", "이전 값 - availableTime: $previousAvailableTime, divideRate: $previousDivideRate")

                var newAvailableTime: Float? = null
                var newDivideRate: Float? = null
                var changedField = ""

                // availableTime 변경
                controlData.availableTime?.let { newAvailableTime ->
                    val newTotalSeconds = newAvailableTime * currentState.divideRate
                    timer.setInitialState(newTotalSeconds, isDivided = false)

                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "사용가능시간이 ${(newAvailableTime/60).toInt()}분으로 설정되었습니다!", Toast.LENGTH_SHORT).show()
                    }
                }

                // divideRate 변경
                controlData.divideRate?.let { newRate ->
                    timer.updateDivideRate(newRate)
                    prefs.edit().putFloat("divide_rate", newRate).apply()

                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "변환 비율이 1/${newRate}로 설정되었습니다!", Toast.LENGTH_SHORT).show()
                    }
                }

                saveCounter()
                // Firebase 로그 저장
                if (changedField.isNotEmpty()) {
                    Log.d("MainActivity", "📝 logRemoteControlExecution 호출 시작: changedField=$changedField")
                    FirebaseManager.getInstance().logRemoteControlExecution(userId, RemoteControlLogData(
                        timestamp = System.currentTimeMillis(),
                        changedField = changedField,
                        previousAvailableTime = previousAvailableTime,
                        newAvailableTime = newAvailableTime,
                        previousDivideRate = previousDivideRate,
                        newDivideRate = newDivideRate
                    ))
                    Log.d("MainActivity", "📝 logRemoteControlExecution 호출 완료")
                } else {
                    Log.w("MainActivity", "⚠️ changedField가 비어있어서 로그를 저장하지 않음")
                }
            }
            //여기까지

            // 초기화
            initializeWakeLock() // WakeLock 초기화
            checkBatteryOptimization() // 배터리 최적화 설정 확인
            checkNotificationPermission() // 알림 권한 확인

            // SharedPreferences 초기화 및 데이터 로드
            prefs = getSharedPreferences("AppLockPrefs", Context.MODE_PRIVATE)

            // Firebase 사용자 ID 설정
            userId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            timer.setUserId(userId)



            // 앱 최초 실행 확인
            val isFirstLaunch = prefs.getBoolean("is_first_launch", true)

            if (isFirstLaunch) {
                // 최초 실행시 3시간(10800초)을 기본으로 제공
                val targetUsableTime = 10800f  // 목표 사용 가능 시간 (3시간 = 10800초)
                val divideRate = timer.timerState.value.divideRate
                val initialTime = targetUsableTime * divideRate  // divideRate를 고려한 실제 적립 시간
                timer.setInitialState(initialTime)

                // 최초 실행 상태를 false로 변경
                prefs.edit()
                    .putBoolean("is_first_launch", false)
                    .putFloat("accumulated_points", initialTime)
                    .apply()

                Toast.makeText(this, "첫 사용자를 위한 3시간이 제공되었습니다!", Toast.LENGTH_LONG).show()
                Log.d("MainActivity", "First launch bonus time provided: 3 hours")
            } else {
                // 기존 사용자의 경우 저장된 시간 복원
                val savedTime = prefs.getFloat("accumulated_points", 0.0f)
                timer.setInitialState(savedTime)
            }

            // 저장된 시간 복원
            val savedTime = prefs.getFloat("accumulated_points", 0.0f)
            timer.setInitialState(savedTime)
            if (timer.timerState.value.totalSeconds == 0f) {
                timer.setMode(TimerMode.COUNT_UP)
                startTimer()
                setIsServiceRunning(true)
                isTimerRunning = true
                acquireWakeLock()
                AppLockMonitorService .getInstance()?.startMonitoring(0f, true)
            }

            // 타이머 상태 관찰
            lifecycleScope.launch {
                timer.timerState.collect { state ->
                    // 총 적립 시간
                    textViewCounter.text = "${state.formatTime()}"
                    // 현재 세션
                    textViewSessionTime.text = "${state.formatSessionTime()}"
                    // 사용 가능 시간
                    textViewDividedCounter.text = "${state.formatAvailableTime()}"
                    saveCounter() // 현재 상태 저장
                    // 설명 텍스트 업데이트
                    updateSubMessageText(state.divideRate)
                }
            }

            // 서비스 실행 상태와 타이머 동기화
            isTimerRunning = isServiceRunning
            if (isServiceRunning) {
                startTimer()
                setupTemporaryTimeButton()
            }

            // 필수 권한 확인
            checkRequiredPermissions()
            // 버튼 클릭 리스너 설정
            setupButtonClickListeners()
            // 브로드캐스트 리시버 등록
            registerReceivers()

            Log.d("MainActivity", "onCreate completed")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error in onCreate: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun setupTemporaryTimeButton() {
        btnTemporaryTime.setOnClickListener {
            val lastUseTime = prefs.getLong(LAST_TEMP_TIME_USE_KEY, 0L)
            val currentTime = System.currentTimeMillis()

            // 현재 시간과 마지막 사용 시간을 날짜 형식으로 변환
            val lastUseDate = java.time.LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(lastUseTime),
                java.time.ZoneId.systemDefault()
            ).toLocalDate()

            val currentDate = java.time.LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(currentTime),
                java.time.ZoneId.systemDefault()
            ).toLocalDate()

            if (currentDate.isAfter(lastUseDate)) {
                // 날짜가 바뀌었으므로 임시 시간 사용 가능
                val currentState = timer.timerState.value
                val addedPoints = TEMP_TIME_DURATION * currentState.divideRate
                timer.setInitialState(currentState.totalSeconds + addedPoints)

                // Firebase에 임시 시간 사용 기록
                FirebaseManager.getInstance().logTempTimeUsage(userId, TempTimeUsageData(
                    usageTime = currentTime,
                    addedPoints = addedPoints
                ))

                // 사용 시간 기록
                prefs.edit()
                    .putLong(LAST_TEMP_TIME_USE_KEY, currentTime)
                    .apply()

                saveCounter()
                Toast.makeText(this, "5분 임시 시간이 제공되었습니다.", Toast.LENGTH_SHORT).show()
            } else {
                // 아직 날짜가 바뀌지 않음
                val now = java.time.LocalDateTime.now()
                val nextDay = now.toLocalDate().plusDays(1).atStartOfDay()
                val hoursUntilNextDay = java.time.Duration.between(now, nextDay).toHours()

                Toast.makeText(
                    this,
                    "임시 시간은 하루에 한 번만 사용 가능합니다. (${hoursUntilNextDay}시간 후 사용 가능)",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }


    private fun updateSubMessageText(divideRate: Float) {
        val rateInt = divideRate.toInt()
        textViewSubMessage.text = "${rateInt}초마다 1초씩 사용 시간이 적립됩니다."
    }

    // WakeLock 초기화
    private fun initializeWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                WAKELOCK_TAG
            )
            wakeLock.setReferenceCounted(false)
        } catch (e: Exception) {
            Log.e("MainActivity", "Error initializing WakeLock: ${e.message}")
        }
    }

    // 배터리 최적화 확인 및 설정 요청
    private fun checkBatteryOptimization() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            AlertDialog.Builder(this)
                .setTitle("배터리 최적화 제외")
                .setMessage("앱이 백그라운드에서 원활하게 동작하기 위해 배터리 최적화에서 제외가 필요합니다.")
                .setPositiveButton("설정") { _, _ ->
                    try {
                        val intent = Intent().apply {
                            action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                            data = Uri.parse("package:$packageName")
                        }
                        startActivity(intent)
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Error launching battery optimization settings: ${e.message}")
                        Toast.makeText(this, "설정을 열 수 없습니다.", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("나중에") { dialog, _ ->
                    dialog.dismiss()
                }
                .show()
        }
    }

    // 알림 권한 확인
    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_CODE
                )
            }
        }
    }

    // WakeLock 획득
    private fun acquireWakeLock() {
        try {
            if (!wakeLock.isHeld) {
                wakeLock.acquire(0) // 무기한 WakeLock
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error acquiring WakeLock: ${e.message}")
            // WakeLock 재시도
            Handler(Looper.getMainLooper()).postDelayed({
                acquireWakeLock()
            }, 1000)
        }
    }

    // WakeLock 해제
    private fun releaseWakeLock() {
        try {
            if (wakeLock.isHeld) {
                wakeLock.release()
                Log.d("MainActivity", "WakeLock released")
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error releasing WakeLock: ${e.message}")
        }
    }

    // 브로드캐스트 리시버 등록
    private fun registerReceivers() {
        registerReceiver(permissionReceiver, IntentFilter("com.example.applock.PERMISSION_ERROR"))
        registerReceiver(timeUpdateReceiver, IntentFilter(AppLockMonitorService.ACTION_TIME_UPDATE))
    }

    // 타이머 상태와 UI 업데이트
    private fun updateTimerDisplay(availableTime: Float, isRunning: Boolean) {
        val state = timer.timerState.value
        textViewDividedCounter.text = "사용 가능 시간: ${state.formatAvailableTime()}"
        Log.d("MainActivity", "updateTimerDisplay: availableTime = $availableTime, isRunning = $isRunning, currentMode = ${timer.getCurrentMode().name}")
    }

    // 필수 권한 확인
    private fun checkRequiredPermissions() {
        Log.d("Permissions", "Checking required permissions...")

        val hasUsageStats = checkUsageStatsPermission()
        Log.d("Permissions", "Usage stats permission: $hasUsageStats")

        if (!hasUsageStats) {
            Log.d("Permissions", "Showing usage stats permission dialog")
            showPermissionDialog("Usage stats permission is required")
        }

        val hasAccessibility = isAccessibilityServiceEnabled(AppLockMonitorService::class.java)
        Log.d("Permissions", "Accessibility service enabled: $hasAccessibility")

        if (!hasAccessibility) {
            Log.d("Permissions", "Showing accessibility permission dialog")
            showAccessibilityPermissionDialog()
        }
    }

    // 사용량 통계 권한 확인
    private fun checkUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    // 권한 요청 다이얼로그 표시
    private fun showPermissionDialog(message: String) {
        AlertDialog.Builder(this)
            .setTitle("Permission Required")
            .setMessage(message)
            .setPositiveButton("Settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // 접근성 서비스 권한 요청 다이얼로그 표시
    private fun showAccessibilityPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle("접근성 서비스 필요")
            .setMessage("앱 잠금 기능을 사용하기 위해서는 접근성 서비스가 필요합니다.")
            .setPositiveButton("설정") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // UI 요소 초기화
    private fun initializeViews() {
        try {
            Log.d("MainActivity", "Initializing views")
            textViewCounter = findViewById(R.id.textViewCounter)
            textViewSessionTime = findViewById(R.id.textViewSessionTime)
            textViewDividedCounter = findViewById(R.id.textViewDividedCounter)
            textViewSubMessage = findViewById(R.id.subMessage)
//            btnViewUsageStats = findViewById(R.id.btn_view_usage_stats)
            btnPointToggle = findViewById(R.id.pointToggleButton)
            btnTemporaryTime = findViewById(R.id.temporaryTimeButton)
            btnPointToggle.text = "포인트 사용"


            val state = timer.timerState.value
            Log.d("MainActivity", "Timer state initialized: $state")

            // 총 적립 시간
            textViewCounter.text = "${state.formatTime()}"
            // 현재 세션
            textViewSessionTime.text = "${state.formatSessionTime()}" // 세션 시간 표시
            // 사용 가능 시간
            textViewDividedCounter.text = "${state.formatAvailableTime()}"
            //btnToggleFeature.text = "적립 시작"

            Log.d("MainActivity", "Setting up temporary time button")
            setupTemporaryTimeButton()

            Log.d("MainActivity", "Views initialized successfully")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error initializing views: ${e.message}")
            throw e
        }
    }

    // SharedPreferences에 현재 카운터 값 저장
    private fun saveCounter() {
        val state = timer.timerState.value
        prefs.edit()
            .putFloat("accumulated_points", state.totalSeconds)
            .putFloat("session_time", state.sessionTime)
            .apply()

//        // Firebase에도 저장
//        FirebaseManager.getInstance().saveUserData(userId, UserData(
//            totalSeconds = state.totalSeconds,
//            availableTime = state.availabletime,
//            divideRate = state.divideRate
//        ))
    }

    // 접근성 서비스가 활성화되어 있는지 확인
    private fun isAccessibilityServiceEnabled(service: Class<out AccessibilityService>): Boolean {
        val enabledServicesSetting = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServicesSetting)
        while (colonSplitter.hasNext()) {
            val componentName = colonSplitter.next()
            if (componentName.equals(
                    ComponentName(applicationContext, service).flattenToString(),
                    ignoreCase = true
                )
            ) {
                return true
            }
        }
        return false
    }

    // 버튼 클릭 리스너 설정
    private fun setupButtonClickListeners() {
        try {
            Log.d("MainActivity", "Setting up button click listeners")

//            //적립 비율 버튼
//            btnViewUsageStats.setOnClickListener {
//                val state = timer.timerState.value
//                showTimeStats(state)
//            }

            // 챗봇 버튼 클릭 리스너 추가
            findViewById<Button>(R.id.ChatbotActivity).setOnClickListener {
                try {
                    Log.d("statelog", "Chatbot button clicked")
                    // ChatbotActivity로 이동
                    val intent = Intent(this, ChatbotActivity::class.java)
                    startActivity(intent)
                    Log.d("statelog", "Started ChatbotActivity")
                } catch (e: Exception) {
                    Log.e("statelog", "Error starting ChatbotActivity", e)
                }
            }

            var initialAvailableTime: Float = 0f  // 포인트 사용 시작할 때의 가용 시간 저장
            var pointUsageStartTime: Long = 0L // 포인트 사용 시작 시간을 저장하기 위한 변수

            btnPointToggle.setOnClickListener { view ->
                if (!isButtonEnabled) return@setOnClickListener

                // 일반 전환이므로 false로 설정
                AppLockMonitorService.isInactivityTransition = false
                Log.d("abcd", "버튼 클릭 전 isInactivityTransition: ${AppLockMonitorService.isInactivityTransition}")


                isButtonEnabled = false
                view.isEnabled = false

                if (btnPointToggle.text == "포인트 사용") {
                    val currentTime = System.currentTimeMillis()
                    val monitorService = AppLockMonitorService.getInstance()
                    val currentState = timer.timerState.value

                    Log.d("abcd", "세션 종료 전 isInactivityTransition: ${AppLockMonitorService.isInactivityTransition}")

                    // 만약 sessionStartTime이 0이라면 서비스의 세션 시작 시간으로 보정
                    if (sessionStartTime == 0L) {
                        sessionStartTime = monitorService?.getCurrentSessionStartTime() ?: 0L
                    }

                    // 미사용 전환이 아닌 일반 전환인 경우에만 세션 종료 기록을 남김
                    if (!AppLockMonitorService.isInactivityTransition && sessionStartTime > 0) {
                        val sessionHours = (currentState.sessionTime / 3600f).toInt()
                        val bonusTime = sessionHours * 600f
                        FirebaseManager.getInstance().saveSessionData(
                            userId, SessionData(
                                startTime = sessionStartTime,
                                endTime = currentTime,
                                accumulatedTime = ((currentTime - sessionStartTime) / 1000f).roundToInt()
                                    .toFloat(),
                                bonusApplied = bonusTime
                            )
                        )
                        sessionStartTime = 0L
                        Log.d("abcd", "세션 종료 기록 저장됨: endTime = ${currentTime}")
                    }else{
                        Log.d("abcd", "미사용 전환으로 인해 세션 종료 기록 생략됨")
                    }

                    // 포인트 사용 모드로 전환
                    if (isTimerRunning) {
                        isTimerRunning = false
                        releaseWakeLock()
                        AppLockMonitorService .getInstance()?.apply {
                            stopMonitoring()
                            resetInitialTimeFlag()
                        }
                        setIsServiceRunning(false)
                    }

                    val state = timer.timerState.value
                    if (state.availabletime > 0) {
                        AppLockMonitorService .getInstance()?.let { service ->
                            // 시작 시간과 초기 가용 시간 저장
                            pointUsageStartTime = System.currentTimeMillis()
                            initialAvailableTime = state.availabletime  // 여기에 초기값 저장 추가

                            timer.resetSession()
                            service.startMonitoring(
                                time = state.availabletime,
                                isAccumulating = false
                            )
                            timer.setMode(TimerMode.COUNT_DOWN)

                            val accessibilityService = AppLockMonitorService .getInstance()
                            if (accessibilityService != null) {
                                accessibilityService.unlockApp()
                                Toast.makeText(this, "앱 잠금이 해제되었습니다. 포인트가 차감됩니다.", Toast.LENGTH_SHORT).show()
                                btnPointToggle.text = "적립 시작"
                            }
                        }
                    } else {
                        Toast.makeText(this, "사용 가능한 시간이 없습니다. 먼저 시간을 적립해주세요.", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    // 적립 모드로 전환
                    // 새 세션 시작 전에 isInactivityTransition 값을 false로 설정하는 것도 고려
                    AppLockMonitorService.isInactivityTransition = false

                    sessionStartTime = System.currentTimeMillis()  // 새로운 세션 시작 시간 기록

                    // 새로운 세션 시작 기록
                    FirebaseManager.getInstance().saveSessionData(userId, SessionData(
                        startTime = sessionStartTime,
                        endTime = 0L,
                        accumulatedTime = 0f,
                        bonusApplied = 0f
                    ))

                    // SharedPreferences에도 저장
                    prefs.edit()
                        .putLong("last_session_start", sessionStartTime)
                        .apply()

                    val monitorService = AppLockMonitorService.getInstance()
                    if (monitorService != null) {
                        val remainingTime = monitorService.getAvailableTime()

                        // 포인트 사용 완료 시에만 로그 기록
                        if (pointUsageStartTime > 0) {
                            val pointData = PointUsageData(
                                startTime = pointUsageStartTime,
                                endTime = System.currentTimeMillis(),
                                pointsUsed = initialAvailableTime,  // 시작할 때 저장해둔 가용 시간 사용
                                returnedPoints = remainingTime
                            )
                            FirebaseManager.getInstance().logPointUsage(userId, pointData)
                            // 변수 초기화
                            pointUsageStartTime = 0L
                            initialAvailableTime = 0f
                        }

                        if (remainingTime > 0) {
                            timer.setInitialState(remainingTime, isDivided = true)
                            saveCounter()
                            Toast.makeText(this, "남은 시간이 포인트로 환급되었습니다.", Toast.LENGTH_SHORT).show()
                        }

                        monitorService.stopMonitoring()
                        monitorService.unlockApp()
                        monitorService.setSkipNextLock(true)
                        monitorService.handleLockCondition()

                        // 바로 적립 모드 시작 및 세션 시작 기록
                        startTimer()
                        acquireWakeLock()
                        monitorService.startMonitoring(timer.timerState.value.availabletime, isAccumulating = true)
                        timer.setMode(TimerMode.COUNT_UP)
                        setIsServiceRunning(true)
                        Toast.makeText(this, "적립 모드로 전환되었습니다.", Toast.LENGTH_SHORT).show()
                    }
                    btnPointToggle.text = "포인트 사용"
                }

                lifecycleScope.launch {
                    delay(500)
                    isButtonEnabled = true
                    view.isEnabled = true
                }
            }

        } catch (e: Exception) {
            Log.e("MainActivity", "Error setting up button listeners: ${e.message}")
            e.printStackTrace()
        }
    }
    private fun showTimeStats(state: TimerState) {
        val items = arrayOf("1", "2", "3", "4", "5", "6")
        AlertDialog.Builder(this)
            .setTitle("현재 설정")
            .setMessage("""
                |적립된 시간: ${state.formatTime()}
                |사용 가능 시간: ${state.formatAvailableTime()}
                |변환 비율: 1 / ${state.divideRate}
            """.trimMargin())
            .setPositiveButton("확인", null)
            .setNeutralButton("비율 변경") { _, _ ->
                showDivideRateDialog(items)
            }
            .show()
    }

    private fun showDivideRateDialog(items: Array<String>) {
        AlertDialog.Builder(this)
            .setTitle("변환 비율 설정")
            .setItems(items) { _, which ->
                val oldRate = timer.timerState.value.divideRate
                val newRate = items[which].toFloat()

                // Timer의 divideRate 업데이트
                timer.updateDivideRate(newRate)

                // SharedPreferences에 저장
                prefs.edit()
                    .putFloat("divide_rate", newRate)
                    .apply()

                Toast.makeText(this, "변환 비율이 1/${newRate}로 설정되었습니다.", Toast.LENGTH_SHORT).show()

                logRateChange(oldRate, newRate)
            }
            .show()
    }

    private fun logRateChange(oldRate: Float, newRate: Float) {
        val state = timer.timerState.value

        // Firebase에 기록
        FirebaseManager.getInstance().logRateChange(userId, RateChangeData(
            oldRate = oldRate,
            newRate = newRate,
            totalSeconds = state.totalSeconds,
            availableTimeBefore = state.totalSeconds / oldRate,
            availableTimeAfter = state.availabletime
        ))

        Log.d("RateChange", "=== 비율 변경 발생 ===")
        Log.d("RateChange", "변경 시간: ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())}")
        Log.d("RateChange", "이전 비율: 1/${oldRate}")
        Log.d("RateChange", "변경된 비율: 1/${newRate}")
        Log.d("RateChange", "적립된 시간: ${state.formatTime()}")
        Log.d("RateChange", "변경 전 사용가능 시간: ${TimerState.fromSeconds(state.totalSeconds / oldRate).formatTime()}")
        Log.d("RateChange", "변경 후 사용가능 시간: ${state.formatAvailableTime()}")
        Log.d("RateChange", "===================")
    }

    private fun saveSessionState(isAccumulating: Boolean) {
        prefs.edit()
            .putLong("session_start_time", if (isAccumulating) System.currentTimeMillis() else 0)
            .putBoolean("is_accumulating", isAccumulating)
            .apply()
    }

    private fun startTimer() {
        if (!isTimerRunning) {
            isTimerRunning = true
            timer.setMode(TimerMode.COUNT_UP)
            acquireWakeLock()
        }
    }

    private fun stopTimer() {
        if (isTimerRunning) {
            isTimerRunning = false
            timer.resetSession() // 세션 시간 초기화
            releaseWakeLock()
        }
    }

    override fun onResume() {
        super.onResume()

        when (timer.getCurrentMode()) {
            TimerMode.COUNT_UP -> btnPointToggle.text = "포인트 사용"
            TimerMode.COUNT_DOWN, TimerMode.IDLE -> btnPointToggle.text = "적립 시작"
        }

        // 현재 타이머 모드 확인
        val currentMode = timer.getCurrentMode()

        // AppMonitorAccessibilityService의 상태 확인
        val monitorService = AppLockMonitorService.getInstance()

        Log.d("MainActivity", "onResume - Current timer mode: $currentMode")

        // 서비스 상태에 따른 WakeLock 처리
        if (isServiceRunning) {
            acquireWakeLock()
        } else {
            releaseWakeLock()
        }

        // 현재 상태 요청
        monitorService?.broadcastTimeUpdate()
    }

    override fun onPause() {
        super.onPause()
        // 서비스 상태 저장
        getSharedPreferences("AppLockPrefs", Context.MODE_PRIVATE).edit()
            .putBoolean("service_was_running", isServiceRunning)
            .apply()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            // 기존 정리 작업
            releaseWakeLock()
            unregisterReceiver(timeUpdateReceiver)
            unregisterReceiver(permissionReceiver)
            timer.stop()
            lifecycleScope.cancel()
        } catch (e: Exception) {
            Log.e("MainActivity", "Error in onDestroy: ${e.message}")
            // Firebase에 에러 로깅 추가
            FirebaseManager.getInstance().logError(userId, "DESTROY_ERROR", e.message ?: "Unknown error")
        }
    }
}
