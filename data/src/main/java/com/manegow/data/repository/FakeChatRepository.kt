package com.manegow.data.repository

import com.manegow.domain.repository.ChatRepository
import com.manegow.model.chat.Chat
import com.manegow.model.chat.ChatId
import com.manegow.model.chat.ChatType
import com.manegow.model.chat.Message
import com.manegow.model.chat.MessageId
import com.manegow.model.chat.MessageStatus
import com.manegow.model.chat.MessageType
import com.manegow.model.common.DeliveryState
import com.manegow.model.common.Timestamp
import com.manegow.model.identity.DisplayName
import com.manegow.model.identity.UserId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.util.UUID

class FakeChatRepository: ChatRepository {

    private val chatsState = MutableStateFlow<List<Chat>>(emptyList())
    private val messagesState = MutableStateFlow<Map<ChatId, List<Message>>>(emptyMap())

    override fun observeMessages(chatId: ChatId): Flow<List<Message>> {
        return messagesState.map { messagesByChat ->
            messagesByChat[chatId].orEmpty()
        }
    }

    override fun observeChats(): Flow<List<Chat>> {
        TODO("Not yet implemented")
    }

    override suspend fun getOrCreateDirectChat(
        peerUserId: UserId,
        peerDisplayName: DisplayName?
    ): Chat {
        val existingChat = chatsState.value.firstOrNull { chat ->
            chat.type == ChatType.DIRECT && chat.participantIds.contains(peerUserId)
        }

        if(existingChat != null) return existingChat

        val newChat = Chat(
            chatId = ChatId(UUID.randomUUID().toString()),
            title = peerDisplayName?.value ?: "Unknown",
            type = ChatType.DIRECT,
            participantIds = listOf(peerUserId),
            lastMessagePreview = null,
            updatedAtEpochMillis = Timestamp(System.currentTimeMillis())
        )

        chatsState.value = chatsState.value + newChat
        messagesState.value = messagesState.value + (newChat.chatId to emptyList())

        return newChat
    }

    override suspend fun sendMessage(
        chatId: ChatId,
        senderId: UserId,
        text: String
    ) {
        val trimmedText = text.trim()
        require(trimmedText.isNotBlank()) { "Message text cannot be blank" }

        val newMessage = Message(
            messageId = MessageId(UUID.randomUUID().toString()),
            chatId = chatId,
            senderId = senderId,
            type = MessageType.TEXT,
            body = trimmedText,
            createdAtEpochMillis = Timestamp(System.currentTimeMillis()),
            status = MessageStatus.SENT_TO_MESH,
            deliveryState = DeliveryState.DELIVERED,
            isEncrypted = false
        )

        val currentMessages = messagesState.value[chatId].orEmpty()
        messagesState.value = messagesState.value + (chatId to (currentMessages + newMessage))

        chatsState.value = chatsState.value.map { chat ->
            if(chat.chatId == chatId) {
                chat.copy(
                    lastMessagePreview = trimmedText,
                    updatedAtEpochMillis = Timestamp(System.currentTimeMillis())
                )
            } else {
                chat
            }
        }
    }

    suspend fun seedChat(chatId: ChatId, messages: List<Message>) {
        messagesState.value += (chatId to messages)
    }
}

