package com.manegow.deschat

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.manegow.data.repository.DataStoreIdentityRepository
import com.manegow.data.repository.RealChatRepository
import com.manegow.data.repository.RealMeshRepository
import com.manegow.deschat.navigation.AppNavHost
import com.manegow.deschat.ui.theme.DesChatTheme
import com.manegow.domain.usecase.chat.GetOrCreateDirectChatUseCase
import com.manegow.domain.usecase.chat.ObserveChatMessagesUseCase
import com.manegow.domain.usecase.chat.ObserveChatsUseCase
import com.manegow.domain.usecase.chat.SendMessageUseCase
import com.manegow.domain.usecase.mesh.ObserveNearbyPeersUseCase
import com.manegow.domain.usecase.mesh.StartPeerDiscoveryUseCase
import com.manegow.domain.usecase.mesh.StopPeerDiscoveryUseCase
import com.manegow.nearby.NearbyViewModel

class MainActivity : ComponentActivity() {

    private val meshRepository by lazy { RealMeshRepository(applicationContext, identityRepository) }

    private val chatRepository by lazy { RealChatRepository(meshRepository) }

    private val identityRepository by lazy { DataStoreIdentityRepository(applicationContext) }

    private val bluetoothManager by lazy {
        getSystemService(BluetoothManager::class.java)
    }

    private val bluetoothAdapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    private val observeNearbyPeersUseCase by lazy {
        ObserveNearbyPeersUseCase(meshRepository)
    }

    private val startPeerDiscoveryUseCase by lazy {
        StartPeerDiscoveryUseCase(meshRepository)
    }

    private val stopPeerDiscoveryUseCase by lazy {
        StopPeerDiscoveryUseCase(meshRepository)
    }

    private val getOrCreateDirectChatUseCase by lazy {
        GetOrCreateDirectChatUseCase(chatRepository)
    }

    private val observeChatMessagesUseCase by lazy {
        ObserveChatMessagesUseCase(chatRepository)
    }

    private val observeChatsUseCase by lazy {
        ObserveChatsUseCase(chatRepository)
    }

    private val sendMessageUseCase by lazy {
        SendMessageUseCase(chatRepository)
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.values.all { it }
        if (granted) {
            ensureBluetoothEnabled()
        }
    }

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        when (result.resultCode) {
            RESULT_OK -> {
                recreate()
            }
            RESULT_CANCELED -> {
                Log.d("MainActivity", "Bluetooth enable cancelled")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val nearbyViewModel = provideNearbyViewModel()

        when {
            !hasRequiredPermissions() -> {
                permissionLauncher.launch(requiredPermissions())
            }
            else -> {
                ensureBluetoothEnabled()
            }
        }
        setContent {
            DesChatTheme {
                AppNavHost(
                    nearbyViewModel = nearbyViewModel,
                    identityRepository = identityRepository,
                    getOrCreateDirectChatUseCase = getOrCreateDirectChatUseCase,
                    observeChatMessagesUseCase = observeChatMessagesUseCase,
                    observeChatsUseCase = observeChatsUseCase,
                    sendMessageUseCase = sendMessageUseCase
                )
            }
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        return requiredPermissions().all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun ensureBluetoothEnabled() {
        val adapter = bluetoothAdapter ?: return
        if(adapter.isEnabled) return

        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val hasConnectPermissions = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED

            if(!hasConnectPermissions) return
        }

        val enableBluetoothIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        enableBluetoothLauncher.launch(enableBluetoothIntent)
    }

    private fun requiredPermissions(): Array<String> {
        return if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
    }


    private fun provideNearbyViewModel(): NearbyViewModel {
        return ViewModelProvider(
            this,
            nearbyViewModelFactory()
        )[NearbyViewModel::class.java]
    }

    private fun nearbyViewModelFactory(): ViewModelProvider.Factory {
        return object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(NearbyViewModel::class.java)) {
                    return NearbyViewModel(
                        observeNearbyPeersUseCase = observeNearbyPeersUseCase,
                        startPeerDiscoveryUseCase = startPeerDiscoveryUseCase,
                        stopPeerDiscoveryUseCase = stopPeerDiscoveryUseCase
                    ) as T
                }
                throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }
        }
    }
}