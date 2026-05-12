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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import com.manegow.data.db.AppDatabase
import com.manegow.data.crypto.CryptographyManager
import com.manegow.data.notifications.NotificationHandler
import com.manegow.data.repository.DataStoreIdentityRepository
import com.manegow.data.repository.RealChatRepository
import com.manegow.data.repository.MeshRepository
import com.manegow.deschat.navigation.AppNavHost
import com.manegow.deschat.ui.theme.DesChatTheme
import com.manegow.domain.usecase.chat.DeleteChatUseCase
import com.manegow.domain.usecase.chat.GetOrCreateDirectChatUseCase
import com.manegow.domain.usecase.chat.ObserveChatMessagesUseCase
import com.manegow.domain.usecase.chat.ObserveChatsUseCase
import com.manegow.domain.usecase.chat.SendMessageUseCase
import com.manegow.domain.usecase.mesh.ObserveNearbyPeersUseCase
import com.manegow.domain.usecase.mesh.StartPeerDiscoveryUseCase
import com.manegow.domain.usecase.mesh.StopPeerDiscoveryUseCase
import com.manegow.nearby.NearbyViewModel

class MainActivity : ComponentActivity() {

    private val meshRepository by lazy { MeshRepository(applicationContext, identityRepository) }

    private val notificationHandler by lazy { NotificationHandler(applicationContext, identityRepository) }

    private val cryptoManager by lazy { CryptographyManager() }

    private val database by lazy { AppDatabase.getDatabase(applicationContext) }

    private var permissionsGranted by mutableStateOf(false)

    private val chatRepository by lazy { 
        RealChatRepository(
            meshRepository = meshRepository,
            identityRepository = identityRepository,
            chatDao = database.chatDao(),
            messageDao = database.messageDao(),
            relayDao = database.relayDao(),
            notificationHandler = notificationHandler,
            cryptoManager = cryptoManager
        ) 
    }

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

    private val deleteChatUseCase by lazy {
        DeleteChatUseCase(chatRepository)
    }

    private val sendMessageUseCase by lazy {
        SendMessageUseCase(chatRepository)
    }

    private val nearbyViewModel by lazy { provideNearbyViewModel() }

    private var initialChatToOpen by mutableStateOf<Pair<String, String?>?>(null)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permissionsGranted = hasRequiredPermissions()
        if (permissionsGranted) {
            ensureBluetoothEnabled()
            nearbyViewModel.onPermissionsGranted()
        } else {
            Log.d("MainActivity", "Permissions denied")
        }
    }

    private fun missingPermissions(): Array<String> {
        return requiredPermissions().filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
    }

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        when (result.resultCode) {
            RESULT_OK -> {
                if(bluetoothAdapter?.isEnabled == true) {
                    nearbyViewModel.onPermissionsGranted()
                }
            }
            RESULT_CANCELED -> {
                Log.d("MainActivity", "Bluetooth enable cancelled")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        handleIntent(intent)
        permissionsGranted = hasRequiredPermissions()

        lifecycleScope.launch {
            identityRepository.getUserIdentity().collectLatest { identity ->
                if (identity != null) {
                    Log.d("MainActivity", "User registered, checking permissions and starting discovery")
                    if(hasRequiredPermissions()) {
                        ensureBluetoothEnabled()
                        nearbyViewModel.onPermissionsGranted()
                    }
                } else {
                    Log.d("MainActivity", "User not registered, stopping discovery")
                    nearbyViewModel.stopDiscovery()
                }
            }
        }


        setContent {
            DesChatTheme {
                AppNavHost(
                    nearbyViewModel = nearbyViewModel,
                    identityRepository = identityRepository,
                    chatRepository = chatRepository,
                    getOrCreateDirectChatUseCase = getOrCreateDirectChatUseCase,
                    observeChatMessagesUseCase = observeChatMessagesUseCase,
                    observeChatsUseCase = observeChatsUseCase,
                    deleteChatUseCase = deleteChatUseCase,
                    sendMessageUseCase = sendMessageUseCase,
                    onRequestPermissions = ::requestNearbyPermissions,
                    permissionsGranted = permissionsGranted,
                    initialChatToOpen = initialChatToOpen,
                    onInitialChatOpened = { initialChatToOpen = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val chatId = intent.getStringExtra("open_chat_id")
        val chatName = intent.getStringExtra("open_chat_name")
        if (chatId != null) {
            initialChatToOpen = chatId to chatName
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        return requiredNearbyPermissions().all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requiredNearbyPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
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

    private fun requestNearbyPermissions() {
        permissionsGranted = hasRequiredPermissions()

        if(permissionsGranted) {
            ensureBluetoothEnabled()
            nearbyViewModel.onPermissionsGranted()
            return
        }

        val missing = missingPermissions()
        if(missing.isNotEmpty()) {
            permissionLauncher.launch(missing)
        } else {
            permissionsGranted = hasRequiredPermissions()
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
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
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