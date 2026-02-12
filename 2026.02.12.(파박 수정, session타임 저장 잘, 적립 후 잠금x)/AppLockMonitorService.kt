package com.example.applock

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import android.content.ComponentCallbacks2
import android.os.Process

class AppLockMonitorService : AccessibilityService() {
    companion object {
        private const val TAG = "AppLockMonitorService"
        private const val NOTIFICATION_CHANNEL_ID = "AppLockMonitorChannel"
        private const val NOTIFICATION_ID_SERVICE = 1
        private const val NOTIFICATION_ID_TIMEOUT = 2

        // Intent actions and extras
        const val ACTION_TIME_UPDATE = "com.example.applock.TIME_UPDATE"
        const val EXTRA_AVAILABLE_TIME = "available_time"
        const val EXTRA_IS_RUNNING = "is_running"
        const val EXTRA_CURRENT_APP = "current_app"

        private var instance: AppLockMonitorService? = null
        private var initialTimeSet = false
        private var skipNextLock = false

        // Inactivity tracking
        private var lastActivityTime: Long = 0L
        private const val INACTIVITY_TIMEOUT = 5 * 60 * 1000L // 5 * 60 * 1000L 포인트사용모드에서 5분 미사용 시 적립모드로 전환 - 0.5는 30초, 5븐 5분
        private var inactivityCheckJob: Job? = null
        private var lastActivePackage: String? = null

        //포인트 모드 파이어베이스 저장을 위한 변수
        private var pointUsageSessionStartTime: Long = 0L      // 세션 시작 시간
        private var initialSessionPointAmount: Float = 0f      // 세션 시작시 총 포인트
        private var isPointUseSession: Boolean = false         // 세션 상태 추적

        // 세션 추적을 위한 변수 추가
        private var currentSessionStartTime: Long = 0L
        private var lastInactivityTransitionTime: Long = 0L

        var isInactivityTransition: Boolean = false

        private val EXCLUDED_PACKAGES = setOf(
            "com.sec.android.app.launcher",
            "com.android.systemui",
            "com.android.settings",
            "com.example.applock",
            "com.samsung.android.app.launcher",
            "com.google.android.packageinstaller",
            "com.android.settings.intelligence",
            "com.google.android.cellbroadcastreceiver",
            "com.samsung.android.spay",
            "com.samsung.android.dialer",
            "com.samsung.android.honeyboard", // 키보드
            "com.navercorp.android.smartboard", // 네이버 키보드
            "com.samsung.android.app.clockpack", // 시계
            "com.samsung.android.app.aodservice", // always on display
            "com.ktcs.whowho", // 후후(전화 스팸차단)
        )

        private lateinit var wakeLock: PowerManager.WakeLock

        // ✅ PID 추적을 위한 변수 추가
        private var servicePID: Int = 0

        fun getInstance(): AppLockMonitorService? = instance

        // throttling
        private const val CONTENT_CHECK_THROTTLE_MS = 1000L  // 1초

        private lateinit var usageTracker: UsageTracker

        // ✅ 잠금화면 중복 방지를 위한 전역 타임스탬프
        @Volatile
        private var globalLastLockTime = 0L
        @Volatile
        private var globalLastLockPackage: String? = null  // ✅ 마지막 잠금 패키지
        private const val LOCK_DEBOUNCE_MS = 1000L
        private const val PREFS_LAST_LOCK_TIME = "last_lock_screen_time"
        private const val PREFS_LAST_LOCK_PACKAGE = "last_lock_screen_package"

        /**
         * 프로세스 레벨 디바운스 체크 (Thread-safe, Multi-instance safe)
         * @param packageName 잠금하려는 앱의 패키지 이름
         * @return true if allowed, false if blocked by debounce
         */
        @JvmStatic
        @Synchronized
        fun checkAndUpdateLockTime(context: Context, packageName: String?): Boolean {
            val now = System.currentTimeMillis()
            val prefs = context.getSharedPreferences("AppLockPrefs", Context.MODE_PRIVATE)

            // 1차: 메모리 체크 (companion object - 같은 프로세스 내 모든 인스턴스 공유)
            if (now - globalLastLockTime < LOCK_DEBOUNCE_MS) {
                // ✅ 같은 패키지인 경우에만 디바운스 적용
                if (packageName == globalLastLockPackage) {
                    Log.d(TAG, "⏱️ 디바운스 차단 (메모리): ${now - globalLastLockTime}ms 간격, 같은 앱: $packageName")
                    return false
                } else {
                    Log.d(TAG, "✅ 디바운스 우회 (메모리): 다른 앱 전환 ($globalLastLockPackage → $packageName)")
                }
            }

            // 2차: 디스크 체크 (프로세스 재시작 + 멀티프로세스 대비)
            val savedTime = prefs.getLong(PREFS_LAST_LOCK_TIME, 0L)
            val savedPackage = prefs.getString(PREFS_LAST_LOCK_PACKAGE, null)
            if (now - savedTime < LOCK_DEBOUNCE_MS) {
                // ✅ 같은 패키지인 경우에만 디바운스 적용
                if (packageName == savedPackage) {
                    Log.d(TAG, "⏱️ 디바운스 차단 (디스크): ${now - savedTime}ms 간격, 같은 앱: $packageName")
                    globalLastLockTime = maxOf(globalLastLockTime, savedTime)
                    globalLastLockPackage = savedPackage
                    return false
                } else {
                    Log.d(TAG, "✅ 디바운스 우회 (디스크): 다른 앱 전환 ($savedPackage → $packageName)")
                }
            }

            // 통과: 양쪽 모두 업데이트 (commit으로 즉시 쓰기)
            globalLastLockTime = now
            globalLastLockPackage = packageName
            prefs.edit()
                .putLong(PREFS_LAST_LOCK_TIME, now)
                .putString(PREFS_LAST_LOCK_PACKAGE, packageName)
                .commit()  // ✅ apply 대신 commit
            Log.d(TAG, "✅ 디바운스 통과: 잠금화면 허용 ($packageName)")
            return true
        }
    }

    // Service state
    private val timer = Timer.getInstance()
    private var serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private var currentActiveApp: String? = null
    private var isMonitoring = false
    private var lastEventTime = 0L
    private var isLockScreenShowing = false
    private var lastBroadcastTime = 0L
    private val BROADCAST_THROTTLE_MS = 1000L
    private var isAccumulatingPoints = false
    private var isLockScreenClosing = false

    // Lock state
    private var isLocked = true
    private var pointUseMode = false

    // Firebase related
    private var userId: String = ""
    private lateinit var prefs: SharedPreferences
    private lateinit var screenReceiver: BroadcastReceiver

    // ✅ LockScreenActivity 종료 감지용 리시버 (nullable로 변경)
    private var lockScreenClosedReceiver: BroadcastReceiver? = null

    // 앱 종료 이벤트 추적을 위한 변수 추가
    private var lastCloseEventTime = 0L
    private var consecutiveCloseEvents = 0
    private var lastLockScreenTime = 0L

    // 홈 화면 전환 관련 변수 추가
    private var pendingLockCheck = false
    private val HOME_TRANSITION_DELAY = 150L

    // 상태 추적을 위한 변수 추가
    private var lastHomeTransitionTime = 0L
    private var isHomeTransition = false
    private var lastAppPackage: String? = null
    private var timerState: TimerMode = TimerMode.IDLE
    private var isInTransition = false
    private var lastLockScreenShowTime = 0L

