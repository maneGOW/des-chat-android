package com.manegow.data.repository

import android.annotation.SuppressLint
import android.util.Log
import com.manegow.data.db.dao.ChatDao
import com.manegow.data.db.dao.MessageDao
import com.manegow.data.db.entities.toDomain
import com.manegow.data.db.entities.toEntity
import com.manegow.data.notifications.NotificationHandler
import com.manegow.domain.repository.ChatRepository
import com.manegow.domain.repository.IMeshRepository
import com.manegow.domain.repository.IdentityRepository
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
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ChatRepository(
    private val meshRepository: IMeshRepository,
    private val identityRepository: IdentityRepository,
    private val chatDao: ChatDao,
    private val messageDao: MessageDao,
    private val notificationHandler: NotificationHandler
) : ChatRepository {

    companion object {
        private const val TAG = "RealChatRepository"
        private const val DEFAULT_TTL = 3
        private const val MAX_SEEN_MESSAGES = 1000
    }

    @SuppressLint("UnsafeOptInUsageError")
    @Serializable
    private data class WireMessage(
        val destinationId: String,
        val senderId: String,
        val body: String,
        val messageId: String,
        val ttl: Int,
        val createdAtEpochMillis: Long
    )

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    private val userIdToDeviceIdMap = ConcurrentHashMap<String, String>()
    private val userIdToDisplayNameMap = ConcurrentHashMap<String, String>()
    private val seenMessageIds = ConcurrentHashMap<String, Long>()

    @Volatile
    private var localUserId: String? = null

    init {
        repositoryScope.launch {
            localUserId = identityRepository.getUserIdentity().firstOrNull()?.userId?.value
            Log.d(TAG, "Local user loaded: $localUserId")
        }

        repositoryScope.launch {
            meshRepository.observeIncomingData().collect { (deviceId, data) ->
                println("Incoming data recieved $deviceId ${String(data)}")
                handleIncomingRawData(deviceId, data)
            }
        }

        repositoryScope.launch {
            meshRepository.observeNearbyPeers().collect { peers ->
                peers.forEach { peer ->
                    val userId = peer.userId?.value ?: return@forEach
                    userIdToDeviceIdMap[userId] = peer.deviceId.value

                    val name = peer.displayName?.value ?: return@forEach
                    if (name.isNotBlank() && name != userId && name != "Desconocido") {
                        userIdToDisplayNameMap[userId] = name
                        updateChatTitleIfNecessary(userId, name)
                    }
                }
            }
        }
    }

    private suspend fun requireLocalUserId(): String? {
        if (localUserId != null) return localUserId
        localUserId = identityRepository.getUserIdentity().firstOrNull()?.userId?.value
        return localUserId
    }

    private fun updateChatTitleIfNecessary(chatId: String, newName: String) {
        repositoryScope.launch {
            val existing = chatDao.getChatById(chatId) ?: return@launch
            if (existing.title == "Desconocido" || existing.title == chatId) {
                chatDao.upsertChat(existing.copy(title = newName))
            }
        }
    }

    override fun observeMessages(chatId: ChatId): Flow<List<Message>> {
        return messageDao.observeMessages(chatId.value).map { list -> list.map { it.toDomain() } }
    }

    override fun observeChats(): Flow<List<Chat>> {
        return chatDao.observeChats().map { list -> list.map { it.toDomain() } }
    }

    override suspend fun getOrCreateDirectChat(
        peerUserId: UserId,
        peerDisplayName: DisplayName?
    ): Chat {
        val chatIdValue = directChatIdFor(peerUserId.value)
        val existing = chatDao.getChatById(chatIdValue)
        if (existing != null) return existing.toDomain()

        val resolvedName = peerDisplayName?.value
            ?: userIdToDisplayNameMap[chatIdValue]
            ?: chatIdValue

        val newChat = Chat(
            chatId = ChatId(chatIdValue),
            title = resolvedName,
            type = ChatType.DIRECT,
            participantIds = listOf(peerUserId),
            lastMessagePreview = null,
            updatedAtEpochMillis = Timestamp(System.currentTimeMillis())
        )

        chatDao.upsertChat(newChat.toEntity())
        return newChat
    }

    override suspend fun sendMessage(chatId: ChatId, senderId: UserId, text: String) {
        val resolvedChatId = chatId.value
        val resolvedSenderId = senderId.value

        val message = Message(
            messageId = MessageId(UUID.randomUUID().toString()),
            chatId = ChatId(resolvedChatId),
            senderId = UserId(resolvedSenderId),
            type = MessageType.TEXT,
            body = text,
            createdAtEpochMillis = Timestamp(System.currentTimeMillis()),
            deliveryState = DeliveryState.QUEUED,
            status = MessageStatus.SENT_TO_MESH,
            isEncrypted = false
        )

        messageDao.upsertMessage(message.toEntity())
        updateChatMetadata(ChatId(resolvedChatId), text)

        repositoryScope.launch {
            val payload = encodeMessagePayload(
                destinationId = resolvedChatId,
                message = message,
                ttl = DEFAULT_TTL
            )

            val directDeviceId = userIdToDeviceIdMap[resolvedChatId]

            if (directDeviceId != null) {
                try {
                    meshRepository.sendData(directDeviceId, payload)
                    messageDao.updateDeliveryState(
                        message.messageId.value,
                        DeliveryState.BROADCASTING.name
                    )
                    return@launch
                } catch (t: Throwable) {
                    Log.w(TAG, "Direct send failed", t)
                }
            }

            floodNetwork(payload, excludeDeviceIds = setOfNotNull(directDeviceId))
            messageDao.updateDeliveryState(
                message.messageId.value,
                DeliveryState.BROADCASTING.name
            )
        }
    }

    private fun directChatIdFor(userId: String): String {
        return userId.take(8)
    }

    private suspend fun floodNetwork(payload: ByteArray, excludeDeviceIds: Set<String>) {
        val nearbyPeers = meshRepository.observeNearbyPeers().firstOrNull().orEmpty()
        nearbyPeers.filterNot { it.deviceId.value in excludeDeviceIds }.forEach { peer ->
            try {
                meshRepository.sendData(peer.deviceId.value, payload)
            } catch (t: Throwable) {
                Log.w(TAG, "Flood failed", t)
            }
        }
    }

    private fun handleIncomingRawData(deviceId: String, data: ByteArray) {
        repositoryScope.launch {
            try {
                val me = localUserId
                val wire = decodeMessagePayload(data) ?: return@launch

                val dedupeKey = "${wire.senderId}:${wire.messageId}"
                if (seenMessageIds.putIfAbsent(dedupeKey, System.currentTimeMillis()) != null) return@launch
                trimSeenMessagesIfNeeded()

                userIdToDeviceIdMap[wire.senderId.take(8)] = deviceId

                val senderChatId = directChatIdFor(wire.senderId)

                val message = Message(
                    messageId = MessageId(wire.messageId),
                    chatId = ChatId(senderChatId),
                    senderId = UserId(wire.senderId),
                    type = MessageType.TEXT,
                    body = wire.body,
                    createdAtEpochMillis = Timestamp(wire.createdAtEpochMillis),
                    deliveryState = DeliveryState.DELIVERED,
                    status = MessageStatus.READ,
                    isEncrypted = false
                )

                val isForMe = wire.destinationId == "all" ||
                        wire.destinationId == me ||
                        (me?.startsWith(wire.destinationId) == true)

                if (isForMe) {
                    processMessageForMe(message)
                }

                if (wire.ttl > 0 && !isForMe) {
                    val relayedPayload = encodeWireMessage(wire.copy(ttl = wire.ttl - 1))
                    floodNetwork(relayedPayload, excludeDeviceIds = setOf(deviceId))
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Error handling raw data", t)
            }
        }
    }

    private suspend fun processMessageForMe(message: Message) {
        val senderFullId = message.senderId.value
        val shortChatId = directChatIdFor(senderFullId)

        val displayName = userIdToDisplayNameMap[shortChatId]?.let { DisplayName(it) }
        val chat = getOrCreateDirectChat(UserId(senderFullId), displayName)

        val normalizedMessage = message.copy(
            chatId = ChatId(shortChatId)
        )

        messageDao.upsertMessage(normalizedMessage.toEntity())
        updateChatMetadata(ChatId(shortChatId), normalizedMessage.body)

        notificationHandler.showMessageNotification(
            message = normalizedMessage,
            senderName = chat.title
        )
    }

    private suspend fun updateChatMetadata(chatId: ChatId, lastMessage: String) {
        val chat = chatDao.getChatById(chatId.value) ?: return
        chatDao.upsertChat(chat.copy(lastMessagePreview = lastMessage, updatedAtEpochMillis = System.currentTimeMillis()))
    }

    override suspend fun clearAllData() {
        chatDao.deleteAll()
        messageDao.deleteAll()
        userIdToDeviceIdMap.clear()
        userIdToDisplayNameMap.clear()
        seenMessageIds.clear()
    }

    private fun encodeMessagePayload(destinationId: String, message: Message, ttl: Int): ByteArray {
        return encodeWireMessage(WireMessage(destinationId, message.senderId.value, message.body, message.messageId.value, ttl, message.createdAtEpochMillis.epochMillis))
    }

    private fun encodeWireMessage(wire: WireMessage): ByteArray = json.encodeToString(wire).toByteArray(Charsets.UTF_8)

    private fun decodeMessagePayload(data: ByteArray): WireMessage? {
        return try { json.decodeFromString<WireMessage>(String(data, Charsets.UTF_8)) } catch (e: Exception) { null }
    }

    private fun trimSeenMessagesIfNeeded() {
        if (seenMessageIds.size > MAX_SEEN_MESSAGES) seenMessageIds.entries.minByOrNull { it.value }?.let { seenMessageIds.remove(it.key) }
    }
}