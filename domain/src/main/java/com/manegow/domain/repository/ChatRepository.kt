package com.manegow.domain.repository

import com.manegow.model.chat.Chat
import com.manegow.model.chat.ChatId
import com.manegow.model.chat.Message
import com.manegow.model.identity.DisplayName
import com.manegow.model.identity.UserId
import kotlinx.coroutines.flow.Flow

interface ChatRepository {
    fun observeMessages(chatId: ChatId): Flow<List<Message>>
    fun observeChats(): Flow<List<Chat>>

    suspend fun getOrCreateDirectChat(
        peerUserId: UserId,
        peerDisplayName: DisplayName?
    ): Chat

    suspend fun sendMessage(
        chatId: ChatId,
        senderId: UserId,
        text: String
    )
}