    // ✅ 잠금화면 표시 전 원래 앱 저장 (재진입 감지용)
    private var appBeforeLock: String? = null

    // throttling
    private val contentCheckHandler = Handler(Looper.getMainLooper())
    private var contentCheckRunnable: Runnable? = null
    private var isContentCheckScheduled = false
    private var lastContentCheckTime = 0L

    // 홈 화면 보호 시간 관련 변수
//    private var isInHomeProtection = false

    //✅UsageEventsMonitor 인스턴스
//    private var usageEventsMonitor: UsageEventsMonitor? = null

    // LockScreenActivity에서 호출할 메서드 추가
    fun onLockScreenClosed() {
        val caller = Thread.currentThread().stackTrace.getOrNull(3)?.let {
            "${it.className}.${it.methodName}:${it.lineNumber}"
        } ?: "unknown"

        Log.d("StateLog", "━━━ onLockScreenClosed 호출 ━━━")
        Log.d("StateLog", "호출자: $caller")
        Log.d("StateLog", "현재 상태: currentActiveApp=$currentActiveApp, appBeforeLock=$appBeforeLock, isLockScreenShowing=$isLockScreenShowing")

        // ✅ 핵심 수정: appBeforeLock이 null이 아닐 때만 복원 (null 전염 방지!)
        val previousApp = currentActiveApp
        if (appBeforeLock != null) {
            currentActiveApp = appBeforeLock
            Log.d("StateLog", "🔄 currentActiveApp 복원: $appBeforeLock")
        } else {
            Log.d("StateLog", "⚠️ appBeforeLock이 null - currentActiveApp 유지: $currentActiveApp")
        }

        isLockScreenShowing = false
        isInTransition = false

        // ✅ 홈 전환 플래그도 초기화 (재진입 감지 방해 방지)
        if (isHomeTransition) {
            Log.d("StateLog", "isHomeTransition 초기화 (재진입 대비)")
            isHomeTransition = false
        }

        Log.d("StateLog", "🔄 상태 복원 완료: currentActiveApp=$currentActiveApp (was: $previousApp)")

        // 타이머 모드 복원 - 포인트 사용 모드인 경우에만
        if (!isAccumulatingPoints) {
            if (timerState == TimerMode.COUNT_DOWN) {
                Log.d("StateLog", "잠금화면 종료 후 타이머 모드 복원: ${timer.getCurrentMode()} -> TimerMode.COUNT_DOWN")
                timer.setMode(TimerMode.COUNT_DOWN)
            }
        }

        Log.d("StateLog", "━━━ onLockScreenClosed 완료 ━━━")

//        resetLockScreenShowing()
    }

    override fun onCreate() {
        super.onCreate()

        // ✅ PID 기록
        servicePID = Process.myPid()

        instance = this
        createNotificationChannel()


        userId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        prefs = getSharedPreferences("AppLockPrefs", Context.MODE_PRIVATE)

        usageTracker = UsageTracker(this, userId)
        Log.d(TAG, "✅ UsageTracker initialized")

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AppLock::WakeLock")
        wakeLock.acquire()

        // 저장된 값 복원
        val savedTime = prefs.getFloat("accumulated_points", 0.0f)
        val savedDivideRate = prefs.getFloat("divide_rate", 3.0f)

        // Timer 초기화
        timer.setUserId(userId)
        timer.updateDivideRate(savedDivideRate)
        timer.setInitialState(savedTime)

        val isFirstRun = prefs.getBoolean("is_first_run", true)

        if (isFirstRun) {
            // 🆕 진짜 첫 실행만: S_START + 초기화
            logCountdownEvent("S_START", "first_boot")
            isAccumulatingPoints = true
            timer.setMode(TimerMode.COUNT_UP)
            prefs.edit().putBoolean("is_first_run", false).apply()
        } else {
            // 🔄 Rebind: 저장된 상태 그대로 복원 (COUNT_DOWN이면 COUNT_DOWN 유지!)
            isAccumulatingPoints = prefs.getBoolean("is_accumulating", false) // ← COUNT_DOWN 중이면 false
            timer.setMode(TimerMode.valueOf(prefs.getString("timer_mode", "COUNT_UP")!!)) // ← COUNT_DOWN 복원
            // ✅ S_START 안 찍음!
            // ✅ 모드 안 바꿈!
        }

        isMonitoring = true
        startForegroundService()
        // 새로운 세션 시작
        currentSessionStartTime = System.currentTimeMillis()

        // ✅UsageEvents 모니터링 시작 - 테스트용(183 한 줄만 주석처리)
        //startUsageEventsMonitoring()

        //잠금 꺼지면 다시 켜기
        registerSystemEventReceivers()

        // ✅ LockScreenActivity 종료 감지 리시버 등록
        registerLockScreenClosedReceiver()



        updateNotification()
        broadcastTimeUpdate()
        initializeTimerObserver()
    }

    // ✅ 추가: UsageEvents 모니터링 시작 함수
//    private fun startUsageEventsMonitoring() {
//        usageEventsMonitor = UsageEventsMonitor(this, userId)
//
//        // 방법 1: 모든 이벤트 모니터링 (30초마다)
//        usageEventsMonitor?.startMonitoring(intervalMs = 60 * 1000L)

//        // 방법 2: (30초마다 체크, 50개씩 배치) - batchsize 부분 수정 필요
//        usageEventsMonitor?.startMonitoring(
//            intervalMs = 60000L,  // 60초마다 체크
//            batchSize = 50        // 50개 모이면 저장
//        )
//
//        Log.d(TAG, "✅ UsageEvents 모니터링이 시작되었습니다")
//    }

    private fun initializeTimerObserver() {
        serviceScope.launch {
            timer.timerState.collect { state ->
                if (isMonitoring) {
                    updateNotification()
                    broadcastTimeUpdate(currentActiveApp ?: "")
//음수 허용
//                    if (state.availabletime <= 0 && timer.getCurrentMode() == TimerMode.COUNT_DOWN) {
//                        handleTimeoutCondition()
//                    }
                }
            }
        }
    }


