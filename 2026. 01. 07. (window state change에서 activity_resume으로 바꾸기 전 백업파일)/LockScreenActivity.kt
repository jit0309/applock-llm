package com.example.applock

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import android.provider.Settings
import android.widget.ImageView

class LockScreenActivity : AppCompatActivity() {
    private var isButtonClickable = true
    // UI 버튼을 통해 정상적으로 해제(잠금 해제 또는 종료)한 경우 true로 설정
    private var isLockDismissed = false
    private var isInactivityTransition = false
    private val SCREEN_TIMEOUT_DELAY = 10000L // 10초
    private var isTimeoutScheduled = false

    private lateinit var powerManager: PowerManager
    private lateinit var wakeLock: PowerManager.WakeLock
    private val handler = Handler(Looper.getMainLooper())
    // 재실행을 위한 Runnable
    private var reLaunchRunnable: Runnable? = null

    // 자동으로 홈 화면으로 전환하도록 할 시간 (밀리초 단위)
    private val AUTO_CLOSE_DELAY = 10000L // 10초 후

    // 자동 종료 진행 여부 플래그
    private var isAutoClosing = false
    // 재실행 여부 플래그 (홈 버튼이나 예외 상황에서 재실행하지 않도록)
    private var shouldRelaunch = true

    // Timer 인스턴스 추가
    private val timer = Timer.getInstance()

    // 임시 시간 관련 변수 추가
    private lateinit var btnTemporaryTime: Button
    private val TEMP_TIME_DURATION = 5f * 60f  // 5분을 초 단위로
    private val LAST_TEMP_TIME_USE_KEY = "last_temp_time_use" // SharedPreferences 키
    private lateinit var prefs: SharedPreferences
    private lateinit var userId: String

