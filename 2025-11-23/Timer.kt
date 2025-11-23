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
    // 시간 증가 (적립 모드) - 1시간 보너스 적용 여부 반환
    fun increment(): Pair<TimerState, Boolean> {
        var newSessionTime = sessionTime + 1f
        val previousHours = (sessionTime / 3600f).toInt()
        val currentHours = (newSessionTime / 3600f).toInt()

        val newTotalSeconds = totalSeconds + 1f
        var newAvailableTime = newTotalSeconds / divideRate

        // 1시간 보너스 적용 여부
        val bonusApplied = currentHours > previousHours
        if (bonusApplied) {
            newAvailableTime += 600f  // 10분(600초) 추가
        }

        val actualTotalSeconds = newAvailableTime * divideRate
        val newHours = (actualTotalSeconds / 3600f)
        val newMinutes = ((actualTotalSeconds % 3600f) / 60)
        val newSeconds = (actualTotalSeconds % 60f)

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

    // 시간 감소 (소진 모드) - 시간 소진 여부 반환
    fun decrement(): Pair<TimerState?, Boolean> {
        if (availabletime <= 0) {
            val zeroState = copy(
                hours = 0f,
                minutes = 0f,
                seconds = 0f,
                availabletime = 0f,
                totalSeconds = 0f
            )
            return Pair(zeroState, true)  // 시간 소진됨
        }

        val newAvailableTime = availabletime - 1
        val newTotalSeconds = newAvailableTime * divideRate
        val newHours = (newTotalSeconds / 3600f)
        val newMinutes = ((newTotalSeconds % 3600f) / 60f)
        val newSeconds = (newTotalSeconds % 60f)

        val result = copy(
            hours = newHours,
            minutes = newMinutes,
            seconds = newSeconds,
            availabletime = newAvailableTime,
            totalSeconds = newTotalSeconds
        )
        return Pair(result, false)  // 아직 남음
    }

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

    private fun startTimer() {
        scope.launch {
            while (running.get()) {
                delay(1000)
                when (currentMode.get()) {
                    TimerMode.COUNT_UP -> {
                        _timerState.update { currentState ->
                            val (newState, bonusApplied) = currentState.increment()

                            // 1시간 보너스 적용 시에만 Firebase 저장
                            if (bonusApplied) {
                                saveToFirebase(newState, "1시간 보너스 적용")
                            }

                            newState
                        }
                        Log.d("Timer", "적립 중: ${_timerState.value.formatTime()} " +
                                "(사용가능: ${_timerState.value.formatAvailableTime()})")
                    }
                    TimerMode.COUNT_DOWN -> {
                        val (newState, depleted) = _timerState.value.decrement()

                        if (depleted || newState == null) {
                            Log.d("Timer", "시간 소진됨")
                            Log.d("Timer", "currentPoints: ${_timerState.value.availabletime}")
                            Log.d("Timer", "initialPoints: $initialPoints")
                            Log.d("Timer", "lastStartTime: $lastStartTime")
                            Log.d("Timer", "userId: $userId")

                            // 시간 소진 시 Firebase 저장
                            newState?.let { saveToFirebase(it, "시간 소진") }

                            // 포인트 사용 기록
                            userId?.let { id ->
                                val currentPoints = _timerState.value.availabletime
                                val pointData = PointUsageData(
                                    startTime = lastStartTime,
                                    endTime = System.currentTimeMillis(),
                                    pointsUsed = initialPoints - currentPoints,
                                    returnedPoints = currentPoints
                                )
                                Log.d("Timer", "pointData: $pointData")
                                firebaseManager.logPointUsage(id, pointData)
                                Log.d("Timer", "logPointUsage 호출 완료")
                            }
                            setMode(TimerMode.COUNT_UP)
                        } else {
                            _timerState.value = newState
                        }

                        Log.d("Timer", "사용 중: ${_timerState.value.formatTime()} " +
                                "(사용가능: ${_timerState.value.formatAvailableTime()})")
                    }
                    TimerMode.IDLE -> { /* Do nothing */ }
                }
            }
        }
    }

    fun setMode(mode: TimerMode, resetSession: Boolean = false) {
        Log.d("Timer", "Setting mode to $mode (resetSession: $resetSession)")

        if (mode == TimerMode.COUNT_DOWN) {
            if (AppLockMonitorService.getInstance()?.getCurrentActiveApp() == "com.example.applock") {
                Log.d("Timer", "Prevented countdown in our app")
                currentMode.set(TimerMode.IDLE)
                _modeFlow.value = TimerMode.IDLE
                return
            }

            if (_timerState.value.availabletime == 0f) {
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

        if (resetSession) {
            Log.d("Timer", "Resetting session as explicitly requested")
            _timerState.update { it.copy(sessionTime = 0f) }
            userId?.let { id ->
                val sessionData = SessionData(
                    startTime = System.currentTimeMillis(),
                    endTime = System.currentTimeMillis(),
                    accumulatedTime = 0f,
                    bonusApplied = 0f
                )
                firebaseManager.saveSessionData(id, sessionData)
            }
        }
    }

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
