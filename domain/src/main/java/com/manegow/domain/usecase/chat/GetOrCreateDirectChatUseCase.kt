package com.manegow.domain.usecase.chat

import com.manegow.domain.repository.ChatRepository
import com.manegow.model.chat.Chat
import com.manegow.model.identity.DisplayName
import com.manegow.model.identity.UserId

class GetOrCreateDirectChatUseCase(
    private val chatRepository: ChatRepository
) {
    suspend operator fun invoke(
        peerUserId: UserId,
        peerDisplayName: DisplayName?
    ): Chat {
        return chatRepository.getOrCreateDirectChat(
            peerUserId = peerUserId,
            peerDisplayName = peerDisplayName
        )
    }
}