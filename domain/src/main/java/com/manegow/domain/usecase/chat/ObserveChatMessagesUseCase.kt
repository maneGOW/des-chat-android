package com.manegow.domain.usecase.chat

import com.manegow.domain.repository.ChatRepository
import com.manegow.model.chat.ChatId
import com.manegow.model.chat.Message
import kotlinx.coroutines.flow.Flow

class ObserveChatMessagesUseCase(
    private val chatRepository: ChatRepository
) {
    operator fun invoke(chatId: ChatId): Flow<List<Message>> {
        return chatRepository.observeMessages(chatId)
    }
}