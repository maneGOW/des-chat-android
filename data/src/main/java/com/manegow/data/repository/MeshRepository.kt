package com.manegow.data.repository

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.*
import android.util.Log
import androidx.core.content.ContextCompat
import com.manegow.domain.repository.IdentityRepository
import com.manegow.domain.repository.MeshRepository
import com.manegow.model.common.Timestamp
import com.manegow.model.identity.DeviceId
import com.manegow.model.identity.DisplayName
import com.manegow.model.identity.UserId
import com.manegow.model.nearby.Peer
import com.manegow.model.nearby.PeerStatus
import com.manegow.model.nearby.SignalStrength
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

@SuppressLint("HardwareIds", "MissingPermissions")
class MeshRepository(
    private val context: Context,
    private val identityRepository: IdentityRepository
) : MeshRepository {

    companion object {
        private const val TAG = "RealMeshRepository"
        private val SERVICE_UUID: UUID = UUID.fromString("0000FE69-0000-1000-8000-00805f9b34fb")
        private val CHAR_UUID: UUID = UUID.fromString("0000FE70-0000-1000-8000-00805f9b34fb")
        private val PUBKEY_CHAR_UUID: UUID = UUID.fromString("0000FE71-0000-1000-8000-00805f9b34fb")
        private val SERVICE_PARCEL = ParcelUuid(SERVICE_UUID)
        private const val PEER_STALE_MS = 30_000L
        private const val PEER_REFRESH_MS = 10_000L
        private const val CONNECT_TIMEOUT_MS = 8_000L
        private const val READ_TIMEOUT_MS = 5_000L
        private const val DEFAULT_ATT_PAYLOAD = 20
        private const val MTU_REQUESTED = 247
        private const val ATT_HEADER_BYTES = 3

        private const val MTU_WANTED = 512
    }

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val adapter = bluetoothManager?.adapter
    private val scanner = adapter?.bluetoothLeScanner
    private val advertiser = adapter?.bluetoothLeAdvertiser

    private val bluetoothLock = Mutex()
    private var gattServer: BluetoothGattServer? = null
    
    private val peersState = MutableStateFlow<List<Peer>>(emptyList())
    private val incomingDataState = MutableSharedFlow<Pair<String, ByteArray>>(extraBufferCapacity = 64)
    private val lastSeenMap = mutableMapOf<String, Long>()

    private var localUserId: String = "unknown"
    private var isScanning = false
    private var isAdvertising = false

    override fun observeNearbyPeers(): Flow<List<Peer>> = peersState.asStateFlow()
    override fun observeIncomingData(): Flow<Pair<String, ByteArray>> = incomingDataState.asSharedFlow()

    @SuppressLint("MissingPermission")
    override suspend fun startDiscovery() {
        localUserId = identityRepository.getUserIdentity().firstOrNull()?.userId?.value ?: "unknown"
        Log.d(TAG, "MESH: Starting discovery for $localUserId")

        if (adapter?.isEnabled != true) return

        setupGattServer()
        startScanningInternal()
        startAdvertisingInternal()
        
        repositoryScope.launch {
            while (isActive) {
                delay(10000)
                refreshPeerStatuses()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun setupGattServer() {
        if (gattServer != null) return
        val callback = object : BluetoothGattServerCallback() {
            override fun onCharacteristicReadRequest(device: BluetoothDevice?, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic?) {
                if (characteristic?.uuid == PUBKEY_CHAR_UUID) {
                    repositoryScope.launch {
                        val identity = identityRepository.getUserIdentity().firstOrNull()
                        val pubKey = identity?.publicKey ?: ""
                        if (device != null) {
                            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, pubKey.toByteArray())
                        }
                    }
                }
            }

            override fun onCharacteristicWriteRequest(device: BluetoothDevice?, requestId: Int, characteristic: BluetoothGattCharacteristic?, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
                if (characteristic?.uuid == CHAR_UUID && value != null) {
                    val addr = device?.address ?: "unknown"
                    Log.i(TAG, "SERVER: Received data from $addr")
                    repositoryScope.launch { incomingDataState.emit(addr to value) }
                }
                if (responseNeeded && device != null) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            }
        }
        gattServer = bluetoothManager?.openGattServer(context, callback)
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val char = BluetoothGattCharacteristic(CHAR_UUID, 
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE, 
            BluetoothGattCharacteristic.PERMISSION_WRITE)
        val pubKeyChar = BluetoothGattCharacteristic(PUBKEY_CHAR_UUID, 
            BluetoothGattCharacteristic.PROPERTY_READ, 
            BluetoothGattCharacteristic.PERMISSION_READ)
        service.addCharacteristic(char)
        service.addCharacteristic(pubKeyChar)
        gattServer?.addService(service)
    }

    @SuppressLint("MissingPermission")
    override suspend fun fetchPublicKey(deviceId: String): String? {
        val device = adapter?.getRemoteDevice(deviceId) ?: return null
        val completed = CompletableDeferred<String?>()

        withContext(Dispatchers.Main) {
            val callback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                        gatt.discoverServices()
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                        gatt.close()
                        completed.complete(null)
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    val char = gatt.getService(SERVICE_UUID)?.getCharacteristic(PUBKEY_CHAR_UUID)
                    if (char != null) {
                        gatt.readCharacteristic(char)
                    } else {
                        gatt.disconnect()
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        completed.complete(characteristic.value?.decodeToString())
                    } else {
                        completed.complete(null)
                    }
                    gatt.disconnect()
                }
            }
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        }

        return withTimeoutOrNull(5000) { completed.await() }
    }

    @SuppressLint("MissingPermission")
    override suspend fun sendData(deviceId: String, data: ByteArray) {
        bluetoothLock.withLock {
            val device = adapter?.getRemoteDevice(deviceId) ?: return
            val completed = CompletableDeferred<Boolean>()

            withContext(Dispatchers.Main) {
                val callback = object : BluetoothGattCallback() {
                    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                        if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                            gatt.requestMtu(MTU_WANTED)
                        } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                            gatt.close()
                            completed.complete(false)
                        }
                    }

                    override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                        gatt.discoverServices()
                    }

                    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                        val char = gatt.getService(SERVICE_UUID)?.getCharacteristic(CHAR_UUID)
                        if (char != null) {
                            Log.d(TAG, "CLIENT: Characteristic found. Writing NO_RESPONSE...")
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                gatt.writeCharacteristic(char, data, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                            } else {
                                char.value = data
                                char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                                gatt.writeCharacteristic(char)
                            }
                        } else {
                            Log.e(TAG, "CLIENT: Service mismatch")
                            gatt.disconnect()
                        }
                    }

                    override fun onCharacteristicWrite(gatt: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
                        gatt.disconnect()
                        completed.complete(status == BluetoothGatt.GATT_SUCCESS)
                    }
                }
                device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            }
            withTimeoutOrNull(8000) { completed.await() }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScanningInternal() {
        if (isScanning || !hasScanPermission()) return
        // Filtramos solo por el Service UUID para encontrar otros nodos de DesChat
        val filter = ScanFilter.Builder().setServiceUuid(SERVICE_PARCEL).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner?.startScan(listOf(filter), settings, scanCallback)
        isScanning = true
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertisingInternal() {
        if (isAdvertising || !hasAdvertisePermission()) return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true)
            .build()
        
        // PAQUETE 1: Solo el UUID para que el filtro del scanner lo encuentre siempre
        val advertiseData = AdvertiseData.Builder()
            .addServiceUuid(SERVICE_PARCEL)
            .build()
        
        // PAQUETE 2 (Scan Response): Aquí metemos el ID de usuario y nombre
        // Esto solo se envía cuando otro teléfono detecta el Paquete 1
        val scanResponse = AdvertiseData.Builder()
            .addServiceData(SERVICE_PARCEL, localUserId.take(10).toByteArray())
            .setIncludeDeviceName(true)
            .build()
        
        advertiser?.startAdvertising(settings, advertiseData, scanResponse, object : AdvertiseCallback() {
            override fun onStartSuccess(s: AdvertiseSettings?) { isAdvertising = true; Log.d(TAG, "RADIO: Advertising ON") }
        })
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(ct: Int, r: ScanResult?) {
            val dev = r?.device ?: return
            // Intentamos sacar el ID del usuario del paquete de datos
            val serviceData = r.scanRecord?.getServiceData(SERVICE_PARCEL) ?: return
            val sid = serviceData.decodeToString()
            
            lastSeenMap[dev.address] = System.currentTimeMillis()
            peersState.update { list ->
                if (list.any { it.deviceId.value == dev.address }) list else {
                    Log.d(TAG, "MESH: Node discovered -> $sid")
                    list + Peer(DeviceId(dev.address), UserId(sid), DisplayName(r.scanRecord?.deviceName ?: sid.take(6)), SignalStrength(r.rssi), PeerStatus.REACHABLE, Timestamp(System.currentTimeMillis()))
                }
            }
        }
    }

    private fun refreshPeerStatuses() {
        val now = System.currentTimeMillis()
        peersState.update { list -> list.filter { (now - (lastSeenMap[it.deviceId.value] ?: 0)) < 30000 } }
    }

    @SuppressLint("MissingPermission")
    override suspend fun stopDiscovery() {
        gattServer?.close()
        gattServer = null
        scanner?.stopScan(scanCallback)
        isScanning = false
        isAdvertising = false
    }

    private fun hasScanPermission() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_SCAN) == android.content.pm.PackageManager.PERMISSION_GRANTED
    } else {
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun hasAdvertisePermission() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_ADVERTISE) == android.content.pm.PackageManager.PERMISSION_GRANTED
    } else true
}
