package com.example.applock

import android.util.Log
import com.google.firebase.database.*
import com.google.firebase.database.ktx.database
import com.google.firebase.database.ktx.getValue
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.tasks.await
import java.util.*
import java.text.SimpleDateFormat

class FirebaseManager private constructor() {
    private val database: DatabaseReference

    init {
        // Firebase 오프라인 퍼시스턴스 활성화
        Firebase.database.apply {
            setPersistenceEnabled(true)
        }

        // 데이터베이스 레퍼런스 초기화
        database = Firebase.database.reference.apply {
            // 주요 데이터에 대한 캐싱 설정
            child("users").keepSynced(true)
            child("sessions").keepSynced(true)
            child("point_usage").keepSynced(true)
            child("app_usage_stats").keepSynced(true)
        }
    }

    companion object {
        @Volatile
        private var instance: FirebaseManager? = null

        fun getInstance(): FirebaseManager {
            return instance ?: synchronized(this) {
                instance ?: FirebaseManager().also { instance = it }
            }
        }
    }

    // 네트워크 상태 모니터링을 위한 리스너
    private val connectedListener = object : ValueEventListener {
        override fun onDataChange(snapshot: DataSnapshot) {
            val connected = snapshot.getValue<Boolean>() ?: false
            if (connected) {
                // 온라인 상태일 때 동기화 작업 수행
                database.child("users").keepSynced(true)
                database.child("sessions").keepSynced(true)
                database.child("point_usage").keepSynced(true)
                database.child("app_usage_stats").keepSynced(true)
            }
        }

        override fun onCancelled(error: DatabaseError) {
            logErrorToFirebase("system", ErrorType.DATABASE_ERROR, "Connection listener failed: ${error.message}")
        }
    }

    // 시간 추가 명령 처리 플래그
    private var isProcessingTimeCommand = false

    // 초기화 시 네트워크 모니터링 시작
    init {
        Firebase.database.getReference(".info/connected")
            .addValueEventListener(connectedListener)
    }

