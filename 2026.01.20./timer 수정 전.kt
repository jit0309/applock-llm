package com.example.applock

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

// 타이머 모드 정의
enum class TimerMode {
    IDLE,       // 대기 상태
    COUNT_UP,   // 시간 적립 모드
    COUNT_DOWN  // 시간 소진 모드
}

data class TimerState(
    val hours: Float = 0f,
    val minutes: Float = 0f,
    val seconds: Float = 0f,
    val availabletime: Float = 0f,
    val sessionTime: Float = 0f,
    val totalSeconds: Float = 0f,
    val hourlyBonus: Float = 600f,
    val divideRate: Float = 3.0f
) {
    // 시간 증가 (적립 모드) - 30분 보너스 적용 여부 반환
    fun increment(): Pair<TimerState, Boolean> {
        var newSessionTime = sessionTime + 1f
        val previousHours = (sessionTime / 1800f).toInt() //1800 - 30분
        val currentHours = (newSessionTime / 1800f).toInt() //1800 - 30분

        val newTotalSeconds = totalSeconds + 1f
        var newAvailableTime = newTotalSeconds / divideRate

        // 1시간 보너스 적용 여부
        val bonusApplied = currentHours > previousHours
        if (bonusApplied) {
            Log.d("BonusDebug", "========== 보너스 발동! ==========")
            Log.d("BonusDebug", "발동 시각: ${System.currentTimeMillis()}")
            Log.d("BonusDebug", "sessionTime: ${sessionTime} → ${newSessionTime}")
            Log.d("BonusDebug", "보너스 전 availableTime: $newAvailableTime")
            Log.d("BonusDebug", "보너스 전 표시: ${formatAvailableTime()}")
            newAvailableTime += 300f  // 5분(300초) 추가

            Log.d("BonusDebug", "보너스 후 availableTime: $newAvailableTime")
        }

        val actualTotalSeconds = newAvailableTime * divideRate
        val newHours = (actualTotalSeconds / 3600f)
        val newMinutes = ((actualTotalSeconds % 3600f) / 60)
        val newSeconds = (actualTotalSeconds % 60f)

        if (bonusApplied) {
            Log.d("BonusDebug", "최종 시:분:초 = ${newHours.toInt()}:${newMinutes.toInt()}:${newSeconds.toInt()}")
            Log.d("BonusDebug", "최종 표시될 시간: ${String.format("%02d:%02d:%02d",
                (newAvailableTime / 3600).toInt(),
                ((newAvailableTime % 3600) / 60).toInt(),
                (newAvailableTime % 60).toInt())}")
            Log.d("BonusDebug", "===================================")
        }

        val newState = copy(
            hours = newHours,
            minutes = newMinutes,
            seconds = newSeconds,
            availabletime = (newTotalSeconds / divideRate),
            sessionTime = newSessionTime,
            totalSeconds = actualTotalSeconds
        )

        return Pair(newState, bonusApplied)
    }

//음수 허용
    fun decrement(): TimerState {
        Log.d("PointUsageLog", "decrement 호출: availabletime = $availabletime")

        val newAvailableTime = availabletime - 1  // 음수도 허용
        val newTotalSeconds = newAvailableTime * divideRate
        val newHours = (newTotalSeconds / 3600f)
        val newMinutes = ((newTotalSeconds % 3600f) / 60f)
        val newSeconds = (newTotalSeconds % 60f)

        return copy(
            hours = newHours,
            minutes = newMinutes,
            seconds = newSeconds,
            availabletime = newAvailableTime,
            totalSeconds = newTotalSeconds
        )
    }

    // 원래코드 시간 감소 (소진 모드) - 시간 소진 여부 반환
//    fun decrement(): Pair<TimerState?, Boolean> {
//        Log.d("PointUsageLog", "decrement 호출: availabletime = $availabletime")
//
//        if (availabletime <= 0) {
//            Log.d("PointUsageLog", "조건 충족: availabletime <= 0, depleted = true 반환")
//            val zeroState = copy(
//                hours = 0f,
//                minutes = 0f,
//                seconds = 0f,
//                availabletime = 0f,
//                totalSeconds = 0f
//            )
//            return Pair(zeroState, true)  // 시간 소진됨
//        }
//
//        Log.d("PointUsageLog", "아직 시간 남음: availabletime = $availabletime")
//        val newAvailableTime = availabletime - 1
//        val newTotalSeconds = newAvailableTime * divideRate
//        val newHours = (newTotalSeconds / 3600f)
//        val newMinutes = ((newTotalSeconds % 3600f) / 60f)
//        val newSeconds = (newTotalSeconds % 60f)
//
//        val result = copy(
//            hours = newHours,
//            minutes = newMinutes,
//            seconds = newSeconds,
//            availabletime = newAvailableTime,
//            totalSeconds = newTotalSeconds
//        )
//        return Pair(result, false)  // 아직 남음
//    }

    fun formatTime(): String {
        return String.format("%02d:%02d:%02d",
            hours.toInt(),
            minutes.toInt(),
            seconds.toInt()
        )
    }

    fun formatSessionTime(): String {
        return String.format("%02d:%02d:%02d",
            (sessionTime / 3600).toInt(),
            ((sessionTime % 3600) / 60).toInt(),
            (sessionTime % 60).toInt()
        )
    }

    fun formatAvailableTime(): String {
        return String.format("%02d:%02d:%02d",
            (availabletime / 3600).toInt(),
            ((availabletime % 3600) / 60).toInt(),
            (availabletime % 60).toInt()
        )
    }

    companion object {
        fun fromSeconds(totalSeconds: Float, divideRate: Float = 6.0f, isDivided: Boolean = false): TimerState {
            val actualSeconds = if (isDivided) totalSeconds * divideRate else totalSeconds
            val actualAvailableTime = actualSeconds / divideRate

            return TimerState(
                hours = (actualSeconds / 3600f),
                minutes = ((actualSeconds % 3600f) / 60f),
                seconds = (actualSeconds % 60f),
                availabletime = actualAvailableTime,
                totalSeconds = actualSeconds,
                divideRate = divideRate
            )
        }
    }
}

