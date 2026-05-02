package com.manegow.data.repository

import com.manegow.data.db.dao.ChatDao
import com.manegow.data.db.dao.MessageDao
import com.manegow.data.db.entities.toDomain
import com.manegow.data.db.entities.toEntity
import com.manegow.domain.repository.ChatRepository
import com.manegow.domain.repository.MeshRepository
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.UUID

class RealChatRepository(
    private val meshRepository: MeshRepository,
    private val chatDao: ChatDao,
    private val messageDao: MessageDao
) : ChatRepository {

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    // Mapa para traducir UserId -> DeviceId (MAC)
    private val userIdToDeviceIdMap = mutableMapOf<String, String>()

    init {
        // Escuchar mensajes entrantes por Bluetooth
        repositoryScope.launch {
            meshRepository.observeIncomingData().collect { (deviceId, data) ->
                handleIncomingRawData(deviceId, data)
            }
        }

        // Observar pares cercanos para mapear UserId a DeviceId
        repositoryScope.launch {
            meshRepository.observeNearbyPeers().collect { peers ->
                peers.forEach { peer ->
                    peer.userId?.let { userId ->
                        userIdToDeviceIdMap[userId.value] = peer.deviceId.value
                    }
                }
            }
        }
    }

    override fun observeMessages(chatId: ChatId): Flow<List<Message>> {
        return messageDao.observeMessages(chatId.value).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun observeChats(): Flow<List<Chat>> {
        return chatDao.observeChats().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun getOrCreateDirectChat(
        peerUserId: UserId,
        peerDisplayName: DisplayName?
    ): Chat {
        val chatId = peerUserId.value
        val existingChat = chatDao.getChatById(chatId)

        if (existingChat != null) return existingChat.toDomain()

        val newChat = Chat(
            chatId = ChatId(chatId),
            title = peerDisplayName?.value ?: "Unknown",
            type = ChatType.DIRECT,
            participantIds = listOf(peerUserId),
            lastMessagePreview = null,
            updatedAtEpochMillis = Timestamp(System.currentTimeMillis())
        )

        chatDao.upsertChat(newChat.toEntity())
        return newChat
    }

    override suspend fun sendMessage(
        chatId: ChatId,
        senderId: UserId,
        text: String
    ) {
        val message = Message(
            messageId = MessageId(UUID.randomUUID().toString()),
            chatId = chatId,
            senderId = senderId,
            type = MessageType.TEXT,
            body = text,
            createdAtEpochMillis = Timestamp(System.currentTimeMillis()),
            deliveryState = DeliveryState.QUEUED,
            status = MessageStatus.SENT_TO_MESH,
            isEncrypted = false
        )

        // 1. Guardar localmente
        messageDao.upsertMessage(message.toEntity())
        updateChatMetadata(chatId, text)

        // 2. Enviar por Bluetooth
        repositoryScope.launch {
            try {
                val targetUserId = chatId.value.take(10)
                val targetDeviceId = userIdToDeviceIdMap[targetUserId]
                
                if (targetDeviceId != null) {
                    val payload = encodeMessagePayload(message)
                    meshRepository.sendData(targetDeviceId, payload)
                    
                    // Actualizar estado a enviado
                    messageDao.updateDeliveryState(message.messageId.value, DeliveryState.DELIVERED.name)
                } else {
                    messageDao.updateDeliveryState(message.messageId.value, DeliveryState.FAILED.name)
                }
            } catch (ignored: Exception) {
                messageDao.updateDeliveryState(message.messageId.value, DeliveryState.FAILED.name)
            }
        }
    }

    private fun handleIncomingRawData(deviceId: String, data: ByteArray) {
        repositoryScope.launch {
            try {
                val message = decodeMessagePayload(deviceId, data) ?: return@launch
                val chatId = message.chatId
                
                // Asegurar que el chat existe antes de guardar el mensaje
                getOrCreateDirectChat(message.senderId, null)
                
                messageDao.upsertMessage(message.toEntity())
                updateChatMetadata(chatId, message.body)
            } catch (e: Exception) {
                // Error al decodificar
            }
        }
    }

    private suspend fun updateChatMetadata(chatId: ChatId, lastMessage: String) {
        val chat = chatDao.getChatById(chatId.value) ?: return
        chatDao.upsertChat(chat.copy(
            lastMessagePreview = lastMessage,
            updatedAtEpochMillis = System.currentTimeMillis()
        ))
    }

    override suspend fun clearAllData() {
        chatDao.deleteAll()
        messageDao.deleteAll()
    }

    // --- Serialización Simple (Lite) ---
    private fun encodeMessagePayload(message: Message): ByteArray {
        val raw = "${message.senderId.value}|${message.body}|${message.messageId.value}"
        return raw.toByteArray(Charsets.UTF_8)
    }

    private fun decodeMessagePayload(@Suppress("UNUSED_PARAMETER") deviceId: String, data: ByteArray): Message? {
        val raw = String(data, Charsets.UTF_8)
        val parts = raw.split("|")
        if (parts.size < 3) return null
        
        val senderId = parts[0]
        val body = parts[1]
        val messageId = parts[2]
        
        return Message(
            messageId = MessageId(messageId),
            chatId = ChatId(senderId),
            senderId = UserId(senderId),
            type = MessageType.TEXT,
            body = body,
            createdAtEpochMillis = Timestamp(System.currentTimeMillis()),
            deliveryState = DeliveryState.DELIVERED,
            status = MessageStatus.READ,
            isEncrypted = false
        )
    }
}
