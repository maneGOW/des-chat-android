package com.manegow.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.manegow.model.chat.ChatId
import com.manegow.model.chat.Message
import com.manegow.model.chat.MessageId
import com.manegow.model.chat.MessageStatus
import com.manegow.model.chat.MessageType
import com.manegow.model.common.DeliveryState
import com.manegow.model.common.Timestamp
import com.manegow.model.identity.UserId

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val messageId: String,
    val chatId: String,
    val senderId: String,
    val type: String,
    val body: String,
    val createdAtEpochMillis: Long,
    val deliveryState: String,
    val status: String,
    val isEncrypted: Boolean
)

fun MessageEntity.toDomain(): Message {
    return Message(
        messageId = MessageId(messageId),
        chatId = ChatId(chatId),
        senderId = UserId(senderId),
        type = MessageType.valueOf(type),
        body = body,
        createdAtEpochMillis = Timestamp(createdAtEpochMillis),
        deliveryState = DeliveryState.valueOf(deliveryState),
        status = MessageStatus.valueOf(status),
        isEncrypted = isEncrypted
    )
}

fun Message.toEntity(): MessageEntity {
    return MessageEntity(
        messageId = messageId.value,
        chatId = chatId.value,
        senderId = senderId.value,
        type = type.name,
        body = body,
        createdAtEpochMillis = createdAtEpochMillis.epochMillis,
        deliveryState = deliveryState.name,
        status = status.name,
        isEncrypted = isEncrypted
    )
}