    // 타임스탬프 보기 쉬운 형식으로 수정
    private fun formatTimestamp(timestamp: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    // 초 단위 시간을 시:분:초 형식으로 변환하는 헬퍼 함수
    private fun formatSeconds(seconds: Float): String {
        val hours = (seconds / 3600).toInt()
        val minutes = ((seconds % 3600) / 60).toInt()
        val secs = (seconds % 60).toInt()
        return String.format("%02d:%02d:%02d", hours, minutes, secs)
    }

    // 오프라인 작업을 위한 확장 함수
    private fun DatabaseReference.setValueWithPriority(path: String, value: Any) {
        this.child(path).setValue(value)
            .addOnFailureListener { error ->
                // 오프라인 상태에서 발생한 에러 처리
                logErrorToFirebase("system", ErrorType.DATABASE_ERROR, "Offline write failed: ${error.message}")
            }
    }

    // 오류 로그
    private fun logErrorToFirebase(userId: String, errorType: String, errorDetails: String) {
        val currentTime = System.currentTimeMillis()
        val errorMap = mapOf(
            "timestamp" to currentTime,
            "formattedTime" to formatTimestamp(currentTime),
            "type" to errorType,
            "details" to errorDetails
        )
        database.child("error_logs").child(userId)
            .child(formatTimestamp(currentTime))
            .setValue(errorMap)
    }

    // 1. 사용자 관련 데이터
    fun saveUserData(userId: String, data: UserData) {
        val currentTime = System.currentTimeMillis()
        val timeInSeconds = mapOf(
            "TotalTime" to formatSeconds(data.totalSeconds), // 총 시간을 시:분:초 형태로
            "AvailableTime" to formatSeconds(data.availableTime), // 사용가능 시간을 시:분:초 형태로
            "divideRate" to data.divideRate,
            "LastUpdated" to formatTimestamp(currentTime),
//            "_timestamp" to ServerValue.TIMESTAMP  // 서버 시간 기록
        )
        database.child("users").child(userId).setValue(timeInSeconds)
            .addOnSuccessListener {
                // 로컬 캐시 업데이트
                database.child("users").child(userId).keepSynced(true)
            }
    }

    // 2. 세션 데이터
    // COUNT_DOWN 세션 로그 (START/END 통합)
    fun logCountdownSession(userId: String, data: CountdownSessionData) {
        val logMap = mapOf(
            "Type" to data.type,  // "START" 또는 "END"
            "AvailableTime" to formatSeconds(data.availableTime),
//            "App" to data.app,
            "Reason" to data.reason  // START일 때는 "", END일 때는 "화면꺼짐", "포인트소진" 등
        )

        database.child("countdown_sessions").child(userId)
            .child(formatTimestamp(data.timestamp))
            .setValue(logMap)
            .addOnSuccessListener {
                Log.d("FirebaseManager", "✅ Countdown session log saved: ${data.type}")
            }
            .addOnFailureListener { error ->
                Log.e("FirebaseManager", "❌ Failed to save countdown session: ${error.message}")
            }
    }

    // 3. 포인트 사용 기록
    fun logPointUsage(userId: String, pointData: PointUsageData, sessionStartTime: Long) {
        val actualUsedPoints = pointData.pointsUsed - pointData.returnedPoints

        val pointMap = mapOf(
            "PointsUsed" to formatSeconds(actualUsedPoints),
            "ReturnedPoints" to formatSeconds(pointData.returnedPoints),
//            "_timestamp" to ServerValue.TIMESTAMP,  // 서버 시간 기록
        )
        database.child("point_usage").child(userId)
            .child(formatTimestamp(sessionStartTime))
            .setValue(pointMap)
            .addOnSuccessListener {
                // 로컬 캐시 업데이트
                database.child("point_usage").child(userId).keepSynced(true)
            }
    }

    // 4. 에러 로그
    fun logError(userId: String, errorType: String, errorDetails: String) {
        when (errorType) {
            ErrorType.DATABASE_ERROR -> {
                // Firebase 연결/저장 실패 등 심각한 데이터베이스 오류
                logErrorToFirebase(userId, errorType, errorDetails)
            }
            ErrorType.PERMISSION_ERROR -> {
                // 앱 실행에 필수적인 권한 거부/해제
                logErrorToFirebase(userId, errorType, errorDetails)
            }
            ErrorType.SERVICE_ERROR -> {
                // AccessibilityService가 예기치 않게 종료된 경우
                logErrorToFirebase(userId, errorType, errorDetails)
            }
            ErrorType.TIMER_SYNC_ERROR -> {
                // 타이머 상태가 비정상적으로 변경된 경우
                logErrorToFirebase(userId, errorType, errorDetails)
            }
            ErrorType.DATA_CORRUPTION -> {
                // 저장된 시간/포인트 데이터가 손상된 경우
                logErrorToFirebase(userId, errorType, errorDetails)
            }
        }
    }

    // 5. 임시 시간 사용 로그
    fun logTempTimeUsage(userId: String, data: TempTimeUsageData) {
        val tempTimeMap = mapOf(
//            "usageTime" to data.usageTime,
            "UsageTime" to formatTimestamp(data.usageTime),
            "addedPoints" to data.addedPoints,
//            "_timestamp" to ServerValue.TIMESTAMP,  // 서버 시간 기록
        )
        database.child("temp_time_usage").child(userId)
            .child(formatTimestamp(data.usageTime))
            .setValue(tempTimeMap)
            .addOnSuccessListener {
                // 로컬 캐시 업데이트
                database.child("temp_time_usage").child(userId).keepSynced(true)
            }
    }

    // 6. 비율 변경 로그 - availabletime 삭제하기, 변경시 감지하기
    fun logRateChange(userId: String, data: RateChangeData) {
        val rateChangeMap = mapOf(
//            "timestamp" to data.timestamp,
            "Timestamp" to formatTimestamp(data.timestamp),
            "oldRate" to data.oldRate,
            "newRate" to data.newRate,
            "totalSeconds" to formatSeconds(data.totalSeconds),
            "availableTimeBefore" to formatSeconds(data.availableTimeBefore),
            "availableTimeAfter" to formatSeconds(data.availableTimeAfter),
//            "_timestamp" to ServerValue.TIMESTAMP,  // 서버 시간 기록
        )
        database.child("rate_changes").child(userId)
            .child(formatTimestamp(data.timestamp))
            .setValue(rateChangeMap)
            .addOnSuccessListener {
                // 로컬 캐시 업데이트
                database.child("rate_changes").child(userId).keepSynced(true)
            }
    }

    // 8. 앱 사용량(앱 실행횟수)
    fun logUsageEvent(userId: String, eventData: UsageEventData) {
        val formattedTimeKey = formatTimestamp(eventData.timestamp)

        val eventMap = mapOf(
            "eventType" to eventData.eventType,
            "formattedTime" to formatTimestamp(eventData.timestamp)
        )

        val path = when (eventData.eventType) {
            "LOCKSCREEN" -> "Lock_mode_events"
            "POINT_USE_LAUNCH" -> "Point_use_mode_events"
            else -> "other_events"
        }

        database.child("usage_events")
            .child(userId)
            .child(path)
            .child(formattedTimeKey)
            .setValue(eventMap)
            .addOnSuccessListener {
                Log.d("FirebaseManager", "Successfully logged $path event")
            }
            .addOnFailureListener { e ->
                Log.e("FirebaseManager", "Failed to log usage event: ${e.message}")
                logError(
                    userId,
                    ErrorType.DATABASE_ERROR,
                    "Failed to log usage event: ${e.message}"
                )
            }
    }

    // 껐다 킨 로그
    fun logBootEvent(userId: String, data: BootEventData) {
        val bootEventMap = mapOf(
            "Timestamp" to formatTimestamp(data.timestamp),
            "EventType" to data.eventType,
            "WasServiceRunning" to data.wasServiceRunning
        )

        database.child("boot_events").child(userId)
            .child(formatTimestamp(data.timestamp))
            .setValue(bootEventMap)
            .addOnSuccessListener {
                database.child("boot_events").child(userId).keepSynced(true)
            }
            .addOnFailureListener { error ->
                logError(userId, ErrorType.DATABASE_ERROR, "Failed to log boot event: ${error.message}")
            }
    }

    //파이어베이스 시간 추가 리스너(앱으로 보냄)
    fun observeTimeCommand(userId: String, callback: (Float) -> Unit) {
        database.child("users").child(userId).child("addTimeCommand")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {

                    if (isProcessingTimeCommand) return

                    val addTime = snapshot.getValue(Float::class.java)
                    if (addTime != null && addTime > 0) {
                        Log.d("FirebaseManager", "⏰ Time command: +$addTime seconds")
                        isProcessingTimeCommand = true
                        callback(addTime)

                        // 사용 후 0으로 리셋
                        snapshot.ref.setValue(0).addOnCompleteListener {
                            isProcessingTimeCommand = false
                        }
                    }
                }
                override fun onCancelled(error: DatabaseError) {
                    Log.e("FirebaseManager", "Error: ${error.message}")
                    isProcessingTimeCommand = false
                }
            })
    }

    // Firebase에서 원격 제어 명령 감지
    fun observeRemoteControl(userId: String, callback: (RemoteControlData) -> Unit) {
        database.child("remoteControl").child(userId)
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    // String과 Number 둘 다 안전하게 처리
                    val setAvailableTime = try {
                        when (val value = snapshot.child("setAvailableTime").value) {
                            is Number -> value.toFloat()
                            is String -> value.toFloatOrNull()
                            else -> null
                        }
                    } catch (e: Exception) {
                        Log.e("FirebaseManager", "Error parsing availableTime: ${e.message}")
                        null
                    }

                    val setDivideRate = try {
                        when (val value = snapshot.child("setDivideRate").value) {
                            is Number -> value.toFloat()
                            is String -> value.toFloatOrNull()
                            else -> null
                        }
                    } catch (e: Exception) {
                        Log.e("FirebaseManager", "Error parsing divideRate: ${e.message}")
                        null
                    }

                    if (setAvailableTime != null || setDivideRate != null) {
                        Log.d("FirebaseManager", "🎮 Remote control: availableTime=$setAvailableTime, divideRate=$setDivideRate")

                        callback(RemoteControlData(
                            availableTime = setAvailableTime,
                            divideRate = setDivideRate
                        ))

                        // 사용 후 삭제
                        snapshot.ref.removeValue()
                    }
                }
                override fun onCancelled(error: DatabaseError) {
                    Log.e("FirebaseManager", "Remote control error: ${error.message}")
                }
            })
    }

    // 시간 추가 명령 실행 로그
    fun logAddTimeCommand(userId: String, data: AddTimeCommandData) {
        // Firebase의 "add_time_commands/{userId}/{timestamp}" 경로에 저장
    }

    // 원격 제어 실행 로그
    fun logRemoteControlExecution(userId: String, data: RemoteControlLogData) {
        // Firebase의 "remote_control_logs/{userId}/{timestamp}" 경로에 저장
    }

    // 챗봇 대화 로그
    fun saveChatMessage(userId: String, messageData: ChatMessageData) {
        val chatMessageMap = mapOf(
            "role" to messageData.role,
            "content" to messageData.content,
            )

        database.child("chat_messages").child(userId)
            .child(formatTimestamp(messageData.timestamp))
            .setValue(chatMessageMap)
    }

    // 챗봇 시간 추가 로그
    fun logChatbotTimeApproval(userId: String, data: ChatbotTimeApprovalData) {
        val logMap = mapOf(
            "Timestamp" to formatTimestamp(data.timestamp),
            "PreviousAvailableTime" to formatSeconds(data.previousAvailableTime),
            "NewAvailableTime" to formatSeconds(data.newAvailableTime),
        )

        database.child("chatbot_approvals").child(userId)
            .child(formatTimestamp(data.timestamp))
            .setValue(logMap)
            .addOnSuccessListener {
                Log.d("FirebaseManager", "✅ Chatbot approval logged successfully")
            }
            .addOnFailureListener { error ->
                Log.e("FirebaseManager", "❌ Failed to log chatbot approval: ${error.message}")
            }
    }


}


