package com.manegow.data.repository

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.manegow.domain.repository.IdentityRepository
import com.manegow.domain.repository.IMeshRepository as MeshRepositoryContract
import com.manegow.model.common.Timestamp
import com.manegow.model.identity.DeviceId
import com.manegow.model.identity.DisplayName
import com.manegow.model.identity.UserId
import com.manegow.model.nearby.Peer
import com.manegow.model.nearby.PeerStatus
import com.manegow.model.nearby.SignalStrength
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

@SuppressLint("MissingPermission", "HardwareIds")
class MeshRepository(
    private val context: Context,
    private val identityRepository: IdentityRepository
) : MeshRepositoryContract {

    companion object {
        private const val TAG = "RealMeshRepository"

        private val SERVICE_UUID: UUID = UUID.fromString("0000FE69-0000-1000-8000-00805f9b34fb")
        private val RX_CHAR_UUID: UUID = UUID.fromString("0000FE70-0000-1000-8000-00805f9b34fb")
        private val TX_CHAR_UUID: UUID = UUID.fromString("0000FE72-0000-1000-8000-00805f9b34fb")
        private val PUBKEY_CHAR_UUID: UUID = UUID.fromString("0000FE71-0000-1000-8000-00805f9b34fb")
        private val IDENTITY_CHAR_UUID: UUID = UUID.fromString("0000FE73-0000-1000-8000-00805f9b34fb")
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private val SERVICE_PARCEL = ParcelUuid(SERVICE_UUID)

        private const val PEER_STALE_MS = 30_000L
        private const val PEER_REFRESH_MS = 10_000L
        private const val CONNECTION_IDLE_MS = 20_000L

        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val ACK_TIMEOUT_MS = 6_000L
        private const val READ_TIMEOUT_MS = 5_000L
        private const val DESCRIPTOR_TIMEOUT_MS = 4_000L
        private const val WRITE_TIMEOUT_MS = 4_000L

        private const val MTU_REQUESTED = 247
        private const val DEFAULT_MTU = 23
        private const val ATT_HEADER_BYTES = 3

        private const val MAX_ADVERTISED_USER_ID_BYTES = 8

        private const val PROTOCOL_VERSION: Byte = 1
        private const val HEADER_SIZE = 18

        private const val TYPE_DATA: Byte = 1
        private const val TYPE_ACK: Byte = 2
        private const val TYPE_PUBKEY_REQ: Byte = 3
        private const val TYPE_PUBKEY_RESP: Byte = 4
        private const val TYPE_ID_REQ: Byte = 5
        private const val TYPE_ID_RESP: Byte = 6
        private const val IDENTITY_RETRY_MS = 15_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val scanner: BluetoothLeScanner? = adapter?.bluetoothLeScanner
    private val advertiser: BluetoothLeAdvertiser? = adapter?.bluetoothLeAdvertiser

    private val peersMap = ConcurrentHashMap<String, Peer>()

    private val peerIdentityRequestsInFlight = ConcurrentHashMap<String, Boolean>()
    private val peerIdentityLastAttemptAt = ConcurrentHashMap<String, Long>()

    private val peersState = MutableStateFlow<List<Peer>>(emptyList())
    private val incomingDataState = MutableSharedFlow<Pair<String, ByteArray>>(extraBufferCapacity = 256)

    private val lastSeenMap = ConcurrentHashMap<String, Long>()
    private val subscribedDevices = ConcurrentHashMap.newKeySet<String>()
    private val preparedWrites = ConcurrentHashMap<String, ByteArrayOutputStream>()
    private val messageAssemblies = ConcurrentHashMap<String, IncomingAssembly>()
    private val seenMessageIds = ConcurrentHashMap<String, Long>()
    private val pendingAcks = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val pendingPubKeyReads = ConcurrentHashMap<String, CompletableDeferred<String?>>()
    private val pendingIdentityReads = ConcurrentHashMap<String, CompletableDeferred<String?>>()
    private val connectionMap = ConcurrentHashMap<String, ConnectionContext>()

    private val messageCounter = AtomicLong(System.currentTimeMillis())

    @Volatile
    private var gattServer: BluetoothGattServer? = null

    @Volatile
    private var localUserId: String = "unknown"
    private var identityJob: kotlinx.coroutines.Job? = null

    @Volatile
    private var isScanning = false

    @Volatile
    private var isAdvertising = false

    @Volatile
    private var refreshLoopStarted = false

    override fun observeNearbyPeers(): Flow<List<Peer>> = peersState.asStateFlow()
    override fun observeIncomingData(): Flow<Pair<String, ByteArray>> = incomingDataState.asSharedFlow()

    private data class MeshFrame(
        val version: Byte,
        val type: Byte,
        val messageId: Long,
        val chunkIndex: Short,
        val totalChunks: Short,
        val payloadLength: Short,
        val flags: Short,
        val payload: ByteArray
    )

    private data class IncomingAssembly(
        val messageId: Long,
        val totalChunks: Int,
        val chunks: MutableMap<Int, ByteArray> = mutableMapOf(),
        var updatedAt: Long = System.currentTimeMillis()
    )

    private data class ConnectionContext(
        val deviceId: String,
        val device: BluetoothDevice,
        val mutex: Mutex = Mutex(),
        @Volatile var gatt: BluetoothGatt? = null,
        @Volatile var mtu: Int = DEFAULT_MTU,
        @Volatile var notificationsEnabled: Boolean = false,
        @Volatile var servicesReady: Boolean = false,
        @Volatile var lastUsedAt: Long = System.currentTimeMillis(),
        @Volatile var descriptorWriteDeferred: CompletableDeferred<Boolean>? = null,
        @Volatile var characteristicWriteDeferred: CompletableDeferred<Boolean>? = null
    )

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            isAdvertising = true
            Log.d(TAG, "Advertising started")
        }

        override fun onStartFailure(errorCode: Int) {
            isAdvertising = false
            Log.e(TAG, "Advertising failed errorCode=$errorCode")
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val device = result?.device ?: return
            val record = result.scanRecord ?: return

            val address = device.address ?: return
            val now = System.currentTimeMillis()

            val serviceData = record.getServiceData(SERVICE_PARCEL)
            val advertisedShortUserId = serviceData
                ?.decodeToString()
                ?.trim()
                ?.takeIf { it.isNotBlank() }

            lastSeenMap[address] = now

            val previous = peersMap[address]

            val resolvedUserId = when {
                !advertisedShortUserId.isNullOrBlank() -> advertisedShortUserId
                previous?.userId?.value?.isNotBlank() == true -> previous.userId?.value
                else -> "unknown"
            }

            val resolvedDisplayName = when {
                !record.deviceName.isNullOrBlank() -> record.deviceName
                previous?.displayName?.value?.isNotBlank() == true &&
                        previous.displayName?.value != "unknown" -> previous.displayName?.value
                resolvedUserId != "unknown" -> resolvedUserId
                else -> address.takeLast(5)
            }

            val peer = Peer(
                deviceId = DeviceId(address),
                userId = UserId(resolvedUserId ?: return),
                displayName = DisplayName(resolvedDisplayName ?: return),
                signalStrength = SignalStrength(result.rssi),
                status = PeerStatus.REACHABLE,
                lastSeen = Timestamp(now)
            )

            peersMap[address] = peer

            peersState.value = peersMap.values
                .sortedByDescending { it.lastSeen.epochMillis }

            if (resolvedUserId == "unknown") {
                val inFlight = peerIdentityRequestsInFlight[address] == true
                val lastAttemptAt = peerIdentityLastAttemptAt[address] ?: 0L
                val shouldRetry = (now - lastAttemptAt) >= IDENTITY_RETRY_MS

                if (!inFlight && shouldRetry) {
                    peerIdentityRequestsInFlight[address] = true
                    peerIdentityLastAttemptAt[address] = now

                    scope.launch {
                        try {
                            // Añadimos un pequeño retraso aleatorio para evitar que ambos
                            // teléfonos intenten conectarse al mismo tiempo (Colisión)
                            delay((500..2000).random().toLong())

                            val fullIdentity = fetchIdentity(address)?.trim()?.lowercase()

                            if (!identity.isNullOrBlank() && identity != "unknown") {
                                val current = peersMap[address]
                                if (current != null) {
                                    val updated = current.copy(
                                        userId = UserId(identity),
                                        displayName = DisplayName(
                                            current.displayName?.value
                                                .takeIf { it?.isNotBlank() == true && it != "unknown" && it != address.takeLast(5) }
                                                ?: identity.take(12)
                                        ),
                                        lastSeen = Timestamp(System.currentTimeMillis())
                                    )

                                    peersMap[address] = updated
                                    peersState.value = peersMap.values
                                        .sortedByDescending { it.lastSeen.epochMillis }
                                }
                            }
                        } catch (t: Throwable) {
                            Log.w(TAG, "fetchIdentity failed for $address", t)
                        } finally {
                            peerIdentityRequestsInFlight.remove(address)
                        }
                    }
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            Log.e(TAG, "Scan failed errorCode=$errorCode")
        }
    }

    private fun updatePeersState() {
        val uniquePeers = peersMap.values
            .groupBy { it.userId?.value?.lowercase() }
            .flatMap { (id, peersWithSameId) ->
                if (id == null || id == "unknown") {
                    peersWithSameId
                } else {
                    // Si hay varios nodos con el mismo ID, nos quedamos con el que tiene mejor señal
                    listOf(peersWithSameId.maxBy { it.signalStrength.rssi })
                }
            }
            .sortedWith(
                // ORDEN ESTABLE DEFINITIVO:
                // 1. Primero los que tienen ID real (arriba), luego los "unknown" (abajo)
                // 2. Dentro de cada grupo, orden alfabético por nombre
                // 3. Como último recurso, por dirección MAC
                compareByDescending<Peer> { it.userId?.value != "unknown" }
                    .thenBy { it.displayName?.value?.lowercase() ?: "" }
                    .thenBy { it.deviceId.value }
            )

        // Evitar parpadeo: Solo emitir si cambió algo más que el RSSI o el Timestamp
        val currentList = peersState.value
        val hasChanges = currentList.size != uniquePeers.size || 
                         currentList.zip(uniquePeers).any { (old, new) -> 
                             old.userId != new.userId || old.displayName != new.displayName 
                         }

        if (hasChanges || currentList.isEmpty()) {
            peersState.value = uniquePeers
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            val address = device?.address ?: return
            Log.d(TAG, "Server connection state device=$address status=$status state=$newState")

            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                subscribedDevices.remove(address)
                preparedWrites.remove(address)
                pendingIdentityReads.remove(address)?.complete(null)
                pendingPubKeyReads.remove(address)?.complete(null)
            }
        }

        override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) {
            Log.d(TAG, "Server MTU changed device=${device?.address} mtu=$mtu")
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            descriptor: BluetoothGattDescriptor?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            val server = gattServer ?: return
            val targetDevice = device ?: return
            val address = targetDevice.address ?: return

            if (descriptor?.uuid != CCCD_UUID) {
                if (responseNeeded) {
                    server.sendResponse(targetDevice, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
                }
                return
            }

            val enableNotification =
                value?.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == true ||
                        value?.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE) == true

            if (enableNotification) subscribedDevices.add(address) else subscribedDevices.remove(address)
            descriptor.value = value

            if (responseNeeded) {
                server.sendResponse(targetDevice, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }

            Log.d(TAG, "SERVER CCCD updated device=$address enabled=$enableNotification")
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice?,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic?
        ) {
            val server = gattServer ?: return
            val targetDevice = device ?: return
            val address = targetDevice.address ?: return

            when (characteristic?.uuid) {
                PUBKEY_CHAR_UUID -> {
                    scope.launch {
                        val identity = identityRepository.getUserIdentity().firstOrNull()
                        val bytes = identity?.publicKey?.toByteArray() ?: ByteArray(0)

                        if (offset > bytes.size) {
                            server.sendResponse(targetDevice, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
                            return@launch
                        }

                        val sliced = bytes.copyOfRange(offset, bytes.size)
                        server.sendResponse(targetDevice, requestId, BluetoothGatt.GATT_SUCCESS, offset, sliced)
                        Log.d(TAG, "Read PUBKEY request device=$address bytes=${sliced.size}")
                    }
                }

                IDENTITY_CHAR_UUID -> {
                    val bytes = localUserId.toByteArray()

                    if (offset > bytes.size) {
                        server.sendResponse(targetDevice, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
                        return
                    }

                    val sliced = bytes.copyOfRange(offset, bytes.size)
                    server.sendResponse(targetDevice, requestId, BluetoothGatt.GATT_SUCCESS, offset, sliced)
                    Log.d(TAG, "Read IDENTITY request device=$address bytes=${sliced.size}")
                }

                else -> {
                    server.sendResponse(targetDevice, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
                }
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            val server = gattServer ?: return
            val targetDevice = device ?: return
            val address = targetDevice.address ?: return

            if (characteristic?.uuid != RX_CHAR_UUID || value == null) {
                if (responseNeeded) {
                    server.sendResponse(targetDevice, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
                }
                return
            }

            try {
                if (preparedWrite) {
                    val buffer = preparedWrites.getOrPut(address) { ByteArrayOutputStream() }
                    val current = buffer.toByteArray()

                    if (offset > current.size) {
                        if (responseNeeded) {
                            server.sendResponse(targetDevice, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
                        }
                        return
                    }

                    val rebuilt = ByteArrayOutputStream()
                    rebuilt.write(current, 0, offset)
                    rebuilt.write(value)
                    if (current.size > offset + value.size) {
                        rebuilt.write(current, offset + value.size, current.size - (offset + value.size))
                    }

                    preparedWrites[address] = rebuilt

                    if (responseNeeded) {
                        server.sendResponse(targetDevice, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                    }
                } else {
                    Log.d(TAG, "SERVER RX write device=$address bytes=${value.size}")
                    handleIncomingRawFrame(address, value)
                    if (responseNeeded) {
                        server.sendResponse(targetDevice, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Server write failed address=$address", t)
                if (responseNeeded) {
                    server.sendResponse(targetDevice, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                }
            }
        }

        override fun onExecuteWrite(device: BluetoothDevice?, requestId: Int, execute: Boolean) {
            val server = gattServer ?: return
            val targetDevice = device ?: return
            val address = targetDevice.address ?: return

            if (!execute) {
                preparedWrites.remove(address)
                server.sendResponse(targetDevice, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                return
            }

            val data = preparedWrites.remove(address)?.toByteArray()
            if (data == null) {
                server.sendResponse(targetDevice, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                return
            }

            Log.d(TAG, "SERVER executeWrite device=$address bytes=${data.size}")
            handleIncomingRawFrame(address, data)
            server.sendResponse(targetDevice, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }
    }

    override suspend fun startDiscovery() {
        if (adapter?.isEnabled != true) {
            Log.w(TAG, "Bluetooth disabled")
            return
        }

        // 1. Primero cargamos la identidad de forma síncrona la primera vez
        val initialIdentity = identityRepository.getUserIdentity().firstOrNull()
        localUserId = initialIdentity?.userId?.value ?: "unknown"
        Log.d(TAG, "Starting discovery with ID: $localUserId")

        // 2. Luego activamos el observador para cambios futuros
        identityJob?.cancel()
        identityJob = scope.launch {
            identityRepository.getUserIdentity().collect { identity ->
                val newId = identity?.userId?.value ?: "unknown"
                if (newId != localUserId && newId != "unknown") {
                    val oldId = localUserId
                    localUserId = newId
                    Log.d(TAG, "Identity updated: $oldId -> $localUserId")
                    
                    // Si ya estamos anunciando, reiniciamos el anuncio con el nuevo ID
                    if (isAdvertising) {
                        stopAdvertisingInternal()
                        delay(300)
                        startAdvertisingInternal()
                    }
                }
            }
        }

        setupGattServer()
        startScanningInternal()
        
        // Esperamos un momento para que el servidor GATT esté listo antes de anunciar
        delay(200)
        startAdvertisingInternal()

        startMaintenanceLoopsIfNeeded()
    }

    override suspend fun stopDiscovery() {
        identityJob?.cancel()
        identityJob = null

        stopScanningInternal()
        stopAdvertisingInternal()

        connectionMap.values.forEach { safeCloseGatt(it.gatt) }
        connectionMap.clear()

        gattServer?.close()
        gattServer = null

        subscribedDevices.clear()
        preparedWrites.clear()
        messageAssemblies.clear()

        pendingAcks.values.forEach { if (!it.isCompleted) it.complete(false) }
        pendingAcks.clear()

        pendingPubKeyReads.values.forEach { if (!it.isCompleted) it.complete(null) }
        pendingPubKeyReads.clear()

        pendingIdentityReads.values.forEach { if (!it.isCompleted) it.complete(null) }
        pendingIdentityReads.clear()

        seenMessageIds.clear()
        lastSeenMap.clear()
        peersState.value = emptyList()
    }

    override suspend fun fetchPublicKey(deviceId: String): String? {
        val ctx = getOrCreateConnection(deviceId) ?: return null

        return ctx.mutex.withLock {
            val gatt = ensureConnected(ctx) ?: return@withLock null
            val service = gatt.getService(SERVICE_UUID) ?: return@withLock null
            val characteristic = service.getCharacteristic(PUBKEY_CHAR_UUID) ?: return@withLock null
            val deferred = CompletableDeferred<String?>()
            pendingPubKeyReads[deviceId] = deferred

            val launched = gatt.readCharacteristic(characteristic)
            if (!launched) {
                pendingPubKeyReads.remove(deviceId)
                return@withLock null
            }

            try {
                withTimeout(READ_TIMEOUT_MS) { deferred.await() }
            } catch (_: TimeoutCancellationException) {
                pendingPubKeyReads.remove(deviceId)
                null
            }
        }
    }

    override suspend fun sendData(deviceId: String, data: ByteArray) {
        val ok = sendDataInternal(deviceId, data)
        if (!ok) {
            throw IllegalStateException("BLE send failed for deviceId=$deviceId")
        }
    }

    suspend fun sendDataInternal(deviceId: String, data: ByteArray): Boolean {
        val ctx = getOrCreateConnection(deviceId) ?: return false

        return ctx.mutex.withLock {
            val gatt = ensureConnected(ctx) ?: return@withLock false

            val notificationsReady = ensureNotificationsEnabled(ctx, gatt)
            if (!notificationsReady) {
                Log.w(TAG, "Notifications not enabled for $deviceId, continuing without app ACK")
            }

            val payloadSize = (ctx.mtu - ATT_HEADER_BYTES - HEADER_SIZE).coerceAtLeast(20)
            val messageId = nextMessageId()
            val chunks = data.chunkedBytes(payloadSize)

            if (chunks.isEmpty()) {
                Log.w(TAG, "sendData skipped empty payload device=$deviceId")
                return@withLock false
            }

            val expectAck = notificationsReady
            val ackKey = ackKey(deviceId, messageId)
            val ackDeferred = if (expectAck) CompletableDeferred<Boolean>() else null
            if (ackDeferred != null) pendingAcks[ackKey] = ackDeferred

            Log.d(
                TAG,
                "TX start device=$deviceId messageId=$messageId mtu=${ctx.mtu} payload=${data.size} chunks=${chunks.size} expectAck=$expectAck"
            )

            try {
                for ((index, chunk) in chunks.withIndex()) {
                    val frame = encodeFrame(
                        type = TYPE_DATA,
                        messageId = messageId,
                        chunkIndex = index,
                        totalChunks = chunks.size,
                        payload = chunk
                    )

                    val success = writeToRx(ctx, gatt, frame)
                    Log.d(TAG, "writeToRx returned=$success device=$deviceId messageId=$messageId chunk=$index")
                    if (!success) {
                        Log.e(TAG, "Failed to send chunk index=$index messageId=$messageId to $deviceId")
                        pendingAcks.remove(ackKey)
                        return@withLock false
                    }

                    Log.d(TAG, "TX chunk sent device=$deviceId messageId=$messageId chunk=${index + 1}/${chunks.size} bytes=${frame.size}")
                    delay(12)
                }

                val appAcked = if (ackDeferred != null) {
                    try {
                        withTimeout(ACK_TIMEOUT_MS) { ackDeferred.await() }
                    } catch (_: TimeoutCancellationException) {
                        Log.w(TAG, "ACK timeout for messageId=$messageId device=$deviceId")
                        false
                    }
                } else {
                    false
                }

                Log.d(TAG, "TX done device=$deviceId messageId=$messageId appAcked=$appAcked")
                true
            } finally {
                pendingAcks.remove(ackKey)
            }
        }
    }

    private fun startMaintenanceLoopsIfNeeded() {
        if (refreshLoopStarted) return
        refreshLoopStarted = true

        scope.launch {
            while (currentCoroutineContext().isActive) {
                delay(PEER_REFRESH_MS)
                refreshPeerStatuses()
                evictOldAssemblies()
                evictSeenCache()
                closeIdleConnections()
            }
        }
    }

    private fun refreshPeerStatuses() {
        val now = System.currentTimeMillis()

        val expiredAddresses = peersMap.values
            .filter { (now - it.lastSeen.epochMillis) >= PEER_STALE_MS }
            .map { it.deviceId.value }

        expiredAddresses.forEach { address ->
            peersMap.remove(address)
            lastSeenMap.remove(address)
            peerIdentityRequestsInFlight.remove(address)
        }

        peersState.value = peersMap.values
            .sortedByDescending { it.lastSeen.epochMillis }
    }

    private fun evictOldAssemblies() {
        val now = System.currentTimeMillis()
        messageAssemblies.entries.removeIf { now - it.value.updatedAt > 60_000L }
    }

    private fun evictSeenCache() {
        val now = System.currentTimeMillis()
        seenMessageIds.entries.removeIf { now - it.value > 120_000L }
    }

    private fun closeIdleConnections() {
        val now = System.currentTimeMillis()
        connectionMap.values.forEach { ctx ->
            val idle = now - ctx.lastUsedAt > CONNECTION_IDLE_MS
            if (idle && !ctx.mutex.isLocked) {
                Log.d(TAG, "Closing idle GATT device=${ctx.deviceId}")
                safeCloseGatt(ctx.gatt)
                ctx.gatt = null
                ctx.servicesReady = false
                ctx.notificationsEnabled = false
                ctx.mtu = DEFAULT_MTU
            }
        }
    }

    private fun setupGattServer() {
        if (gattServer != null) return

        val server = bluetoothManager?.openGattServer(context, serverCallback)
        if (server == null) {
            Log.e(TAG, "Unable to open GATT server")
            return
        }

        val service = BluetoothGattService(
            SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        val rxCharacteristic = BluetoothGattCharacteristic(
            RX_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        val txCharacteristic = BluetoothGattCharacteristic(
            TX_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        val cccd = BluetoothGattDescriptor(
            CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        txCharacteristic.addDescriptor(cccd)

        val pubKeyCharacteristic = BluetoothGattCharacteristic(
            PUBKEY_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        val identityCharacteristic = BluetoothGattCharacteristic(
            IDENTITY_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        service.addCharacteristic(rxCharacteristic)
        service.addCharacteristic(txCharacteristic)
        service.addCharacteristic(pubKeyCharacteristic)
        service.addCharacteristic(identityCharacteristic)

        val added = server.addService(service)
        if (!added) {
            server.close()
            Log.e(TAG, "Failed to add GATT service")
            return
        }

        gattServer = server
        Log.d(TAG, "GATT server ready")
    }

    private fun startScanningInternal() {
        if (isScanning || !hasScanPermission()) return

        val filter = ScanFilter.Builder()
            .setServiceUuid(SERVICE_PARCEL)
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner?.startScan(listOf(filter), settings, scanCallback)
        isScanning = true
    }

    private fun stopScanningInternal() {
        if (!isScanning) return
        scanner?.stopScan(scanCallback)
        isScanning = false
    }

    private fun startAdvertisingInternal() {
        if (isAdvertising || !hasAdvertisePermission()) return

        val shortUserId = localUserId.encodeToByteArray().take(MAX_ADVERTISED_USER_ID_BYTES).toByteArray()

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()

        val advertiseData = AdvertiseData.Builder()
            .addServiceUuid(SERVICE_PARCEL)
            .build()

        val scanResponse = AdvertiseData.Builder()
            .addServiceData(SERVICE_PARCEL, shortUserId)
            .setIncludeDeviceName(false)
            .build()

        advertiser?.startAdvertising(settings, advertiseData, scanResponse, advertiseCallback)
    }

    private fun stopAdvertisingInternal() {
        if (!isAdvertising) return
        advertiser?.stopAdvertising(advertiseCallback)
        isAdvertising = false
    }

    private fun getOrCreateConnection(deviceId: String): ConnectionContext? {
        val device = adapter?.getRemoteDevice(deviceId) ?: return null
        return connectionMap.getOrPut(deviceId) {
            ConnectionContext(deviceId = deviceId, device = device)
        }
    }

    private suspend fun ensureConnected(ctx: ConnectionContext): BluetoothGatt? {
        ctx.lastUsedAt = System.currentTimeMillis()
        ctx.gatt?.let { if (ctx.servicesReady) return it }

        val connectedDeferred = CompletableDeferred<BluetoothGatt?>()

        val callback = object : BluetoothGattCallback() {

            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                when {
                    status != BluetoothGatt.GATT_SUCCESS -> {
                        Log.e(TAG, "Client connection error device=${ctx.deviceId} status=$status")
                        safeCloseGatt(gatt)
                        if (!connectedDeferred.isCompleted) connectedDeferred.complete(null)
                    }

                    newState == BluetoothProfile.STATE_CONNECTED -> {
                        Log.d(TAG, "Client connected device=${ctx.deviceId}, requesting MTU")
                        gatt.requestMtu(MTU_REQUESTED)
                    }

                    newState == BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.w(TAG, "Client disconnected device=${ctx.deviceId}")
                        if (!connectedDeferred.isCompleted) connectedDeferred.complete(null)
                        if (ctx.gatt === gatt) {
                            ctx.gatt = null
                            ctx.servicesReady = false
                            ctx.notificationsEnabled = false
                            ctx.mtu = DEFAULT_MTU
                        }
                        ctx.characteristicWriteDeferred?.complete(false)
                        ctx.characteristicWriteDeferred = null
                        ctx.descriptorWriteDeferred?.complete(false)
                        ctx.descriptorWriteDeferred = null
                        safeCloseGatt(gatt)
                    }
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                ctx.mtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else DEFAULT_MTU
                Log.d(TAG, "Client MTU device=${ctx.deviceId} mtu=${ctx.mtu} status=$status")
                gatt.discoverServices()
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.e(TAG, "Service discovery failed device=${ctx.deviceId} status=$status")
                    if (!connectedDeferred.isCompleted) connectedDeferred.complete(null)
                    gatt.disconnect()
                    return
                }

                ctx.gatt = gatt
                ctx.servicesReady = true
                ctx.lastUsedAt = System.currentTimeMillis()
                Log.d(TAG, "Services ready device=${ctx.deviceId}")
                if (!connectedDeferred.isCompleted) connectedDeferred.complete(gatt)
            }

            @Deprecated("Deprecated in Java")
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                when (characteristic.uuid) {
                    PUBKEY_CHAR_UUID -> {
                        pendingPubKeyReads.remove(ctx.deviceId)?.complete(
                            if (status == BluetoothGatt.GATT_SUCCESS) characteristic.value?.decodeToString() else null
                        )
                    }

                    IDENTITY_CHAR_UUID -> {
                        pendingIdentityReads.remove(ctx.deviceId)?.complete(
                            if (status == BluetoothGatt.GATT_SUCCESS) characteristic.value?.decodeToString() else null
                        )
                    }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                if (characteristic.uuid != TX_CHAR_UUID) return
                val value = characteristic.value ?: return
                Log.d(TAG, "CLIENT notify legacy device=${ctx.deviceId} bytes=${value.size}")
                handleIncomingRawFrame(ctx.deviceId, value)
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                if (characteristic.uuid != TX_CHAR_UUID) return
                Log.d(TAG, "CLIENT notify device=${ctx.deviceId} bytes=${value.size}")
                handleIncomingRawFrame(ctx.deviceId, value)
            }

            @Deprecated("Deprecated in Java")
            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int
            ) {
                if (descriptor.uuid != CCCD_UUID) return
                val ok = status == BluetoothGatt.GATT_SUCCESS
                Log.d(TAG, "CLIENT onDescriptorWrite device=${ctx.deviceId} status=$status ok=$ok")
                ctx.descriptorWriteDeferred?.complete(ok)
                ctx.descriptorWriteDeferred = null
            }

            @Deprecated("Deprecated in Java")
            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                if (characteristic.uuid != RX_CHAR_UUID) return
                val ok = status == BluetoothGatt.GATT_SUCCESS
                Log.d(TAG, "CLIENT onCharacteristicWrite device=${ctx.deviceId} status=$status ok=$ok")
                ctx.characteristicWriteDeferred?.complete(ok)
                ctx.characteristicWriteDeferred = null
            }
        }

        val gatt = withContext(Dispatchers.Main) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                ctx.device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            } else {
                ctx.device.connectGatt(context, false, callback)
            }
        } ?: return null

        return try {
            withTimeout(CONNECT_TIMEOUT_MS) { connectedDeferred.await() }
        } catch (_: TimeoutCancellationException) {
            Log.e(TAG, "Connect timeout device=${ctx.deviceId}")
            safeCloseGatt(gatt)
            null
        }
    }

    private suspend fun ensureNotificationsEnabled(
        ctx: ConnectionContext,
        gatt: BluetoothGatt
    ): Boolean {
        if (ctx.notificationsEnabled) {
            Log.d(TAG, "Notifications already enabled for ${ctx.deviceId}")
            return true
        }

        val service = gatt.getService(SERVICE_UUID)
        if (service == null) {
            Log.e(TAG, "ensureNotificationsEnabled: service not found")
            return false
        }

        val tx = service.getCharacteristic(TX_CHAR_UUID)
        if (tx == null) {
            Log.e(TAG, "ensureNotificationsEnabled: TX char not found")
            return false
        }

        val cccd = tx.getDescriptor(CCCD_UUID)
        if (cccd == null) {
            Log.e(TAG, "ensureNotificationsEnabled: CCCD not found")
            return false
        }

        val localEnabled = gatt.setCharacteristicNotification(tx, true)
        Log.d(TAG, "setCharacteristicNotification result=$localEnabled device=${ctx.deviceId}")
        if (!localEnabled) return false

        val deferred = CompletableDeferred<Boolean>()
        ctx.descriptorWriteDeferred = deferred

        val launched = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val result = gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            Log.d(TAG, "writeDescriptor(T+) result=$result device=${ctx.deviceId}")
            result == BluetoothStatusCodes.SUCCESS
        } else {
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            val result = gatt.writeDescriptor(cccd)
            Log.d(TAG, "writeDescriptor(legacy) result=$result device=${ctx.deviceId}")
            result
        }

        if (!launched) {
            ctx.descriptorWriteDeferred = null
            Log.e(TAG, "writeDescriptor was not launched for ${ctx.deviceId}")
            return false
        }

        val ok = try {
            withTimeout(DESCRIPTOR_TIMEOUT_MS) { deferred.await() }
        } catch (_: TimeoutCancellationException) {
            Log.e(TAG, "Descriptor write timeout for ${ctx.deviceId}")
            false
        }

        ctx.notificationsEnabled = ok
        ctx.lastUsedAt = System.currentTimeMillis()
        Log.d(TAG, "Notifications enabled result=$ok device=${ctx.deviceId}")
        return ok
    }

    private fun handleIncomingRawFrame(deviceId: String, raw: ByteArray) {
        Log.d(TAG, "RX raw from $deviceId bytes=${raw.size}")

        val frame = decodeFrame(raw)
        if (frame == null) {
            Log.w(TAG, "RX decode failed from $deviceId bytes=${raw.size}")
            return
        }

        Log.d(
            TAG,
            "RX frame from=$deviceId type=${frame.type} messageId=${frame.messageId} chunk=${frame.chunkIndex}/${frame.totalChunks} payload=${frame.payload.size}"
        )

        when (frame.type) {
            TYPE_ACK -> {
                val key = ackKey(deviceId, frame.messageId)
                val completed = pendingAcks.remove(key)?.complete(true) ?: false
                Log.d(TAG, "RX ACK from=$deviceId messageId=${frame.messageId} completed=$completed")
            }

            TYPE_PUBKEY_REQ -> {
                scope.launch {
                    val identity = identityRepository.getUserIdentity().firstOrNull()
                    val pubKey = identity?.publicKey?.toByteArray() ?: ByteArray(0)
                    val sent = sendFrameBackToSubscriber(
                        deviceId,
                        encodeFrame(TYPE_PUBKEY_RESP, frame.messageId, 0, 1, pubKey)
                    )
                    Log.d(TAG, "PUBKEY_RESP sent=$sent to=$deviceId")
                }
            }

            TYPE_PUBKEY_RESP -> {
                pendingPubKeyReads.remove(deviceId)?.complete(frame.payload.decodeToString())
                Log.d(TAG, "PUBKEY_RESP received from=$deviceId")
            }

            TYPE_ID_REQ -> {
                scope.launch {
                    val sent = sendFrameBackToSubscriber(
                        deviceId,
                        encodeFrame(TYPE_ID_RESP, frame.messageId, 0, 1, localUserId.toByteArray())
                    )
                    Log.d(TAG, "ID_RESP sent=$sent to=$deviceId")
                }
            }

            TYPE_ID_RESP -> {
                pendingIdentityReads.remove(deviceId)?.complete(frame.payload.decodeToString())
                Log.d(TAG, "ID_RESP received from=$deviceId payload=${frame.payload.decodeToString()}")
            }

            TYPE_DATA -> {
                val totalChunks = frame.totalChunks.toInt()
                val chunkIndex = frame.chunkIndex.toInt()

                if (totalChunks <= 0 || chunkIndex < 0 || chunkIndex >= totalChunks) {
                    Log.w(
                        TAG,
                        "Invalid DATA frame from=$deviceId messageId=${frame.messageId} chunk=$chunkIndex total=$totalChunks"
                    )
                    return
                }

                val assemblyKey = "$deviceId:${frame.messageId}"
                val assembly = messageAssemblies.getOrPut(assemblyKey) {
                    IncomingAssembly(frame.messageId, totalChunks)
                }

                if (assembly.totalChunks != totalChunks) {
                    Log.w(TAG, "Assembly mismatch from=$deviceId messageId=${frame.messageId} expected=${assembly.totalChunks} got=$totalChunks")
                    messageAssemblies.remove(assemblyKey)
                    return
                }

                assembly.updatedAt = System.currentTimeMillis()
                assembly.chunks[chunkIndex] = frame.payload

                Log.d(
                    TAG,
                    "Assembly update from=$deviceId messageId=${frame.messageId} chunks=${assembly.chunks.size}/$totalChunks"
                )

                if (assembly.chunks.size == assembly.totalChunks) {
                    val out = ByteArrayOutputStream()

                    for (i in 0 until assembly.totalChunks) {
                        val chunk = assembly.chunks[i]
                        if (chunk == null) {
                            Log.w(TAG, "Assembly incomplete from=$deviceId messageId=${frame.messageId} missing=$i")
                            return
                        }
                        out.write(chunk)
                    }

                    messageAssemblies.remove(assemblyKey)
                    val payload = out.toByteArray()
                    val seenKey = "$deviceId:${frame.messageId}"
                    val inserted = seenMessageIds.putIfAbsent(seenKey, System.currentTimeMillis()) == null

                    Log.d(
                        TAG,
                        "Assembly complete from=$deviceId messageId=${frame.messageId} bytes=${payload.size} inserted=$inserted"
                    )

                    if (inserted) {
                        scope.launch {
                            Log.d(
                                TAG,
                                "Emitting incomingData to UI from=$deviceId bytes=${payload.size} text=${payload.decodeToString()}"
                            )
                            incomingDataState.emit(deviceId to payload)
                        }
                    } else {
                        Log.d(TAG, "Duplicate DATA ignored from=$deviceId messageId=${frame.messageId}")
                    }

                    scope.launch {
                        val ackSent = sendFrameBackToSubscriber(
                            deviceId,
                            encodeFrame(TYPE_ACK, frame.messageId, 0, 1, ByteArray(0))
                        )
                        Log.d(TAG, "ACK back sent=$ackSent to=$deviceId messageId=${frame.messageId}")
                    }
                }
            }

            else -> {
                Log.w(TAG, "Unknown frame type=${frame.type} from=$deviceId")
            }
        }
    }

    private suspend fun sendFrameBackToSubscriber(deviceId: String, frame: ByteArray): Boolean {
        val server = gattServer ?: return false

        if (!subscribedDevices.contains(deviceId)) {
            Log.w(TAG, "notify skipped: device $deviceId not subscribed to TX")
            return false
        }

        val device = adapter?.getRemoteDevice(deviceId) ?: return false
        val service = server.getService(SERVICE_UUID) ?: return false
        val tx = service.getCharacteristic(TX_CHAR_UUID) ?: return false

        return try {
            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                server.notifyCharacteristicChanged(device, tx, false, frame)
            } else {
                tx.value = frame
                @Suppress("DEPRECATION")
                if (server.notifyCharacteristicChanged(device, tx, false)) {
                    BluetoothStatusCodes.SUCCESS
                } else {
                    BluetoothStatusCodes.ERROR_UNKNOWN
                }
            }

            val ok = result == BluetoothStatusCodes.SUCCESS
            Log.d(TAG, "notify result=$result ok=$ok device=$deviceId bytes=${frame.size}")
            ok
        } catch (t: Throwable) {
            Log.e(TAG, "notify failed device=$deviceId bytes=${frame.size}", t)
            false
        }
    }

    private fun encodeFrame(
        type: Byte,
        messageId: Long,
        chunkIndex: Int,
        totalChunks: Int,
        payload: ByteArray
    ): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_SIZE + payload.size).order(ByteOrder.BIG_ENDIAN)
        buffer.put(PROTOCOL_VERSION)
        buffer.put(type)
        buffer.putLong(messageId)
        buffer.putShort(chunkIndex.toShort())
        buffer.putShort(totalChunks.toShort())
        buffer.putShort(payload.size.toShort())
        buffer.putShort(0)
        buffer.put(payload)
        return buffer.array()
    }

    private fun decodeFrame(raw: ByteArray): MeshFrame? {
        if (raw.size < HEADER_SIZE) return null

        val buffer = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)
        val version = buffer.get()
        val type = buffer.get()
        val messageId = buffer.long
        val chunkIndex = buffer.short
        val totalChunks = buffer.short
        val payloadLength = buffer.short
        val flags = buffer.short

        if (version != PROTOCOL_VERSION) return null
        if (payloadLength < 0) return null
        if (totalChunks <= 0) return null
        if (chunkIndex < 0) return null
        if (raw.size < HEADER_SIZE + payloadLength) return null

        val payload = ByteArray(payloadLength.toInt())
        buffer.get(payload)

        return MeshFrame(
            version = version,
            type = type,
            messageId = messageId,
            chunkIndex = chunkIndex,
            totalChunks = totalChunks,
            payloadLength = payloadLength,
            flags = flags,
            payload = payload
        )
    }

    private suspend fun writeToRx(
        ctx: ConnectionContext,
        gatt: BluetoothGatt,
        value: ByteArray
    ): Boolean {
        val service = gatt.getService(SERVICE_UUID)
        if (service == null) {
            Log.e(TAG, "writeToRx: service not found")
            return false
        }

        val rx = service.getCharacteristic(RX_CHAR_UUID)
        if (rx == null) {
            Log.e(TAG, "writeToRx: RX char not found")
            return false
        }

        val deferred = CompletableDeferred<Boolean>()
        ctx.characteristicWriteDeferred = deferred

        val launched = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val result = gatt.writeCharacteristic(
                    rx,
                    value,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                )
                val ok = result == BluetoothStatusCodes.SUCCESS
                Log.d(TAG, "writeToRx(T+) launchResult=$result ok=$ok bytes=${value.size}")
                ok
            } else {
                rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                rx.value = value
                val ok = gatt.writeCharacteristic(rx)
                Log.d(TAG, "writeToRx(legacy) launched=$ok bytes=${value.size}")
                ok
            }
        } catch (t: Throwable) {
            ctx.characteristicWriteDeferred = null
            Log.e(TAG, "writeToRx failed bytes=${value.size}", t)
            return false
        }

        if (!launched) {
            ctx.characteristicWriteDeferred = null
            return false
        }

        return try {
            withTimeout(WRITE_TIMEOUT_MS) { deferred.await() }
        } catch (_: TimeoutCancellationException) {
            ctx.characteristicWriteDeferred = null
            Log.e(TAG, "writeToRx timeout bytes=${value.size}")
            false
        }
    }

    private fun nextMessageId(): Long = messageCounter.incrementAndGet()

    private fun ackKey(deviceId: String, messageId: Long): String = "$deviceId:$messageId"

    private fun ByteArray.chunkedBytes(chunkSize: Int): List<ByteArray> {
        if (isEmpty()) return emptyList()
        if (size <= chunkSize) return listOf(this)

        val result = ArrayList<ByteArray>()
        var start = 0
        while (start < size) {
            val end = (start + chunkSize).coerceAtMost(size)
            result.add(copyOfRange(start, end))
            start = end
        }
        return result
    }

    private suspend fun fetchIdentity(deviceId: String): String? {
        val ctx = getOrCreateConnection(deviceId) ?: return null

        return ctx.mutex.withLock {
            val gatt = ensureConnected(ctx) ?: return@withLock null
            val service = gatt.getService(SERVICE_UUID) ?: return@withLock null
            val characteristic = service.getCharacteristic(IDENTITY_CHAR_UUID) ?: return@withLock null
            val deferred = CompletableDeferred<String?>()
            pendingIdentityReads[deviceId] = deferred

            val launched = gatt.readCharacteristic(characteristic)
            if (!launched) {
                pendingIdentityReads.remove(deviceId)
                return@withLock null
            }

            try {
                withTimeout(READ_TIMEOUT_MS) { deferred.await() }
            } catch (_: TimeoutCancellationException) {
                pendingIdentityReads.remove(deviceId)
                null
            }
        }
    }

    private fun safeCloseGatt(gatt: BluetoothGatt?) {
        try { gatt?.disconnect() } catch (_: Throwable) {}
        try { gatt?.close() } catch (_: Throwable) {}
    }

    private fun hasScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasAdvertisePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.BLUETOOTH_ADVERTISE
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun destroy() {
        stopScanningInternal()
        stopAdvertisingInternal()
        connectionMap.values.forEach { safeCloseGatt(it.gatt) }
        connectionMap.clear()
        gattServer?.close()
        gattServer = null
        scope.coroutineContext.cancelChildren()
    }
}