// 타이머 클래스 (싱글톤 패턴 적용)
class Timer {
    private val firebaseManager = FirebaseManager.getInstance()
    private var userId: String? = null

    fun setUserId(id: String) {
        userId = id
    }

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private val running = AtomicBoolean(true)
    private val currentMode = AtomicReference(TimerMode.IDLE)
    private val _timerState = MutableStateFlow(TimerState())
    val timerState: StateFlow<TimerState> = _timerState.asStateFlow()

    private val _modeFlow = MutableStateFlow<TimerMode>(TimerMode.IDLE)
    val modeFlow: StateFlow<TimerMode> = _modeFlow.asStateFlow()

    private var lastStartTime: Long = 0L
    private var initialPoints: Float = 0f

    init {
        startTimer()
    }

    // Firebase 저장 헬퍼 함수
    private fun saveToFirebase(state: TimerState, reason: String) {
        userId?.let { id ->
            try {
                firebaseManager.saveUserData(id, UserData(
                    totalSeconds = state.totalSeconds,
                    availableTime = state.availabletime,
                    divideRate = state.divideRate
                ))
                Log.d("Timer", "Firebase 저장 완료 - 이유: $reason")
            } catch (e: Exception) {
                firebaseManager.logError(id, "TIMER_UPDATE_ERROR",
                    "Error saving to Firebase ($reason): ${e.message}")
            }
        }
    }

