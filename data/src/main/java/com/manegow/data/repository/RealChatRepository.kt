package com.manegow.data.repository

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.UUID

class RealChatRepository(
    private val meshRepository: MeshRepository,
) : ChatRepository {

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    // Almacenamiento en memoria para demostración (debería ser una DB en el futuro)
    private val messagesState = MutableStateFlow<Map<ChatId, List<Message>>>(emptyMap())
    private val chatsState = MutableStateFlow<List<Chat>>(emptyList())

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
        return messagesState.map { it[chatId].orEmpty() }
    }

    override fun observeChats(): Flow<List<Chat>> {
        return chatsState.asStateFlow()
    }

    override suspend fun getOrCreateDirectChat(
        peerUserId: UserId,
        peerDisplayName: DisplayName?
    ): Chat {
        val existingChat = chatsState.value.firstOrNull { chat ->
            chat.type == ChatType.DIRECT && chat.participantIds.contains(peerUserId)
        }

        if (existingChat != null) return existingChat

        val newChat = Chat(
            chatId = ChatId(peerUserId.value), // Usamos el userId como chatId para chats directos simplificados
            title = peerDisplayName?.value ?: "Unknown",
            type = ChatType.DIRECT,
            participantIds = listOf(peerUserId),
            lastMessagePreview = null,
            updatedAtEpochMillis = Timestamp(System.currentTimeMillis())
        )

        chatsState.value = chatsState.value + newChat
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
        val currentMessages = messagesState.value[chatId].orEmpty()
        messagesState.value = messagesState.value + (chatId to (currentMessages + message))
        updateChatMetadata(chatId, text)

        // 2. Enviar por Bluetooth
        repositoryScope.launch {
            try {
                // Buscamos la dirección MAC (DeviceId) asociada a este UserId (chatId)
                // Usamos la misma lógica de truncación que en el descubrimiento (10 chars)
                val targetUserId = chatId.value.take(10)
                val targetDeviceId = userIdToDeviceIdMap[targetUserId]
                
                if (targetDeviceId != null) {
                    val payload = encodeMessagePayload(message)
                    meshRepository.sendData(targetDeviceId, payload)
                    
                    // Actualizar estado a enviado
                    updateMessageDeliveryState(chatId, message.messageId, DeliveryState.DELIVERED)
                } else {
                    // Si no tenemos el DeviceId, el par no está al alcance o no se ha descubierto aún
                    updateMessageDeliveryState(chatId, message.messageId, DeliveryState.FAILED)
                }
            } catch (ignored: Exception) {
                updateMessageDeliveryState(chatId, message.messageId, DeliveryState.FAILED)
            }
        }
    }

    private fun handleIncomingRawData(deviceId: String, data: ByteArray) {
        try {
            val message = decodeMessagePayload(deviceId, data) ?: return
            val chatId = message.chatId
            
            val currentMessages = messagesState.value[chatId].orEmpty()
            if (currentMessages.none { it.messageId == message.messageId }) {
                messagesState.value = messagesState.value + (chatId to (currentMessages + message))
                
                // Asegurar que el chat existe
                repositoryScope.launch {
                    getOrCreateDirectChat(message.senderId, null)
                    updateChatMetadata(chatId, message.body)
                }
            }
        } catch (e: Exception) {
            // Error al decodificar
        }
    }

    private fun updateMessageDeliveryState(chatId: ChatId, messageId: MessageId, newState: DeliveryState) {
        val currentMessages = messagesState.value[chatId].orEmpty()
        val updatedMessages = currentMessages.map { 
            if (it.messageId == messageId) it.copy(deliveryState = newState) else it
        }
        messagesState.value = messagesState.value + (chatId to updatedMessages)
    }

    private fun updateChatMetadata(chatId: ChatId, lastMessage: String) {
        val currentChats = chatsState.value
        val updatedChats = currentChats.map { chat ->
            if (chat.chatId == chatId) {
                chat.copy(
                    lastMessagePreview = lastMessage,
                    updatedAtEpochMillis = Timestamp(System.currentTimeMillis())
                )
            } else {
                chat
            }
        }.sortedByDescending { it.updatedAtEpochMillis.epochMillis }
        
        chatsState.value = updatedChats
    }

    // --- Serialización Simple (Lite) ---
    // Formato: senderId|body|messageId
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
            chatId = ChatId(senderId), // El chat de vuelta es el ID del que me envió
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