// 데이터 클래스들
data class UserData(
    val totalSeconds: Float = 0f,
    val availableTime: Float = 0f,
    val divideRate: Float = 3.0f,
    val lastUpdated: Long = 0L
)

//data class SessionData(
//    val startTime: Long = 0L,
//    val endTime: Long = 0L,
//    val accumulatedTime: Float = 0f,
//    val bonusApplied: Float = 0f
//)
//
data class PointUsageData(
    val startTime: Long = 0L,
    val endTime: Long = 0L,
    val pointsUsed: Float = 0f,
    val returnedPoints: Float = 0f
)

// 데이터 클래스 추가 (맨 아래)
data class CountdownSessionData(
    val timestamp: Long = System.currentTimeMillis(),
    val type: String = "",  // "START" 또는 "END"
    val availableTime: Float = 0f,
    val app: String = "null",
    val reason: String = ""  // END일 때만: "화면꺼짐", "포인트소진", "5분미사용", "홈화면"
)

data class TempTimeUsageData(
    val usageTime: Long = System.currentTimeMillis(),
    val addedPoints: Float = 0f
)

object ErrorType {
    const val DATABASE_ERROR = "DATABASE_ERROR"           // Firebase 데이터베이스 연결/저장 실패
    const val PERMISSION_ERROR = "PERMISSION_ERROR"       // 필수 권한 획득 실패
    const val SERVICE_ERROR = "SERVICE_ERROR"            // AccessibilityService 비정상 종료
    const val TIMER_SYNC_ERROR = "TIMER_SYNC_ERROR"      // 타이머 상태 동기화 실패
    const val DATA_CORRUPTION = "DATA_CORRUPTION"         // 저장된 데이터 손상/불일치
}