    //음수도 허용
    private fun startTimer() {
        scope.launch {
            while (running.get()) {
                delay(1000)
                when (currentMode.get()) {
                    TimerMode.COUNT_UP -> {
                        _timerState.update { currentState ->
                            val (newState, bonusApplied) = currentState.increment()


                            newState
                        }
                        Log.d("Timer", "적립 중: ${_timerState.value.formatTime()} " +
                                "(사용가능: ${_timerState.value.formatAvailableTime()})")
                    }
                    TimerMode.COUNT_DOWN -> {
                        _timerState.update { currentState ->
                            currentState.decrement()
                        }
                        Log.d("Timer", "사용 중: ${_timerState.value.formatTime()} " +
                                "(사용가능: ${_timerState.value.formatAvailableTime()})")
                    }
                    TimerMode.IDLE -> { /* Do nothing */ }
                }
            }
        }
    }

//원래 코드
//    private fun startTimer() {
//        scope.launch {
//            while (running.get()) {
//                delay(1000)
//                when (currentMode.get()) {
//                    TimerMode.COUNT_UP -> {
//                        _timerState.update { currentState ->
//                            val (newState, bonusApplied) = currentState.increment()
//
//                            // 1시간 보너스 적용 시에만 Firebase 저장
//                            if (bonusApplied) {
//                                saveToFirebase(newState, "1시간 보너스 적용")
//                            }
//
//                            newState
//                        }
//                        Log.d("Timer", "적립 중: ${_timerState.value.formatTime()} " +
//                                "(사용가능: ${_timerState.value.formatAvailableTime()})")
//                    }
//                    TimerMode.COUNT_DOWN -> {
//                        val (newState, depleted) = _timerState.value.decrement()
//                        Log.d("PointUsageLog", "COUNT_DOWN 처리: depleted=$depleted, newState=$newState")
//
//                        if (depleted || newState == null) {
//                            Log.d("PointUsageLog", "=== 포인트 소진 감지 시작 ===")
//                            Log.d("PointUsageLog", "depleted: $depleted, newState: $newState")
//
//                            // Firebase에 직접 저장하지 않고 Service에 알림
//                            val service = AppLockMonitorService.getInstance()
//                            Log.d("PointUsageLog", "Service 인스턴스: ${service != null}")
//
//                            if (service != null) {
//                                Log.d("PointUsageLog", "onTimerDepleted() 호출")
//                                service.onTimerDepleted()
//                                Log.d("PointUsageLog", "onTimerDepleted() 완료")
//                            } else {
//                                Log.e("PointUsageLog", "ERROR: AppLockMonitorService 인스턴스가 null!")
//                            }
//
//                            setMode(TimerMode.COUNT_UP)
//                            Log.d("PointUsageLog", "=== 포인트 소진 처리 완료 ===")
//                        } else {
//                            _timerState.value = newState
//                            // ✅ 업데이트 후 0이 되었는지 즉시 체크
//                            if (newState.availabletime <= 0) {
//                                Log.d("PointUsageLog", "=== 업데이트 후 0 감지 ===")
//                                Log.d("PointUsageLog", "availabletime: ${newState.availabletime}")
//
//                                val service = AppLockMonitorService.getInstance()
//                                Log.d("PointUsageLog", "Service 인스턴스: ${service != null}")
//
//                                if (service != null) {
//                                    Log.d("PointUsageLog", "onTimerDepleted() 호출")
//                                    service.onTimerDepleted()
//                                    Log.d("PointUsageLog", "onTimerDepleted() 완료")
//                                } else {
//                                    Log.e("PointUsageLog", "ERROR: AppLockMonitorService 인스턴스가 null!")
//                                }
//
//                                setMode(TimerMode.COUNT_UP)
//                                Log.d("PointUsageLog", "=== 포인트 소진 처리 완료 ===")
//                            }
//                        }
//
//                        Log.d("Timer", "사용 중: ${_timerState.value.formatTime()} " +
//                                "(사용가능: ${_timerState.value.formatAvailableTime()})")
//                    }
//                    TimerMode.IDLE -> { /* Do nothing */ }
//                }
//            }
//        }
//    }

//음수 허용
    fun setMode(mode: TimerMode, resetSession: Boolean = false) {
        Log.d("Timer", "Setting mode to $mode (resetSession: $resetSession)")

        if (mode == TimerMode.COUNT_DOWN) {
            if (AppLockMonitorService.getInstance()?.getCurrentActiveApp() == "com.example.applock") {
                Log.d("Timer", "Prevented countdown in our app")
                currentMode.set(TimerMode.IDLE)
                _modeFlow.value = TimerMode.IDLE
                return
            }

            lastStartTime = System.currentTimeMillis()
            initialPoints = _timerState.value.availabletime
        }

        currentMode.set(mode)
        _modeFlow.value = mode

        // 모드 변경 시 Firebase 저장
        saveToFirebase(_timerState.value, "모드 변경: $mode")
    }
//원래 코드
//    fun setMode(mode: TimerMode, resetSession: Boolean = false) {
//        Log.d("Timer", "Setting mode to $mode (resetSession: $resetSession)")
//
//        if (mode == TimerMode.COUNT_DOWN) {
//            if (AppLockMonitorService.getInstance()?.getCurrentActiveApp() == "com.example.applock") {
//                Log.d("Timer", "Prevented countdown in our app")
//                currentMode.set(TimerMode.IDLE)
//                _modeFlow.value = TimerMode.IDLE
//                return
//            }
//
//            if (_timerState.value.availabletime == 0f) {
//                currentMode.set(TimerMode.IDLE)
//                _modeFlow.value = TimerMode.IDLE
//                return
//            }
//
//            lastStartTime = System.currentTimeMillis()
//            initialPoints = _timerState.value.availabletime
//        }
//
//        currentMode.set(mode)
//        _modeFlow.value = mode
//
//        // 모드 변경 시 Firebase 저장
//        saveToFirebase(_timerState.value, "모드 변경: $mode")
//    }

    fun getCurrentMode(): TimerMode = currentMode.get()

    fun resetSession() {
        Log.d("Timer", "Explicitly resetting session")
        _timerState.update { it.copy(sessionTime = 0f) }
    }

    fun setInitialState(seconds: Float, isDivided: Boolean = false) {
        val currentSessionTime = _timerState.value.sessionTime
        val currentDivideRate = _timerState.value.divideRate
        val newState = TimerState.fromSeconds(seconds, currentDivideRate, isDivided).copy(
            sessionTime = currentSessionTime,
        )
        _timerState.value = newState

        // 초기 상태 설정 시 Firebase 저장
        saveToFirebase(newState, "초기 상태 설정")
    }

    fun updateDivideRate(newRate: Float) {
        _timerState.update { currentState ->
            val newTotalSeconds = currentState.availabletime * newRate

            currentState.copy(
                hours = (newTotalSeconds / 3600f),
                minutes = ((newTotalSeconds % 3600f) / 60f),
                seconds = (newTotalSeconds % 60f),
                totalSeconds = newTotalSeconds,
                divideRate = newRate
            )
        }

        // divideRate 변경 시 Firebase 저장
        saveToFirebase(_timerState.value, "divideRate 변경: $newRate")
    }

    fun stop() {
        running.set(false)
        scope.cancel()
    }

    companion object {
        private var instance: Timer? = null
        private val lock = Any()

        fun getInstance(): Timer {
            return instance ?: synchronized(lock) {
                instance ?: Timer().also { instance = it }
            }
        }
    }
}
