package com.manegow.domain.usecase.chat

import com.manegow.domain.repository.ChatRepository
import com.manegow.model.chat.Chat
import kotlinx.coroutines.flow.Flow

class ObserveChatsUseCase(
    private val chatRepository: ChatRepository
) {
    operator fun invoke(): Flow<List<Chat>> {
        return chatRepository.observeChats()
    }
}