data class RateChangeData(
    val timestamp: Long = System.currentTimeMillis(),
    val oldRate: Float = 0f,
    val newRate: Float = 0f,
    val totalSeconds: Float = 0f,
    val availableTimeBefore: Float = 0f,
    val availableTimeAfter: Float = 0f
)

//// 포인트모드에서 5분 미사용시 적립모드로 넘어간 로그
//data class InactivityTransitionData(
//    val timestamp: Long = System.currentTimeMillis(),
//    val remainingPoints: Float = 0f,    // 전환 시점의 남은 포인트
//    val sessionDuration: Long = 0L,     // 포인트 사용 세션이 지속된 시간
//    val originalPoints: Float = 0f      // 처음 사용 시작했던 포인트
//)

// 새로운 이벤트 데이터 클래스들
data class UsageEventData(
    val timestamp: Long = System.currentTimeMillis(),
    val eventType: String = "",  // "LOCKSCREEN" 또는 "POINT_USE_LAUNCH"
    val formattedTime: String = ""  // 보기 쉬운 시간 형식
)

// 껐다 킨 로그
data class BootEventData(
    val timestamp: Long = System.currentTimeMillis(),
    val eventType: String = "",  // "BOOT_COMPLETED" 등
    val wasServiceRunning: Boolean = false
)
// 원격 제어 데이터
data class RemoteControlData(
    val availableTime: Float? = null,
    val divideRate: Float? = null
)

// 시간 추가 명령 로그 데이터
data class AddTimeCommandData(
    val timestamp: Long = System.currentTimeMillis(),
    val addedTime: Float = 0f,              // 추가된 시간 (초)
    val previousAvailableTime: Float = 0f,  // 추가 전 사용가능 시간
    val newAvailableTime: Float = 0f        // 추가 후 사용가능 시간
)

// 원격 제어 실행 로그 데이터
data class RemoteControlLogData(
    val timestamp: Long = System.currentTimeMillis(),
    val changedField: String = "",           // "availableTime" 또는 "divideRate" 또는 "both"
    val previousAvailableTime: Float? = null,
    val newAvailableTime: Float? = null,
    val previousDivideRate: Float? = null,
    val newDivideRate: Float? = null
)

// 챗봇 채팅 데이터 저장
data class ChatMessageData(
    val role: String = "",
    val content: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

// 챗봇 시간 추가 데이터
data class ChatbotTimeApprovalData(
    val timestamp: Long = System.currentTimeMillis(),
    val previousAvailableTime: Float,
    val newAvailableTime: Float
)