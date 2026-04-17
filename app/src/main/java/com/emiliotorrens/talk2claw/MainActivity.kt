package com.emiliotorrens.talk2claw

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.emiliotorrens.talk2claw.service.GatewayService
import com.emiliotorrens.talk2claw.settings.SettingsManager
import com.emiliotorrens.talk2claw.ui.MainScreen
import com.emiliotorrens.talk2claw.ui.MainViewModel
import com.emiliotorrens.talk2claw.ui.SettingsScreen
import com.emiliotorrens.talk2claw.ui.theme.Talk2ClawTheme

class MainActivity : ComponentActivity() {

    companion object {
        /** Intent extra to auto-start conversation (used by widget and tile). */
        const val EXTRA_START_CONVERSATION = "start_conversation"
    }

    private val viewModel: MainViewModel by viewModels()

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            // STT will fail gracefully without mic permission
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        SettingsManager.init(applicationContext)

        // Request mic permission if needed
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        // Start foreground service to keep WebSocket alive in the background
        GatewayService.start(this)

        // Check if launched with auto-start conversation intent
        handleStartConversationIntent(intent)

        setContent {
            Talk2ClawTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var showSettings by remember { mutableStateOf(false) }
                    val settings by viewModel.settings.collectAsState()
                    val connectionState by viewModel.connectionState.collectAsState()

                    if (showSettings) {
                        SettingsScreen(
                            settings = settings,
                            connectionState = connectionState,
                            onSave = { viewModel.updateSettings(it) },
                            onReconnect = { viewModel.reconnect() },
                            onBack = { showSettings = false },
                            onPreviewVoice = { viewModel.previewVoice(it) },
                            onModelChanged = { viewModel.sendModelCommand(it) },
                            onThinkingChanged = { viewModel.sendThinkingCommand(it) },
                        )
                    } else {
                        MainScreen(
                            viewModel = viewModel,
                            onNavigateSettings = { showSettings = true }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleStartConversationIntent(intent)
    }

    private fun handleStartConversationIntent(intent: android.content.Intent?) {
        if (intent?.getBooleanExtra(EXTRA_START_CONVERSATION, false) == true) {
            // Auto-start conversation if not already active
            if (!viewModel.conversationActive.value) {
                viewModel.toggleConversation()
            }
            // Clear the extra so it doesn't re-trigger on config change
            intent.removeExtra(EXTRA_START_CONVERSATION)
        }
    }
}
