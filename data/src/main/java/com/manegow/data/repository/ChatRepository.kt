package com.manegow.data.repository

import android.util.Log
import com.manegow.data.db.dao.ChatDao
import com.manegow.data.db.dao.MessageDao
import com.manegow.data.db.entities.toDomain
import com.manegow.data.db.entities.toEntity
import com.manegow.domain.repository.ChatRepository
import com.manegow.domain.repository.IdentityRepository
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
import com.manegow.data.notifications.NotificationHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.UUID

class ChatRepository(
    private val meshRepository: MeshRepository,
    private val identityRepository: IdentityRepository,
    private val chatDao: ChatDao,
    private val messageDao: MessageDao,
    private val notificationHandler: NotificationHandler
) : ChatRepository {

    companion object {
        private const val TAG = "RealChatRepository"
        private const val DEFAULT_TTL = 3 // Máximo de 3 saltos para evitar saturar la red
    }

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    // Almacenamos mapeos de ID -> MAC y ID -> Nombre para resolver el problema de "Desconocido"
    private val truncatedUserIdToDeviceIdMap = mutableMapOf<String, String>()
    private val userIdToDisplayNameMap = mutableMapOf<String, String>()
    
    // Cache de IDs de mensajes vistos para evitar re-procesar o bucles infinitos de relay
    private val seenMessageIds = mutableSetOf<String>()

    private var localUserId: String? = null

    init {
        // Cargar ID local
        repositoryScope.launch {
            localUserId = identityRepository.getUserIdentity().firstOrNull()?.userId?.value?.take(10)
        }

        // Escuchar mensajes entrantes
        repositoryScope.launch {
            meshRepository.observeIncomingData().collect { (deviceId, data) ->
                handleIncomingRawData(deviceId, data)
            }
        }

        // Observar pares cercanos para aprender nombres y direcciones MAC
        repositoryScope.launch {
            meshRepository.observeNearbyPeers().collect { peers ->
                peers.forEach { peer ->
                    peer.userId?.let { userId ->
                        val id = userId.value
                        truncatedUserIdToDeviceIdMap[id] = peer.deviceId.value
                        
                        // Si encontramos un nombre real para un ID, lo guardamos y actualizamos chats existentes
                        peer.displayName?.value?.let { name ->
                            if (name != id && name != "Desconocido") {
                                userIdToDisplayNameMap[id] = name
                                updateChatTitleIfNecessary(id, name)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun updateChatTitleIfNecessary(chatId: String, newName: String) {
        repositoryScope.launch {
            val existing = chatDao.getChatById(chatId)
            if (existing != null && (existing.title == "Desconocido" || existing.title == chatId)) {
                chatDao.upsertChat(existing.copy(title = newName))
                Log.d(TAG, "Chat title updated from Desconocido to $newName")
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

        // Intentamos recuperar el nombre si ya lo conocíamos por Nearby
        val resolvedName = peerDisplayName?.value 
            ?: userIdToDisplayNameMap[chatId] 
            ?: "Desconocido"

        val newChat = Chat(
            chatId = ChatId(chatId),
            title = resolvedName,
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

        messageDao.upsertMessage(message.toEntity())
        updateChatMetadata(chatId, text)

        repositoryScope.launch {
            val destinationId = chatId.value.take(10)
            val payload = encodeMessagePayload(destinationId, message, DEFAULT_TTL)
            
            // Intentar envío directo primero
            val targetDeviceId = truncatedUserIdToDeviceIdMap[destinationId]
            if (targetDeviceId != null) {
                try {
                    Log.d(TAG, "Relay: Intentando envío directo a $destinationId ($targetDeviceId)")
                    meshRepository.sendData(targetDeviceId, payload)
                    messageDao.updateDeliveryState(message.messageId.value, DeliveryState.DELIVERED.name)
                    return@launch
                } catch (e: Exception) {
                    Log.w(TAG, "Relay: Falló envío directo, intentando inundación (flooding)...")
                }
            }

            // Si el envío directo falla o no conocemos la MAC, inundamos la red (Flooding)
            // Enviamos a todos los nodos conocidos para que ellos lo retransmitan
            floodNetwork(payload)
            // Marcamos como enviado a la red (aunque no sepamos si llegó al destino final aún)
            messageDao.updateDeliveryState(message.messageId.value, DeliveryState.BROADCASTING.name)
        }
    }

    private suspend fun floodNetwork(payload: ByteArray) {
        val nearbyPeers = meshRepository.observeNearbyPeers().firstOrNull() ?: emptyList()
        Log.d(TAG, "Relay: Inundando red. Enviando a ${nearbyPeers.size} nodos cercanos")
        nearbyPeers.forEach { peer ->
            try {
                meshRepository.sendData(peer.deviceId.value, payload)
            } catch (e: Exception) {
                Log.w(TAG, "Relay: Error inundando nodo")
            }
        }
    }

    private fun handleIncomingRawData(deviceId: String, data: ByteArray) {
        repositoryScope.launch {
            try {
                val (destId, message, ttl) = decodeMessagePayload(data) ?: return@launch
                
                // 1. Evitar duplicados
                if (seenMessageIds.contains(message.messageId.value)) {
                    return@launch
                }
                seenMessageIds.add(message.messageId.value)
                if (seenMessageIds.size > 1000) seenMessageIds.remove(seenMessageIds.first())

                val senderIdShort = message.senderId.value.take(10)
                truncatedUserIdToDeviceIdMap[senderIdShort] = deviceId

                // 2. ¿Es para mí?
                if (destId == localUserId || destId == "all") {
                    processMessageForMe(message, deviceId)
                } 
                
                // 3. ¿Debo retransmitirlo? (Relay)
                if (ttl > 0 && destId != localUserId) {
                    Log.i(TAG, "Relay: Retransmitiendo mensaje de $senderIdShort para $destId (Saltos restantes: ${ttl-1})")
                    val relayedPayload = encodeMessagePayload(destId, message, ttl - 1)
                    floodNetwork(relayedPayload)
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error processing incoming data", e)
            }
        }
    }

    private suspend fun processMessageForMe(message: Message, deviceId: String) {
        val senderIdNormalized = message.senderId.value.take(10)
        val normalizedChatId = ChatId(senderIdNormalized)
        val normalizedSenderId = UserId(senderIdNormalized)
        
        // Antes de crear el chat, intentamos ver si ya conocemos el nombre de este ID
        val name = userIdToDisplayNameMap[senderIdNormalized]
        val chat = getOrCreateDirectChat(normalizedSenderId, name?.let { DisplayName(it) })
        
        val normalizedMessage = message.copy(
            chatId = normalizedChatId,
            senderId = normalizedSenderId
        )
        
        messageDao.upsertMessage(normalizedMessage.toEntity())
        updateChatMetadata(normalizedChatId, message.body)
        
        notificationHandler.showMessageNotification(
            message = normalizedMessage,
            senderName = chat.title
        )
        Log.i(TAG, "Relay: Mensaje recibido para mí de $senderIdNormalized")
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
        truncatedUserIdToDeviceIdMap.clear()
        userIdToDisplayNameMap.clear()
        seenMessageIds.clear()
    }

    private fun encodeMessagePayload(destinationId: String, message: Message, ttl: Int): ByteArray {
        // Formato: destinationId|senderId|body|messageId|ttl
        val raw = "${destinationId}|${message.senderId.value}|${message.body}|${message.messageId.value}|${ttl}"
        return raw.toByteArray(Charsets.UTF_8)
    }

    private fun decodeMessagePayload(data: ByteArray): Triple<String, Message, Int>? {
        try {
            val raw = String(data, Charsets.UTF_8).trim()
            val parts = raw.split("|")
            if (parts.size < 5) return null
            
            val destId = parts[0].trim()
            val senderId = parts[1].trim()
            val body = parts[2]
            val messageId = parts[3].trim()
            val ttl = parts[4].trim().toIntOrNull() ?: 0
            
            val senderIdShort = senderId.take(10)
            
            val message = Message(
                messageId = MessageId(messageId),
                chatId = ChatId(senderIdShort), 
                senderId = UserId(senderIdShort),
                type = MessageType.TEXT,
                body = body,
                createdAtEpochMillis = Timestamp(System.currentTimeMillis()),
                deliveryState = DeliveryState.DELIVERED,
                status = MessageStatus.READ,
                isEncrypted = false
            )
            return Triple(destId, message, ttl)
        } catch (e: Exception) {
            return null
        }
    }
}