    override fun onServiceConnected() {
        super.onServiceConnected()

        // ✅ SERVICE_CREATE 로그 기록
        FirebaseManager.getInstance().logError(
            userId,
            ErrorType.SERVICE_ERROR,
            "SERVICE_CREATE | PID=$servicePID}"
        )
        Log.d(TAG, "✅ AccessibilityService onServiceConnected - PID: $servicePID")

        val config = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOWS_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_SCROLLED  // 스크롤 자체의 직접 신호 (lightweight 활동 업데이트용)

            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or  // RecyclerView ViewHolder 이벤트 수신에 필수
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
//                    AccessibilityServiceInfo.FLAG_REPORT_GENERIC_ACTIONS  // content-change 서브타입 suppression 방지
            notificationTimeout = 100
        }
        serviceInfo = config
        Log.d(TAG, "Accessibility Service Connected")
    }

    // ✅ onTaskRemoved 메서드 추가
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)

        // TASK_REMOVED 로그 기록
        FirebaseManager.getInstance().logError(
            userId,
            ErrorType.SERVICE_ERROR,
            "TASK_REMOVED | PID=$servicePID | wasMonitoring=$isMonitoring"
        )
        Log.d(TAG, "📱 onTaskRemoved - 최근 앱 목록에서 제거됨, PID: $servicePID")
    }

    // ✅ onTrimMemory 메서드 추가
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)

        val levelName = getTrimMemoryLevelName(level)

        // TRIM_MEMORY 로그 기록
        FirebaseManager.getInstance().logError(
            userId,
            ErrorType.SERVICE_ERROR,
            "TRIM_MEMORY_SERVICE | level=$level ($levelName) | PID=$servicePID}"
        )

        Log.w(TAG, "⚠️ Service onTrimMemory - Level: $level ($levelName), PID: $servicePID")
    }

    // ✅ Helper 메서드 추가
    private fun getTrimMemoryLevelName(level: Int): String {
        return when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> "RUNNING_MODERATE"
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "RUNNING_LOW"
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "RUNNING_CRITICAL"
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "UI_HIDDEN"
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "BACKGROUND"
            ComponentCallbacks2.TRIM_MEMORY_MODERATE -> "MODERATE"
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "COMPLETE"
            else -> "UNKNOWN_$level"
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {

        // =====================================================================
        // TYPE_VIEW_SCROLLED — 스크롤 자체의 직접 신호 (최대 60fps)
        // 여기서는 lastActivityTime 업데이트만. 무거운 것 아무것도 안 함.
        // =====================================================================
        if (event?.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            val packageName = event.packageName?.toString() ?: return
            if (EXCLUDED_PACKAGES.contains(packageName) || packageName == "com.example.applock") return
            // 홈 화면에 있으면 무시. 위지짜트 스크롤이 활동으로 잡으면 안 됨.
            if (isHomeTransition) return
            // Long 하나의 대입. CPU cost 0. Throttle 없음.
            lastActivityTime = System.currentTimeMillis()
            return
        }

        // =====================================================================
        // TYPE_WINDOW_CONTENT_CHANGED — 스크롤 앱의 PRIMARY 활동 신호
        // 모든 타이머 모드에서 처리. 활동 감지가 먼저.
        // =====================================================================
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {

            val packageName = event.packageName?.toString() ?: return

            // EXCLUDED 패키지만 여기서 막음. className으로 필터는 하지 않음.
            if (EXCLUDED_PACKAGES.contains(packageName) || packageName == "com.example.applock") return

            // 홈 화면에 있으면 완전히 무시.
            // 백그라운드 앱의 잔여 이벤트나 홈 위지짜트 이벤트가
            // lastActivityTime 업데이트 / COUNT_DOWN 복원 / handleAppChange를 트리거하면 안 됨.
            // isHomeTransition은 앱 STATE_CHANGED에서 해제되므로,
            // 실제로 앱을 열었을 때는 STATE_CHANGED 핸들러가 COUNT_DOWN을 세팅.
            if (isHomeTransition) return

            val currentTime = System.currentTimeMillis()

            // ✅ BUG B 수정: lastActivityTime 무조건 업데이트.
            //    타이머 모드 체크 없음. 패키지 변경 체크 없음. Throttle 없음.
            lastActivityTime = currentTime

            // ✅ BUG A 수정: point-use IDLE이면 COUNT_DOWN 복원.
            //    Honeyboard 닫기 후 복귀 경로.
            //    홈 화면은 위에서 이미 차단됨.
            if (!isAccumulatingPoints && isMonitoring && timer.getCurrentMode() == TimerMode.IDLE) {
                currentActiveApp = packageName
                lastActivePackage = packageName
                timer.setMode(TimerMode.COUNT_DOWN)
                Log.d(TAG, "✅ CONTENT 이벤트: IDLE → COUNT_DOWN 복원 완료 ($packageName)")
            }

            // 무거운 처리는 throttle 안에서. 활동 시간은 위에서 이미 완료.
            if (!isContentCheckScheduled) {
                val timeSinceLastCheck = currentTime - lastContentCheckTime
                if (timeSinceLastCheck >= CONTENT_CHECK_THROTTLE_MS) {
                    scheduleContentCheck(packageName, event.className?.toString() ?: "", currentTime)
                }
            }

            return  // STATE_CHANGED 로직으로 떨어지지 않음
        }

        val packageName = event?.packageName?.toString()
        val className = event?.className?.toString()
        val currentTime = System.currentTimeMillis()

        // 노이즈 클래스 필터
        if (className == "android.widget.TextView" ||
            className == "android.widget.FrameLayout") {
            return
        }

        Log.d("LockDebug", """
        Event Details:
        - Type: ${event?.eventType}
        - Package: ${event?.packageName}
        - Class: ${event?.className}
        - Text: ${event?.text}
        - Window ID: ${event?.windowId}
        - Action: ${event?.action}
        - Content Change Types: ${event?.contentChangeTypes}
        - Movement Granularity: ${event?.movementGranularity}
        - Window Changes: ${event?.windowChanges}
        - Is Full Screen: ${event?.isFullScreen}
        """.trimIndent())
//테스트용
        event?.let {
            val eventData = AccessibilityEventData(
                //timestamp = System.currentTimeMillis(),
                eventType = it.eventType,
                packageName = it.packageName?.toString(),
                className = it.className?.toString(),
                windowId = it.windowId,
                //isFullScreen = it.isFullScreen,
            )
            FirebaseManager.getInstance().logAccessibilityEvent(userId, eventData)
        }

        // 잠금화면이 표시된 후 일정 시간이 지나면 상태 검증
        if (isLockScreenShowing && currentTime - lastLockScreenShowTime > 5000) {
            // 잠금화면이 표시된 지 5초 이상 지났고, 현재 패키지가 우리 앱이 아니면
            // 잠금화면 상태를 초기화 (사용자가 다른 방법으로 우회했을 수 있음)
            if (packageName != "com.example.applock" && !EXCLUDED_PACKAGES.contains(packageName)) {
                Log.d("StateLog", "잠금화면 상태 불일치 감지: isLockScreenShowing=$isLockScreenShowing, 현재 패키지=$packageName")
                isLockScreenShowing = false
            }
        }

        // 홈 화면 전환 감지
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            when {
                // 홈 화면으로의 전환
                packageName in setOf("com.sec.android.app.launcher", "com.samsung.android.app.launcher") -> {
                    Log.d("StateLog", "홈 화면 감지: className=$className, isHomeTransition=$isHomeTransition, timerMode=${timer.getCurrentMode()}")

                    if (className == "com.sec.android.app.launcher.Launcher") {
                        isHomeTransition = true
                        lastHomeTransitionTime = currentTime

                        // ✅ 홈 화면 전환 시 재진입 컨텍스트 종료
                        if (appBeforeLock != null) {
                            Log.d("StateLog", "홈 전환 감지 - appBeforeLock 초기화 (was: $appBeforeLock)")
                            appBeforeLock = null
                        }

                        // 타이머가 COUNT_DOWN 모드일 때 IDLE로 변경
                        if (timer.getCurrentMode() == TimerMode.COUNT_DOWN) {
                            timerState = TimerMode.COUNT_DOWN  // 상태는 저장
                            Log.d("StateLog", "홈 화면 전환 - 타이머 IDLE로 변경")
                            timer.setMode(TimerMode.IDLE)
                        }

                        // 현재 포인트 사용 모드이고 잠금화면이 아닌 경우 잠금화면 표시
                        if (!isLockScreenShowing && !isAccumulatingPoints &&
                            lastAppPackage != null && !EXCLUDED_PACKAGES.contains(lastAppPackage)
                        ) {
                            Log.d("StateLog", "홈 화면 전환 후 잠금화면 표시")
                            showLockScreen()
                        }
                    }
                }

                // 앱으로의 전환 - 일반 앱
                packageName != null && !EXCLUDED_PACKAGES.contains(packageName) -> {
                    // ✅ appBeforeLock이 있으면 홈 전환 체크 우회 (재진입 감지 우선)
                    val isReentryAttempt = (packageName == appBeforeLock)

                    // 홈 화면 전환 후 일정 시간 내에 발생한 이벤트 무시 (예: 500ms)
                    if (isHomeTransition && (currentTime - lastHomeTransitionTime) < 500 && !isReentryAttempt) {
                        Log.d("StateLog", "홈 전환 후 지속되는 이벤트 무시")
                        return
                    }

                    if (isReentryAttempt) {
                        Log.d("StateLog", "🔄 재진입 시도 감지 (appBeforeLock=$appBeforeLock) - 홈 전환 체크 우회")
                    }

                    Log.d("StateLog", "일반 앱 전환 감지: $packageName, isLockScreenShowing=$isLockScreenShowing")

                    if (packageName != "com.example.applock") {
//                        if (isInHomeProtection) {
//                            Log.d("StateLog", "앱 실행 감지로 홈 화면 보호 시간 해제")
//                            isInHomeProtection = false
//                        }
                        lastAppPackage = packageName
                        isHomeTransition = false  // 앱으로 전환될 때 홈 화면 플래그 초기화

                        // 포인트 사용 모드이면 타이머 상태를 COUNT_DOWN으로 복원
                        if (!isAccumulatingPoints) {
                            Log.d("StateLog", "타이머 모드 변경: ${timer.getCurrentMode()} -> TimerMode.COUNT_DOWN")
                            timer.setMode(TimerMode.COUNT_DOWN)
                        }

                        // 잠금화면 표시
                        if (!isLockScreenShowing && !isInTransition && !isAccumulatingPoints) {
                            showLockScreen()
                        }
                    }
                }
            }
        }


        // 최근 앱 버튼(□)에서의 앱 종료 이벤트 감지
        if (packageName == "com.sec.android.app.launcher" &&
            className == "com.android.quickstep.RecentsActivity") {

            if (event.contentChangeTypes == 4097) { // 4097: 최근 앱 목록에서 앱이 제거될 때, 스와이프로 앱 닫기, "모두 닫기" 버튼

                // 연속된 종료 이벤트 처리
                if (currentTime - lastCloseEventTime < 1000) {
                    consecutiveCloseEvents++
                } else {
                    consecutiveCloseEvents = 1
                }
                lastCloseEventTime = currentTime

                Handler(Looper.getMainLooper()).postDelayed({
                    if (!isLockScreenShowing && !isAccumulatingPoints &&
                        currentActiveApp != null && currentActiveApp != "com.example.applock") {
                        Log.d("StateLog", "최근 앱 종료 후 잠금화면 표시 시도")
                        showLockScreen()
                    }
                }, 300)
            }
            return
        }

        // Activity나 Launcher 클래스의 실제 앱 전환 이벤트만 처리
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
//            event.isFullScreen &&
            event.packageName != null &&
            event.className != null) {

            val className = event.className.toString()
            val currentTime = System.currentTimeMillis()
            val packageName = event.packageName.toString()
            val eventText = event.text

            lastEventTime = currentTime

            //usagetracker
            if (packageName != null && !EXCLUDED_PACKAGES.contains(packageName)) {
                usageTracker.onAppChanged(packageName)
            }

            // 활동 추적 업데이트
            if (packageName != lastActivePackage &&
                !EXCLUDED_PACKAGES.contains(packageName) &&
                packageName != "com.example.applock") {
                lastActivePackage = packageName
                updateLastActivityTime()
            }

//            handleAppChange(packageName)

            // systemui면서 FrameLayout인 경우 예외처리 (볼륨, 카메라, 알림 패널 감지 방지)
            if (packageName == "com.android.systemui") {
                // 볼륨패널은 항상 예외 처리 (포인트 차감 없이 타이머 계속 진행)
                if (className == "com.android.systemui.volume.view.VolumePanelWindow") {
                    Log.d(TAG, "Volume panel event - ignoring")
                    return
                }
                // 알림창 감지 추가
                if (className == "android.widget.FrameLayout" &&
                    (eventText.toString().contains("알림 세부정보"))) {
                    Log.d(TAG, "알림창 이벤트 감지 - 상태 변경 처리")
                    handleExcludedPackage()
                    return
                }
            }

            // Process app change
            if (EXCLUDED_PACKAGES.contains(packageName)) {
                if (packageName != "com.android.systemui") {  // systemui는 처리하지 않음
                    handleExcludedPackage()
                }
            } else {
                if (!isMonitoring && packageName != "com.example.applock") {
                    handleLockCondition()
                } else {
                    handleAppChange(packageName,event.eventType)
                }
            }
        }
    }

    // CONTENT 이벤트를 throttle하여 처리
    private fun scheduleContentCheck(packageName: String, className: String, eventTime: Long) {
        // 이미 예약됨 → 무시
        if (isContentCheckScheduled) {
            Log.d(TAG, "⏱️ CONTENT 체크 이미 예약됨 - 무시")
            return
        }

        // 너무 빠름 → 무시
        val timeSinceLastCheck = eventTime - lastContentCheckTime
        if (timeSinceLastCheck < CONTENT_CHECK_THROTTLE_MS) {
            Log.d(TAG, "⏱️ CONTENT 체크 간격 부족 (${timeSinceLastCheck}ms) - 무시")
            return
        }

        Log.d(TAG, "⏱️ CONTENT 체크 예약: $packageName")  // ← 추가

        // ✅ 1초 후 실행 예약
        isContentCheckScheduled = true

        contentCheckRunnable = Runnable {
            try {
                // 홈 화면에 있으면 무시.
                // 이 Runnable은 1초 후 실행된다. 예약 시점에는 홈 전환이 아니었더라도
                // 실행 시점에는 홈 화면일 수 있음. 재체크 필수.
                if (isHomeTransition) {
                    Log.d(TAG, "⏱️ CONTENT 체크 실행 시점에 홈 화면 감지 → 스킵 ($packageName)")
                    return@Runnable
                }

                Log.d(TAG, "✅ CONTENT 체크 실행: $packageName")

                lastEventTime = System.currentTimeMillis()

                // 패키지 변경 추적 (로깅용). 활동 시간은 이미 이벤트 레벨에서 업데이트됨.
                if (packageName != lastActivePackage &&
                    !EXCLUDED_PACKAGES.contains(packageName) &&
                    packageName != "com.example.applock") {
                    lastActivePackage = packageName
                }

                // 앱 변경 처리 로직
                if (EXCLUDED_PACKAGES.contains(packageName)) {
                    if (packageName != "com.android.systemui") {
                        handleExcludedPackage()
                    }
                } else {
                    if (!isMonitoring && packageName != "com.example.applock") {
                        Log.d("StateLog", "CONTENT: handleLockCondition 호출 (isMonitoring=false)")
                        handleLockCondition()
                    } else if (packageName != currentActiveApp) {
                        // ✅ 앱이 바뀌었으면 handleAppChange 호출
                        // (재진입 체크는 handleAppChange 내부에서 처리)
                        Log.d("StateLog", "CONTENT: 앱 변경 감지 ($currentActiveApp -> $packageName)")
                        handleAppChange(packageName, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
                    } else {
                        Log.d("StateLog", "CONTENT: 같은 앱 ($packageName) - 스킵")
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "CONTENT 체크 중 에러: ${e.message}")
            } finally {
                isContentCheckScheduled = false
                lastContentCheckTime = System.currentTimeMillis()
            }
        }

        contentCheckHandler.postDelayed(contentCheckRunnable!!, CONTENT_CHECK_THROTTLE_MS)
    }

    private fun handleExcludedPackage() {
        // ✅ 잠금화면이 켜져있으면 currentActiveApp 보호
        // 이유: 잠금 해제 후 홈 화면 이벤트가 와도 currentActiveApp 유지 필요
        if (isLockScreenShowing) {
            Log.d("StateLog", "handleExcludedPackage: 잠금화면 표시 중 - currentActiveApp 보호")
            return
        }

        if (currentActiveApp != null) {
            currentActiveApp = null
            if (timer.getCurrentMode() == TimerMode.COUNT_DOWN) {
                timer.setMode(TimerMode.IDLE)
            }
            broadcastTimeUpdate("")
        }
    }

    private fun handleAppChange(newApp: String, eventType: Int) {

        Log.d("StateLog", "━━━ handleAppChange 호출 ━━━")
        Log.d("StateLog", "이전 앱: $currentActiveApp → 새 앱: $newApp")
        Log.d("StateLog", "eventType: $eventType, isAccumulatingPoints: $isAccumulatingPoints")

        // ✅ 핵심 수정 1: LockScreenActivity는 앱 변경이 아님 (오버레이일 뿐)
        if (newApp == "com.example.applock") {
            Log.d("StateLog", "⛔ LockScreenActivity 이벤트 - 앱 변경 아님, 무시")
            return
        }

        // ✅ 핵심 수정 2: 패키지가 같으면 앱 변경 아님 (32번/2048번 중복 호출 방지)
        if (newApp == currentActiveApp) {
            Log.d("StateLog", "⛔ 같은 앱 ($newApp) - 앱 변경 아님, 무시")
            return
        }

        // ✅ 핵심 수정 3: 잠금 전 앱으로 재진입 = 앱 변경 아님 (잠금 우회)
        if (newApp == appBeforeLock && appBeforeLock != null) {
            Log.d("StateLog", "🔄 잠금 전 앱 재진입 ($appBeforeLock) - currentActiveApp 복원, 잠금 안 함")
            currentActiveApp = appBeforeLock
            appBeforeLock = null  // 사용 후 초기화
            return
        }

        // ✅ 새로운 앱으로 전환 = 재진입 컨텍스트 종료
        if (appBeforeLock != null && newApp != appBeforeLock) {
            Log.d("StateLog", "다른 앱으로 전환 ($appBeforeLock → $newApp) - appBeforeLock 초기화")
            appBeforeLock = null
        }

        //usagetracker
        if (!EXCLUDED_PACKAGES.contains(newApp)) {
            usageTracker.onAppChanged(newApp)
        }

        if (isMonitoring) {
            Log.d("StateLog", "앱 변경: $currentActiveApp -> $newApp, timerMode=${timer.getCurrentMode()}, isLockScreenShowing=$isLockScreenShowing")
            Log.d(TAG, "App changed: $currentActiveApp -> $newApp")

            // 포인트 사용 모드에서 새로운 앱 실행시 이벤트 로깅
            if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                !isAccumulatingPoints && !EXCLUDED_PACKAGES.contains(newApp)) {
                val eventData = UsageEventData(
                    timestamp = System.currentTimeMillis(),
                    eventType = "POINT_USE_LAUNCH"
                )
                FirebaseManager.getInstance().logUsageEvent(userId, eventData)
            }

            // 이전 앱 저장
            val prevApp = currentActiveApp
            currentActiveApp = newApp

            updateLastActivityTime()

            when {
                EXCLUDED_PACKAGES.contains(newApp) -> {
                    // 제외된 앱(설정, 홈 화면 등)으로 전환되었을 때 타이머를 IDLE로 설정
                    Log.d("StateLog", "앱 전환: 제외된 앱으로 전환됨 - 타이머 일시정지")
                    if (timer.getCurrentMode() == TimerMode.COUNT_DOWN) {
                        Log.d("StateLog", "타이머 모드 변경: ${timer.getCurrentMode()} -> TimerMode.IDLE")
                        timer.setMode(TimerMode.IDLE)
                    }
                    startInactivityCheck()
                }

                else -> {
                    // 일반 앱으로 전환
                    if (!isLockScreenShowing) {
                        val state = timer.timerState.value
                        if (isAccumulatingPoints) {
                            // 적립 모드인 경우 잠금화면 표시
                            showLockScreen()
                        } else {
                            // 포인트 사용 모드인 경우
                            if (state.availabletime > 0) {
                                // 남은 시간이 있으면 COUNT_DOWN으로 설정하고 잠금화면 표시하지 않음
                                Log.d(
                                    "StateLog",
                                    "타이머 모드 변경: ${timer.getCurrentMode()} -> TimerMode.COUNT_DOWN"
                                )
                                timer.setMode(TimerMode.COUNT_DOWN)
                                startInactivityCheck()
                                // 잠금화면이 표시되지 않도록 이중 확인
                                isLockScreenShowing = false
                            } else {
                                // 시간 소진 시에만 잠금화면 표시
                                showLockScreen()
                            }
                        }
                    } else if (!isAccumulatingPoints && timer.timerState.value.availabletime > 0) {
                        // 이미 잠금화면이 표시되어 있고, 포인트 사용 모드이면서 시간이 남아있는 경우
                        // LockScreenActivity 종료 시도
                        isLockScreenShowing = false
                        Log.d("StateLog", "포인트 사용 모드에서 불필요한 잠금화면 상태 감지 - 초기화")

                        // 잠재적인 잠금 화면을 닫기 위해 홈 인텐트 전송
                        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                            addCategory(Intent.CATEGORY_HOME)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        startActivity(homeIntent)
                    }
                }
            }

            broadcastTimeUpdate(newApp)
            updateNotification()
        }
    }

    private fun handleTimeoutCondition() {
        currentActiveApp = null
        showLockScreen()

        isAccumulatingPoints = true
        isMonitoring = true
        timer.setMode(TimerMode.COUNT_UP)

        updateNotification()
        showTimeoutNotification()
    }

    // Timer에서 자동 소진 시 호출되는 메서드
    fun onTimerDepleted() {

        //usagetracker
        usageTracker.endSession()
        Log.d(TAG, "✅ UsageTracker session ended (timer depleted)")

        Log.d("PointUsageLog", "=== onTimerDepleted 진입 ===")
        logCountdownEvent("END", "포인트소진")

        // 포인트 사용 세션이 있었다면 기록
        if (isPointUseSession && pointUsageSessionStartTime > 0) {
            val currentTime = System.currentTimeMillis()
            val remainingTime = timer.timerState.value.availabletime

//            val pointData = PointUsageData(
//                pointsUsed = initialSessionPointAmount - remainingTime,
//                returnedPoints = remainingTime
//            )

//            try {
//                FirebaseManager.getInstance().logPointUsage(userId, pointData, pointUsageSessionStartTime)
//                Log.d("PointUsageLog", "Firebase logPointUsage 호출 성공")
//            } catch (e: Exception) {
//                Log.e("PointUsageLog", "Firebase logPointUsage 실패: ${e.message}", e)
//            }

            // 세션 변수 초기화
            pointUsageSessionStartTime = 0L
            initialSessionPointAmount = 0f
            isPointUseSession = false
        } else {
            Log.w("PointUsageLog", "포인트 사용 세션이 없음 - 로그 저장 건너뜀")
        }

        // 적립 모드로 전환
        currentActiveApp = null
        isAccumulatingPoints = true
        isMonitoring = true

        // 새로운 세션 시작
        currentSessionStartTime = System.currentTimeMillis()

        // 잠금화면 표시
        showLockScreen()
        updateNotification()
        showTimeoutNotification()
    }

    fun handleLockCondition() {
        if (!isMonitoring) {
            stopMonitoring()
            currentActiveApp = null

            if (skipNextLock) {
                skipNextLock = false
                return
            }

            showLockScreen()

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.cancel(NOTIFICATION_ID_SERVICE)
        }
    }

    private fun showLockScreen() {
        // ✅ 호출 위치 추적 (디버깅용)
        val caller = Thread.currentThread().stackTrace.getOrNull(3)?.let {
            "${it.className}.${it.methodName}:${it.lineNumber}"
        } ?: "unknown"

        Log.d("StateLog", "━━━ showLockScreen 진입 ━━━")
        Log.d("StateLog", "호출자: $caller")
        Log.d("StateLog", "상태: isLockScreenShowing=$isLockScreenShowing, isAccumulating=$isAccumulatingPoints")
        Log.d("StateLog", "현재 앱: $currentActiveApp")

        // ✅ 전역 디바운스 체크 (프로세스 레벨, Thread-safe)
        // 같은 앱의 중복 잠금만 차단, 다른 앱 전환은 허용
        if (!checkAndUpdateLockTime(this, currentActiveApp)) {
            Log.d("StateLog", "━━━ showLockScreen 차단됨 (디바운스) ━━━")
            return  // 디바운스에 의해 차단됨
        }

        //음수 허용
        if (!isAccumulatingPoints) {
            Log.d("StateLog", "━━━ showLockScreen 차단됨 (포인트 사용 모드) ━━━")
            return  // 포인트 사용 모드면 무조건 잠금 안 함
        }

        if (isLockScreenShowing) {
            Log.d("StateLog", "━━━ showLockScreen 차단됨 (이미 표시 중) ━━━")
            return
        }

        // ✅ 잠금화면 표시 전 현재 앱 저장 (재진입 감지용)
        appBeforeLock = currentActiveApp
        Log.d("StateLog", "💾 잠금 전 앱 저장: $appBeforeLock")

        isLockScreenShowing = true
        lastLockScreenTime = System.currentTimeMillis()

        val intent = Intent(this, LockScreenActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
            addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }
        startActivity(intent)

        Log.d("StateLog", "🔒 잠금화면 Activity 시작됨")
        Log.d("StateLog", "━━━ showLockScreen 완료 ━━━")

        // 적립 모드일 때만 이벤트 로깅
        if (isAccumulatingPoints) {
            val eventData = UsageEventData(
                timestamp = System.currentTimeMillis(),
                eventType = "LOCKSCREEN"
            )
            FirebaseManager.getInstance().logUsageEvent(userId, eventData)
        }

    }


    fun startMonitoring(time: Float, isAccumulating: Boolean = false) {
        isAccumulatingPoints = isAccumulating
        isMonitoring = true
        timer.setInitialState(seconds = time, isDivided = true)

        if (isAccumulating) {
            timer.setMode(TimerMode.COUNT_UP)
            logCountdownEvent("P_END")  // start 로그
            // 적립 모드에서는 포인트 사용 세션 변수들 초기화
            pointUsageSessionStartTime = 0L
            initialSessionPointAmount = 0f
            isPointUseSession = false

            // 새로운 세션 시작
            currentSessionStartTime = System.currentTimeMillis()

            prefs.edit().apply {
                putBoolean("is_accumulating", true)
                putString("timer_mode", "COUNT_UP")
                apply()
            }

        } else {
            // 포인트 사용 모드 시작시 세션 정보 저장
            pointUsageSessionStartTime = System.currentTimeMillis()
            initialSessionPointAmount = time
            isPointUseSession = true
            timer.setMode(TimerMode.IDLE)
            startInactivityCheck()

            //usagetracker
            usageTracker.startSession()
            Log.d(TAG, "✅ UsageTracker session started")

            prefs.edit().apply {
                putBoolean("is_accumulating", false)
                putString("timer_mode", "IDLE")
                apply()
            }

        }

        currentActiveApp = "com.example.applock"
        isLockScreenShowing = false

        updateNotification()
        broadcastTimeUpdate()
    }

    fun stopMonitoring() {
        if (isMonitoring) {

            //usagetracker
            usageTracker.endSession()
            Log.d(TAG, "✅ UsageTracker session ended")

            isMonitoring = false
            currentActiveApp = null
            updateNotification()
            broadcastTimeUpdate()
        }
    }

    private fun registerSystemEventReceivers() {
        val filter = IntentFilter().apply {
            // 화면 켜짐/꺼짐
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            // 사용자 해제(잠금화면 해제)
            addAction(Intent.ACTION_USER_PRESENT)
//            // 홈 버튼, 최근 앱 버튼 등의 시스템 UI 상호작용 - 12이상부터 사라짐
//            addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
            // 패키지 변경(앱 설치/제거)
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_ON -> {
                        // 화면이 켜질 때 현재 앱 상태 확인
                        if (!isLockScreenShowing && !EXCLUDED_PACKAGES.contains(currentActiveApp)) {
                            handleLockCondition()
                        }
                    }
                    Intent.ACTION_USER_PRESENT -> {

                        //usage tracker
                        if (usageTracker.isSessionActive()) {
                            usageTracker.resumeTracking(currentActiveApp)
                        }

                        // 잠금화면 해제 후 상태 확인
                        if (!isLockScreenShowing && !EXCLUDED_PACKAGES.contains(currentActiveApp)) {
                            handleLockCondition()
                        }
                    }
                    Intent.ACTION_CLOSE_SYSTEM_DIALOGS -> {
                        // 시스템 다이얼로그 닫힐 때(홈 버튼, 최근 앱 등)
                        val reason = intent.getStringExtra("reason")
                        if (reason == "homekey" || reason == "recentapps") {
                            // 홈 키나 최근 앱 버튼 눌렀을 때
                            isLockScreenShowing = false
                            pointUseMode = false
                            currentActiveApp = null
                        }
                    }
                    Intent.ACTION_SCREEN_OFF -> {

                        //usagetracker
                        usageTracker.pauseTracking()

                        // 화면이 꺼질 때 포인트 차감을 일시 중지
                        if (timer.getCurrentMode() == TimerMode.COUNT_DOWN) {
                            logCountdownEvent("END", "화면꺼짐")  // end 로그
                            timer.setMode(TimerMode.IDLE)  // IDLE 모드로 전환하여 포인트 차감 중지
                            startInactivityCheck()  // inactivity 체크 시작 (5분 후 COUNT_UP으로 전환될 수 있음)
                        }
                        // 화면이 꺼질 때 상태 초기화
                        isLockScreenShowing = false
                        pointUseMode = false
                    }
                }
            }
        }

        // ✅ Android 13+ 대응: RECEIVER_NOT_EXPORTED 플래그 추가
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
        this.screenReceiver = receiver  // 서비스 종료 시 해제를 위해 저장
    }

    /**
     * ✅ LockScreenActivity 종료 감지 BroadcastReceiver 등록
     *
     * 왜 필요한가?
     * AccessibilityService는 자신이 시작한 Activity의 생명주기를 자동으로 알 수 없습니다.
     * LockScreenActivity가 finish()되어도 AccessibilityService는 이를 감지하지 못하므로,
     * isLockScreenShowing 같은 상태 변수가 잘못된 값으로 남아있게 됩니다.
     *
     * 이로 인해 발생하는 문제:
     * - 같은 앱을 다시 진입할 때 currentActiveApp이 이미 설정되어 있어 잠금 화면이 표시되지 않음
     * - 상태 불일치로 인한 예측 불가능한 동작
     *
     * 해결 방법:
     * LockScreenActivity의 onDestroy()에서 Broadcast를 전송하고,
     * AccessibilityService에서 이를 수신하여 상태를 초기화합니다.
     */
    private fun registerLockScreenClosedReceiver() {
        try {
            val filter = IntentFilter("com.example.applock.LOCK_SCREEN_CLOSED")

            lockScreenClosedReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    Log.d(TAG, "📡 Broadcast received: LockScreenActivity closed")

                    // ✅ 핵심: 상태 완전 초기화
                    val previousApp = currentActiveApp
                    currentActiveApp = null
                    isLockScreenShowing = false
                    isInTransition = false

                    // ✅ appBeforeLock은 초기화하지 않음! (재진입 감지에 필요)
                    // appBeforeLock = null  ← 제거!

                    Log.d(TAG, "🔄 State reset via Broadcast: currentActiveApp=null (was: $previousApp), appBeforeLock 유지")

                    // 타이머 모드 복원 (포인트 사용 모드였다면)
                    if (!isAccumulatingPoints && timerState == TimerMode.COUNT_DOWN) {
                        timer.setMode(TimerMode.COUNT_DOWN)
                        Log.d(TAG, "⏱️ Timer mode restored to COUNT_DOWN")
                    }

                    broadcastTimeUpdate()
                }
            }

            // ✅ Android 13+ 대응: RECEIVER_NOT_EXPORTED 플래그 필수
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(lockScreenClosedReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(lockScreenClosedReceiver, filter)
            }

            Log.d(TAG, "✅ LockScreenClosedReceiver registered successfully")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error registering LockScreenClosedReceiver: ${e.message}", e)
            lockScreenClosedReceiver = null
        }
    }

    private fun startInactivityCheck() {
        inactivityCheckJob?.cancel() // 기존 작업이 있다면 취소
        updateLastActivityTime() // 체크 시작시 현재 시간으로 초기화

        inactivityCheckJob = serviceScope.launch {
            while (isActive) {
                delay(30000) // 30초마다 체크 (10초로 수정해서 확인)

                when (timer.getCurrentMode()) {
                    TimerMode.COUNT_DOWN -> {
                        // 포인트 사용 모드에서는 사용 중이므로 inactivity 시간을 0으로 리셋
                        updateLastActivityTime()
                        Log.d(TAG, "Inactivity check - Mode: COUNT_DOWN, Time since last activity: 0s")
                    }

                    TimerMode.IDLE -> {
                        val currentTime = System.currentTimeMillis()
                        val timeSinceLastActivity = currentTime - lastActivityTime

                        Log.d(
                            TAG,
                            "Inactivity check - Mode: IDLE, Time since last activity: ${timeSinceLastActivity / 1000}s"
                        )

                        if (timeSinceLastActivity >= INACTIVITY_TIMEOUT) {
                            Log.d(TAG, "Inactivity detected - Converting to accumulation mode")
                            // 미사용 전환임을 표시
                            isInactivityTransition = true

                            // ✅ 추가: 5분 비활성 시 현재 세션 종료 (Firebase 저장)
                            if (usageTracker.isSessionActive()) {
                                usageTracker.endSession()
                                Log.d(TAG, "✅ UsageTracker session ended (5min inactivity)")
                            }

                            // 미사용 전환 시, 세션 종료 기록 없이 적립 모드로 전환하는 로직 수행
                            withContext(Dispatchers.Main) {
                                // 현재 남은 시간과 원래 시간 저장
                                val remainingTime = timer.timerState.value.availabletime
                                lastInactivityTransitionTime = currentTime  // 비활성 전환 시간 기록

                                // 포인트 사용 내역 기록
                                if (isPointUseSession && pointUsageSessionStartTime > 0) {
                                    // 전체 포인트 사용 세션 기록
//                                    val pointData = PointUsageData(
//                                        pointsUsed = initialSessionPointAmount,
//                                        returnedPoints = remainingTime
//                                    )
//                                    FirebaseManager.getInstance().logPointUsage(userId, pointData, pointUsageSessionStartTime)


                                    // 새로운 세션 시작
                                    currentSessionStartTime = currentTime

                                    // 세션 변수들 초기화
                                    pointUsageSessionStartTime = 0L
                                    initialSessionPointAmount = 0f
                                    isPointUseSession = false
                                }

                                if (remainingTime > 0) {
                                    timer.setInitialState(remainingTime, isDivided = true)
                                }

                                // 적립 모드로 전환
                                isAccumulatingPoints = true
                                isMonitoring = true
                                timer.setMode(TimerMode.COUNT_UP)
                                logCountdownEvent("P_END", "5min inactive")  // end 로그

                                timer.resetSession()

                                // SharedPreferences 업데이트
                                prefs.edit()
                                    .putLong("last_session_start", currentSessionStartTime)
                                    .putLong("last_inactivity_transition", lastInactivityTransitionTime)

                                    .putString("last_mode", timer.getCurrentMode().name)
                                    .putFloat("last_time", timer.timerState.value.availabletime)
                                    .apply()

                                // 앱 실행을 위한 PendingIntent 생성
                                val intent = Intent(this@AppLockMonitorService, MainActivity::class.java).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                                }
                                val pendingIntent = PendingIntent.getActivity(
                                    this@AppLockMonitorService,
                                    0,
                                    intent,
                                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                                )

                                // 알림 표시
                                Toast.makeText(
                                    this@AppLockMonitorService,
                                    "5분간 활동이 없어 적립 모드로 전환되었습니다.",
                                    Toast.LENGTH_SHORT
                                ).show()

//                                // 상단바 알림
//                                val notificationBuilder = NotificationCompat.Builder(
//                                    this@AppLockMonitorService,
//                                    NOTIFICATION_CHANNEL_ID
//                                )
//                                    .setContentTitle("비활성 감지")
//                                    .setContentText("5분간 활동이 없어 적립 모드로 전환되었습니다.")
//                                    .setSmallIcon(R.drawable.khu)
//                                    .setPriority(NotificationCompat.PRIORITY_HIGH)
//                                    .setAutoCancel(true)
//                                    .setContentIntent(pendingIntent)  // PendingIntent 설정(누르면 앱으로 이동)
//
//                                val notificationManager =
//                                    getSystemService(NotificationManager::class.java)
//                                notificationManager.notify(4, notificationBuilder.build())

                                // 상태 업데이트
                                updateNotification()
                                broadcastTimeUpdate()
                            }
                            break
                        }
                    }
                    TimerMode.COUNT_UP -> {//별도 처리 필요x
                    }
                }
            }
        }
    }

    // 현재 세션 시작 시간을 반환하는 함수 추가
    fun getCurrentSessionStartTime(): Long = currentSessionStartTime

    private fun updateLastActivityTime() {
        lastActivityTime = System.currentTimeMillis()
    }

    fun broadcastTimeUpdate(currentApp: String = "") {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastBroadcastTime < BROADCAST_THROTTLE_MS) {
            Log.d(TAG, "broadcastTimeUpdate: 호출 간격이 짧아서 브로드캐스트 전송 생략")
            return
        }

        lastBroadcastTime = currentTime
        val state = timer.timerState.value
        val timeToSend = if (isAccumulatingPoints) state.totalSeconds else state.availabletime
        val currentTimerMode = timer.getCurrentMode().name
        Log.d(TAG, "broadcastTimeUpdate: mode = $currentTimerMode, availableTime = $timeToSend, isMonitoring = $isMonitoring")

        val intent = Intent(ACTION_TIME_UPDATE).apply {
            putExtra(EXTRA_AVAILABLE_TIME, timeToSend)
            putExtra(EXTRA_IS_RUNNING, isMonitoring)
            putExtra(EXTRA_CURRENT_APP, currentApp)
            // 추가: 현재 타이머 모드를 문자열로 전달 (TimerMode의 name 사용)
            putExtra("EXTRA_TIMER_MODE", timer.getCurrentMode().name)
            `package` = packageName
        }
        sendBroadcast(intent)
        Log.d(TAG, "broadcastTimeUpdate: 브로드캐스트 전송 완료")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "App Lock Monitor Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitors app usage and locks"
                setShowBadge(false)
                setBlockable(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundService() {
        val notification = createBaseNotification()
            .setContentTitle("앱 사용량 모니터링")
            .setContentText("서비스 실행 중")
            .build()

        startForeground(NOTIFICATION_ID_SERVICE, notification)
    }

    private fun updateNotification() {
        val state = timer.timerState.value
        val timeString = state.formatAvailableTime()

        val notification = createBaseNotification().apply {
            if (isMonitoring) {
                if (isAccumulatingPoints) {
                    setContentTitle("포인트 적립 중")
                    setContentText("사용 가능 시간(포인트): $timeString")
                    setSmallIcon(R.drawable.lock_im)  // 적립 중에는 lock_im
                } else {
                    setContentTitle("포인트 사용 중")
                    setContentText("사용 가능 시간(포인트): $timeString")
                    setSmallIcon(R.drawable.unlock_im)  // 사용 중에는 unlock_im
                }
            } else {
                setContentTitle("📱 앱 사용 관리")
                setContentText("터치하여 적립 시작하기")
            }
        }.build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID_SERVICE, notification)
    }

    private fun createBaseNotification(): NotificationCompat.Builder {
        val launchIntent = packageManager.getLaunchIntentForPackage("com.example.applock")?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.lock_im)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pendingIntent)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                }
            }
    }

    private fun showTimeoutNotification() {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("포인트 소진")
            .setContentText("사용 가능한 시간이 모두 소진되었습니다.")
            .setSmallIcon(R.drawable.lock_im)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID_TIMEOUT, notification)
    }

    // 시간 파이어베이스 넘기는 코드
    private fun logCountdownEvent(eventType: String, reason: String = "") {
        FirebaseManager.getInstance().logCountdownSession(
            userId = userId,
            data = CountdownSessionData(
                timestamp = System.currentTimeMillis(),
                type = eventType,
                availableTime = timer.timerState.value.availabletime,
                app = getCurrentActiveApp() ?: "null",
                reason = reason
            )
        )
    }

    // Public helper methods
    fun setSkipNextLock(skip: Boolean) {
        skipNextLock = skip
        Log.d(TAG, "Skip next lock set to: $skip")
    }

    fun setLockScreenClosing(closing: Boolean) {
        isLockScreenClosing = closing
    }

    fun unlockApp() {
        pointUseMode = true
        isLockScreenShowing = false
        Log.d(TAG, "App unlocked")
    }

    fun resetPointUseMode() {
        Log.d(TAG, "resetPointUseMode called - pointUseMode changing from $pointUseMode to false")
        pointUseMode = false
    }

    fun resetLockScreenShowing() {
        Log.d(TAG, "resetLockScreenShowing called - isLockScreenShowing changing from $isLockScreenShowing to false")
        isLockScreenShowing = false
    }

    fun resetInitialTimeFlag() {
        initialTimeSet = false
    }

    fun getAvailableTime(): Float {
        return timer.timerState.value.availabletime
    }

    fun getCurrentActiveApp(): String? {
        return currentActiveApp
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        // ✅ SERVICE_DESTROY 로그 기록 (super 호출 전에!)
        try {
            FirebaseManager.getInstance().logError(
                userId,
                ErrorType.SERVICE_ERROR,
                "SERVICE_DESTROY | PID=$servicePID | wasMonitoring=$isMonitoring"
            )
            Log.d(TAG, "🛑 AccessibilityService onDestroy - PID: $servicePID")
        } catch (e: Exception) {
            Log.e(TAG, "Error logging SERVICE_DESTROY: ${e.message}")
        }

        super.onDestroy()

        try {
            // ✅ UsageEvents 모니터링 중지
//            usageEventsMonitor?.stopMonitoring()
//            usageEventsMonitor = null

            //usage tracker
            if (usageTracker.isSessionActive()) {
                Log.d(TAG, "⚠️ Service destroyed with active session, ending session")
                usageTracker.endSession()
            }

            // ✅ Handler 정리 추가 (제일 먼저!)
            contentCheckRunnable?.let {
                contentCheckHandler.removeCallbacks(it)
            }
            isContentCheckScheduled = false
            Log.d(TAG, "✅ ContentCheck Handler 정리 완료")

            unregisterReceiver(screenReceiver)

            // ✅ LockScreenClosedReceiver 해제 (nullable 안전하게)
            lockScreenClosedReceiver?.let {
                try {
                    unregisterReceiver(it)
                    Log.d(TAG, "✅ LockScreenClosedReceiver unregistered")
                } catch (e: Exception) {
                    Log.e(TAG, "Error unregistering lockScreenClosedReceiver: ${e.message}")
                }
            }

            inactivityCheckJob?.cancel()
            serviceScope.cancel()
            instance = null
            timer.setMode(TimerMode.IDLE)
            stopMonitoring()
            resetInitialTimeFlag()
            wakeLock.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onDestroy: ${e.message}")
        }
    }
    fun isInAccumulatingMode(): Boolean {
        return isAccumulatingPoints
    }
}
