package com.manegow.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.manegow.model.chat.Chat
import com.manegow.model.chat.ChatId
import com.manegow.model.chat.ChatType
import com.manegow.model.common.Timestamp
import com.manegow.model.identity.UserId

@Entity(tableName = "chats")
data class ChatEntity(
    @PrimaryKey val chatId: String,
    val title: String,
    val type: String,
    val participantIds: String, // Comma separated IDs for simplicity in this version
    val lastMessagePreview: String?,
    val updatedAtEpochMillis: Long
)

fun ChatEntity.toDomain(): Chat {
    return Chat(
        chatId = ChatId(chatId),
        title = title,
        type = ChatType.valueOf(type),
        participantIds = participantIds.split(",").filter { it.isNotBlank() }.map { UserId(it) },
        lastMessagePreview = lastMessagePreview,
        updatedAtEpochMillis = Timestamp(updatedAtEpochMillis)
    )
}

fun Chat.toEntity(): ChatEntity {
    return ChatEntity(
        chatId = chatId.value,
        title = title,
        type = type.name,
        participantIds = participantIds.joinToString(",") { it.value },
        lastMessagePreview = lastMessagePreview,
        updatedAtEpochMillis = updatedAtEpochMillis.epochMillis
    )
}
