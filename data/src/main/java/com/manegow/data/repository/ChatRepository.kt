package com.manegow.data.repository

import android.annotation.SuppressLint
import android.util.Log
import com.manegow.data.crypto.CryptographyManager
import com.manegow.data.db.dao.ChatDao
import com.manegow.data.db.dao.MessageDao
import com.manegow.data.db.dao.RelayDao
import com.manegow.data.db.entities.RelayMessageEntity
import com.manegow.data.db.entities.toDomain
import com.manegow.data.db.entities.toEntity
import com.manegow.data.notifications.NotificationHandler
import com.manegow.domain.repository.ChatRepository
import com.manegow.domain.repository.IMeshRepository
import com.manegow.domain.repository.IdentityRepository
import com.manegow.model.chat.*
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

class RealChatRepository(
    private val meshRepository: IMeshRepository,
    private val identityRepository: IdentityRepository,
    private val chatDao: ChatDao,
    private val messageDao: MessageDao,
    private val relayDao: RelayDao,
    private val notificationHandler: NotificationHandler,
    private val cryptoManager: CryptographyManager
) : ChatRepository {

    companion object {
        private const val TAG = "RealChatRepository"
        private const val DEFAULT_TTL = 3
        private const val MAX_SEEN_MESSAGES = 1000
        private const val DIRECT_CHAT_ID_LENGTH = 8
        private const val UNKNOWN_ID = "unknown"
        private const val BROADCAST_ID = "all"
    }

    @SuppressLint("UnsafeOptInUsageError")
    @Serializable
    private data class WireMessage(
        val destinationId: String,
        val senderId: String,
        val body: String,
        val messageId: String,
        val ttl: Int,
        val createdAtEpochMillis: Long,
        val isEncrypted: Boolean = false
    )

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    private val shortUserIdToDeviceIdMap = ConcurrentHashMap<String, String>()

    private val shortUserIdToFullUserIdMap = ConcurrentHashMap<String, String>()

    private val shortUserIdToDisplayNameMap = ConcurrentHashMap<String, String>()

    // Cache de llaves públicas remotas
    private val shortUserIdToPublicKeyMap = ConcurrentHashMap<String, String>()

    private val seenMessageIds = ConcurrentHashMap<String, Long>()

    @Volatile
    private var localUserId: String? = null
    
    @Volatile
    private var localPrivateKeyString: String? = null

    init {
        repositoryScope.launch {
            identityRepository.getUserIdentity().collect { identity ->
                localUserId = identity?.userId?.value
                localPrivateKeyString = identityRepository.getPrivateKey()
                Log.d(TAG, "Local identity updated: $localUserId (Key present: ${localPrivateKeyString != null})")
            }
        }

        repositoryScope.launch {
            meshRepository.observeIncomingData().collect { (deviceId, data) ->
                Log.d(TAG, "Incoming data recieved $deviceId ${String(data, Charsets.UTF_8)}")
                handleIncomingRawData(deviceId, data)
            }
        }

        repositoryScope.launch {
            meshRepository.observeNearbyPeers().collect { peers ->
                peers.forEach { peer ->
                    val peerUserId = peer.userId?.value ?: return@forEach
                    if (peerUserId.isBlank() || peerUserId == UNKNOWN_ID) return@forEach

                    val shortId = directChatIdFor(peerUserId)
                    shortUserIdToDeviceIdMap[shortId] = peer.deviceId.value
                    shortUserIdToFullUserIdMap[shortId] = peerUserId
                    
                    peer.publicKey?.let { pubKey ->
                        shortUserIdToPublicKeyMap[shortId] = pubKey
                    }

                    tryDeliverPendingRelays(peerUserId, peer.deviceId.value)

                    val name = peer.displayName?.value
                    if (!name.isNullOrBlank() && name != UNKNOWN_ID && name != shortId) {
                        shortUserIdToDisplayNameMap[shortId] = name
                        updateChatTitleIfNecessary(shortId, name)
                    }
                }
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
        val shortChatId = directChatIdFor(peerUserId.value).lowercase()
        val existing = chatDao.getChatById(shortChatId)
        if (existing != null) return existing.toDomain()

        val fullPeerUserId = peerUserId.value.lowercase()
        shortUserIdToFullUserIdMap[shortChatId] = fullPeerUserId

        val resolvedName = peerDisplayName?.value
            ?: shortUserIdToDisplayNameMap[shortChatId]
            ?: shortChatId

        val newChat = Chat(
            chatId = ChatId(shortChatId),
            title = resolvedName,
            type = ChatType.DIRECT,
            participantIds = listOf(UserId(fullPeerUserId)),
            lastMessagePreview = null,
            updatedAtEpochMillis = Timestamp(System.currentTimeMillis()),
            remotePublicKey = shortUserIdToPublicKeyMap[shortChatId]
        )

        chatDao.upsertChat(newChat.toEntity())
        return newChat
    }

    override suspend fun sendMessage(chatId: ChatId, senderId: UserId, text: String) {
        val resolvedChatId = chatId.value.lowercase()
        val resolvedSenderId = senderId.value.lowercase()

        if (resolvedChatId.isBlank() || resolvedChatId == UNKNOWN_ID) {
            Log.w(TAG, "Refusing to send message with invalid destinationId=$resolvedChatId")
            return
        }

        // --- ENCRYPTION LOGIC ---
        var encryptedBody = text
        var isEncrypted = false

        try {
            val remotePubKey = shortUserIdToPublicKeyMap[resolvedChatId] 
                ?: chatDao.getChatById(resolvedChatId)?.remotePublicKey
                ?: meshRepository.fetchPublicKey(shortUserIdToDeviceIdMap[resolvedChatId] ?: "")

            val privateKeyStr = localPrivateKeyString
            
            if (remotePubKey != null && privateKeyStr != null) {
                val privKey = cryptoManager.stringToPrivateKey(privateKeyStr)
                val sharedSecret = cryptoManager.getSharedSecret(privKey, remotePubKey)
                encryptedBody = cryptoManager.encrypt(text, sharedSecret)
                isEncrypted = true
                Log.d(TAG, "Message encrypted for $resolvedChatId")
                
                // Cache the key for future use if it came from mesh
                shortUserIdToPublicKeyMap[resolvedChatId] = remotePubKey
                chatDao.getChatById(resolvedChatId)?.let {
                    if (it.remotePublicKey == null) {
                        chatDao.upsertChat(it.copy(remotePublicKey = remotePubKey))
                    }
                }
            } else {
                Log.w(TAG, "Sending unencrypted message (Keys missing: Remote=${remotePubKey!=null}, LocalPriv=${privateKeyStr!=null})")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Encryption failed, sending plain text", e)
        }

        val message = Message(
            messageId = MessageId(UUID.randomUUID().toString()),
            chatId = ChatId(resolvedChatId),
            senderId = UserId(resolvedSenderId),
            type = MessageType.TEXT,
            body = text, // Store plain text locally
            createdAtEpochMillis = Timestamp(System.currentTimeMillis()),
            deliveryState = DeliveryState.QUEUED,
            status = MessageStatus.SENT_TO_MESH,
            isEncrypted = isEncrypted
        )

        messageDao.upsertMessage(message.toEntity())
        updateChatMetadata(ChatId(resolvedChatId), text)

        repositoryScope.launch {
            try {
                val wireBody = if (isEncrypted) encryptedBody else text
                val payload = encodeMessagePayload(
                    destinationId = resolvedChatId,
                    message = message.copy(body = wireBody),
                    isEncrypted = isEncrypted
                )

                val directDeviceId = shortUserIdToDeviceIdMap[resolvedChatId]

                if (directDeviceId != null) {
                    try {
                        Log.d(TAG, "Direct send chatId=$resolvedChatId device=$directDeviceId")
                        meshRepository.sendData(directDeviceId, payload)
                        messageDao.updateDeliveryState(
                            message.messageId.value,
                            DeliveryState.BROADCASTING.name
                        )
                        return@launch
                    } catch (t: Throwable) {
                        Log.w(TAG, "Direct send failed chatId=$resolvedChatId", t)
                    }
                }

                floodNetwork(payload, excludeDeviceIds = setOfNotNull(directDeviceId))

                messageDao.updateDeliveryState(
                    message.messageId.value,
                    DeliveryState.BROADCASTING.name
                )
            } catch (t: Throwable) {
                Log.e(TAG, "sendMessage failed", t)
                messageDao.updateDeliveryState(
                    message.messageId.value,
                    DeliveryState.FAILED.name
                )
            }
        }
    }

    private suspend fun floodNetwork(
        payload: ByteArray,
        excludeDeviceIds: Set<String> = emptySet()
    ) {
        val nearbyPeers = meshRepository.observeNearbyPeers().firstOrNull().orEmpty()
        val targets = nearbyPeers.filterNot { it.deviceId.value in excludeDeviceIds }

        Log.d(TAG, "Relay: flooding to ${targets.size} nearby peers")

        targets.forEach { peer ->
            try {
                meshRepository.sendData(peer.deviceId.value, payload)
            } catch (t: Throwable) {
                Log.w(TAG, "Relay: flood failed device=${peer.deviceId.value}", t)
            }
        }
    }

    private fun handleIncomingRawData(deviceId: String, data: ByteArray) {
        repositoryScope.launch {
            try {
                val wire = decodeMessagePayload(data) ?: run {
                    Log.w(TAG, "Could not decode message from $deviceId")
                    return@launch
                }
                
                val me = (localUserId ?: identityRepository.getUserIdentity().firstOrNull()?.userId?.value)?.lowercase()
                
                Log.d(
                    TAG,
                    "Incoming message: sender=${wire.senderId} dest=${wire.destinationId} me=$me"
                )

                if (wire.destinationId.isBlank() || wire.destinationId == UNKNOWN_ID) {
                    Log.w(
                        TAG,
                        "Dropping message with invalid destinationId messageId=${wire.messageId} sender=${wire.senderId}"
                    )
                    return@launch
                }

                val senderFullId = wire.senderId.lowercase()
                if (senderFullId.isBlank() || senderFullId == UNKNOWN_ID) {
                    Log.w(TAG, "Dropping message with invalid senderId messageId=${wire.messageId}")
                    return@launch
                }

                val dedupeKey = "${senderFullId}:${wire.messageId}"
                val inserted = seenMessageIds.putIfAbsent(
                    dedupeKey,
                    System.currentTimeMillis()
                ) == null

                if (!inserted) {
                    Log.d(TAG, "Duplicate ignored key=$dedupeKey from=$deviceId")
                    return@launch
                }

                trimSeenMessagesIfNeeded()

                val senderShortId = directChatIdFor(senderFullId)
                shortUserIdToDeviceIdMap[senderShortId] = deviceId
                shortUserIdToFullUserIdMap[senderShortId] = senderFullId

                val isOwnLoopedMessage = me != null && senderFullId == me
                if (isOwnLoopedMessage) {
                    Log.d(TAG, "Dropping looped own messageId=${wire.messageId}")
                    return@launch
                }

                val destinationId = wire.destinationId.lowercase()
                val isForMe = matchesLocalId(destinationId, me)

                // --- DECRYPTION LOGIC ---
                var finalBody = wire.body
                if (wire.isEncrypted && isForMe) {
                    try {
                        val remotePubKey = shortUserIdToPublicKeyMap[senderShortId]
                            ?: meshRepository.fetchPublicKey(deviceId)
                            ?: chatDao.getChatById(senderShortId)?.remotePublicKey

                        val privateKeyStr = localPrivateKeyString
                        
                        if (remotePubKey != null && privateKeyStr != null) {
                            val privKey = cryptoManager.stringToPrivateKey(privateKeyStr)
                            val sharedSecret = cryptoManager.getSharedSecret(privKey, remotePubKey)
                            finalBody = cryptoManager.decrypt(wire.body, sharedSecret)
                            Log.d(TAG, "Message decrypted from $senderShortId")
                            
                            // Save remote key if we didn't have it
                            if (shortUserIdToPublicKeyMap[senderShortId] == null) {
                                shortUserIdToPublicKeyMap[senderShortId] = remotePubKey
                                chatDao.getChatById(senderShortId)?.let {
                                    if (it.remotePublicKey == null) {
                                        chatDao.upsertChat(it.copy(remotePublicKey = remotePubKey))
                                    }
                                }
                            }
                        } else {
                            Log.w(TAG, "Could not decrypt message: Keys missing")
                            finalBody = "[Mensaje cifrado]"
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Decryption failed", e)
                        finalBody = "[Error al descifrar]"
                    }
                }

                val message = Message(
                    messageId = MessageId(wire.messageId),
                    chatId = ChatId(senderShortId),
                    senderId = UserId(senderFullId),
                    type = MessageType.TEXT,
                    body = finalBody,
                    createdAtEpochMillis = Timestamp(wire.createdAtEpochMillis),
                    deliveryState = DeliveryState.DELIVERED,
                    status = MessageStatus.READ,
                    isEncrypted = wire.isEncrypted
                )

                if (isForMe) {
                    Log.d(TAG, "Message is for me: ${wire.messageId}")
                    processMessageForMe(message)
                } else {
                    Log.d(TAG, "Message is for someone else, caching in relay bag: ${wire.destinationId}")
                    relayDao.insert(RelayMessageEntity(
                        messageId = wire.messageId,
                        destinationId = wire.destinationId,
                        senderId = wire.senderId,
                        body = wire.body,
                        createdAtEpochMillis = wire.createdAtEpochMillis,
                        ttl = wire.ttl,
                        isEncrypted = wire.isEncrypted
                    ))
                }

                if (wire.ttl > 0 && !isForMe) {
                    val nextTtl = wire.ttl - 1
                    Log.d(TAG, "Relaying messageId=${wire.messageId} ttl=$nextTtl")
                    val relayedPayload = encodeWireMessage(wire.copy(
                        destinationId = destinationId,
                        senderId = senderFullId,
                        ttl = nextTtl
                    ))
                    floodNetwork(relayedPayload, excludeDeviceIds = setOf(deviceId))
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Error handling raw data", t)
            }
        }
    }

    private suspend fun processMessageForMe(message: Message) {
        val senderFullId = message.senderId.value
        val senderShortId = directChatIdFor(senderFullId)

        shortUserIdToFullUserIdMap[senderShortId] = senderFullId

        val displayName = shortUserIdToDisplayNameMap[senderShortId]?.let { DisplayName(it) }
        val chat = getOrCreateDirectChat(UserId(senderFullId), displayName)

        val normalizedMessage = message.copy(
            chatId = ChatId(senderShortId)
        )

        messageDao.upsertMessage(normalizedMessage.toEntity())
        updateChatMetadata(ChatId(senderShortId), normalizedMessage.body)

        notificationHandler.showMessageNotification(
            message = normalizedMessage,
            senderName = chat.title
        )

        Log.i(TAG, "Message received for me from=$senderFullId chatId=$senderShortId")
    }

    private suspend fun tryDeliverPendingRelays(fullDestId: String, deviceId: String) {
        val shortId = directChatIdFor(fullDestId)
        val pending = relayDao.getPendingFor(fullDestId) + relayDao.getPendingFor(shortId)
        
        if (pending.isNotEmpty()) {
            Log.i(TAG, "Relay: Delivering ${pending.size} pending messages to $fullDestId")
            pending.forEach { relay ->
                try {
                    val wire = WireMessage(
                        destinationId = relay.destinationId,
                        senderId = relay.senderId,
                        body = relay.body,
                        messageId = relay.messageId,
                        ttl = relay.ttl,
                        createdAtEpochMillis = relay.createdAtEpochMillis,
                        isEncrypted = relay.isEncrypted
                    )
                    meshRepository.sendData(deviceId, encodeWireMessage(wire))
                    relayDao.deleteById(relay.messageId)
                    Log.d(TAG, "Relay: Successfully delivered messageId=${relay.messageId}")
                } catch (t: Throwable) {
                    Log.w(TAG, "Relay: Could not deliver messageId=${relay.messageId} yet")
                }
            }
        }
    }

    private fun updateChatTitleIfNecessary(chatId: String, newName: String) {
        repositoryScope.launch {
            val existing = chatDao.getChatById(chatId) ?: return@launch
            if (existing.title == UNKNOWN_ID || existing.title == chatId) {
                chatDao.upsertChat(existing.copy(title = newName))
                Log.d(TAG, "Chat title updated to $newName for chatId=$chatId")
            }
        }
    }

    private suspend fun updateChatMetadata(chatId: ChatId, lastMessage: String) {
        val chat = chatDao.getChatById(chatId.value) ?: return
        chatDao.upsertChat(
            chat.copy(
                lastMessagePreview = lastMessage,
                updatedAtEpochMillis = System.currentTimeMillis()
            )
        )
    }

    override suspend fun deleteChat(chatId: ChatId) {
        chatDao.deleteById(chatId.value)
        messageDao.deleteByChatId(chatId.value)
        shortUserIdToDeviceIdMap.remove(chatId.value)
        shortUserIdToFullUserIdMap.remove(chatId.value)
        shortUserIdToDisplayNameMap.remove(chatId.value)
    }

    override suspend fun clearAllData() {
        chatDao.deleteAll()
        messageDao.deleteAll()
        shortUserIdToDeviceIdMap.clear()
        shortUserIdToFullUserIdMap.clear()
        shortUserIdToDisplayNameMap.clear()
        seenMessageIds.clear()
    }

    private fun directChatIdFor(userId: String): String {
        return userId.trim().lowercase().take(DIRECT_CHAT_ID_LENGTH)
    }

    private fun matchesLocalId(destinationId: String, localId: String?): Boolean {
        if (destinationId == BROADCAST_ID) return true
        if (localId.isNullOrBlank()) return false
        val dest = destinationId.lowercase()
        val me = localId.lowercase()
        return dest == me || me.startsWith(dest)
    }

    private fun encodeMessagePayload(
        destinationId: String,
        message: Message,
        isEncrypted: Boolean = false
    ): ByteArray {
        val wire = WireMessage(
            destinationId = destinationId,
            senderId = message.senderId.value,
            body = message.body,
            messageId = message.messageId.value,
            ttl = DEFAULT_TTL,
            createdAtEpochMillis = message.createdAtEpochMillis.epochMillis,
            isEncrypted = isEncrypted
        )
        return encodeWireMessage(wire)
    }

    private fun encodeWireMessage(wire: WireMessage): ByteArray {
        return json.encodeToString(wire).toByteArray(Charsets.UTF_8)
    }

    private fun decodeMessagePayload(data: ByteArray): WireMessage? {
        return try {
            val raw = String(data, Charsets.UTF_8)
            json.decodeFromString(WireMessage.serializer(), raw)
        } catch (t: Throwable) {
            Log.w(TAG, "decodeMessagePayload failed", t)
            null
        }
    }

    private fun trimSeenMessagesIfNeeded() {
        if (seenMessageIds.size <= MAX_SEEN_MESSAGES) return
        val oldest = seenMessageIds.entries.minByOrNull { it.value } ?: return
        seenMessageIds.remove(oldest.key)
    }
}