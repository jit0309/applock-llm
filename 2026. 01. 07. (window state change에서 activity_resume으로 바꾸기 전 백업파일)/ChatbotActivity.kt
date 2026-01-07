package com.example.applock

import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.applock.data.ChatDatabase
import com.example.applock.data.ChatDao
import com.example.applock.data.ChatMessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ChatbotActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "ChatbotActivity"
        private const val OPENAI_API_KEY =""
        private const val API_URL = "https://api.openai.com/v1/chat/completions"
    }

    //파이어베이스 userid
    private lateinit var userId: String

    private lateinit var recyclerView: RecyclerView
    private lateinit var editTextMessage: EditText
    private lateinit var buttonSend: ImageButton
    private lateinit var progressBar: ProgressBar
    private lateinit var chatAdapter: ChatAdapter

    private val messages = mutableListOf<ChatMessage>()
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // 챗봇으로 시간 추가
    private val timer = Timer.getInstance()


    // Room DB
    private lateinit var chatDao: ChatDao

    override fun onCreate(savedInstanceState: Bundle?) {
        userId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        try {
            Log.d(TAG, "onCreate: Starting")
            super.onCreate(savedInstanceState)
            setContentView(R.layout.activity_chatbot)

            // DB 초기화
            val database = ChatDatabase.getDatabase(applicationContext)
            chatDao = database.chatDao()

            initViews()
            setupRecyclerView()
            setupListeners()

            // 이전 대화 불러오기 또는 새로 시작
            lifecycleScope.launch {
                loadPreviousMessages()

            }

            Log.d(TAG, "onCreate: Completed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "onCreate: Error occurred", e)
        }
    }

    private fun initViews() {
        recyclerView = findViewById(R.id.recyclerViewChat)
        editTextMessage = findViewById(R.id.editTextMessage)
        buttonSend = findViewById(R.id.buttonSend)
        progressBar = findViewById(R.id.progressBar)
    }

    private fun setupRecyclerView() {
        chatAdapter = ChatAdapter(messages)
        recyclerView.apply {
            layoutManager = LinearLayoutManager(this@ChatbotActivity)
            adapter = chatAdapter
        }
    }

    private fun setupListeners() {
        buttonSend.setOnClickListener {
            val message = editTextMessage.text.toString().trim()
            if (message.isNotEmpty()) {
                sendMessage(message)
            }
        }
    }

    /**
     * 이전 대화 불러오기
     */
    private suspend fun loadPreviousMessages() = withContext(Dispatchers.IO) {
        val savedMessages = chatDao.getAllMessages()

        withContext(Dispatchers.Main) {
            if (savedMessages.isEmpty()) {
                // 첫 실행 - System Role 추가 ⭐
                val systemPrompt = """   
                
                    너는 스마트폰 사용 협상가다.
                    
                    [출력 형식] - JSON만 출력
                    
                    반드시 아래 JSON 형식으로만 응답:
                    
                    {
                      "판정": "승인" 또는 "거부",
                      "복구시간": 숫자 (분 단위, 거부시 0),
                      "설명": "간단한 설명"
                    }
                    
                    예시 1 (승인):
                    {
                      "판정": "승인",
                      "복구시간": 15,
                      "설명": "학습 영상 시청 근거가 명확하므로 70% 복구를 인정합니다."
                    }
                    
                    예시 2 (거부):
                    {
                      "판정": "거부",
                      "복구시간": 0,
                      "설명": "근거가 불충분합니다. 구체적인 증거를 제시하세요."
                    }
                    
                    절대로 다른 형식으로 답변하지 마라.            
                """.trimIndent()

                // System 메시지 DB에 저장 (화면엔 안 보임)
                saveMessageToDB("system", systemPrompt)

                // 환영 메시지 (화면에 보임)
                val welcomeMsg = "안녕하세요! 협상 전문가입니다. 어떤 협상을 도와드릴까요?"
                addBotMessage(welcomeMsg)
                saveMessageToDB("assistant", welcomeMsg)
            } else {
                // 이전 대화 복원
                savedMessages.forEach { msg ->
                    if (msg.role == "user") {
                        messages.add(ChatMessage(msg.content, true))
                    } else if (msg.role == "assistant") {
                        messages.add(ChatMessage(msg.content, false))
                    }
                    // system role은 화면에 표시 안 함
                }
                chatAdapter.notifyDataSetChanged()
                scrollToBottom()
                Log.d(TAG, "Loaded ${savedMessages.size} previous messages")
            }
        }
    }

    /**
     * 메시지 DB에 저장
     */
    private suspend fun saveMessageToDB(role: String, content: String) =
        withContext(Dispatchers.IO) {
            val message = ChatMessageEntity(
                role = role,
                content = content,
                timestamp = System.currentTimeMillis()
            )
            chatDao.insertMessage(message)
        }

    /**
     * 전체 대화 내역 가져오기 (OpenAI API 형식)
     */
    private suspend fun getAllMessagesForAPI(): List<Map<String, String>> =
        withContext(Dispatchers.IO) {
            val allMessages = chatDao.getAllMessages()
            allMessages.map { msg ->
                mapOf(
                    "role" to msg.role,
                    "content" to msg.content
                )
            }
        }

    /**
     * 메시지 전송
     */
    private fun sendMessage(message: String) {
        // 사용자 메시지 추가
        addUserMessage(message)
        editTextMessage.text.clear()

        // DB에 저장
        lifecycleScope.launch {
            try {
                saveMessageToDB("user", message)
                FirebaseManager.getInstance().saveChatMessage(
                    userId,
                    ChatMessageData("user", message)
                )
                Log.d(TAG, "User message saved to DB")
            } catch (e: Exception) {
                Log.e(TAG, "Error saving user message", e)
            }
        }

        // API 호출
        showLoading(true)
        lifecycleScope.launch {
            try {
                // 전체 대화 내역 포함
                val history = getAllMessagesForAPI()
                val response = callOpenAI(history)

                // 봇 응답 추가 및 저장
                addBotMessage(response)
                saveMessageToDB("assistant", response)
                parseAndAddTime(response)
                FirebaseManager.getInstance().saveChatMessage(
                    userId,
                    ChatMessageData("assistant", response)
                )
                Log.d(TAG, "Assistant message saved to DB")
            } catch (e: Exception) {
                Log.e(TAG, "Error calling OpenAI API", e)
                val errorMessage = "죄송합니다. 오류가 발생했습니다: ${e.message}"
                addBotMessage(errorMessage)
                saveMessageToDB("assistant", errorMessage)
            } finally {
                showLoading(false)
            }
        }
    }

    private suspend fun callOpenAI(conversationHistory: List<Map<String, String>>): String =
        withContext(Dispatchers.IO) {
            val json = JSONObject().apply {
                put("model", "gpt-4o-mini")
                put("messages", JSONArray().apply {
                    conversationHistory.forEach { msg ->
                        put(JSONObject().apply {
                            put("role", msg["role"])
                            put("content", msg["content"])
                        })
                    }
                })
                put("max_tokens", 300)
                put("temperature", 0.7)
            }

            val requestBody = json.toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())

            val request = Request.Builder()
                .url(API_URL)
                .addHeader("Authorization", "Bearer $OPENAI_API_KEY")
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: throw Exception("Empty response")

            if (!response.isSuccessful) {
                throw Exception("API Error: ${response.code} - $responseBody")
            }

            val jsonResponse = JSONObject(responseBody)
            jsonResponse.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        }

    private fun addUserMessage(text: String) {
        messages.add(ChatMessage(text, true))
        chatAdapter.notifyItemInserted(messages.size - 1)
        scrollToBottom()
    }

    private fun addBotMessage(text: String) {
        messages.add(ChatMessage(text, false))
        chatAdapter.notifyItemInserted(messages.size - 1)
        scrollToBottom()
    }

    private fun scrollToBottom() {
        recyclerView.smoothScrollToPosition(messages.size - 1)
    }

    private fun showLoading(show: Boolean) {
        progressBar.visibility = if (show) View.VISIBLE else View.GONE
        buttonSend.isEnabled = !show
    }

    // 챗봇 시간 추가
    private fun parseAndAddTime(response: String) {
        try {
            Log.d(TAG, "========== parseAndAddTime 시작 ==========")
            Log.d(TAG, "GPT 응답 전문: $response")

            // JSON 파싱
            val jsonResponse = JSONObject(response)
            val judgment = jsonResponse.optString("판정", "")
            val minutes = jsonResponse.optInt("복구시간", 0)
            val explanation = jsonResponse.optString("설명", "")

            Log.d(TAG, "판정: $judgment")
            Log.d(TAG, "복구시간: $minutes 분")
            Log.d(TAG, "설명: $explanation")

            if (judgment == "승인" && minutes > 0) {
                Log.d(TAG, "✅ 승인 감지!")

                val currentState = timer.timerState.value
                val timeInSeconds = minutes * 60f
                val addedPoints = timeInSeconds * currentState.divideRate

                timer.setInitialState(currentState.totalSeconds + addedPoints)

                //sharedpreference에 저장
                val prefs = getSharedPreferences("AppLockPrefs", MODE_PRIVATE)
                prefs.edit()
                    .putFloat("accumulated_points", currentState.totalSeconds + addedPoints)
                    .apply()


                //챗봇 승인 로그를 Firebase에 별도 저장

                val previousAvailableTime = currentState.availabletime
                val newAvailableTime = previousAvailableTime + timeInSeconds

                FirebaseManager.getInstance().logChatbotTimeApproval(
                    userId,
                    ChatbotTimeApprovalData(
                        timestamp = System.currentTimeMillis(),
                        previousAvailableTime = previousAvailableTime,
                        newAvailableTime = newAvailableTime
                    )
                )

                runOnUiThread {
                    Toast.makeText(this, "✅ ${minutes}분 추가!", Toast.LENGTH_LONG).show()
                }

                Log.d(TAG, "✅ 시간 추가 완료")
            } else {
                Log.d(TAG, "ℹ️ 거부 또는 0분")
            }

            Log.d(TAG, "========== parseAndAddTime 종료 ==========")
        } catch (e: Exception) {
            Log.e(TAG, "❌ JSON 파싱 에러", e)
        }
    }
}

data class ChatMessage(
    val text: String,
    val isUser: Boolean
)