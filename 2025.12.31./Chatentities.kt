package com.example.applock.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 채팅 메시지 Entity (단일 테이블)
 */
@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val role: String,  // "system", "user", "assistant"
    val content: String,
    val timestamp: Long
)