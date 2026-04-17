package com.manegow.data.repository

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.manegow.domain.repository.MeshRepository
import com.manegow.model.common.Timestamp
import com.manegow.model.identity.DeviceId
import com.manegow.model.identity.DisplayName
import com.manegow.model.identity.UserId
import com.manegow.model.nearby.Peer
import com.manegow.model.nearby.PeerStatus
import com.manegow.model.nearby.SignalStrength
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

@SuppressLint("HardwareIds")
class RealMeshRepository(
    private val context: Context
) : MeshRepository {

    companion object {
        private const val TAG = "RealMeshRepository"

        private const val PEER_REACHABLE_TIMEOUT_MILLIS = 10_000L
        private const val PEER_REMOVE_TIMEOUT_MILLIS = 20_000L
        private const val PEER_CLEANUP_INTERVAL_MILLIS = 3_000L

        private const val MAX_DISPLAY_NAME_LENGTH = 12

        private val SERVICE_UUID: UUID =
            UUID.fromString("12345678-1234-1234-1234-1234567890AB")
    }

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(BluetoothManager::class.java)

    private val bluetoothAdapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    private val scanner
        get() = bluetoothAdapter?.bluetoothLeScanner

    private val advertiser: BluetoothLeAdvertiser?
        get() = bluetoothAdapter?.bluetoothLeAdvertiser

    private val peersState = MutableStateFlow<List<Peer>>(emptyList())
    private val lastSeenMap = mutableMapOf<String, Long>()

    private var cleanupJob: Job? = null
    private var isScanning = false
    private var isAdvertising = false

    private val localUserId: String by lazy {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?.take(12)
            ?.ifBlank { null }
            ?: UUID.randomUUID().toString().take(12)
    }

    private val localDisplayName: String by lazy {
        (Build.MODEL ?: "Android")
            .trim()
            .ifBlank { "Android" }
            .take(MAX_DISPLAY_NAME_LENGTH)
    }

    override fun observeNearbyPeers(): Flow<List<Peer>> = peersState.asStateFlow()

    @SuppressLint("MissingPermission")
    override suspend fun startDiscovery() {
        Log.d(
            TAG,
            "startDiscovery bluetoothEnabled=${bluetoothAdapter?.isEnabled} " +
                    "hasScan=${hasScanPermission()} hasAdvertise=${hasAdvertisePermission()}"
        )

        if (bluetoothAdapter?.isEnabled != true) {
            Log.d(TAG, "Bluetooth disabled")
            return
        }

        startAdvertisingInternal()
        startScanningInternal()
        startCleanupLoop()
    }

    @SuppressLint("MissingPermission")
    override suspend fun stopDiscovery() {
        stopScanningInternal()
        stopAdvertisingInternal()
        stopCleanupLoop()
        peersState.value = emptyList()
        lastSeenMap.clear()
    }

    @SuppressLint("MissingPermission")
    private fun startScanningInternal() {
        if (isScanning) return
        if (!hasScanPermission()) {
            Log.d(TAG, "Missing BLUETOOTH_SCAN permission")
            return
        }

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner?.startScan(null, scanSettings, scanCallback)
        isScanning = true
        Log.d(TAG, "BLE scan started without filters")
    }

    @SuppressLint("MissingPermission")
    private fun stopScanningInternal() {
        if (!isScanning) return
        if (!hasScanPermission()) return

        scanner?.stopScan(scanCallback)
        isScanning = false
        Log.d(TAG, "BLE scan stopped")
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertisingInternal() {
        if (isAdvertising) return
        if (!hasAdvertisePermission()) {
            Log.d(TAG, "Missing BLUETOOTH_ADVERTISE permission")
            return
        }

        val adapter = bluetoothAdapter ?: return
        if (!adapter.isMultipleAdvertisementSupported) {
            Log.d(TAG, "BLE advertising not supported on this device")
            return
        }

        val bleAdvertiser = advertiser ?: run {
            Log.d(TAG, "bluetoothLeAdvertiser is null")
            return
        }

        val serviceUuid = ParcelUuid(SERVICE_UUID)
        val payload = buildAdvertisePayload(
            userId = localUserId,
        )

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .build()

        val advertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceData(serviceUuid, payload)
            .build()

        val scanResponse = AdvertiseData.Builder()
            .build()

        Log.d(
            TAG,
            "Starting advertising userId=$localUserId displayName=$localDisplayName payloadSize=${payload.size}"
        )

        bleAdvertiser.startAdvertising(
            settings,
            advertiseData,
            scanResponse,
            advertiseCallback
        )
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertisingInternal() {
        if (!isAdvertising) return
        if (!hasAdvertisePermission()) return

        advertiser?.stopAdvertising(advertiseCallback)
        isAdvertising = false
        Log.d(TAG, "BLE advertising stopped")
    }

    private fun startCleanupLoop() {
        if (cleanupJob?.isActive == true) return

        cleanupJob = repositoryScope.launch {
            while (isActive) {
                refreshPeerStatuses()
                delay(PEER_CLEANUP_INTERVAL_MILLIS)
            }
        }
    }

    private fun stopCleanupLoop() {
        cleanupJob?.cancel()
        cleanupJob = null
    }

    private fun refreshPeerStatuses() {
        val now = System.currentTimeMillis()
        val currentPeers = peersState.value

        val updatedPeers = currentPeers.mapNotNull { peer ->
            val lastSeen = lastSeenMap[peer.deviceId.value] ?: return@mapNotNull null
            val age = now - lastSeen

            when {
                peer.status == PeerStatus.CONNECTED || peer.status == PeerStatus.CONNECTING -> {
                    peer
                }
                age > PEER_REMOVE_TIMEOUT_MILLIS -> {
                    Log.d(TAG, "Removing peer ${peer.deviceId.value} after LOST timeout")
                    lastSeenMap.remove(peer.deviceId.value)
                    null
                }
                age > PEER_REACHABLE_TIMEOUT_MILLIS -> {
                    peer.copy(status = PeerStatus.LOST)
                }
                peer.status == PeerStatus.DISCOVERED -> {
                    peer.copy(status = PeerStatus.REACHABLE)
                }
                else -> {
                    peer.copy(status = PeerStatus.REACHABLE)
                }
            }
        }.sortedBy { it.displayName?.value.orEmpty() }

        if (updatedPeers != currentPeers) {
            peersState.value = updatedPeers
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            isAdvertising = true
            Log.d(TAG, "BLE advertising started successfully")
        }

        override fun onStartFailure(errorCode: Int) {
            isAdvertising = false
            Log.e(TAG, "BLE advertising failed: $errorCode")
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val peer = result?.toPeer() ?: return
            val deviceId = peer.deviceId.value
            val now = System.currentTimeMillis()

            lastSeenMap[deviceId] = now

            val current = peersState.value
            val existingPeer = current.firstOrNull { it.deviceId == peer.deviceId }

            val updatedPeer = when {
                existingPeer == null -> {
                    peer.copy(status = PeerStatus.DISCOVERED)
                }
                existingPeer.status == PeerStatus.CONNECTED -> {
                    peer.copy(status = PeerStatus.CONNECTED)
                }
                existingPeer.status == PeerStatus.CONNECTING -> {
                    peer.copy(status = PeerStatus.CONNECTING)
                }
                else -> {
                    peer.copy(status = PeerStatus.REACHABLE)
                }
            }

            val withoutDuplicate = current.filterNot { it.deviceId == updatedPeer.deviceId }
            peersState.value = (withoutDuplicate + updatedPeer)
                .sortedBy { it.displayName?.value.orEmpty() }

            Log.d(
                TAG,
                "Peer visible id=${updatedPeer.deviceId.value} " +
                        "userId=${updatedPeer.userId?.value} " +
                        "name=${updatedPeer.displayName?.value} " +
                        "status=${updatedPeer.status} " +
                        "rssi=${updatedPeer.signalStrength.rssi}"
            )
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed: $errorCode")
        }
    }

    @SuppressLint("MissingPermission")
    private fun ScanResult.toPeer(): Peer? {
        val bluetoothDevice = device ?: return null
        val rawDeviceId = bluetoothDevice.address ?: return null

        val serviceData = scanRecord?.getServiceData(ParcelUuid(SERVICE_UUID))
        val parsedUserId = parseAdvertisePayload(serviceData) ?: return null

        val nameFromAdvertising = scanRecord?.deviceName?.trim().orEmpty()
        val nameFromDevice = runCatching { bluetoothDevice.name?.trim().orEmpty() }
            .getOrDefault("")

        val resolvedName = when {
            nameFromAdvertising.isNotBlank() -> nameFromAdvertising
            nameFromDevice.isNotBlank() -> nameFromDevice
            else -> parsedUserId.take(8)
        }

        return Peer(
            deviceId = DeviceId(rawDeviceId),
            userId = UserId(parsedUserId),
            displayName = DisplayName(resolvedName),
            signalStrength = SignalStrength(rssi),
            status = PeerStatus.DISCOVERED,
            lastSeen = timestampFromScanResult(this)
        )
    }

    private fun buildAdvertisePayload(
        userId: String,
    ): ByteArray {
        return userId.trim().take(8).toByteArray(Charsets.UTF_8)
    }

    private fun parseAdvertisePayload(data: ByteArray?): String? {
        return data
            ?.toString(Charsets.UTF_8)
            ?.trim()
            ?.ifBlank { null }
    }

    private fun timestampFromScanResult(result: ScanResult): Timestamp {
        val nowWallClockMillis = System.currentTimeMillis()
        val nowElapsedRealtimeMillis = SystemClock.elapsedRealtime()
        val seenElapsedRealtimeMillis = result.timestampNanos / 1_000_000L
        val approxWallClockMillis =
            nowWallClockMillis - (nowElapsedRealtimeMillis - seenElapsedRealtimeMillis)
        return Timestamp(approxWallClockMillis)
    }

    private fun hasScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasAdvertisePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_ADVERTISE
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
}