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
import com.emiliotorrens.talk2claw.settings.SettingsManager
import com.emiliotorrens.talk2claw.ui.MainScreen
import com.emiliotorrens.talk2claw.ui.MainViewModel
import com.emiliotorrens.talk2claw.ui.SettingsScreen
import com.emiliotorrens.talk2claw.ui.theme.Talk2ClawTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            // Could show a snackbar but STT will just fail gracefully
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

        setContent {
            Talk2ClawTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var showSettings by remember { mutableStateOf(false) }
                    val settings by viewModel.settings.collectAsState()

                    if (showSettings) {
                        SettingsScreen(
                            settings = settings,
                            onSave = { viewModel.updateSettings(it) },
                            onBack = { showSettings = false }
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
}
