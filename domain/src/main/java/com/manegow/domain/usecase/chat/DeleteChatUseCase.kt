package com.manegow.domain.usecase.chat

import com.manegow.domain.repository.ChatRepository
import com.manegow.model.chat.ChatId

class DeleteChatUseCase(
    private val chatRepository: ChatRepository
) {
    suspend operator fun invoke(chatId: ChatId) {
        chatRepository.deleteChat(chatId)
    }
}