    //잠금 아이콘
    private lateinit var lockIcon: ImageView


    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)

        // 기본적으로 재실행 가능하도록 설정
        shouldRelaunch = true

        isInactivityTransition = intent.getBooleanExtra("inactivity_transition", false)

        // 액티비티 애니메이션 효과 제거
        window.setWindowAnimations(0)

        // 전체 화면으로 설정
        window.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )

        // 배경을 검정색으로
        window.setBackgroundDrawableResource(android.R.color.black)
        setContentView(R.layout.activity_lock_screen)

        // WakeLock 초기화 - 화면을 한 번만 켜기 위한 설정
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "AppLock:LockScreenWakeLock"
        )

        // SharedPreferences 초기화 및 사용자 ID 설정
        prefs = getSharedPreferences("AppLockPrefs", Context.MODE_PRIVATE)
        userId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)


        // 화면을 켜고 WakeLock 획득 (일회성)
        if (!wakeLock.isHeld) {
            wakeLock.acquire(SCREEN_TIMEOUT_DELAY)
        }

        // 뒤로가기 버튼 비활성화
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // 아무 동작도 하지 않음
            }
        })

        //잠금 아이콘
        lockIcon = findViewById(R.id.lockIcon)

        // 잠금 화면 표시 설정
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        setupButtons()

        // 임시 시간 버튼 설정
        setupTemporaryTimeButton()

        if (!isTimeoutScheduled) {
            scheduleScreenTimeout()
            isTimeoutScheduled = true
        }

        // 일정 시간 후 자동으로 홈 화면으로 전환하도록 스케줄링
        scheduleAutoClose()

        // 초기 UI 업데이트
        updateBonusInfo()

        val currentMode = timer.getCurrentMode()
        lockIcon.setImageResource(
            when (currentMode) {
                TimerMode.COUNT_UP -> R.drawable.lock_im
                TimerMode.COUNT_DOWN -> R.drawable.unlock_im
                TimerMode.IDLE -> R.drawable.unlock_im
            }
        )
        // 주기적 업데이트 시작
        uiUpdateHandler.post(uiUpdateRunnable)
    }

    private fun setupButtons() {
        // "잠금 앱 열기" 버튼을 "포인트 사용" 버튼 기능으로 변경
        findViewById<Button>(R.id.openAppButton).setOnClickListener {
            if (isButtonClickable) {
                isButtonClickable = false

                // 현재 타이머 상태 확인
                val state = timer.timerState.value
                if (state.availabletime > 0) {
                    // 현재 세션 종료 정보 기록
                    val currentTime = System.currentTimeMillis()
                    val monitorService = AppLockMonitorService.getInstance()
                    val sessionStartTime = monitorService?.getCurrentSessionStartTime() ?: 0L

                    if (sessionStartTime > 0) {
                        val sessionHours = (state.sessionTime / 3600f).toInt()
                        val bonusTime = sessionHours * 600f

                        Log.d("LockScreenActivity", "세션 종료 기록 저장됨: endTime = ${currentTime}")
                    }

                    // 포인트 사용 모드로 전환
                    AppLockMonitorService.getInstance()?.let { service ->
                        service.startMonitoring(
                            time = state.availabletime,
                            isAccumulating = false
                        )
                        timer.setMode(TimerMode.COUNT_DOWN)

                        FirebaseManager.getInstance().logCountdownSession(
                            userId = userId,
                            data = CountdownSessionData(
                                type = "P_START",
                                availableTime = state.availabletime,
                                reason = ""
                            )
                        )

                        service.unlockApp()
                        Toast.makeText(this, "앱 잠금이 해제되었습니다. 포인트가 차감됩니다.", Toast.LENGTH_SHORT).show()

                        // 잠금화면 닫기 설정만 하고 화면 종료
                        isLockDismissed = true
                        shouldRelaunch = false
                        service.setLockScreenClosing(true)
                        service.setSkipNextLock(true)

                        // LockScreenActivity만 종료하고 다른 앱으로 자동 전환됨
                        finish()
                    }
                } else {
                    // 사용 가능한 시간이 없는 경우 메인 화면으로 이동
                    Toast.makeText(this, "사용 가능한 시간이 없습니다. 먼저 시간을 적립해주세요.", Toast.LENGTH_SHORT).show()

                    isLockDismissed = true
                    shouldRelaunch = false

                    val mainIntent = Intent(this, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }

                    AppLockMonitorService.getInstance()?.setLockScreenClosing(true)
                    startActivity(mainIntent)
                    finishAffinity()
                }
            }
        }

        findViewById<Button>(R.id.closeButton).setOnClickListener {
            if (isButtonClickable) {
                isButtonClickable = false
                // 정상적인 종료를 위해 플래그 설정
                isLockDismissed = true
                shouldRelaunch = false

                AppLockMonitorService.getInstance()?.setLockScreenClosing(true)

                val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }

                finish()
                startActivity(homeIntent)
            }
        }
    }

    // 임시 시간 버튼 설정 함수
    private fun setupTemporaryTimeButton() {
        btnTemporaryTime = findViewById(R.id.temporaryTimeButton)
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

                // 세션 종료 시 사용 가능한 시간을 SharedPreferences에 저장
                saveCounter()

                Toast.makeText(this, "5분 임시 시간이 제공되었습니다.", Toast.LENGTH_SHORT).show()

                // UI 업데이트를 위해 상태 갱신
                updateBonusInfo()
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

    // SharedPreferences에 현재 상태 저장
    private fun saveCounter() {
        val state = timer.timerState.value
        prefs.edit()
            .putFloat("accumulated_points", state.totalSeconds)
            .putFloat("session_time", state.sessionTime)
            .apply()

        // Firebase에도 저장
        FirebaseManager.getInstance().saveUserData(userId, UserData(
            totalSeconds = state.totalSeconds,
            availableTime = state.availabletime,
            divideRate = state.divideRate
        ))
    }

    private fun updateBonusInfo() {
        // 진행 상태 업데이트
        val progressBar = findViewById<ProgressBar>(R.id.bonusProgressBar)
        val currentProgressText = findViewById<TextView>(R.id.currentProgressText)
        val totalProgressText = findViewById<TextView>(R.id.totalProgressText)
        val bonusTimeText = findViewById<TextView>(R.id.bonusTimeText)
        val checkMark = findViewById<TextView>(R.id.checkMark)
        val availableTimeText = findViewById<TextView>(R.id.availableTimeText)

        // 서비스에서 현재 세션 시간 가져오기
        val service = AppLockMonitorService.getInstance()
        val currentSessionTime = service?.getCurrentSessionStartTime() ?: 0L
        val elapsedTimeMillis = System.currentTimeMillis() - currentSessionTime

        // 여기가 중요한 변경 부분: 3600초(60분)로 나머지 연산을 수행하여 항상 0~3600 사이 값이 되도록 함
        val elapsedSeconds = (elapsedTimeMillis / 1000).toInt() % 3600

        // 시:분:초 형식으로 변환
        val hours = elapsedSeconds / 3600
        val minutes = (elapsedSeconds % 3600) / 60
        val seconds = elapsedSeconds % 60
        val timeString = String.format("%02d:%02d:%02d", hours, minutes, seconds)

        // UI 업데이트
        currentProgressText.text = timeString

        // 60분(3600초) 기준으로 진행 상태 계산
        val targetSeconds = 3600
        val progressPercentage = (elapsedSeconds.toFloat() / targetSeconds) * 100
        progressBar.progress = progressPercentage.toInt().coerceAtMost(100)

        // 남은 시간 계산 (60분 - 경과 시간)
        val remainingSeconds = (targetSeconds - elapsedSeconds).coerceAtLeast(0)
        val remainingMinutes = remainingSeconds / 60
        val remainingSecondsInMinute = remainingSeconds % 60
        bonusTimeText.text = String.format("%d분 %d초 후 10분 추가!", remainingMinutes, remainingSecondsInMinute)

        // 진행 상태에 따라 체크 마크 표시
        if (elapsedSeconds >= targetSeconds - 10) { // 거의 완료 시 체크 마크 표시 (60분에 몇 초 부족할 때)
            checkMark.visibility = View.VISIBLE
        } else {
            checkMark.visibility = View.GONE
        }

        // Timer 인스턴스에서 상태 가져오기
        val timerState = Timer.getInstance().timerState.value

        // formatAvailableTime과 getBonusText 메서드 사용
        val formattedTime = timerState.formatAvailableTime()
//        val bonusText = timerState.getBonusText()

        // 시간 표시
        availableTimeText.text = "$formattedTime"
    }

    // UI 업데이트 타이머를 초 단위로 실행
    private val uiUpdateHandler = Handler(Looper.getMainLooper())
    private val uiUpdateRunnable = object : Runnable {
        override fun run() {
            updateBonusInfo()
            uiUpdateHandler.postDelayed(this, 1000) // 1초마다 업데이트
        }
    }

    private fun scheduleScreenTimeout() {
        handler.postDelayed({
            try {
                if (wakeLock.isHeld) {
                    wakeLock.release()
                }
                // 화면 켜짐 플래그 제거
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

                // 시스템의 화면 자동 꺼짐 허용
                window.addFlags(WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON)

                // 화면 밝기를 시스템 기본값으로
                val layoutParams = window.attributes
                layoutParams.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                window.attributes = layoutParams
            } catch (e: Exception) {
                Log.e("LockScreenActivity", "Error in screen timeout: ${e.message}")
            }
        }, SCREEN_TIMEOUT_DELAY)
    }

    override fun onDestroy() {
        super.onDestroy()
        uiUpdateHandler.removeCallbacks(uiUpdateRunnable)

        handler.removeCallbacksAndMessages(null)

        // 서비스에 잠금화면 종료 알림
        val service = AppLockMonitorService.getInstance()
        service?.onLockScreenClosed()

        if (wakeLock.isHeld) {
            try {
                wakeLock.release()
            } catch (e: Exception) {
                Log.e("LockScreenActivity", "Error releasing wake lock in onDestroy: ${e.message}")
            }
        }
    }

    // onResume에서 버튼 클릭 가능 상태 초기화
    override fun onResume() {
        super.onResume()
        isButtonClickable = true
        // (필요에 따라) 재실행 가능 상태로 복구
        if (!isLockDismissed) {
            shouldRelaunch = true
        }
    }

    // onPause()나 onUserLeaveHint()에서 finish() 호출하지 않고,
    // 대신 onWindowFocusChanged에서 포커스 상실 시 재실행하도록 함.
    override fun onPause() {
        super.onPause()

        // ✅ 적립 모드에서는 사용자가 명시적으로 버튼을 누른 경우에만 재실행 방지
        val service = AppLockMonitorService.getInstance()
        if (service != null) {
            // 적립 모드에서 잠금화면이 떴는데 사용자 액션 없이 onPause가 호출된 경우
            // -> 정상적으로 종료되어야 함
            if (service.isInAccumulatingMode() && !isLockDismissed) {
                Log.d("LockScreenActivity", "적립 모드에서 비정상 종료 감지 - 정상 종료 처리")
                isLockDismissed = true
                shouldRelaunch = false
            }
        }

        if (!isLockDismissed && !isAutoClosing && shouldRelaunch) {
            Log.d("LockScreenActivity", "onPause() - Scheduling LockScreenActivity restart")

            handler.postDelayed({
                if (!isLockDismissed && shouldRelaunch) {
                    val intent = Intent(this, LockScreenActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    }
                    startActivity(intent)
                }
            }, 500) // 0.5초 후 재실행하여 성능 저하 방지
        }
    }


    // 홈 버튼이나 최근 앱 버튼 등 시스템에 의해 액티비티가 떠날 때 호출됨.
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()

        if (!isLockDismissed && !isAutoClosing && shouldRelaunch) {
            Log.d("LockScreenActivity", "User left the app - Scheduling LockScreenActivity restart")

            handler.postDelayed({
                if (!isLockDismissed && shouldRelaunch) {
                    val intent = Intent(this, LockScreenActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    }
                    startActivity(intent)
                }
            }, 1000) // 1초 후 재실행하여 시스템이 홈 화면으로 이동할 기회를 줌
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)

        // 시스템 UI로 인한 포커스 손실인지 확인 (잠금화면에서 상단바 내리면 무한 잠금 발생 오류)
        if (!hasFocus) {
            // 현재 decorView의 systemUiVisibility 플래그를 확인
            // 만약 SYSTEM_UI_FLAG_FULLSCREEN 플래그가 적용되지 않았다면
            // 이는 상단바가 내려와 있다는 의미이므로 재실행 로직을 건너 뜀
            val decorView = window.decorView
            if (decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0) {
                Log.d("LockScreenActivity", "상단바가 표시되어 있음 - 재실행 건너뜀")
                return
            }

            // 그 외의 경우에만 일정 시간 후 재실행하도록 합니다.
            handler.postDelayed({
                if (!hasWindowFocus() && !isLockDismissed && !isAutoClosing && shouldRelaunch) {
                    val intent = Intent(this, LockScreenActivity::class.java)
                    startActivity(intent)
                }
            }, 1000)  // 1초 후 재실행
        }
    }

    // 액티비티 시작 시 자동 종료 Runnable 스케줄(잠금화면 일정시간 후 홈화면으로 나가도록)
    private fun scheduleAutoClose() {
        handler.postDelayed({
            isAutoClosing = true
            reLaunchRunnable?.let { handler.removeCallbacks(it) }
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(homeIntent)
            finish()
        }, AUTO_CLOSE_DELAY)
    }

    override fun onStop() {
        super.onStop()
        // 액티비티가 종료될 때, 정상적인 해제(UI 버튼)라면 플래그가 설정되어 있으므로 reset
        // 그렇지 않으면, 이 액티비티가 계속 유지되도록(재실행) 하기 위한 처리
        if (isFinishing) {
            Log.d("LockDebug", "LockScreenActivity onStop – isFinishing true")
            AppLockMonitorService.getInstance()?.resetPointUseMode()
            AppLockMonitorService.getInstance()?.resetLockScreenShowing()
        }
    }
}